package app.finance.android.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.finance.ai.contract.AiContext
import app.finance.ai.contract.AiDraft
import app.finance.ai.contract.AiError
import app.finance.ai.contract.AiResult
import app.finance.ai.contract.DraftStatus
import app.finance.ai.contract.InferenceRequest
import app.finance.ai.contract.LocalInferenceEngine
import app.finance.ai.contract.LocalModelRef
import app.finance.ai.parser.AiDraftParser
import app.finance.android.ledger.EntryFields
import app.finance.android.ledger.InputResult
import app.finance.android.ledger.buildEntry
import app.finance.android.ledger.matchesRequest
import app.finance.data.repository.LedgerStore
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock

internal data class AiPreview(
    val index: Int,
    val id: LedgerId,
    val fields: EntryFields,
    val confirmed: Boolean = false,
    val canceled: Boolean = false,
    val inputError: InputResult.Failure? = null,
    val ledgerError: LedgerError? = null,
    val rateCandidate: app.finance.domain.exchange.CachedRate? = null,
    val rateProblem: LedgerError? = null,
    val rateNotice: app.finance.android.ledger.RateNotice? = null
) {
    override fun toString() = "AiPreview"
}
internal data class AiUiState(
    val prompt: String = "",
    val busy: Boolean = false,
    val generating: Boolean = false,
    val error: AiError? = null,
    val draft: AiDraft? = null,
    val previews: List<AiPreview> = emptyList(),
    val savedVersion: Long = 0,
    val clarification: Boolean = false,
    val unsupported: Boolean = false,
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val connectionError: AiError? = null,
    val connectionStatus: ModelConnection = ModelConnection(),
    val diagnosticMode: Boolean = false,
    val generationProgress: GenerationProgress? = null,
    val diagnosticCanceled: Boolean = false
) {
    override fun toString() = "AiUiState"
}

internal class AiViewModel(
    private val store: LedgerStore,
    private val engine: LocalInferenceEngine? = null,
    private val model: LocalModelRef? = null,
    val experimental: Boolean = false,
    private val clock: Clock = Clock.System
) : ViewModel() {
    private val mutable = MutableStateFlow(AiUiState())
    val state = mutable.asStateFlow()
    val configured: Boolean get() = engine != null && model != null
    val available: Boolean get() = state.value.connected
    private val runtime = Mutex()
    private var connection: Job? = null
    private var connectionEpoch = 0L
    private var userCanceledGeneration = false
    fun connect() {
        if (state.value.busy || state.value.connected || state.value.previews.isNotEmpty()) return
        val e = engine
        val m = model
        if (e == null || m == null) {
            mutable.update {
                it.copy(
                    connectionError = AiError.MODEL_UNAVAILABLE,
                    connectionStatus = ModelConnection(failure = ConnectionFailure.RUNTIME_MISSING)
                )
            }
            return
        }
        val previous = connection
        val token = ++connectionEpoch
        mutable.update {
            it.copy(
                busy = true,
                connecting = true,
                connected = false,
                error = null,
                connectionError = null,
                connectionStatus = ModelConnection()
            )
        }
        connection = viewModelScope.launch {
            try {
                previous?.join()
                val result = withContext(Dispatchers.IO) {
                    withTimeout(120_000) {
                        val attempt = currentCoroutineContext()
                        runtime.withLock {
                            if (e is ConnectionReportingEngine) {
                                e.loadModel(m) { status ->
                                    if (token == connectionEpoch && attempt.isActive) {
                                        mutable.update { it.copy(connectionStatus = status) }
                                    }
                                }
                            } else {
                                e.loadModel(m)
                            }
                        }
                    }
                }
                when (result) {
                    is AiResult.Success -> {
                        mutable.update {
                            it.copy(busy = false, connecting = false, connected = true)
                        }
                        // Keep ownership until screen/lifecycle/ViewModel cancellation.
                        awaitCancellation()
                    }
                    is AiResult.Failure -> mutable.update {
                        it.copy(
                            connectionError = result.error,
                            connectionStatus = it.connectionStatus.copy(
                                failure =
                                it.connectionStatus.failure ?: connectionFailure(result.error)
                            )
                        )
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                mutable.update {
                    it.copy(
                        connectionError = AiError.TIMEOUT,
                        connectionStatus = it.connectionStatus.copy(
                            failure = ConnectionFailure.TIMEOUT
                        )
                    )
                }
            } catch (
                cancel: CancellationException
            ) {
                throw cancel
            } catch (
                _: OutOfMemoryError
            ) {
                mutable.update {
                    it.copy(
                        connectionError = AiError.OUT_OF_MEMORY,
                        connectionStatus = it.connectionStatus.copy(
                            failure = ConnectionFailure.MEMORY
                        )
                    )
                }
            } catch (
                _: Exception
            ) {
                mutable.update {
                    it.copy(
                        connectionError = AiError.ENGINE_FAILURE,
                        connectionStatus = it.connectionStatus.copy(
                            failure = ConnectionFailure.LOAD_FAILED
                        )
                    )
                }
            } finally {
                try {
                    withContext(NonCancellable + Dispatchers.IO) { runtime.withLock { e.unload() } }
                } catch (
                    cancel: CancellationException
                ) {
                    throw cancel
                } catch (_: Exception) {
                    if (token ==
                        connectionEpoch
                    ) {
                        mutable.update { it.copy(connectionError = AiError.ENGINE_FAILURE) }
                    }
                } finally {
                    if (token == connectionEpoch) {
                        mutable.update {
                            it.copy(
                                busy = if (it.connecting) false else it.busy,
                                connecting = false,
                                connected = false
                            )
                        }
                    }
                }
            }
        }
    }
    fun disconnect() {
        userCanceledGeneration = false
        cancelGeneration()
        connection?.cancel()
        mutable.update {
            it.copy(
                connected = false,
                connectionError = null,
                connectionStatus = ModelConnection(ConnectionStage.STOPPING)
            )
        }
    }
    private var generation: Job? = null
    private var epoch = 0L
    private fun id() = (LedgerId.parse(UUID.randomUUID().toString()) as LedgerResult.Success).value
    fun prompt(text: String) {
        if (!state.value.busy && state.value.previews.isEmpty()) {
            mutable.update {
                it.copy(prompt = text.take(4097), error = null)
            }
        }
    }
    fun diagnostics(enabled: Boolean) {
        if (experimental && !state.value.busy && state.value.previews.isEmpty()) {
            mutable.update {
                it.copy(
                    diagnosticMode = enabled,
                    generationProgress = null,
                    diagnosticCanceled = false
                )
            }
        }
    }
    fun generate() {
        if (state.value.busy || state.value.previews.isNotEmpty() || !available) return
        val e = engine
        if (e == null ||
            model == null
        ) {
            mutable.update { it.copy(error = AiError.MODEL_UNAVAILABLE) }
            return
        }
        val text = state.value.prompt
        val diagnostic = experimental && state.value.diagnosticMode
        val token = ++epoch
        mutable.update {
            it.copy(
                busy = true,
                generating = true,
                error = null,
                clarification = false,
                unsupported = false,
                generationProgress = GenerationProgress(),
                diagnosticCanceled = false
            )
        }
        userCanceledGeneration = false
        generation = viewModelScope.launch {
            var releaseRuntime = false
            var releaseError: AiError? = null
            try {
                withContext(Dispatchers.IO) {
                    runtime.withLock {
                        val settings = (store.settings.current() as? LedgerResult.Success)?.value
                        if (settings ==
                            null
                        ) {
                            publishError(token, AiError.INVALID_CONTEXT)
                            return@withContext
                        }
                        val categories = (
                            store.categories.list(
                                limit = 200
                            ) as? LedgerResult.Success
                            )?.value
                        if (categories == null ||
                            (
                                categories.size == 200 &&
                                    (
                                        store.categories.list(
                                            limit = 1,
                                            offset = 200
                                        ) as? LedgerResult.Success
                                        )?.value?.isNotEmpty() !=
                                    false
                                )
                        ) {
                            publishError(token, AiError.INVALID_CONTEXT)
                            return@withContext
                        }
                        val c = AiContext.create(clock.now(), settings, categories)
                        if (c !is AiResult.Success) {
                            publishError(token, (c as AiResult.Failure).error)
                            return@withContext
                        }
                        val request = InferenceRequest.create(text, c.value)
                        if (request !is AiResult.Success) {
                            publishError(token, (request as AiResult.Failure).error)
                            return@withContext
                        }
                        val attempt = currentCoroutineContext()
                        val output = if (e is DiagnosticInferenceEngine) {
                            e.generate(
                                request.value,
                                GenerationOptions(diagnostic = diagnostic)
                            ) { progress ->
                                if (token == epoch && attempt.isActive) {
                                    mutable.update { it.copy(generationProgress = progress) }
                                }
                            }
                        } else {
                            e.generate(request.value)
                        }
                        when (output) {
                            is AiResult.Failure -> {
                                releaseRuntime = output.error.requiresModelReload()
                                releaseError = output.error
                                publishError(token, output.error)
                            }
                            is AiResult.Success -> when (
                                val parsed = AiDraftParser().parse(
                                    output.value.text,
                                    c.value,
                                    id()
                                )
                            ) {
                                is AiResult.Failure -> publishError(token, parsed.error)
                                is AiResult.Success -> if (token == epoch &&
                                    currentCoroutineContext().isActive
                                ) {
                                    val d = parsed.value
                                    mutable.update {
                                        it.copy(
                                            draft = d,
                                            previews = d.proposals.mapIndexed { index, p ->
                                                AiPreview(
                                                    index,
                                                    id(),
                                                    resolveDraftTime(
                                                        EntryFields(
                                                            p.type,
                                                            p.money.formatDecimal(),
                                                            p.money.currency.code,
                                                            p.categoryId,
                                                            "",
                                                            p.date.toString() + "T",
                                                            c.value.settings.zoneId,
                                                            note = p.description
                                                        ),
                                                        p.date,
                                                        text,
                                                        d.context.reference,
                                                        d.proposals.size
                                                    )
                                                )
                                            },
                                            clarification =
                                            d.status == DraftStatus.NEEDS_CLARIFICATION &&
                                                d.proposals.isEmpty(),
                                            unsupported = d.status == DraftStatus.UNSUPPORTED
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (
                cancel: CancellationException
            ) {
                releaseRuntime = true
                throw cancel
            } catch (
                _: OutOfMemoryError
            ) {
                releaseRuntime = true
                releaseError = AiError.OUT_OF_MEMORY
                publishError(token, AiError.OUT_OF_MEMORY)
            } catch (
                _: Exception
            ) {
                releaseRuntime = true
                releaseError = AiError.ENGINE_FAILURE
                publishError(token, AiError.ENGINE_FAILURE)
            } finally {
                try {
                    if (releaseRuntime) {
                        mutable.update {
                            it.copy(
                                connected = false,
                                connectionError =
                                releaseError
                                    ?: if (userCanceledGeneration) {
                                        AiError.MODEL_UNAVAILABLE
                                    } else {
                                        null
                                    }
                            )
                        }
                        connection?.cancel()
                        withContext(NonCancellable) { connection?.join() }
                    }
                } finally {
                    mutable.update { it.copy(busy = false, generating = false) }
                }
            }
        }
    }
    private fun publishError(token: Long, error: AiError) {
        if (token ==
            epoch
        ) {
            mutable.update { it.copy(error = error, draft = null, previews = emptyList()) }
        }
    }
    fun cancelGeneration() {
        epoch++
        generation?.cancel()
    }
    fun cancel() {
        userCanceledGeneration = true
        cancelGeneration()
        if (!state.value.busy || state.value.generating) {
            mutable.update {
                AiUiState(
                    busy = it.generating && it.busy,
                    generating = it.generating && it.busy,
                    savedVersion = it.savedVersion,
                    connected = it.connected,
                    connecting = it.connecting,
                    connectionError = it.connectionError,
                    connectionStatus = it.connectionStatus,
                    diagnosticMode = it.diagnosticMode,
                    generationProgress = it.generationProgress,
                    diagnosticCanceled = it.generating && it.generationProgress != null
                )
            }
        }
    }
    fun edit(index: Int, change: (EntryFields) -> EntryFields) {
        if (state.value.busy) return
        mutable.update {
            it.copy(
                previews = it.previews.map { p ->
                    if (p.index == index &&
                        !p.confirmed &&
                        !p.canceled
                    ) {
                        val next = change(p.fields)
                        val old = p.fields
                        val pairChanged = next.currency.trim().uppercase() !=
                            old.currency.trim().uppercase()
                        val timeChanged = next.localTime != old.localTime ||
                            next.zoneId != old.zoneId ||
                            next.offset != old.offset
                        val changed = pairChanged || timeChanged
                        val manualEdit =
                            next.rate != old.rate && next.selectedQuote === old.selectedQuote
                        p.copy(
                            fields = next.copy(
                                rate = if (pairChanged ||
                                    (timeChanged && old.selectedQuote != null)
                                ) {
                                    ""
                                } else {
                                    next.rate
                                },
                                selectedQuote = if (changed ||
                                    manualEdit
                                ) {
                                    null
                                } else {
                                    next.selectedQuote
                                }
                            ),
                            inputError = null,
                            ledgerError = null,
                            rateCandidate = if (changed) null else p.rateCandidate,
                            rateNotice = null,
                            rateProblem = null
                        )
                    } else {
                        p
                    }
                }
            )
        }
    }
    private fun rateAction(index: Int, block: suspend (AiDraft, AiPreview) -> AiPreview) {
        val s = state.value
        if (s.busy) return
        val draft = s.draft ?: return
        val preview =
            s.previews.singleOrNull { it.index == index && !it.confirmed && !it.canceled } ?: return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) { block(draft, preview) }
                mutable.update { current ->
                    if (current.draft?.id != draft.id) {
                        current
                    } else {
                        current.copy(
                            previews = current.previews.map {
                                if (it.id ==
                                    preview.id
                                ) {
                                    updated
                                } else {
                                    it
                                }
                            }
                        )
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                mutable.update { current ->
                    current.copy(
                        previews = current.previews.map {
                            if (it.id ==
                                preview.id
                            ) {
                                it.copy(rateProblem = LedgerError.STORAGE_FAILURE)
                            } else {
                                it
                            }
                        }
                    )
                }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }
    fun findRate(index: Int) = rateAction(index) { draft, preview ->
        when (
            val request = app.finance.android.ledger.rateRequest(
                preview.fields,
                draft.context.settings
            )
        ) {
            is InputResult.Failure -> preview.copy(inputError = request)
            is InputResult.Success -> when (
                val found = store.rates.latest(
                    request.value.pair,
                    request.value.notAfter
                )
            ) {
                is LedgerResult.Failure -> preview.copy(
                    rateCandidate = null,
                    rateProblem = found.error
                )
                is LedgerResult.Success -> {
                    val rate = found.value
                    if (rate != null && !rate.matchesRequest(request.value)) {
                        preview.copy(rateCandidate = null, rateProblem = LedgerError.CORRUPT_DATA)
                    } else {
                        preview.copy(
                            rateCandidate = rate,
                            rateProblem = null,
                            inputError = null,
                            rateNotice = if (rate ==
                                null
                            ) {
                                app.finance.android.ledger.RateNotice.EMPTY
                            } else {
                                null
                            }
                        )
                    }
                }
            }
        }
    }
    fun rememberRate(index: Int) = rateAction(index) { draft, preview ->
        when (
            val input = app.finance.android.ledger.manualRateInput(
                preview.fields,
                draft.context.settings,
                clock.now()
            )
        ) {
            is InputResult.Failure -> preview.copy(inputError = input)
            is InputResult.Success -> when (val saved = store.rates.put(input.value)) {
                is LedgerResult.Failure -> preview.copy(rateProblem = saved.error)
                is LedgerResult.Success -> {
                    val wanted = input.value.quote
                    val got = saved.value.quote
                    val problem = when {
                        got.sourceCurrency != wanted.sourceCurrency ||
                            got.baseCurrency != wanted.baseCurrency ||
                            got.effectiveAt != wanted.effectiveAt ||
                            got.sourceId != wanted.sourceId -> LedgerError.CORRUPT_DATA
                        got.rate != wanted.rate -> LedgerError.CONFLICT
                        else -> null
                    }
                    preview.copy(
                        inputError = null,
                        rateProblem = problem,
                        rateNotice = if (problem ==
                            null
                        ) {
                            app.finance.android.ledger.RateNotice.SAVED
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }
    fun useRateCandidate(index: Int) {
        val s = state.value
        if (s.busy) return
        val draft = s.draft ?: return
        val preview =
            s.previews.singleOrNull { it.index == index && !it.confirmed && !it.canceled } ?: return
        val candidate = preview.rateCandidate ?: return
        val request = app.finance.android.ledger.rateRequest(
            preview.fields,
            draft.context.settings
        )
        if (request !is InputResult.Success || !candidate.matchesRequest(request.value)) return
        edit(index) {
            it.copy(rate = candidate.quote.rate.canonicalText, selectedQuote = candidate.quote)
        }
    }
    fun discard(index: Int) {
        if (!state.value.busy) {
            mutable.update {
                it.copy(
                    previews = it.previews.map { p ->
                        if (p.index ==
                            index &&
                            !p.confirmed
                        ) {
                            p.copy(canceled = true)
                        } else {
                            p
                        }
                    }
                )
            }
        }
    }
    fun confirm(index: Int) {
        val s = state.value
        if (s.busy) return
        val d = s.draft ?: return
        val preview =
            s.previews.singleOrNull { it.index == index && !it.confirmed && !it.canceled } ?: return
        val proposal = d.proposals.getOrNull(index) ?: return
        val fields = resolveDraftTime(
            preview.fields,
            proposal.date,
            s.prompt,
            d.context.reference,
            d.proposals.size
        )
        if (fields != preview.fields) {
            mutable.update { state ->
                state.copy(
                    previews = state.previews.map { p ->
                        if (p.index == index) p.copy(fields = fields) else p
                    }
                )
            }
        }
        val result =
            buildEntry(fields, d.context.settings, null, preview.id, d.context.reference)
        if (result is InputResult.Failure) {
            mutable.update {
                it.copy(
                    previews = it.previews.map { p ->
                        if (p.index ==
                            index
                        ) {
                            p.copy(inputError = result)
                        } else {
                            p
                        }
                    }
                )
            }
            return
        }
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val saved =
                    withContext(Dispatchers.IO) {
                        store.aiConfirmations.confirm(
                            d.id,
                            index,
                            (result as InputResult.Success).value
                        )
                    }
                mutable.update {
                    it.copy(
                        previews = it.previews.map { p ->
                            if (p.index !=
                                index
                            ) {
                                p
                            } else {
                                when (saved) {
                                    is LedgerResult.Success -> p.copy(
                                        confirmed = true,
                                        ledgerError = null
                                    )
                                    is LedgerResult.Failure -> p.copy(ledgerError = saved.error)
                                }
                            }
                        },
                        savedVersion = it.savedVersion + if (saved is LedgerResult.Success) 1 else 0
                    )
                }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }
}
