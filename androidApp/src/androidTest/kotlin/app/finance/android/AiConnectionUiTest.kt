package app.finance.android

import androidx.compose.runtime.mutableStateOf
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
    private fun render() {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = models
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(c: Class<T>): T = AiViewModel(
                store,
                engine,
                (LocalModelRef.create("fixture") as AiResult.Success).value
            ) as T
        }
        compose.runOnUiThread { model = ViewModelProvider(owner, factory)[AiViewModel::class.java] }
        compose.setContent { FinanceTheme { if (visible.value) AiScreen(model, {}, {}) } }
    }
    private fun waitTag(tag: String) {
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
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

    private class ProbeEngine : ConnectionReportingEngine {
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
        override suspend fun generate(request: InferenceRequest): AiResult<InferenceText> {
            generations.incrementAndGet()
            if (suspendGenerate) awaitCancellation()
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
