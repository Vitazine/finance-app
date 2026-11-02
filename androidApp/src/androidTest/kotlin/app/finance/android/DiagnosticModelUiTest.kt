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
        runBlocking {
            store = openLedgerStore(context, name)
            val usd = (CurrencyCode.resolve("USD") as MoneyResult.Success).value
            store.settings.initialize(
                (LedgerSettings.create(usd, "UTC") as LedgerResult.Success).value
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
                experimental = true
            ) as T
        }
        compose.runOnUiThread { model = ViewModelProvider(owner, factory)[AiViewModel::class.java] }
        compose.setContent { FinanceTheme { AiScreen(model, {}, {}) } }
        compose.waitUntil(60000) { model.available || model.state.value.connectionError != null }
        assertTrue("Actual model must connect", model.available)
        compose.onNodeWithTag("ai_diagnostic_toggle").performScrollTo().performClick()
        compose.runOnUiThread {
            model.prompt("Купил шаурму за 250 RUB 2026-11-02")
            model.generate()
        }
        compose.waitUntil(15000) {
            model.state.value.generationProgress?.phase ==
                GenerationPhase.PREFILL
        }
        compose.onNodeWithTag("ai_diagnostic_phase").performScrollTo().assertIsDisplayed()
        capture("diagnostic-ui-prefill.png")
        compose.waitUntil(615000) { !model.state.value.busy }
        assertNull("Validated output must become a preview", model.state.value.error)
        assertEquals(1, model.state.value.previews.size)
        assertTrue(model.state.value.generationProgress!!.outputTokens > 0)
        compose.onNodeWithTag("ai_diagnostic_first").performScrollTo().assertIsDisplayed()
        capture("diagnostic-ui-complete.png")
        assertEquals(
            0L,
            runBlocking {
                (store.transactions.overview() as LedgerResult.Success).value.count
            }
        )
    }
}
