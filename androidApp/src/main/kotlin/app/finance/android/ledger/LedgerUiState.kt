package app.finance.android.ledger

import app.finance.core.money.CurrencyCode
import app.finance.domain.exchange.CachedRate
import app.finance.domain.ledger.Category
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.HistoryCursor
import app.finance.domain.ledger.HistoryItem
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerFilter
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerOverview
import app.finance.domain.ledger.LedgerSettings
import app.finance.domain.ledger.StoredTransaction

internal data class EditorState(
    val id: LedgerId,
    val fields: EntryFields,
    val original: StoredTransaction? = null,
    val selectedCategory: Category? = null,
    val originalCategory: Category? = selectedCategory,
    val rateCandidate: CachedRate? = null,
    val rateProblem: LedgerError? = null,
    val rateNotice: RateNotice? = null
) {
    override fun toString() = "EditorState"
}
internal data class CategoryDialogState(
    val id: LedgerId,
    val type: EntryType,
    val name: String = "",
    val existing: Category? = null,
    val fromEditor: Boolean = false
) {
    override fun toString() = "CategoryDialogState"
}
internal data class LedgerUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val fatalError: LedgerError? = null,
    val error: LedgerError? = null,
    val settings: LedgerSettings? = null,
    val legacyBasis: CurrencyCode? = null,
    val setupIssue: InputIssue? = null,
    val overview: LedgerOverview? = null,
    val budget: app.finance.domain.budget.BudgetOverview? = null,
    val analytics: app.finance.domain.analytics.SpendingSnapshot? = null,
    val analyticsReference: kotlinx.datetime.Instant? = null,
    val analyticsLoading: Boolean = false,
    val analyticsError: LedgerError? = null,
    val budgetError: LedgerError? = null,
    val budgetSaveError: LedgerError? = null,
    val budgetSaveVersion: Long = 0,

    val overviewError: LedgerError? = null,
    val categories: List<Category> = emptyList(),
    val moreCategories: Boolean = false,
    val history: List<HistoryItem> = emptyList(),
    val cursor: HistoryCursor? = null,
    val historyError: LedgerError? = null,
    val filter: LedgerFilter = LedgerFilter.ALL,
    val editor: EditorState? = null,
    val editorIssue: InputResult.Failure? = null,
    val editorSaveAttempt: Long = 0,
    val categoryDialog: CategoryDialogState? = null,
    val deleteTransaction: StoredTransaction? = null,
    val deleteCategory: Category? = null
) {
    override fun toString() = "LedgerUiState"
}

internal enum class RateNotice { EMPTY, SAVED }
