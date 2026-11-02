package app.finance.android

import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import app.finance.android.ledger.LedgerViewModel
import app.finance.core.money.ConversionSnapshot
import app.finance.core.money.CurrencyCode
import app.finance.core.money.Money
import app.finance.core.money.MoneyResult
import app.finance.data.database.openLedgerStore
import app.finance.data.repository.LedgerStore
import app.finance.domain.analytics.AnalyticsRepository
import app.finance.domain.analytics.SpendingSnapshot
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerFilter
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerOverview
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import app.finance.domain.ledger.ManualEntry
import app.finance.domain.ledger.TransactionRepository
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AnalyticsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "fin015-ui-" + UUID.randomUUID() + ".db"
    private val models = ViewModelStore()
    private lateinit var store: LedgerStore
    private lateinit var model: LedgerViewModel
    private var now = Instant.parse("2026-11-02T04:00:00Z")
    private val clock = object : Clock {
        override fun now() = this@AnalyticsUiTest.now
    }
    private var failure: LedgerError? = null
    private val analyticsCalls = AtomicInteger()
    private var overviewGate: CompletableDeferred<Unit>? = null
    private var overviewStarted: CompletableDeferred<Unit>? = null
    private fun ledger() = object : TransactionRepository by store.transactions {
        override suspend fun overview(): LedgerResult<LedgerOverview> {
            overviewStarted?.complete(Unit)
            overviewGate?.await()
            return store.transactions.overview()
        }
    }
    private var gate: CompletableDeferred<Unit>? = null
    private val analytics = object : AnalyticsRepository {
        override suspend fun snapshot(reference: Instant): LedgerResult<SpendingSnapshot> {
            analyticsCalls.incrementAndGet()
            gate?.await()
            return failure?.let { LedgerResult.Failure(it) } ?: store.analytics.snapshot(reference)
        }
    }
    private fun <T> LedgerResult<T>.value() = (this as LedgerResult.Success).value
    private fun <T> MoneyResult<T>.value() = (this as MoneyResult.Success).value
    private val usd = CurrencyCode.resolve("USD").value()
    private fun id(n: Int) =
        LedgerId.parse("00000000-0000-4000-8000-" + n.toString().padStart(12, '0')).value()

    @Before fun prepare() = runBlocking<Unit> {
        store = openLedgerStore(context, name)
        store.settings.initialize(LedgerSettings.create(usd, "Asia/Manila").value()).value()
        store.categories.create(id(1), EntryType.EXPENSE, "Cafe fixture").value()
        store.categories.create(id(2), EntryType.INCOME, "Income fixture").value()
    }

    @After fun cleanup() {
        gate?.complete(Unit)
        overviewGate?.complete(Unit)
        val job = if (::model.isInitialized) model.viewModelScope.coroutineContext[Job] else null
        compose.runOnUiThread { models.clear() }
        runBlocking { withTimeout(15000) { job?.join() } }
        store.close()
        context.deleteDatabase(name)
    }
    private suspend fun post(
        n: Int,
        minor: Long,
        time: String,
        income: Boolean = false,
        category: Int = 1
    ) {
        val at = Instant.parse(time)
        val money = Money.ofMinor(minor, usd)
        val snapshot = ConversionSnapshot.identity(money, at).value()
        store.transactions.postManual(
            ManualEntry.create(
                id(n),
                if (income) EntryType.INCOME else EntryType.EXPENSE,
                id(if (income) 2 else category),
                snapshot,
                at,
                "UTC",
                null,
                at
            ).value()
        ).value()
    }
    private fun render() {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = models
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LedgerViewModel(
                store.settings,
                store.categories,
                ledger(),
                store.rates,
                store.budgets,
                analytics,
                clock
            ) as T
        }
        compose.runOnUiThread {
            model = ViewModelProvider(owner, factory)[LedgerViewModel::class.java]
        }
        compose.setContent { FinanceTheme { FinanceApp(model) } }
        waitTag("home_add")
    }
    private fun waitTag(tag: String, idle: Boolean = true) {
        compose.waitUntil(15000) {
            (
                !idle ||
                    !model.state.value.busy
                ) &&
                compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun bring(tag: String) = compose.onNodeWithTag(tag).performScrollTo()
    private fun budget() {
        compose.onNodeWithTag("nav_ANALYTICS").performClick()
        waitTag("analytics_panel")
    }

    @Test fun goldenRatesAndExactDailyDetailsRemainSeparateFromMonthActual() {
        runBlocking {
            post(100, 125, "2026-11-01T15:59:59.999999999Z")
            post(101, 1000, "2026-11-01T16:00:00Z")
            post(102, 10000, "2026-11-01T12:00:00Z", income = true)
        }
        render()
        budget()
        bring("analytics_total").assertTextContains("1.25 USD", substring = true)
        bring("analytics_daily_average").assertTextContains("≈ 0.04 USD", substring = true)
        bring("analytics_weekly").assertTextContains("≈ 0.29 USD", substring = true)
        bring("analytics_normalized").assertTextContains("1.25 USD", substring = true)
        bring("analytics_month_actual").assertTextContains("11.25 USD", substring = true)
        bring("daily_chart").assertContentDescriptionContains("30", substring = true)
        bring("analytics_days_toggle").performClick()
        assertEquals(30, compose.onAllNodesWithTag("analytics_day").fetchSemanticsNodes().size)
        bring("analytics_categories_title").assertIsDisplayed()
        bring(
            "analytics_category_" + id(1).text
        ).assertTextContains("Cafe fixture", substring = true)
        compose.onNodeWithTag("nav_HOME").performClick()
        compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("analytics_panel"))
        compose.onNodeWithTag("analytics_total").assertTextContains("1.25 USD", substring = true)
    }

    @Test fun emptyAnalyticsShowsThirtyZeroDaysWithoutFakeActivity() {
        render()
        budget()
        bring("analytics_empty").assertIsDisplayed()
        bring("analytics_total").assertTextContains("0.00 USD", substring = true)
        bring("analytics_days_toggle").performClick()
        assertEquals(30, compose.onAllNodesWithTag("analytics_day").fetchSemanticsNodes().size)
    }

    @Test fun loadingAndErrorClearOldChartButManualEntryAndRetryRemainAvailable() {
        runBlocking { post(100, 125, "2026-11-01T12:00:00Z") }
        render()
        budget()
        waitTag("analytics_total")
        compose.runOnUiThread {
            gate = CompletableDeferred()
            model.refresh()
        }
        waitTag("analytics_loading", idle = false)
        compose.onNodeWithTag("analytics_total").assertDoesNotExist()
        compose.runOnUiThread {
            failure = LedgerError.STORAGE_FAILURE
            gate!!.complete(Unit)
        }
        waitTag("analytics_error")
        compose.onNodeWithTag("daily_chart").assertDoesNotExist()
        compose.onNodeWithTag("nav_HOME").performClick()
        waitTag("home_add")
        budget()
        compose.runOnUiThread {
            failure = null
            gate = null
        }
        bring("analytics_retry").performClick()
        waitTag("analytics_total")
        bring("analytics_total").assertTextContains("1.25 USD", substring = true)
    }

    @Test fun filteredHistoryDoesNotPresentGlobalChartAsFilteredData() {
        runBlocking { post(100, 125, "2026-11-01T12:00:00Z") }
        render()
        compose.onNodeWithTag("nav_TRANSACTIONS").performClick()
        waitTag("edit_${id(100).text}")
        compose.onNodeWithTag("edit_${id(100).text}").assertIsDisplayed()
        compose.onNodeWithTag("daily_chart").assertDoesNotExist()
        compose.onNodeWithTag("history_chart_toggle").performClick()
        compose.onNodeWithTag("history_list").performScrollToNode(hasTestTag("analytics_panel"))
        compose.runOnUiThread { model.filter(LedgerFilter.create(type = EntryType.INCOME).value()) }
        waitTag("analytics_history_scope")
        compose.onNodeWithTag("daily_chart").assertDoesNotExist()
        compose.runOnUiThread { model.filter(LedgerFilter.ALL) }
        compose.waitUntil(15000) { !model.state.value.busy }
        compose.onNodeWithTag("history_list").performScrollToNode(hasTestTag("analytics_panel"))
        compose.onNodeWithTag("daily_chart").assertExists()
    }

    @Test fun selectedZoneMidnightRefreshesCompletedDaysWithoutMonthChange() {
        now = Instant.parse("2026-11-01T15:59:59.999999999Z")
        runBlocking { post(100, 125, "2026-11-01T15:00:00Z") }
        render()
        budget()
        bring("analytics_total").assertTextContains("0.00 USD", substring = true)
        assertEquals(1L, model.millisUntilCalendarBoundary())
        compose.runOnUiThread {
            now = Instant.parse("2026-11-01T16:00:00Z")
            model.refreshCalendarIfNeeded()
        }
        compose.waitUntil(15000) {
            !model.state.value.busy &&
                model.state.value.analytics?.completed?.today.toString() == "2026-11-02"
        }
        bring("analytics_total").assertTextContains("1.25 USD", substring = true)
        bring("analytics_period").assertTextContains("Asia/Manila", substring = true)
    }

    @Test fun integrityErrorNeverLeavesPlausibleLegacyFinancialNumbersVisible() {
        render()
        budget()
        waitTag("analytics_total")
        compose.runOnUiThread {
            failure = LedgerError.CORRUPT_DATA
            model.refresh()
        }
        compose.waitUntil(15000) {
            !model.state.value.busy &&
                model.state.value.fatalError == LedgerError.CORRUPT_DATA
        }
        compose.onNodeWithTag("analytics_total").assertDoesNotExist()
        compose.onNodeWithTag("budget_spent").assertDoesNotExist()
        compose.onNodeWithTag("home_balance").assertDoesNotExist()
        compose.onNodeWithTag("ledger_error").assertIsDisplayed()
    }

    @Test fun allCategoriesRemainReachableBeyondInitialVisibleGroup() {
        runBlocking {
            for (n in 3..11) {
                store.categories.create(
                    id(n),
                    EntryType.EXPENSE,
                    "Long category fixture " + n.toString() + " with a descriptive name"
                ).value()
                post(n + 100, 100, "2026-11-01T12:00:00Z", category = n)
            }
        }
        render()
        budget()
        bring("analytics_more_categories").performClick()
        assertEquals(9, compose.onAllNodesWithTag("analytics_category").fetchSemanticsNodes().size)
    }

    @Test fun fatalIntegrityErrorHidesEditorAndBlocksSavingUntilVerifiedRetry() {
        runBlocking { post(100, 125, "2026-11-01T12:00:00Z") }
        render()
        val item = runBlocking { store.transactions.history().value().items.single() }
        compose.runOnUiThread { model.editEntry(item) }
        waitTag("editor_amount")
        compose.runOnUiThread {
            model.fields { it.copy(amount = "9.99") }
            failure =
                LedgerError.CORRUPT_DATA
            model.refresh()
        }
        compose.waitUntil(15000) {
            !model.state.value.busy &&
                model.state.value.fatalError == LedgerError.CORRUPT_DATA
        }
        compose.onNodeWithTag("editor_amount").assertDoesNotExist()
        compose.onNodeWithTag("editor_save").assertDoesNotExist()
        compose.runOnUiThread { model.saveEditor() }
        assertEquals(
            125L,
            runBlocking {
                store.transactions.find(
                    item.transaction.entry.id
                ).value().entry.conversion.convertedMinor
            }
        )
        compose.runOnUiThread {
            failure = null
            gate = CompletableDeferred()
            model.refresh()
        }
        compose.waitUntil(15000) { model.state.value.analyticsLoading }
        compose.onNodeWithTag("editor_amount").assertDoesNotExist()
        compose.runOnUiThread {
            gate!!.complete(Unit)
            gate = null
        }
        waitTag("editor_amount")
        compose.onNodeWithTag("editor_amount").assertTextContains("9.99")
        assertEquals(
            125L,
            runBlocking {
                store.transactions.find(
                    item.transaction.entry.id
                ).value().entry.conversion.convertedMinor
            }
        )
    }

    @Test fun fatalIntegrityErrorHidesPendingDeleteAndBlocksConfirmation() {
        runBlocking { post(100, 125, "2026-11-01T12:00:00Z") }
        render()
        val entry = runBlocking { store.transactions.history().value().items.single().transaction }
        compose.runOnUiThread { model.askDelete(entry) }
        waitTag("delete_confirm")
        compose.runOnUiThread {
            failure = LedgerError.CORRUPT_DATA
            model.refresh()
        }
        compose.waitUntil(15000) {
            !model.state.value.busy &&
                model.state.value.fatalError == LedgerError.CORRUPT_DATA
        }
        compose.onNodeWithTag("delete_confirm").assertDoesNotExist()
        compose.runOnUiThread { model.confirmDelete() }
        assertEquals(1L, runBlocking { store.transactions.overview().value().count })
        compose.runOnUiThread {
            failure = null
            model.refresh()
        }
        waitTag("delete_confirm")
        compose.onNodeWithTag("delete_cancel").performClick()
    }

    @Test fun replyCrossingMidnightIsDiscardedBeforeLegacyReadsOrPublication() {
        now = Instant.parse("2026-11-01T15:30:00Z")
        runBlocking { post(100, 125, "2026-11-01T15:00:00Z") }
        render()
        budget()
        waitTag("analytics_total")
        val before = analyticsCalls.get()
        compose.runOnUiThread {
            gate = CompletableDeferred()
            overviewGate = CompletableDeferred()
            overviewStarted =
                CompletableDeferred()
            model.refresh()
        }
        waitTag("analytics_loading", idle = false)
        compose.runOnUiThread {
            now = Instant.parse("2026-11-01T16:00:00Z")
            gate!!.complete(Unit)
            gate =
                null
        }
        compose.waitUntil(15000) { overviewStarted!!.isCompleted }
        assertEquals("2026-11-02", model.state.value.analytics!!.completed.today.toString())
        assertEquals(125L, model.state.value.analytics!!.total.amountMinor)
        assertEquals(before + 2, analyticsCalls.get())
        compose.runOnUiThread { overviewGate!!.complete(Unit) }
    }
}
