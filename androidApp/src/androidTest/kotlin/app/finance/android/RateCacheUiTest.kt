package app.finance.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
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
import app.finance.android.ledger.InputIssue
import app.finance.android.ledger.LedgerViewModel
import app.finance.android.ledger.RateNotice
import app.finance.core.money.CurrencyCode
import app.finance.core.money.DecimalRate
import app.finance.core.money.ExchangeRateQuote
import app.finance.core.money.MoneyResult
import app.finance.data.database.openLedgerStore
import app.finance.data.repository.LedgerStore
import app.finance.domain.exchange.CachedRate
import app.finance.domain.exchange.CurrencyPair
import app.finance.domain.exchange.RateCache
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class RateCacheUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "fin012-ui-" + UUID.randomUUID() + ".db"
    private val models = ViewModelStore()
    private lateinit var store: LedgerStore
    private lateinit var model: LedgerViewModel
    private val at = Instant.parse("2026-11-01T01:02:03.123456789Z")
    private fun <T> LedgerResult<T>.value() = (this as LedgerResult.Success).value
    private fun <T> MoneyResult<T>.value() = (this as MoneyResult.Success).value
    private fun currency(code: String) = CurrencyCode.resolve(code).value()
    private fun pair() = CurrencyPair.create(currency("KWD"), currency("USD")).value()

    @Before fun prepare() = runBlocking<Unit> {
        store = openLedgerStore(context, name)
        store.settings.initialize(LedgerSettings.create(currency("USD"), "UTC").value()).value()
        store.categories.create(
            LedgerId.parse("00000000-0000-4000-8000-000000000001").value(),
            EntryType.EXPENSE,
            "Cache expense"
        ).value()
    }

    @After fun cleanup() {
        val job = if (::model.isInitialized) model.viewModelScope.coroutineContext[Job] else null
        compose.runOnUiThread { models.clear() }
        runBlocking { withTimeout(15000) { job?.join() } }
        store.close()
        context.deleteDatabase(name)
    }
    private fun render(cache: RateCache = store.rates) {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = models
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LedgerViewModel(
                store.settings,
                store.categories,
                store.transactions,
                cache,
                store.budgets,
                store.analytics
            ) as T
        }
        model = ViewModelProvider(owner, factory)[LedgerViewModel::class.java]
        compose.setContent { FinanceTheme { FinanceApp(model) } }
    }
    private fun idle() {
        compose.waitUntil(15000) { !model.state.value.busy }
    }
    private fun waitTag(tag: String) {
        compose.waitUntil(15000) {
            !model.state.value.busy &&
                compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun bring(tag: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        idle()
        val node = compose.onNodeWithTag(tag)
        try {
            node.assertIsDisplayed()
        } catch (_: AssertionError) {
            node.performScrollTo()
        }
        return node
    }
    private fun click(tag: String) {
        bring(tag).performClick()
    }
    private fun text(tag: String, value: String) {
        bring(tag).performTextReplacement(value)
    }
    private fun foreign(time: String = "2026-11-01T02:02:03.123456789") {
        waitTag("home_add")
        click("home_add")
        text("editor_amount", "1.245")
        text("editor_currency", "KWD")
        text("editor_time", time)
        click("editor_category")
        compose.onNode(hasText("Cache expense") and hasClickAction()).performClick()
    }
    private fun seedQuote() = runBlocking<Unit> {
        store.rates.put(
            CachedRate(
                ExchangeRateQuote.create(
                    currency("KWD"),
                    currency("USD"),
                    DecimalRate.parse("1.004").value(),
                    at,
                    "manual"
                ).value(),
                at + 1.seconds
            )
        ).value()
    }

    @Test fun rememberCancelThenExplicitUsePreservesQuoteWithoutEarlyPosting() {
        render()
        foreign("2026-11-01T01:02:03.123456789")
        text("editor_rate", "1.004")
        click("fx_remember")
        compose.waitUntil(15000) {
            !model.state.value.busy &&
                model.state.value.editor?.rateNotice == RateNotice.SAVED
        }
        bring("fx_saved").assertIsDisplayed()
        assertEquals(0L, runBlocking { store.transactions.overview().value().count })
        assertEquals(at, runBlocking { store.rates.latest(pair(), at).value()!!.quote.effectiveAt })
        click("editor_cancel")
        foreign()
        click("fx_find")
        compose.waitUntil(15000) {
            !model.state.value.busy &&
                model.state.value.editor?.rateCandidate != null
        }
        assertEquals("", model.state.value.editor!!.fields.rate)
        assertNull(model.state.value.editor!!.fields.selectedQuote)
        bring("fx_candidate_rate").assertTextContains("1.004", substring = true)
        bring("fx_candidate_source").assertTextContains("Ручной курс", substring = true)
        bring("fx_candidate_date").assertTextContains("1 ноября 2026", substring = true)
        bring("fx_candidate_retrieved").assertIsDisplayed()
        bring("fx_candidate_age").assertIsDisplayed()
        click("editor_save")
        compose.onNodeWithTag("editor_error").assertIsDisplayed()
        assertEquals(InputIssue.MISSING_RATE, model.state.value.editorIssue?.issue)
        click("fx_use")
        assertNotNull(model.state.value.editor!!.fields.selectedQuote)
        assertEquals("1.004", model.state.value.editor!!.fields.rate)
        click("editor_save")
        waitTag("home_balance")
        val saved =
            runBlocking { store.transactions.history().value().items.single().transaction.entry }
        assertEquals(125L, saved.conversion.convertedMinor)
        assertEquals(at, saved.conversion.effectiveAt)
        assertEquals(at + 1.hours, saved.occurredAt)
        assertEquals("manual", saved.conversion.sourceId)
        assertEquals(
            -125L,
            runBlocking {
                store.transactions.overview().value().balance.amountMinor
            }
        )
    }

    @Test fun cacheFailureDoesNotPreventExplicitManualEntry() {
        val failed = object : RateCache {
            override suspend fun put(rate: CachedRate): LedgerResult<CachedRate> =
                LedgerResult.Failure(LedgerError.STORAGE_FAILURE)
            override suspend fun latest(
                pair: CurrencyPair,
                notAfter: Instant
            ): LedgerResult<CachedRate?> = LedgerResult.Failure(LedgerError.STORAGE_FAILURE)
        }
        render(failed)
        foreign()
        click("fx_find")
        idle()
        assertEquals(LedgerError.STORAGE_FAILURE, model.state.value.editor!!.rateProblem)
        bring("fx_problem").assertTextContains("вручную", substring = true)
        text("editor_rate", "1.004")
        click("fx_remember")
        idle()
        assertEquals(LedgerError.STORAGE_FAILURE, model.state.value.editor!!.rateProblem)
        assertEquals(0L, runBlocking { store.transactions.overview().value().count })
        click("editor_save")
        waitTag("home_balance")
        assertEquals(1L, runBlocking { store.transactions.overview().value().count })
        val sourceId = runBlocking {
            val entry = store.transactions.history().value().items.single().transaction.entry
            entry.conversion.sourceId
        }
        assertEquals("manual", sourceId)
    }

    @Test fun pairAndTimeChangesClearChoiceAndMissingCacheNeverGuesses() {
        seedQuote()
        render()
        foreign()
        click("fx_find")
        idle()
        click("fx_use")
        text("editor_amount", "2.00")
        assertNotNull(model.state.value.editor!!.fields.selectedQuote)
        text("editor_time", "2026-10-31T02:02:03")
        assertNull(model.state.value.editor!!.rateCandidate)
        assertNull(model.state.value.editor!!.fields.selectedQuote)
        assertEquals("", model.state.value.editor!!.fields.rate)
        click("fx_find")
        idle()
        assertEquals(RateNotice.EMPTY, model.state.value.editor!!.rateNotice)
        text("editor_time", "2026-11-01T02:02:03")
        click("fx_find")
        idle()
        click("fx_use")
        text("editor_currency", "EUR")
        assertNull(model.state.value.editor!!.rateCandidate)
        assertNull(model.state.value.editor!!.fields.selectedQuote)
        assertEquals("", model.state.value.editor!!.fields.rate)
        click("fx_find")
        idle()
        assertEquals(RateNotice.EMPTY, model.state.value.editor!!.rateNotice)
        bring("fx_empty").assertTextContains("вручную", substring = true)
        click("editor_save")
        compose.onNodeWithTag("editor_error").assertIsDisplayed()
        assertEquals(InputIssue.MISSING_RATE, model.state.value.editorIssue?.issue)
        assertEquals(0L, runBlocking { store.transactions.overview().value().count })
        click("editor_cancel")
    }
}
