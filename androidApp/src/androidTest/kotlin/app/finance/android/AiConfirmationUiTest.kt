package app.finance.android

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "fin017-ui-" + UUID.randomUUID() + ".db"
    private var previousKeyboard = "null"
    private lateinit var store: LedgerStore
    private val models = ViewModelStore()
    private lateinit var ai: AiViewModel
    private lateinit var ledger: LedgerViewModel
    private val raw = """{"schema_version":1,"status":"ready","transactions":[{"type":"expense"""" +
""","amount":"12.50","currency":"USD","category_hint":"Fixture","date":"2""" +
"""026-11-02","description":"Synthetic"}],"missing_fields":[]}"""

    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText().trim() }

    @Before fun setup() = runBlocking<Unit> {
        previousKeyboard = shell("settings get secure show_ime_with_hard_keyboard")
        shell("settings put secure show_ime_with_hard_keyboard 1")
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
        store = openLedgerStore(context, name)
        store.categories.create(
            (LedgerId.parse("00000000-0000-4000-8000-000000000001") as LedgerResult.Success).value,
            EntryType.EXPENSE,
            "Fixture"
        )
    }

    @After fun close() {
        try {
            val jobs =
                listOfNotNull(
                    if (::ai.isInitialized) ai.viewModelScope.coroutineContext[Job] else null,
                    if (::ledger.isInitialized) {
                        ledger.viewModelScope.coroutineContext[Job]
                    } else {
                        null
                    }
                )
            compose.runOnUiThread { models.clear() }
            runBlocking { withTimeout(15000) { jobs.forEach { it.join() } } }
            store.close()
            context.deleteDatabase(name)
        } finally {
            if (previousKeyboard == "null") {
                shell("settings delete secure show_ime_with_hard_keyboard")
            } else {
                shell("settings put secure show_ime_with_hard_keyboard $previousKeyboard")
            }
        }
    }
    private fun render(output: String = raw, delay: Long = 0, baseCurrency: String = "USD") {
        runBlocking {
            val base = (CurrencyCode.resolve(baseCurrency) as MoneyResult.Success).value
            assertTrue(
                store.settings.initialize(
                    (LedgerSettings.create(base, "UTC") as LedgerResult.Success).value
                ) is LedgerResult.Success
            )
        }
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = models
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                AiViewModel::class.java -> AiViewModel(
                    store,
                    FakeInferenceEngine(output, delayMillis = delay),
                    (LocalModelRef.create("fixture") as AiResult.Success).value,
                    clock = object : kotlinx.datetime.Clock {
                        override fun now() =
                            kotlinx.datetime.Instant.parse("2026-11-03T12:34:56.123Z")
                    }
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
        // Touch coordinates must be taken after Android finishes resizing for the real IME.
        var previousBottom = -1
        var stableSince = android.os.SystemClock.elapsedRealtime()
        compose.waitUntil(10000) {
            val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            val bottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            val now = android.os.SystemClock.elapsedRealtime()
            if (bottom != previousBottom) {
                previousBottom = bottom
                stableSince = now
            }
            bottom > 0 &&
                insets?.isVisible(WindowInsetsCompat.Type.ime()) == true &&
                now - stableSince >= 350
        }
    }
    private fun waitTag(tag: String) {
        compose.waitUntil(15000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun count() = runBlocking {
        (store.transactions.overview() as LedgerResult.Success).value.count
    }

    @Test fun previewHasNoEditableTimeZoneOrUtcOffset() {
        render()
        text("ai_prompt", "Synthetic purchase")
        click("ai_generate")
        waitTag("ai_amount_0")
        compose.onNodeWithTag("ai_zone_0").assertDoesNotExist()
        compose.onNodeWithTag("ai_offset_0").assertDoesNotExist()
        assertEquals(0L, count())
    }

    @Test fun foreignUsdDraftShowsDirectionAndRequiresExplicitManualRate() {
        render(baseCurrency = "RUB")
        text("ai_prompt", "Synthetic taxi purchase")
        click("ai_generate")
        waitTag("ai_amount_0")
        compose.onNodeWithTag("ai_rate_0").performScrollTo()
            .assertTextContains("USD", substring = true)
            .assertTextContains("RUB", substring = true)
        click("ai_confirm_0")
        waitTag("ai_input_error_0")
        assertEquals(0L, count())
        text("ai_rate_0", "90")
        compose.onNodeWithTag(
            "ai_fx_0_converted"
        ).assertTextContains("1125.00 RUB", substring = true)
        compose.onNodeWithTag("ai_fx_0_converted").performScrollTo()
        saveProof("ai-fx-converted")
        assertEquals(0L, count())
        click("ai_confirm_0")
        waitTag("ai_confirmed_0")
        val row = runBlocking {
            (store.transactions.history() as LedgerResult.Success).value.items.single().transaction
        }
        assertEquals(1250L, row.entry.conversion.sourceAmount.amountMinor)
        assertEquals(112500L, row.entry.conversion.convertedMinor)
    }

    @Test fun savedFxIsOnlyAppliedAfterExplicitChoiceAndFreezesMetadata() {
        val source = (CurrencyCode.resolve("USD") as MoneyResult.Success).value
        val base = (CurrencyCode.resolve("RUB") as MoneyResult.Success).value
        val quote = (
            app.finance.core.money.ExchangeRateQuote.create(
                source,
                base,
                (app.finance.core.money.DecimalRate.parse("90") as MoneyResult.Success).value,
                kotlinx.datetime.Instant.parse("2026-11-01T00:00:00Z"),
                "synthetic:offline"
            ) as MoneyResult.Success
            ).value
        runBlocking {
            store.rates.put(
                app.finance.domain.exchange.CachedRate(
                    quote,
                    kotlinx.datetime.Instant.parse("2026-11-01T12:00:00Z")
                )
            )
        }
        render(baseCurrency = "RUB")
        text("ai_prompt", "Synthetic taxi purchase")
        click("ai_generate")
        waitTag("ai_amount_0")
        click("ai_fx_0_find")
        waitTag("ai_fx_0_candidate_rate")
        assertEquals("", ai.state.value.previews.single().fields.rate)
        assertEquals(0L, count())
        saveProof("ai-fx-candidate")
        click("ai_fx_0_use")
        val selected = ai.state.value.previews.single().fields.selectedQuote!!
        assertEquals(quote.effectiveAt, selected.effectiveAt)
        assertEquals(quote.sourceId, selected.sourceId)
        assertEquals(quote.rate.canonicalText, selected.rate.canonicalText)
        click("ai_confirm_0")
        waitTag("ai_confirmed_0")
        val stored = runBlocking {
            (store.transactions.history() as LedgerResult.Success).value.items.single().transaction
        }
        assertEquals("synthetic:offline", stored.entry.conversion.sourceId)
        assertEquals(quote.effectiveAt, stored.entry.conversion.effectiveAt)
        assertEquals(112500L, stored.entry.conversion.convertedMinor)
    }

    @Test fun emptyCacheAllowsRememberingManualFxWithoutPosting() {
        render(baseCurrency = "RUB")
        text("ai_prompt", "Synthetic purchase")
        click("ai_generate")
        waitTag("ai_amount_0")
        click("ai_fx_0_find")
        waitTag("ai_fx_0_empty")
        assertEquals("", ai.state.value.previews.single().fields.rate)
        text("ai_rate_0", "90")
        click("ai_fx_0_remember")
        waitTag("ai_fx_0_saved")
        assertEquals(0L, count())
        click("ai_fx_0_find")
        waitTag("ai_fx_0_candidate_rate")
        assertEquals(null, ai.state.value.previews.single().fields.selectedQuote)
        assertEquals(0L, count())
    }

    @Test fun editingCurrencyOrDateInvalidatesFxAndFutureQuoteIsExcluded() {
        val source = (CurrencyCode.resolve("USD") as MoneyResult.Success).value
        val base = (CurrencyCode.resolve("RUB") as MoneyResult.Success).value
        val quote = (
            app.finance.core.money.ExchangeRateQuote.create(
                source,
                base,
                (app.finance.core.money.DecimalRate.parse("90") as MoneyResult.Success).value,
                kotlinx.datetime.Instant.parse("2026-11-01T00:00:00Z"),
                "synthetic:offline"
            ) as MoneyResult.Success
            ).value
        runBlocking {
            store.rates.put(
                app.finance.domain.exchange.CachedRate(
                    quote,
                    kotlinx.datetime.Instant.parse("2026-11-01T12:00:00Z")
                )
            )
        }
        render(baseCurrency = "RUB")
        text("ai_prompt", "Synthetic purchase")
        click("ai_generate")
        waitTag("ai_amount_0")
        click("ai_fx_0_find")
        waitTag("ai_fx_0_candidate_rate")
        click("ai_fx_0_use")
        compose.runOnUiThread { ai.edit(0) { it.withCurrency("EUR") } }
        assertEquals(null, ai.state.value.previews.single().fields.selectedQuote)
        assertEquals(null, ai.state.value.previews.single().rateCandidate)
        assertEquals("", ai.state.value.previews.single().fields.rate)
        compose.runOnUiThread {
            ai.edit(0) {
                it.withCurrency("USD").withLocalTime(
                    app.finance.android.ledger.replaceOccurrenceDate(
                        it.localTime,
                        java.time.LocalDate.of(2026, 10, 31)
                    )
                )
            }
        }
        click("ai_fx_0_find")
        waitTag("ai_fx_0_empty")
        assertEquals(0L, count())
    }

    @Test fun missingTimeUsesRequestTimeAndCanBeExplicitlyConfirmed() {
        render()
        text("ai_prompt", "Synthetic purchase")
        click("ai_generate")
        waitTag("ai_amount_0")
        compose.onNodeWithTag("ai_time_0").assertTextContains("2 ноября 2026")
        compose.onNodeWithTag("ai_rate_0").assertDoesNotExist()
        assertEquals(
            "2 ноября 2026",
            compose.onNodeWithTag("ai_time_0")
                .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        )
        assertEquals(0L, count())
        click("ai_confirm_0")
        waitTag("ai_confirmed_0")
        assertEquals(1L, count())
        assertEquals(
            kotlinx.datetime.Instant.parse("2026-11-02T12:34:56.123Z"),
            runBlocking {
                (store.transactions.history() as LedgerResult.Success).value.items.single()
                    .transaction.entry.occurredAt
            }
        )
    }

    @Test fun explicitTimeInRequestWinsOverCurrentTime() {
        render()
        text("ai_prompt", "Synthetic purchase at 22:15")
        click("ai_generate")
        waitTag("ai_amount_0")
        compose.onNodeWithTag("ai_time_0").assertTextContains("2 ноября 2026")
        assertEquals("2026-11-02T22:15", ai.state.value.previews.single().fields.localTime)
        assertEquals(0L, count())
    }

    @Test fun timeRangeRequiresEditingAndNeverPostsAnArbitraryTime() {
        render()
        text("ai_prompt", "Synthetic purchase between 10:15-22:15")
        click("ai_generate")
        waitTag("ai_amount_0")
        assertEquals(0L, count())
        click("ai_confirm_0")
        waitTag("ai_input_error_0")
        assertEquals(0L, count())
        compose.onNodeWithTag("ai_confirmed_0").assertDoesNotExist()
    }

    @Test fun dateOnlyEditIsCompletedWithFrozenRequestTimeOnConfirmation() {
        render()
        text("ai_prompt", "Synthetic purchase")
        click("ai_generate")
        waitTag("ai_amount_0")
        compose.runOnUiThread {
            ai.edit(0) {
                it.withLocalTime(
                    app.finance.android.ledger.replaceOccurrenceDate(
                        it.localTime,
                        java.time.LocalDate.of(2026, 11, 1)
                    )
                )
            }
        }
        click("ai_confirm_0")
        waitTag("ai_confirmed_0")
        assertEquals(1L, count())
        assertEquals(
            kotlinx.datetime.Instant.parse("2026-11-01T12:34:56.123Z"),
            runBlocking {
                (store.transactions.history() as LedgerResult.Success).value.items.single()
                    .transaction.entry.occurredAt
            }
        )
    }

    @Test fun categoryFromModelIsSelectedAutomaticallyAndRemainsEditableWithoutPosting() {
        runBlocking { app.finance.domain.ledger.DefaultCategories.install(store.categories) }
        render(raw.replace("Fixture", "cafes"))
        text("ai_prompt", "Synthetic cafe purchase")
        click("ai_generate")
        waitTag("ai_amount_0")
        compose.onNodeWithTag("ai_category_0").assertTextContains("Кафе и рестораны")
        compose.onNodeWithTag("ai_time_0").performScrollTo()
        saveProof("ai-readable-preview")
        assertEquals(0L, count())
        click("ai_category_0")
        compose.onNodeWithText("Fixture").performClick()
        compose.onNodeWithTag("ai_category_0").assertTextContains("Fixture")
        click("ai_discard")
        assertEquals(0L, count())
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
        compose.runOnUiThread { ai.edit(0) { it.withLocalTime("invalid") } }
        click("ai_confirm_0")
        waitTag("ai_input_error_0")
        assertEquals(0L, count())
        compose.runOnUiThread { ai.edit(0) { it.withLocalTime("2026-11-02T12:00:00") } }
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
