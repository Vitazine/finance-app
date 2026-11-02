package app.finance.android

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import app.finance.ai.contract.AiResult
import app.finance.ai.contract.LocalModelRef
import app.finance.ai.inference.FakeInferenceEngine
import app.finance.android.ai.AiViewModel
import app.finance.android.ledger.LedgerViewModel
import app.finance.core.money.CurrencyCode
import app.finance.core.money.MoneyResult
import app.finance.data.database.openLedgerStore
import app.finance.data.repository.LedgerStore
import app.finance.domain.ledger.EntrySource
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AiConfirmationUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "fin017-ui-" + UUID.randomUUID() + ".db"
    private lateinit var store: LedgerStore
    private val models = ViewModelStore()
    private lateinit var ai: AiViewModel
    private lateinit var ledger: LedgerViewModel
    private val raw = """{"schema_version":1,"status":"ready","transactions":[{"type":"expense"""" +
""","amount":"12.50","currency":"USD","category_hint":"Fixture","date":"2""" +
"""026-11-02","description":"Synthetic"}],"missing_fields":[]}"""

    @Before fun setup() = runBlocking<Unit> {
        store = openLedgerStore(context, name)
        val usd = (CurrencyCode.resolve("USD") as MoneyResult.Success).value
        store.settings.initialize((LedgerSettings.create(usd, "UTC") as LedgerResult.Success).value)
        store.categories.create(
            (LedgerId.parse("00000000-0000-4000-8000-000000000001") as LedgerResult.Success).value,
            EntryType.EXPENSE,
            "Fixture"
        )
    }

    @After fun close() {
        val jobs =
            listOfNotNull(
                if (::ai.isInitialized) ai.viewModelScope.coroutineContext[Job] else null,
                if (::ledger.isInitialized) ledger.viewModelScope.coroutineContext[Job] else null
            )
        compose.runOnUiThread { models.clear() }
        runBlocking { withTimeout(15000) { jobs.forEach { it.join() } } }
        store.close()
        context.deleteDatabase(name)
    }
    private fun render(output: String = raw, delay: Long = 0) {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = models
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                AiViewModel::class.java -> AiViewModel(
                    store,
                    FakeInferenceEngine(output, delayMillis = delay),
                    (LocalModelRef.create("fixture") as AiResult.Success).value
                )
                else -> LedgerViewModel(
                    store.settings,
                    store.categories,
                    store.transactions,
                    store.rates,
                    store.budgets,
                    store.analytics
                )
            } as T
        }
        compose.runOnUiThread {
            ledger = ViewModelProvider(owner, factory)[LedgerViewModel::class.java]
            ai = ViewModelProvider(owner, factory)[AiViewModel::class.java]
        }
        compose.setContent { FinanceTheme { FinanceApp(ledger, ai) } }
        compose.waitUntil(15000) { !ledger.state.value.busy }
        compose.onNodeWithTag("nav_AI").performClick()
        compose.waitUntil(15000) { ai.available && !ai.state.value.busy }
    }
    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun text(tag: String, value: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
    }
    private fun waitTag(tag: String) {
        compose.waitUntil(15000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun count() = runBlocking {
        (store.transactions.overview() as LedgerResult.Success).value.count
    }

    @Test fun generateEditAndExplicitConfirmAreSeparateAndDoubleConfirmCannotDuplicate() {
        render()
        text("ai_prompt", "Synthetic input")
        click("ai_generate")
        waitTag("ai_amount_0")
        saveProof("ai-preview")
        assertEquals(0L, count())
        text("ai_amount_0", "15.00")
        assertEquals(0L, count())
        click("ai_confirm_0")
        waitTag("ai_input_error_0")
        assertEquals(0L, count())
        text("ai_time_0", "2026-11-02T12:00:00")
        click("ai_confirm_0")
        waitTag("ai_confirmed_0")
        saveProof("ai-confirmed")
        assertEquals(1L, count())
        val row =
            runBlocking {
                (store.transactions.history() as LedgerResult.Success).value.items
                    .single().transaction
            }
        assertEquals(1500L, row.entry.conversion.convertedMinor)
        assertEquals(EntrySource.AI, row.source)
        compose.onNodeWithTag("ai_confirm_0").assertDoesNotExist()
        compose.onNodeWithTag("nav_HOME").performClick()
        compose.waitUntil(15000) { !ledger.state.value.busy }
        compose.onNodeWithTag("home_balance").assertTextContains("-15.00 USD", substring = true)
    }

    @Test fun cancelDiscardsGeneratedDraftWithoutWriting() {
        render()
        text("ai_prompt", "Synthetic")
        click("ai_generate")
        waitTag("ai_amount_0")
        click("ai_discard")
        compose.onNodeWithTag("ai_amount_0").assertDoesNotExist()
        assertEquals(0L, count())
    }

    @Test fun invalidOutputAndUnavailableEngineKeepManualFallback() {
        render("{bad")
        text("ai_prompt", "Synthetic")
        click("ai_generate")
        waitTag("ai_error")
        assertEquals(0L, count())
        click("ai_manual")
        waitTag("editor_amount")
    }

    @Test fun cancelPendingGenerationNeverPublishesOrWrites() {
        render(delay = 60000)
        text("ai_prompt", "Synthetic")
        click("ai_generate")
        waitTag("ai_loading")
        click("ai_cancel_generation")
        compose.waitUntil(15000) { !ai.state.value.busy }
        compose.onNodeWithTag("ai_amount_0").assertDoesNotExist()
        assertEquals(0L, count())
    }

    @Test fun leavingAiTabCancelsGenerationAndNeverPublishesBackgroundDraft() {
        render(delay = 60000)
        text("ai_prompt", "Synthetic")
        click("ai_generate")
        waitTag("ai_loading")
        compose.onNodeWithTag("nav_HOME").performClick()
        compose.waitUntil(15000) { !ai.state.value.busy }
        assertTrue(ai.state.value.previews.isEmpty())
        assertEquals(0L, count())
    }
    private fun saveProof(name: String) {
        if (InstrumentationRegistry.getArguments().getString("saveProof") != "true") return
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        requireNotNull(bitmap)
        java.io.File(context.filesDir, "$name.png").outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
