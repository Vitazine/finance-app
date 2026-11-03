package app.finance.android

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import app.finance.ai.contract.AiError
import app.finance.ai.contract.AiResult
import app.finance.ai.contract.InferenceRequest
import app.finance.ai.contract.InferenceText
import app.finance.ai.contract.LocalModelRef
import app.finance.android.ai.AiScreen
import app.finance.android.ai.AiViewModel
import app.finance.android.ai.ConnectionFailure
import app.finance.android.ai.ConnectionReportingEngine
import app.finance.android.ai.ConnectionStage
import app.finance.android.ai.DiagnosticInferenceEngine
import app.finance.android.ai.GenerationOptions
import app.finance.android.ai.GenerationPhase
import app.finance.android.ai.GenerationProgress
import app.finance.android.ai.ModelConnection
import app.finance.data.database.openLedgerStore
import app.finance.data.repository.LedgerStore
import app.finance.domain.ledger.LedgerResult
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AiConnectionUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "ai-connect-" + UUID.randomUUID() + ".db"
    private lateinit var store: LedgerStore
    private lateinit var model: AiViewModel
    private val models = ViewModelStore()
    private val visible = mutableStateOf(true)
    private val dark = mutableStateOf(false)
    private val engine = ProbeEngine()

    @Before fun setup() = runBlocking<Unit> {
        store = openLedgerStore(context, name)
        val currency = (
            app.finance.core.money.CurrencyCode.resolve(
                "USD"
            ) as app.finance.core.money.MoneyResult.Success
            ).value
        store.settings.initialize(
            (
                app.finance.domain.ledger.LedgerSettings.create(
                    currency,
                    "UTC"
                ) as LedgerResult.Success
                ).value
        )
    }

    @After fun close() {
        val job = if (::model.isInitialized) model.viewModelScope.coroutineContext[Job] else null
        compose.runOnUiThread { models.clear() }
        runBlocking { withTimeout(15000) { job?.join() } }
        store.close()
        context.deleteDatabase(name)
    }
    private fun render(experimental: Boolean = false) {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = models
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(c: Class<T>): T = AiViewModel(
                store,
                engine,
                (LocalModelRef.create("fixture") as AiResult.Success).value,
                experimental = experimental
            ) as T
        }
        compose.runOnUiThread { model = ViewModelProvider(owner, factory)[AiViewModel::class.java] }
        compose.setContent {
            FinanceTheme(dark.value) {
                androidx.compose.material3.Surface {
                    if (visible.value) AiScreen(model, {}, {})
                }
            }
        }
    }
    private fun waitTag(tag: String) {
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun saveProof(name: String) {
        if (InstrumentationRegistry.getArguments().getString("saveProof") != "true") return
        compose.waitForIdle()
        // System UI applies window appearance asynchronously after Compose becomes idle.
        android.os.SystemClock.sleep(350)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(context.filesDir, name + ".png").outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test fun debugDiagnosticsAreExplicitAndAbsentFromOrdinaryUi() {
        render(experimental = true)
        waitTag("ai_model_ready")
        compose.onNodeWithTag("ai_diagnostic_toggle").assertExists()
        compose.onNodeWithTag("ai_diagnostic_toggle").performClick()
        compose.onNodeWithTag("ai_diagnostic_notice")
            .assertTextContains("10 минут", substring = true)
        val count =
            runBlocking { (store.transactions.overview() as LedgerResult.Success).value.count }
        assertEquals(0L, count)
    }

    @Test fun ordinaryUiDoesNotOfferDiagnosticMode() {
        render()
        waitTag("ai_model_ready")
        compose.onNodeWithTag("ai_diagnostic_toggle").assertDoesNotExist()
    }

    @Test fun diagnosticGenerationReportsOnlyMetricsAndIgnoresLateCanceledCallback() {
        engine.suspendGenerate = true
        render(experimental = true)
        waitTag("ai_model_ready")
        compose.onNodeWithTag("ai_diagnostic_toggle").performClick()
        compose.runOnUiThread {
            model.prompt("Synthetic fixture")
            model.generate()
        }
        compose.waitUntil(5000) { engine.generations.get() == 1 }
        waitTag("ai_diagnostic_phase")
        compose.onNodeWithTag(
            "ai_diagnostic_phase"
        ).assertTextContains("обработка входного", substring = true)
        compose.onNodeWithTag(
            "ai_diagnostic_prompt"
        ).assertTextContains("256 из 900", substring = true)
        compose.onNodeWithTag("ai_diagnostic_elapsed").assertTextContains("12 с", substring = true)
        compose.onNodeWithTag(
            "ai_diagnostic_first"
        ).assertTextContains("ещё не получен", substring = true)
        assertEquals(600_000L, engine.options!!.timeoutMs)
        val oldReport = engine.generationReport!!
        compose.runOnUiThread { model.cancel() }
        compose.waitUntil(5000) { !model.state.value.busy }
        val stopped = model.state.value.generationProgress
        compose.runOnUiThread {
            oldReport(GenerationProgress(phase = GenerationPhase.FINISHED, outputTokens = 77))
        }
        assertEquals(stopped, model.state.value.generationProgress)
        assertEquals(true, model.state.value.diagnosticCanceled)
        assertEquals(false, model.available)
        assertEquals(
            0L,
            runBlocking {
                (store.transactions.overview() as LedgerResult.Success).value.count
            }
        )
    }

    @Test fun ordinaryGenerationKeepsDefaultTimeoutAndReportsProgress() {
        render(experimental = true)
        waitTag("ai_model_ready")
        compose.runOnUiThread {
            model.prompt("Synthetic fixture")
            model.generate()
        }
        compose.waitUntil(5000) { engine.generations.get() == 1 && !model.state.value.busy }
        assertEquals(false, engine.options!!.diagnostic)
        assertEquals(120_000L, engine.options!!.timeoutMs)
        assertEquals(256, model.state.value.generationProgress!!.processedTokens)
    }

    @Test fun ordinaryRequestShowsInputProgressThenIndeterminateAnswerAndCancel() {
        engine.suspendGenerate = true
        render(experimental = true)
        waitTag("ai_model_ready")
        compose.runOnUiThread {
            model.prompt("Synthetic fixture")
            model.generate()
        }
        waitTag("ai_generation_phase")
        compose.onNodeWithTag("ai_generation_phase")
            .assertTextContains("Обработка запроса", substring = true)
        compose.onNodeWithTag("ai_loading").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(256f / 900f, 0f..1f)
            )
        )
        saveProof("day09-generation-light")
        compose.runOnIdle { dark.value = true }
        saveProof("day09-generation-dark")
        val bounds = compose.onNodeWithTag("ai_loading").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("Must be a horizontal bar", bounds.width > bounds.height * 8)
        val report = engine.generationReport!!
        compose.runOnUiThread {
            report(GenerationProgress(GenerationPhase.GENERATING, outputTokens = 5))
        }
        compose.onNodeWithTag("ai_generation_phase")
            .assertTextContains("Модель формирует ответ", substring = true)
        compose.onNodeWithTag("ai_loading").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate
            )
        )
        compose.onNodeWithTag("ai_cancel_generation").performClick()
        compose.waitUntil(5000) { !model.state.value.busy }
        compose.onNodeWithTag("ai_loading").assertDoesNotExist()
        val stopped = model.state.value.generationProgress
        compose.runOnUiThread { report(GenerationProgress(outputTokens = 77)) }
        assertEquals(stopped, model.state.value.generationProgress)
        assertEquals(120_000L, engine.options!!.timeoutMs)
        assertEquals(
            0L,
            runBlocking {
                (store.transactions.overview() as LedgerResult.Success).value.count
            }
        )
    }

    @Test fun rejectedModelOutputKeepsConnectionAndAllowsExplicitRetryWithoutWrites() {
        render(experimental = true)
        waitTag("ai_model_ready")
        for (reason in listOf(
            AiError.INVALID_AMOUNT,
            AiError.INVALID_DATE,
            AiError.INVALID_CURRENCY,
            AiError.INVALID_JSON,
            AiError.INVALID_SCHEMA,
            AiError.OUTPUT_TOO_LONG
        )) {
            engine.generationError = reason
            compose.runOnUiThread {
                model.prompt("Synthetic fixture")
                model.generate()
            }
            compose.waitUntil(5000) { !model.state.value.busy && model.state.value.error == reason }
            compose.onNodeWithTag("ai_model_error").assertDoesNotExist()
            compose.onNodeWithTag("ai_error").assertExists()
            org.junit.Assert.assertTrue(
                "A rejected answer must not unload the model",
                model.available
            )
            assertEquals(0, engine.unloads.get())
        }
        engine.generationError = null
        compose.runOnUiThread { model.generate() }
        compose.waitUntil(5000) { !model.state.value.busy && model.state.value.unsupported }
        assertEquals(1, engine.loads.get())
        assertEquals(
            0L,
            runBlocking {
                (store.transactions.overview() as LedgerResult.Success).value.count
            }
        )
    }

    @Test fun generationEngineFailureIsNotPresentedAsFailedConnectionAndRetryCanRecover() {
        engine.generationError = AiError.ENGINE_FAILURE
        render(experimental = true)
        waitTag("ai_model_ready")
        compose.runOnUiThread {
            model.prompt("Synthetic fixture")
            model.generate()
        }
        compose.waitUntil(5000) { !model.state.value.busy && model.state.value.error != null }
        compose.onNodeWithTag("ai_model_error").assertDoesNotExist()
        compose.onNodeWithTag("ai_error").assertTextContains("подготовки ответа", substring = true)
        assertEquals(false, model.available)
        assertEquals(1, engine.loads.get())
        compose.runOnUiThread { model.prompt("Another synthetic fixture") }
        compose.onNodeWithTag("ai_model_error").assertDoesNotExist()
        engine.generationError = null
        compose.onNodeWithTag("ai_connect_retry").performClick()
        waitTag("ai_model_ready")
        compose.onNodeWithTag("ai_error").assertDoesNotExist()
        assertEquals(2, engine.loads.get())
        assertEquals(
            0L,
            runBlocking {
                (store.transactions.overview() as LedgerResult.Success).value.count
            }
        )
    }

    @Test fun ordinaryTimeoutExplainsExplicitDiagnosticOptionWithoutChangingDeadline() {
        engine.generationError = AiError.TIMEOUT
        render(experimental = true)
        waitTag("ai_model_ready")
        compose.runOnUiThread {
            model.prompt("Synthetic fixture")
            model.generate()
        }
        compose.waitUntil(5000) {
            !model.state.value.busy &&
                model.state.value.error == AiError.TIMEOUT
        }
        compose.onNodeWithTag("ai_model_error").assertDoesNotExist()
        compose.onNodeWithTag("ai_timeout_hint").assertTextContains("10 минут", substring = true)
        assertEquals(false, model.state.value.diagnosticMode)
        assertEquals(120_000L, engine.options!!.timeoutMs)
    }

    @Test fun entryAutomaticallyLoadsOnceWithoutGeneratingOrWriting() {
        render()
        waitTag("ai_model_ready")
        assertEquals(1, engine.loads.get())
        assertEquals(0, engine.generations.get())
        val count =
            runBlocking { (store.transactions.overview() as LedgerResult.Success).value.count }
        assertEquals(0L, count)
    }

    @Test fun leavingDuringLoadingCancelsAndReentryCanConnectAgain() {
        engine.suspendLoad = true
        render()
        waitTag("ai_model_loading")
        compose.waitUntil(5000) { engine.loads.get() == 1 }
        compose.runOnUiThread { visible.value = false }
        compose.waitUntil(5000) { !model.state.value.busy && engine.unloads.get() > 0 }
        engine.suspendLoad = false
        compose.runOnUiThread { visible.value = true }
        waitTag("ai_model_ready")
        assertEquals(2, engine.loads.get())
        assertEquals(0, engine.generations.get())
    }

    @Test fun failedLoadStaysUnavailableUntilExplicitRetry() {
        engine.fail = true
        render()
        waitTag("ai_model_error")
        assertEquals(1, engine.loads.get())
        compose.onNodeWithTag("ai_model_ready").assertDoesNotExist()
        engine.fail = false
        compose.onNodeWithTag("ai_connect_retry").performClick()
        waitTag("ai_model_ready")
        assertEquals(2, engine.loads.get())
    }

    @Test fun loadingShowsStageAndElapsedTimeAndCancellationReturnsToIdle() {
        engine.suspendLoad = true
        render()
        waitTag("ai_model_loading")
        compose.onNodeWithTag(
            "ai_connection_stage"
        ).assertTextContains("Проверка устройства", substring = true)
        compose.onNodeWithTag(
            "ai_connection_elapsed"
        ).assertTextContains("Прошло", substring = true)
        compose.onNodeWithTag("ai_connect_cancel").performClick()
        compose.waitUntil(5000) { !model.state.value.busy }
        compose.onNodeWithTag(
            "ai_model_idle"
        ).assertTextContains("Подключение остановлено", substring = true)
        assertEquals(1, engine.loads.get())
        compose.onNodeWithTag("ai_model_loading").assertDoesNotExist()
    }

    @Test fun hashProgressIsVisibleAndLateCallbackCannotOverwriteCanceledAttempt() {
        engine.suspendLoad = true
        engine.reportStatus = ModelConnection(ConnectionStage.VERIFY, 37)
        render()
        waitTag("ai_connection_percent")
        compose.onNodeWithTag("ai_connection_percent").assertTextContains("37%", substring = true)
        compose.onNodeWithTag(
            "ai_connection_stage"
        ).assertTextContains("Проверка целостности", substring = true)
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("ai_connection_elapsed").fetchSemanticsNodes()
                .any {
                    it.config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                        .any { text -> text.text != "Прошло: 0 с" }
                }
        }
        val oldReport = engine.lastReport!!
        compose.onNodeWithTag("ai_connect_cancel").performClick()
        compose.waitUntil(5000) { !model.state.value.busy }
        compose.runOnUiThread { oldReport(ModelConnection(ConnectionStage.LOAD)) }
        assertEquals(ConnectionStage.STOPPING, model.state.value.connectionStatus.stage)
        engine.suspendLoad = false
        engine.reportStatus = null
        compose.onNodeWithTag("ai_connect_retry").performClick()
        waitTag("ai_model_ready")
        compose.runOnUiThread { oldReport(ModelConnection(ConnectionStage.VERIFY, 99)) }
        assertEquals(null, model.state.value.connectionStatus.percent)
        assertEquals(true, model.available)
        assertEquals(2, engine.loads.get())
    }

    @Test fun connectionFailureExplainsActionInsteadOfGenericUnavailable() {
        engine.fail = true
        render()
        waitTag("ai_model_error")
        compose.onNodeWithTag(
            "ai_model_error"
        ).assertTextContains("Не удалось подключить модель", substring = true)
        compose.onNodeWithTag("ai_model_error").assertTextContains("Повторите", substring = true)
        assertEquals(false, model.available)
    }

    @Test fun preciseChecksumFailureIsShownAndRetryClearsIt() {
        engine.fail = true
        engine.reportStatus =
            ModelConnection(ConnectionStage.VERIFY, 100, ConnectionFailure.MODEL_HASH)
        render()
        waitTag("ai_model_error")
        compose.onNodeWithTag(
            "ai_model_error"
        ).assertTextContains("Контрольная сумма", substring = true)
        assertEquals(ConnectionFailure.MODEL_HASH, model.state.value.connectionStatus.failure)
        engine.fail = false
        engine.reportStatus = null
        compose.onNodeWithTag("ai_connect_retry").performClick()
        waitTag("ai_model_ready")
        assertEquals(null, model.state.value.connectionStatus.failure)
    }

    @Test fun loadTimeoutStopsAutomaticRetryAndShowsFailure() {
        engine.timeoutLoad = true
        render()
        waitTag("ai_model_error")
        assertEquals(AiError.TIMEOUT, model.state.value.connectionError)
        assertEquals(1, engine.loads.get())
    }

    @Test fun successfulGenerationRetainsTheActuallyLoadedRuntime() {
        render()
        waitTag("ai_model_ready")
        compose.runOnUiThread {
            model.prompt("Synthetic")
            model.generate()
        }
        compose.waitUntil(5000) { engine.generations.get() == 1 && !model.state.value.busy }
        assertEquals(true, engine.loaded)
        assertEquals(true, model.available)
        assertEquals(1, engine.loads.get())
    }

    @Test fun generationCancellationClearsReadinessAndReleasesRuntime() {
        engine.suspendGenerate = true
        render()
        waitTag("ai_model_ready")
        compose.runOnUiThread {
            model.prompt("Synthetic")
            model.generate()
        }
        compose.waitUntil(5000) { engine.generations.get() == 1 }
        compose.runOnUiThread { model.cancel() }
        compose.waitUntil(5000) { !model.state.value.busy }
        assertEquals(false, engine.loaded)
        assertEquals(false, model.available)
    }

    private class ProbeEngine :
        ConnectionReportingEngine,
        DiagnosticInferenceEngine {
        val loads = AtomicInteger()
        val unloads = AtomicInteger()
        val generations = AtomicInteger()

        @Volatile var suspendLoad = false

        @Volatile var fail = false

        @Volatile var loaded = false

        @Volatile var timeoutLoad = false

        @Volatile var suspendGenerate = false

        @Volatile var reportStatus: ModelConnection? = null

        @Volatile var lastReport: ((ModelConnection) -> Unit)? = null
        override suspend fun loadModel(
            model: LocalModelRef,
            report: (ModelConnection) -> Unit
        ): AiResult<Unit> {
            lastReport = report
            reportStatus?.let(report)
            loads.incrementAndGet()
            if (timeoutLoad) withTimeout(10) { awaitCancellation() }
            if (suspendLoad) awaitCancellation()
            loaded = !fail
            return if (fail) AiResult.Failure(AiError.MODEL_UNAVAILABLE) else AiResult.Success(Unit)
        }

        @Volatile var generationError: AiError? = null

        @Volatile var options: GenerationOptions? = null

        @Volatile var generationReport: ((GenerationProgress) -> Unit)? = null
        override suspend fun generate(request: InferenceRequest): AiResult<InferenceText> =
            generate(request, GenerationOptions()) {}
        override suspend fun generate(
            request: InferenceRequest,
            options: GenerationOptions,
            report: (GenerationProgress) -> Unit
        ): AiResult<InferenceText> {
            this.options = options
            generationReport = report
            run {
                report(
                    GenerationProgress(
                        GenerationPhase.PREFILL,
                        promptTokens = 900,
                        processedTokens = 256,
                        elapsedMs = 12000,
                        pssKiB = 4096
                    )
                )
            }
            generations.incrementAndGet()
            if (suspendGenerate) awaitCancellation()
            generationError?.let { return AiResult.Failure(it) }
            return InferenceText.create(
                """{"schema_version":1,"status":"unsupported","transactions":[],"missing_fields":[]}"""
            )
        }
        override suspend fun unload() {
            unloads.incrementAndGet()
            loaded = false
        }
    }
}
