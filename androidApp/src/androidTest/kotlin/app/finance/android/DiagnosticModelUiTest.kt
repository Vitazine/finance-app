package app.finance.android

import android.app.ActivityManager
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import app.finance.ai.contract.AiResult
import app.finance.ai.contract.LocalModelRef
import app.finance.android.ai.AiScreen
import app.finance.android.ai.AiViewModel
import app.finance.android.ai.AutoLocalModelEngine
import app.finance.android.ai.GenerationPhase
import app.finance.android.ai.NativeInferenceEngine
import app.finance.core.money.CurrencyCode
import app.finance.core.money.MoneyResult
import app.finance.data.database.openLedgerStore
import app.finance.data.repository.LedgerStore
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test

/** Opt-in real model UI test with its own synthetic database. Never opens the user's ledger. */
class DiagnosticModelUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "diagnostic-ui-" + UUID.randomUUID() + ".db"
    private val models = ViewModelStore()
    private lateinit var store: LedgerStore
    private lateinit var model: AiViewModel

    @After fun close() {
        val job = if (::model.isInitialized) model.viewModelScope.coroutineContext[Job] else null
        compose.runOnUiThread { models.clear() }
        runBlocking { withTimeout(15000) { job?.join() } }
        if (::store.isInitialized) {
            store.close()
            context.deleteDatabase(name)
        }
    }
    private fun capture(name: String) {
        // Parameterized reproduction retains neither supplied text nor a screenshot of it.
        if (InstrumentationRegistry.getArguments().containsKey("syntheticInput")) return
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.filesDir, name).outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test fun actualModelDisplaysMeasuredPrefillAndLeavesDraftUncommitted() {
        Assume.assumeTrue(
            "Explicit provisioned model required",
            InstrumentationRegistry.getArguments().containsKey("diagnosticModel")
        )
        Assume.assumeTrue(BuildConfig.DEBUG && BuildConfig.NATIVE_AI)
        val args = InstrumentationRegistry.getArguments()
        val ordinary = args.getString("ordinaryGeneration") == "true"
        val clock = args.getString("referenceInstant")?.let { value ->
            val instant = kotlinx.datetime.Instant.parse(value)
            object : kotlinx.datetime.Clock {
                override fun now() = instant
            }
        } ?: kotlinx.datetime.Clock.System
        runBlocking {
            store = openLedgerStore(context, name)
            val usd = (
                CurrencyCode.resolve(
                    args.getString("baseCurrency") ?: "USD"
                ) as MoneyResult.Success
                ).value
            store.settings.initialize(
                (LedgerSettings.create(usd, "UTC") as LedgerResult.Success).value
            )
            assertTrue(
                app.finance.domain.ledger.DefaultCategories.install(store.categories)
                    is LedgerResult.Success
            )
        }
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = models
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(c: Class<T>): T = AiViewModel(
                store,
                AutoLocalModelEngine(context.filesDir, {
                    val info = ActivityManager.MemoryInfo()
                    context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
                    info.totalMem
                }),
                (LocalModelRef.create(NativeInferenceEngine.KEY) as AiResult.Success).value,
                experimental = true,
                clock = clock
            ) as T
        }
        compose.runOnUiThread { model = ViewModelProvider(owner, factory)[AiViewModel::class.java] }
        compose.setContent { FinanceTheme { AiScreen(model, {}, {}) } }
        compose.waitUntil(60000) { model.available || model.state.value.connectionError != null }
        assertTrue("Actual model must connect", model.available)
        if (!ordinary) {
            compose.onNodeWithTag("ai_diagnostic_toggle").performScrollTo().performClick()
        }
        compose.runOnUiThread {
            model.prompt(args.getString("syntheticInput") ?: "Купил шаурму за 250 RUB 2026-11-02")
            model.generate()
        }
        compose.waitUntil(15000) {
            model.state.value.generationProgress?.phase ==
                GenerationPhase.PREFILL
        }
        compose.onNodeWithTag(if (ordinary) "ai_generation_phase" else "ai_diagnostic_phase")
            .performScrollTo().assertIsDisplayed()
        capture("diagnostic-ui-prefill.png")
        fun saveMetrics() {
            val progress = model.state.value.generationProgress ?: return
            File(context.filesDir, "debug-ui-generation-metrics.json").writeText(
                org.json.JSONObject()
                    .put("diagnostic", !ordinary)
                    .put(
                        "outcome",
                        if (model.state.value.busy) {
                            "RUNNING"
                        } else {
                            model.state.value.error?.name
                                ?: "VALIDATED_DRAFT"
                        }
                    )
                    .put("phase", progress.phase.name)
                    .put("prompt_tokens", progress.promptTokens)
                    .put("processed_tokens", progress.processedTokens)
                    .put("output_tokens", progress.outputTokens)
                    .put("elapsed_ms", progress.elapsedMs)
                    .put("first_token_ms", progress.firstTokenMs ?: org.json.JSONObject.NULL)
                    .toString(2)
            )
        }
        var savedAt = 0L
        compose.waitUntil(if (ordinary) 135000 else 615000) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - savedAt >= 1000) {
                saveMetrics()
                savedAt = now
            }
            !model.state.value.busy
        }
        saveMetrics()
        assertNull("Validated output must become a preview", model.state.value.error)
        assertEquals(1, model.state.value.previews.size)
        val draft = model.state.value.draft!!
        args.getString("expectedDate")?.let { expected ->
            assertTrue(
                "Synthetic relative date must match reference",
                draft.proposals.single().date.toString() == expected
            )
        }
        args.getString("expectedMinor")?.toLong()?.let { expected ->
            assertTrue(
                "Synthetic amount must match",
                draft.proposals.single().money.amountMinor == expected
            )
        }
        assertTrue("A known category must be selected", draft.proposals.single().categoryId != null)
        assertTrue(model.state.value.generationProgress!!.outputTokens > 0)
        if (!ordinary) {
            compose.onNodeWithTag("ai_diagnostic_first").performScrollTo().assertIsDisplayed()
        }
        capture("diagnostic-ui-complete.png")
        assertEquals(
            0L,
            runBlocking {
                (store.transactions.overview() as LedgerResult.Success).value.count
            }
        )
    }
}
