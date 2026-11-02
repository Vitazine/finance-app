package app.finance.android.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.finance.core.money.CurrencyCode
import app.finance.core.money.Money
import app.finance.core.money.MoneyResult
import app.finance.domain.budget.BudgetMonth
import app.finance.domain.exchange.CachedRate
import app.finance.domain.exchange.RateCache
import app.finance.domain.ledger.Category
import app.finance.domain.ledger.CategoryRepository
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.HistoryItem
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerFilter
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import app.finance.domain.ledger.SettingsRepository
import app.finance.domain.ledger.StoredTransaction
import app.finance.domain.ledger.TransactionRepository
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

internal class LedgerViewModel(
    private val settingsRepository: SettingsRepository,
    private val categoryRepository: CategoryRepository,
    private val transactionRepository: TransactionRepository,
    private val rateCache: RateCache,
    private val budgetRepository: app.finance.domain.budget.BudgetRepository,
    private val analyticsRepository: app.finance.domain.analytics.AnalyticsRepository,
    private val clock: Clock = Clock.System
) : ViewModel() {
    private val mutable = MutableStateFlow(LedgerUiState())
    val state = mutable.asStateFlow()
    init {
        refresh()
    }

    private fun action(allowWhenFatal: Boolean = false, block: suspend () -> Unit) {
        if (mutable.value.busy || (!allowWhenFatal && mutable.value.fatalError != null)) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } finally {
                mutable.update { it.copy(busy = false, loading = false) }
                refreshCalendarIfNeeded()
            }
        }
    }
    fun refresh() = action(allowWhenFatal = true) { reload() }
    fun lockForImport(): Boolean {
        if (mutable.value.busy) return false
        mutable.update { it.copy(busy = true) }
        return true
    }
    fun importFinished(replaced: Boolean) {
        if (replaced) mutable.value = LedgerUiState() else mutable.update { it.copy(busy = false) }
        refresh()
    }

    fun saveBudget(month: BudgetMonth, text: String) {
        if (mutable.value.busy) return
        val settings = mutable.value.settings ?: return
        val limit = Money.parsePositive(text.trim().replace(',', '.'), settings.primaryCurrency)
        if (limit !is MoneyResult.Success) {
            mutable.update { it.copy(budgetSaveError = LedgerError.INVALID_BUDGET) }
            return
        }
        action {
            val currentMonth = BudgetMonth.at(clock.now(), TimeZone.of(settings.zoneId))
            if (currentMonth !is LedgerResult.Success || currentMonth.value != month) {
                loadCalendar(clock.now())
                mutable.update { it.copy(budgetSaveError = LedgerError.INVALID_MONTH) }
                return@action
            }
            when (val result = budgetRepository.put(month, limit.value, clock.now())) {
                is LedgerResult.Failure -> mutable.update {
                    it.copy(budgetSaveError = result.error)
                }
                is LedgerResult.Success -> {
                    mutable.update {
                        it.copy(
                            budgetSaveError = null,
                            budgetSaveVersion =
                            it.budgetSaveVersion + 1
                        )
                    }
                    loadCalendar(clock.now())
                }
            }
        }
    }
    fun clearBudgetSaveError() {
        mutable.update { it.copy(budgetSaveError = null) }
    }
    fun refreshCalendarIfNeeded() {
        val current = mutable.value
        val settings = current.settings ?: return
        if (current.busy || current.fatalError != null) return
        val zone = TimeZone.of(settings.zoneId)
        val today = clock.now().toLocalDateTime(zone).date
        if (current.analyticsReference?.toLocalDateTime(zone)?.date != today) {
            action { loadCalendar(clock.now()) }
        }
    }
    fun millisUntilCalendarBoundary(): Long {
        val settings = mutable.value.settings ?: return 60_000L
        val now = clock.now()
        val zone = TimeZone.of(settings.zoneId)
        val next = now.toLocalDateTime(zone).date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
        return (next - now).inWholeMilliseconds.coerceIn(1L, 60_000L)
    }
    private enum class AnalyticsLoad { CURRENT, STALE, FATAL }
    private suspend fun loadCalendar(reference: Instant) {
        val current = loadCurrentAnalytics(reference) ?: return
        loadBudget(current)
    }
    private suspend fun loadCurrentAnalytics(initial: Instant): Instant? {
        var reference = initial
        repeat(2) {
            when (loadAnalytics(reference)) {
                AnalyticsLoad.CURRENT -> return if (mutable.value.fatalError ==
                    null
                ) {
                    reference
                } else {
                    null
                }
                AnalyticsLoad.FATAL -> return null
                AnalyticsLoad.STALE -> reference = clock.now()
            }
        }
        // An unstable system date must not create an automatic retry loop or stale numbers.
        mutable.update {
            it.copy(
                analytics = null,
                analyticsReference = clock.now(),
                analyticsError = LedgerError.INVALID_FILTER
            )
        }
        return null
    }
    private suspend fun loadAnalytics(reference: Instant): AnalyticsLoad {
        mutable.update {
            it.copy(
                analytics = null,
                analyticsReference = reference,
                analyticsLoading = true,
                analyticsError = null
            )
        }
        try {
            val result = analyticsRepository.snapshot(reference)
            if (result is LedgerResult.Failure && result.error == LedgerError.CORRUPT_DATA) {
                mutable.update {
                    it.copy(
                        analytics = null, analyticsError = result.error, fatalError = result.error,
                        overview = null, overviewError = result.error, budget = null,
                        budgetError = result.error, history = emptyList(), cursor = null,
                        historyError = result.error
                    )
                }
                return AnalyticsLoad.FATAL
            }
            val zone = TimeZone.of(mutable.value.settings!!.zoneId)
            if (reference.toLocalDateTime(zone).date != clock.now().toLocalDateTime(zone).date) {
                return AnalyticsLoad.STALE
            }
            when (result) {
                is LedgerResult.Success -> mutable.update {
                    it.copy(analytics = result.value, fatalError = null)
                }
                is LedgerResult.Failure -> mutable.update {
                    it.copy(analytics = null, analyticsError = result.error)
                }
            }
            return AnalyticsLoad.CURRENT
        } finally {
            mutable.update { it.copy(analyticsLoading = false) }
        }
    }
    private suspend fun loadBudget(reference: Instant = clock.now()) {
        when (val result = budgetRepository.current(reference)) {
            is LedgerResult.Success -> mutable.update {
                it.copy(budget = result.value, budgetError = null)
            }
            is LedgerResult.Failure -> mutable.update {
                it.copy(budget = null, budgetError = result.error)
            }
        }
    }

    private suspend fun reload() {
        val settings = when (val result = settingsRepository.current()) {
            is LedgerResult.Success -> result.value
            is LedgerResult.Failure -> {
                if (result.error == LedgerError.CORRUPT_DATA) {
                    mutable.update {
                        it.copy(
                            fatalError = result.error,
                            overview = null,
                            budget = null,
                            analytics = null,
                            history = emptyList(),
                            cursor = null,
                            categories = emptyList(),
                            overviewError = result.error,
                            budgetError = result.error,
                            analyticsError = result.error,
                            historyError = result.error
                        )
                    }
                    return
                }
                mutable.update {
                    it.copy(
                        fatalError = if (it.fatalError ==
                            LedgerError.CORRUPT_DATA
                        ) {
                            it.fatalError
                        } else {
                            result.error
                        }
                    )
                }
                return
            }
        }
        if (settings == null) {
            if (mutable.value.fatalError == LedgerError.CORRUPT_DATA) return
            when (val basis = settingsRepository.existingBasis()) {
                is LedgerResult.Success -> mutable.update {
                    it.copy(settings = null, legacyBasis = basis.value, fatalError = null)
                }
                is LedgerResult.Failure -> mutable.update { it.copy(fatalError = basis.error) }
            }
            return
        }
        mutable.update {
            it.copy(
                settings = settings,
                fatalError = if (it.fatalError ==
                    LedgerError.CORRUPT_DATA
                ) {
                    it.fatalError
                } else {
                    null
                }
            )
        }
        val reference = loadCurrentAnalytics(clock.now()) ?: return
        loadCategories(reset = true)
        when (val result = transactionRepository.overview()) {
            is LedgerResult.Success -> mutable.update {
                it.copy(overview = result.value, overviewError = null)
            }
            is LedgerResult.Failure -> mutable.update {
                it.copy(overview = null, overviewError = result.error)
            }
        }
        loadBudget(reference)
        loadHistory(reset = true)
    }

    fun initialize(currencyText: String, zone: String) {
        val currency = if (currencyText.length <=
            8
        ) {
            CurrencyCode.resolve(currencyText.trim().uppercase())
        } else {
            null
        }
        if (currency !is MoneyResult.Success) {
            mutable.update { it.copy(setupIssue = InputIssue.INVALID_CURRENCY) }
            return
        }
        val settings = LedgerSettings.create(currency.value, zone)
        if (settings !is LedgerResult.Success) {
            mutable.update { it.copy(setupIssue = InputIssue.INVALID_TIME) }
            return
        }
        action {
            when (val result = settingsRepository.initialize(settings.value)) {
                is LedgerResult.Failure -> mutable.update { it.copy(error = result.error) }
                is LedgerResult.Success -> {
                    mutable.update { it.copy(setupIssue = null) }
                    reload()
                }
            }
        }
    }

    private suspend fun loadCategories(reset: Boolean) {
        val offset = if (reset) 0 else mutable.value.categories.size
        when (
            val result = categoryRepository.list(
                includeArchived = true,
                limit = 200,
                offset = offset
            )
        ) {
            is LedgerResult.Failure -> mutable.update { it.copy(error = result.error) }
            is LedgerResult.Success -> mutable.update {
                it.copy(
                    categories =
                    (if (reset) emptyList() else it.categories) + result.value,
                    moreCategories =
                    result.value.size == 200
                )
            }
        }
    }
    fun moreCategories() = action { loadCategories(reset = false) }
    private suspend fun loadHistory(reset: Boolean) {
        val current = mutable.value
        when (
            val result = transactionRepository.history(
                current.filter,
                if (reset) null else current.cursor
            )
        ) {
            is LedgerResult.Failure -> mutable.update {
                it.copy(history = emptyList(), cursor = null, historyError = result.error)
            }
            is LedgerResult.Success -> mutable.update {
                it.copy(
                    history =
                    (if (reset) emptyList() else it.history) + result.value.items,
                    cursor = result.value.nextCursor,
                    historyError = null
                )
            }
        }
    }
    fun filter(filter: LedgerFilter) = action {
        mutable.update { it.copy(filter = filter) }
        loadHistory(reset = true)
    }
    fun moreHistory() {
        if (mutable.value.cursor != null) action { loadHistory(reset = false) }
    }

    fun createEntry() {
        if (mutable.value.busy) return
        val settings = mutable.value.settings ?: return
        mutable.update {
            it.copy(
                editor = EditorState(newId(), newFields(settings, clock.now())),
                editorIssue = null,
                error = null
            )
        }
    }
    fun editEntry(item: HistoryItem) = loadEditor(item.transaction.entry.id, item.category)
    fun reloadEditor() {
        val editor = mutable.value.editor ?: return
        val original = editor.original ?: return
        val category = editor.originalCategory ?: return
        loadEditor(original.entry.id, category)
    }
    private fun loadEditor(id: LedgerId, category: Category) = action {
        when (val result = transactionRepository.find(id)) {
            is LedgerResult.Failure -> mutable.update { it.copy(error = result.error) }
            is LedgerResult.Success -> mutable.update {
                it.copy(
                    editor = EditorState(
                        result.value.entry.id,
                        fieldsFor(result.value.entry),
                        result.value,
                        category
                    ),
                    editorIssue = null
                )
            }
        }
    }
    fun fields(change: (EntryFields) -> EntryFields) {
        if (!mutable.value.busy) {
            mutable.update { state ->
                state.copy(
                    editor = state.editor?.let { editor ->
                        val next = change(editor.fields)
                        val old = editor.fields
                        val pairChanged =
                            next.currency.trim().uppercase() != old.currency.trim().uppercase()
                        val timeChanged =
                            next.localTime != old.localTime ||
                                next.zoneId != old.zoneId ||
                                next.offset != old.offset
                        val manualEdit =
                            next.rate != old.rate && next.selectedQuote === old.selectedQuote
                        val fields = next.copy(
                            rate = if (pairChanged ||
                                (timeChanged && old.selectedQuote != null)
                            ) {
                                ""
                            } else {
                                next.rate
                            },
                            selectedQuote = if (pairChanged ||
                                timeChanged ||
                                manualEdit
                            ) {
                                null
                            } else {
                                next.selectedQuote
                            }
                        )
                        editor.copy(
                            fields = fields,
                            rateCandidate = if (pairChanged ||
                                timeChanged
                            ) {
                                null
                            } else {
                                editor.rateCandidate
                            },
                            rateProblem = null,
                            rateNotice = null,
                            selectedCategory = if (fields.categoryId ==
                                editor.fields.categoryId
                            ) {
                                editor.selectedCategory
                            } else {
                                state.categories.find {
                                    it.id ==
                                        fields.categoryId
                                }
                            }
                        )
                    },
                    editorIssue = null,
                    error = null
                )
            }
        }
    }
    fun cancelEditor() {
        if (!mutable.value.busy) {
            mutable.update {
                it.copy(editor = null, editorIssue = null, error = null)
            }
        }
    }
    fun saveEditor() {
        val current = mutable.value
        val editor = current.editor ?: return
        val settings = current.settings ?: return
        val now = Clock.System.now()
        val input = buildEntry(editor.fields, settings, editor.original, editor.id, now)
        if (input is InputResult.Failure) {
            mutable.update { it.copy(editorIssue = input) }
            return
        }
        action {
            val entry = (input as InputResult.Success).value
            val result = if (editor.original ==
                null
            ) {
                transactionRepository.postManual(entry)
            } else {
                transactionRepository.updateManual(entry, editor.original.revision, now)
            }
            when (result) {
                is LedgerResult.Failure -> mutable.update { it.copy(error = result.error) }
                is LedgerResult.Success -> {
                    mutable.update { it.copy(editor = null, editorIssue = null) }
                    reload()
                }
            }
        }
    }
    fun findRate() {
        val current = mutable.value
        if (current.busy) return
        val editor = current.editor ?: return
        val settings = current.settings ?: return
        val request = when (val result = rateRequest(editor.fields, settings)) {
            is InputResult.Success -> result.value
            is InputResult.Failure -> {
                mutable.update { it.copy(editorIssue = result) }
                return
            }
        }
        action {
            when (val result = rateCache.latest(request.pair, request.notAfter)) {
                is LedgerResult.Failure -> rateState(null, result.error, null)
                is LedgerResult.Success -> {
                    val candidate = result.value
                    if (candidate != null && !candidate.matches(request)) {
                        rateState(null, LedgerError.CORRUPT_DATA, null)
                    } else {
                        rateState(
                            candidate,
                            null,
                            if (candidate ==
                                null
                            ) {
                                RateNotice.EMPTY
                            } else {
                                null
                            }
                        )
                    }
                }
            }
        }
    }
    fun rememberRate() {
        val current = mutable.value
        if (current.busy) return
        val editor = current.editor ?: return
        if (editor.fields.selectedQuote != null) return
        val settings = current.settings ?: return
        val rate = when (
            val result = manualRateInput(
                editor.fields,
                settings,
                Clock.System.now(),
                editor.original?.entry?.conversion
            )
        ) {
            is InputResult.Success -> result.value
            is InputResult.Failure -> {
                mutable.update { it.copy(editorIssue = result) }
                return
            }
        }
        action {
            when (val result = rateCache.put(rate)) {
                is LedgerResult.Failure -> rateState(null, result.error, null)
                is LedgerResult.Success -> {
                    val saved = result.value.quote
                    val wanted = rate.quote
                    val sameKey = saved.sourceCurrency == wanted.sourceCurrency &&
                        saved.baseCurrency == wanted.baseCurrency &&
                        saved.sourceId == wanted.sourceId &&
                        saved.effectiveAt == wanted.effectiveAt
                    when {
                        !sameKey -> rateState(null, LedgerError.CORRUPT_DATA, null)
                        saved.rate != wanted.rate -> rateState(null, LedgerError.CONFLICT, null)
                        else -> rateState(null, null, RateNotice.SAVED)
                    }
                }
            }
        }
    }
    fun useRateCandidate() {
        val current = mutable.value
        if (current.busy) return
        val editor = current.editor ?: return
        val rate = editor.rateCandidate ?: return
        val settings = current.settings ?: return
        val request = when (val result = rateRequest(editor.fields, settings)) {
            is InputResult.Success -> result.value
            is InputResult.Failure -> {
                mutable.update { it.copy(editorIssue = result) }
                return
            }
        }
        if (!rate.matches(request)) {
            rateState(null, LedgerError.CORRUPT_DATA, null)
            return
        }
        fields { it.copy(rate = rate.quote.rate.canonicalText, selectedQuote = rate.quote) }
    }
    private fun rateState(candidate: CachedRate?, problem: LedgerError?, notice: RateNotice?) {
        mutable.update {
            it.copy(
                editor = it.editor?.copy(
                    rateCandidate = candidate,
                    rateProblem = problem,
                    rateNotice = notice
                ),
                editorIssue = null
            )
        }
    }
    private fun CachedRate.matches(request: RateRequest): Boolean =
        quote.sourceCurrency == request.pair.source &&
            quote.baseCurrency == request.pair.base &&
            quote.effectiveAt <= request.notAfter
    fun askDelete(transaction: StoredTransaction) {
        if (!mutable.value.busy) mutable.update { it.copy(deleteTransaction = transaction) }
    }
    fun cancelDelete() {
        if (!mutable.value.busy) mutable.update { it.copy(deleteTransaction = null) }
    }
    fun confirmDelete() {
        val record = mutable.value.deleteTransaction ?: return
        action {
            mutate(transactionRepository.deleteManual(record.entry.id, record.revision)) {
                mutable.update { it.copy(deleteTransaction = null) }
            }
        }
    }

    fun categoryDialog(type: EntryType, category: Category? = null, fromEditor: Boolean = false) {
        if (!mutable.value.busy) {
            mutable.update {
                it.copy(
                    categoryDialog = CategoryDialogState(
                        category?.id ?: newId(),
                        type,
                        category?.name.orEmpty(),
                        category,
                        fromEditor
                    ),
                    error = null
                )
            }
        }
    }
    fun categoryName(name: String) {
        if (!mutable.value.busy) {
            mutable.update {
                it.copy(categoryDialog = it.categoryDialog?.copy(name = name), error = null)
            }
        }
    }
    fun categoryType(type: EntryType) {
        if (!mutable.value.busy) {
            mutable.update { state ->
                state.copy(
                    categoryDialog = state.categoryDialog?.let {
                        if (it.existing ==
                            null &&
                            !it.fromEditor
                        ) {
                            it.copy(type = type)
                        } else {
                            it
                        }
                    }
                )
            }
        }
    }
    fun closeCategory() {
        if (!mutable.value.busy) mutable.update { it.copy(categoryDialog = null, error = null) }
    }
    fun saveCategory() {
        val dialog = mutable.value.categoryDialog ?: return
        action {
            val result = if (dialog.existing ==
                null
            ) {
                categoryRepository.create(dialog.id, dialog.type, dialog.name)
            } else {
                categoryRepository.rename(dialog.id, dialog.name)
            }
            when (result) {
                is LedgerResult.Failure -> mutable.update { it.copy(error = result.error) }
                is LedgerResult.Success -> {
                    mutable.update { state ->
                        state.copy(
                            categoryDialog = null,
                            editor = if (dialog.fromEditor) {
                                state.editor?.let {
                                    it.copy(
                                        fields = it.fields.copy(categoryId = result.value.id),
                                        selectedCategory = result.value
                                    )
                                }
                            } else {
                                state.editor
                            }
                        )
                    }
                    reload()
                }
            }
        }
    }
    fun archive(category: Category) =
        action { mutate(categoryRepository.archive(category.id, Clock.System.now())) }
    fun restore(category: Category) = action { mutate(categoryRepository.restore(category.id)) }
    fun askDeleteCategory(category: Category) {
        if (!mutable.value.busy) mutable.update { it.copy(deleteCategory = category) }
    }
    fun cancelDeleteCategory() {
        if (!mutable.value.busy) mutable.update { it.copy(deleteCategory = null) }
    }
    fun confirmDeleteCategory() {
        val category = mutable.value.deleteCategory ?: return
        action {
            mutate(categoryRepository.deleteUnused(category.id)) {
                mutable.update { it.copy(deleteCategory = null) }
            }
        }
    }
    private suspend fun <T> mutate(result: LedgerResult<T>, success: () -> Unit = {}) {
        when (result) {
            is LedgerResult.Failure -> mutable.update { it.copy(error = result.error) }
            is LedgerResult.Success -> {
                success()
                reload()
            }
        }
    }
    private fun newId(): LedgerId =
        (LedgerId.parse(UUID.randomUUID().toString()) as LedgerResult.Success).value
}
