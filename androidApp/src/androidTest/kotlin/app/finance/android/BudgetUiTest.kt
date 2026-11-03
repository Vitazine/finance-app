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
import app.finance.android.ledger.LedgerViewModel
import app.finance.core.money.CurrencyCode
import app.finance.core.money.Money
import app.finance.core.money.MoneyResult
import app.finance.data.database.openLedgerStore
import app.finance.data.repository.LedgerStore
import app.finance.domain.budget.BudgetMonth
import app.finance.domain.budget.BudgetOverview
import app.finance.domain.budget.BudgetRepository
import app.finance.domain.budget.MonthlyBudget
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BudgetUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "fin013-ui-" + UUID.randomUUID() + ".db"
    private val models = ViewModelStore()
    private lateinit var store: LedgerStore
    private lateinit var model: LedgerViewModel
    private var unavailable = false
    private val budgets = object : BudgetRepository {
        override suspend fun current(at: Instant): LedgerResult<BudgetOverview> = if (unavailable) {
            LedgerResult.Failure(
                LedgerError.STORAGE_FAILURE
            )
        } else {
            store.budgets.current(at)
        }
        override suspend fun put(
            month: BudgetMonth,
            limit: Money,
            updatedAt: Instant
        ): LedgerResult<MonthlyBudget> = store.budgets.put(month, limit, updatedAt)
    }
    private var now = Instant.parse("2026-11-15T00:00:00Z")
    private val clock = object : Clock {
        override fun now() = this@BudgetUiTest.now
    }
    private fun <T> LedgerResult<T>.value() = (this as LedgerResult.Success).value
    private val usd = (CurrencyCode.resolve("USD") as MoneyResult.Success).value
    private val category = LedgerId.parse("00000000-0000-4000-8000-000000000001").value()

    @Before fun prepare() = runBlocking<Unit> {
        store = openLedgerStore(context, name)
        store.settings.initialize(LedgerSettings.create(usd, "UTC").value()).value()
        store.categories.create(category, EntryType.EXPENSE, "Budget fixture").value()
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = models
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LedgerViewModel(
                store.settings,
                store.categories,
                store.transactions,
                store.rates,
                budgets,
                store.analytics,
                clock
            ) as T
        }
        compose.runOnUiThread {
            model = ViewModelProvider(owner, factory)[LedgerViewModel::class.java]
        }
        compose.setContent { FinanceTheme { FinanceApp(model) } }
        waitTag("home_add")
    }

    @After fun cleanup() {
        val job = if (::model.isInitialized) model.viewModelScope.coroutineContext[Job] else null
        compose.runOnUiThread { models.clear() }
        runBlocking { withTimeout(15000) { job?.join() } }
        store.close()
        context.deleteDatabase(name)
    }
    private fun waitTag(tag: String) {
        compose.waitUntil(15000) {
            !model.state.value.busy &&
                compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun click(tag: String) {
        waitTag(tag)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun budget() {
        compose.onNodeWithTag("nav_ANALYTICS").performClick()
        waitTag("budget_edit")
    }

    @Test fun explicitSaveRejectsZeroAndCancelDoesNotPersist() {
        budget()
        click("budget_edit")
        compose.onNodeWithTag("budget_amount").performTextReplacement("0")
        click("budget_save")
        waitTag("budget_save_error")
        Assert.assertNull(runBlocking { store.budgets.current(now).value().budget })
        compose.onNodeWithTag("budget_amount").performTextReplacement("10.00")
        click("budget_cancel")
        Assert.assertNull(runBlocking { store.budgets.current(now).value().budget })
        click("budget_edit")
        compose.onNodeWithTag("budget_amount").performTextReplacement("10.00")
        click("budget_save")
        waitTag("budget_remaining")
        compose.onNodeWithTag("budget_remaining").assertTextContains("10.00 USD", substring = true)
        Assert.assertEquals(
            1000L,
            runBlocking {
                store.budgets.current(now).value().budget!!.limit.amountMinor
            }
        )
    }

    @Test fun ledgerSaveAndDeleteRefreshBudgetAndHome() {
        runBlocking {
            store.budgets.put(
                BudgetMonth.create(2026, 11).value(),
                Money.ofMinor(1000, usd),
                now
            ).value()
        }
        compose.runOnUiThread {
            model.createEntry()
            model.fields { it.copy(amount = "12.50", categoryId = category) }
            model.saveEditor()
        }
        waitTag("home_add")
        budget()
        waitTag("budget_overrun")
        compose.onNodeWithTag(
            "budget_overrun"
        ).performScrollTo().assertTextContains("2.50 USD", substring = true)
        val transaction =
            runBlocking { store.transactions.history().value().items.single().transaction }
        compose.runOnUiThread {
            model.askDelete(transaction)
            model.confirmDelete()
        }
        waitTag("budget_spent")
        compose.onNodeWithTag("budget_spent").assertTextContains("0.00 USD", substring = true)
        compose.onNodeWithTag("nav_HOME").performClick()
        waitTag("budget_remaining")
        compose.onNodeWithTag(
            "budget_remaining"
        ).performScrollTo().assertTextContains("10.00 USD", substring = true)
    }

    @Test fun unavailableBudgetClearsOldTotalsAndKeepsManualLedgerAvailable() {
        budget()
        waitTag("budget_spent")
        compose.runOnUiThread {
            unavailable = true
            model.refresh()
        }
        waitTag("budget_error")
        compose.onNodeWithTag("budget_spent").assertDoesNotExist()
        compose.onNodeWithTag("budget_edit").assertDoesNotExist()
        compose.onNodeWithTag("nav_HOME").performClick()
        waitTag("home_add")
        compose.onNodeWithTag("home_balance").assertTextContains("0.00 USD", substring = true)
    }

    @Test fun currentMonthRefreshNeverCarriesLimitOrSavesStaleMonthDraft() {
        budget()
        click("budget_edit")
        compose.onNodeWithTag("budget_amount").performTextReplacement("10.00")
        val old = BudgetMonth.create(2026, 11).value()
        compose.runOnUiThread {
            now = Instant.parse("2026-12-01T00:00:00Z")
            model.saveBudget(old, "10.00")
        }
        waitTag("budget_month")
        compose.waitUntil(15000) {
            compose.onAllNodesWithTag("budget_month").fetchSemanticsNodes().any {
                it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].any { text ->
                    text.text.contains("декабрь 2026")
                }
            }
        }
        Assert.assertNull(runBlocking { store.budgets.current(now).value().budget })
        Assert.assertNull(
            runBlocking {
                store.budgets.current(Instant.parse("2026-11-15T00:00:00Z")).value().budget
            }
        )
        click("budget_edit")
        compose.onNodeWithTag("budget_amount").performTextReplacement("20.00")
        click("budget_save")
        waitTag("budget_remaining")
        compose.runOnUiThread {
            now = Instant.parse("2027-01-01T00:00:00Z")
            model.refreshCalendarIfNeeded()
        }
        waitTag("budget_empty")
        Assert.assertNull(runBlocking { store.budgets.current(now).value().budget })
    }
}
