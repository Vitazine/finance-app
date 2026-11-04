package app.finance.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import app.finance.android.ledger.LedgerViewModel
import app.finance.core.money.ConversionSnapshot
import app.finance.core.money.CurrencyCode
import app.finance.core.money.DecimalRate
import app.finance.core.money.ExchangeRateQuote
import app.finance.core.money.Money
import app.finance.core.money.MoneyResult
import app.finance.data.database.openLedgerStore
import app.finance.data.repository.LedgerStore
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import app.finance.domain.ledger.ManualEntry
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LedgerUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "fin011-ui-${UUID.randomUUID()}.db"
    private val models = ViewModelStore()
    private lateinit var store: LedgerStore
    private lateinit var model: LedgerViewModel
    private val at = Instant.parse("2026-11-01T12:00:00.123456789Z")
    private fun <T> LedgerResult<T>.value() = (this as LedgerResult.Success).value
    private fun <T> MoneyResult<T>.value() = (this as MoneyResult.Success).value
    private fun id(n: Int) =
        LedgerId.parse("00000000-0000-4000-8000-${n.toString().padStart(12, '0')}").value()

    @Before
    fun prepare() {
        store = openLedgerStore(context, name)
    }

    @After
    fun cleanup() {
        val job = if (::model.isInitialized) model.viewModelScope.coroutineContext[Job] else null
        compose.runOnUiThread { models.clear() }
        runBlocking { withTimeout(15000) { job?.join() } }
        store.close()
        context.deleteDatabase(name)
    }
    private fun render(dark: androidx.compose.runtime.State<Boolean>? = null) {
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
                store.budgets,
                store.analytics
            ) as T
        }
        model = ViewModelProvider(owner, factory)[LedgerViewModel::class.java]
        compose.setContent {
            FinanceTheme(dark?.value ?: androidx.compose.foundation.isSystemInDarkTheme()) {
                FinanceApp(model)
            }
        }
    }
    private fun waitTag(tag: String) {
        compose.waitUntil(10000) {
            !model.state.value.busy &&
                compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun bring(tag: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose.waitUntil(10000) { !model.state.value.busy }
        val node = compose.onNodeWithTag(tag)
        try {
            node.assertIsDisplayed()
        } catch (_: AssertionError) {
            node.performScrollTo()
        }
        return node
    }
    private fun text(tag: String, value: String) {
        bring(tag).performTextReplacement(value)
    }
    private fun click(tag: String) {
        bring(tag).performClick()
    }
    private fun setupUi() {
        waitTag("setup_currency")
        text("setup_currency", "USD")
        compose.onNodeWithTag("setup_zone").assertDoesNotExist()
        click("setup_save")
        waitTag("home_balance")
    }
    private fun seedReady() = runBlocking {
        store.settings.initialize(
            LedgerSettings.create(CurrencyCode.resolve("USD").value(), "UTC").value()
        ).value()
        store.categories.create(id(1), EntryType.EXPENSE, "Synthetic expense").value()
    }
    private fun addCategory(name: String) {
        click("editor_new_category")
        waitTag("category_name")
        text("category_name", name)
        click("category_save")
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("category_name").fetchSemanticsNodes().isEmpty()
        }
        compose.waitUntil(10000) {
            !model.state.value.busy && model.state.value.editor?.fields?.categoryId != null
        }
        waitTag("editor_save")
    }
    private fun historyRows() = runBlocking { store.transactions.history().value().items }

    private fun saveProof(name: String) {
        if (InstrumentationRegistry.getArguments().getString("saveProof") != "true") return
        compose.waitForIdle()
        android.os.SystemClock.sleep(350)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(context.filesDir, name + ".png").outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test fun materialHomeKeepsPrimaryActionsReadableInBothThemes() {
        seedReady()
        val dark = androidx.compose.runtime.mutableStateOf(false)
        render(dark)
        waitTag("home_balance")
        compose.onNodeWithTag("home_balance").assertTextEquals("0.00 USD")
        compose.onNodeWithTag("home_history").assertIsDisplayed()
        bring("home_add").assertIsDisplayed()
        saveProof("day09-home-light")
        compose.runOnIdle { dark.value = true }
        bring("home_add").assertIsDisplayed()
        saveProof("day09-home-dark")
        click("home_add")
        waitTag("editor_save")
        click("editor_cancel")
        waitTag("home_balance")
        assertEquals(0, historyRows().size)
    }

    @Test fun outOfCalendarYearCanOpenAndCancelWithoutLosingDraft() {
        seedReady()
        render()
        waitTag("home_add")
        click("home_add")
        compose.runOnUiThread { model.fields { it.withLocalTime("0000-11-02T22:15") } }
        click("editor_time_picker")
        waitTag("editor_time_date_apply")
        click("editor_time_picker_cancel")
        assertEquals("0000-11-02T22:15", model.state.value.editor!!.fields.localTime)
        assertEquals(0, historyRows().size)
        click("editor_cancel")
    }

    @Test fun readableDatePickerCancelPreservesPrecisionAndExplicitApplyDoesNotPost() {
        seedReady()
        render()
        waitTag("home_add")
        click("home_add")
        compose.runOnUiThread {
            model.fields { it.withLocalTime("2026-11-02T22:15:03.123456789") }
        }
        compose.onNodeWithTag("editor_time").assertTextContains("2 ноября 2026")
        click("editor_time_picker")
        waitTag("editor_time_date_apply")
        saveProof("date-only-calendar")
        click("editor_time_picker_cancel")
        assertEquals("2026-11-02T22:15:03.123456789", model.state.value.editor!!.fields.localTime)
        click("editor_time_picker")
        waitTag("editor_time_date_apply")
        click("editor_time_date_apply")
        assertEquals("2026-11-02T22:15:03.123456789", model.state.value.editor!!.fields.localTime)
        compose.onNodeWithTag("editor_time_time_save").assertDoesNotExist()
        assertEquals(0, historyRows().size)
        click("editor_cancel")
    }

    @Test
    fun setupCategoryCreateEditCancelDeleteUpdateRealLedger() {
        render()
        setupUi()
        click("home_add")
        text("editor_amount", "12.50")
        addCategory("Synthetic expense")
        click("editor_save")
        waitTag("home_balance")
        compose.onNodeWithTag("home_balance").assertIsDisplayed()
        assertEquals(
            -1250L,
            runBlocking {
                store.transactions.overview().value().balance.amountMinor
            }
        )
        val saved = historyRows().single().transaction
        click("home_add")
        click("type_INCOME")
        text("editor_amount", "50.00")
        addCategory("Synthetic income")
        click("editor_save")
        waitTag("home_balance")
        assertEquals(
            3750L,
            runBlocking {
                store.transactions.overview().value().balance.amountMinor
            }
        )
        val income = historyRows().first {
            it.transaction.entry.type == EntryType.INCOME
        }.transaction
        compose.onNodeWithTag("nav_TRANSACTIONS").performClick()
        compose.waitUntil(10000) { !model.state.value.busy }
        compose.onNodeWithTag(
            "history_list"
        ).performScrollToNode(hasTestTag("edit_${saved.entry.id.text}"))
        waitTag("edit_${saved.entry.id.text}")
        click("edit_${saved.entry.id.text}")
        text("editor_amount", "20.00")
        click("editor_save")
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.editor == null }
        compose.onNodeWithTag(
            "history_list"
        ).performScrollToNode(hasTestTag("edit_${saved.entry.id.text}"))
        waitTag("edit_${saved.entry.id.text}")
        assertEquals(
            3000L,
            runBlocking {
                store.transactions.overview().value().balance.amountMinor
            }
        )
        click("delete_${saved.entry.id.text}")
        click("delete_cancel")
        assertEquals(2L, runBlocking { store.transactions.overview().value().count })
        click("delete_${saved.entry.id.text}")
        click("delete_confirm")
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.history.size == 1 }
        assertEquals(
            5000L,
            runBlocking {
                store.transactions.overview().value().balance.amountMinor
            }
        )
        click("delete_${income.entry.id.text}")
        click("delete_confirm")
        waitTag("history_empty")
        assertEquals(0L, runBlocking { store.transactions.overview().value().balance.amountMinor })
    }

    @Test
    fun amountErrorAppearsAtFieldAndClearsAfterCorrectionInBothThemes() {
        seedReady()
        val dark = androidx.compose.runtime.mutableStateOf(false)
        render(dark)
        waitTag("home_add")
        click("home_add")
        click("editor_category")
        compose.onNode(hasText("Synthetic expense") and hasClickAction()).performClick()
        for (darkTheme in listOf(false, true)) {
            compose.runOnIdle { dark.value = darkTheme }
            for (invalid in listOf("0", "92233720368547758.08")) {
                text("editor_amount", invalid)
                click("editor_save")
                waitTag("editor_error")
                val message = context.getString(
                    if (invalid == "0") R.string.ledger_err_amount else R.string.ledger_err_overflow
                )
                bring("editor_amount")
                compose.onNodeWithTag("editor_amount_error", useUnmergedTree = true)
                    .assertIsDisplayed().assertTextEquals(message)
                compose.onNodeWithTag("editor_amount")
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, message))
                compose.onNodeWithTag("editor_error").assertIsDisplayed().assertTextEquals(message)
                assertEquals(0, historyRows().size)
                saveProof("backlog-001-" + darkTheme + "-" + invalid.take(1))
                text("editor_amount", "1.00")
                compose.onNodeWithTag(
                    "editor_amount_error",
                    useUnmergedTree = true
                ).assertDoesNotExist()
                compose.onNodeWithTag("editor_amount")
                    .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
                compose.onNodeWithTag("editor_error").assertDoesNotExist()
            }
        }
        text("editor_currency", "EUR")
        click("editor_save")
        waitTag("editor_error")
        compose.onNodeWithTag("editor_amount_error", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("editor_amount")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        assertEquals(0, historyRows().size)
        text("editor_currency", "USD")
        click("editor_save")
        waitTag("home_balance")
        assertEquals(
            100L,
            historyRows().single().transaction.entry.conversion.sourceAmount.amountMinor
        )
    }

    @Test
    fun invalidAmountAndMissingFxNeverWriteAndSourceChangeClearsRate() {
        seedReady()
        render()
        waitTag("home_add")
        click("home_add")
        click("editor_category")
        compose.onNode(hasText("Synthetic expense") and hasClickAction()).performClick()
        text("editor_currency", "EUR")
        text("editor_amount", "0")
        click("editor_save")
        waitTag("editor_error")
        assertEquals(0L, runBlocking { store.transactions.overview().value().count })
        text("editor_amount", "1.00")
        click("editor_save")
        compose.onNodeWithText("Укажите курс для этой операции").assertIsDisplayed()
        text("editor_rate", "1.10")
        text("editor_currency", "JPY")
        click("editor_save")
        compose.onNodeWithText("Укажите курс для этой операции").assertIsDisplayed()
        click("editor_cancel")
        waitTag("home_balance")
        assertEquals(0L, runBlocking { store.transactions.overview().value().count })
        click("home_add")
        click("editor_category")
        compose.onNode(hasText("Synthetic expense") and hasClickAction()).performClick()
        text("editor_currency", "KWD")
        text("editor_amount", "1.245")
        text("editor_rate", "1.004")
        click("editor_save")
        waitTag("home_balance")
        compose.onNodeWithTag("home_balance").assertTextEquals("-1.25 USD")
        val posted = historyRows().single().transaction.entry.conversion
        assertEquals(1245L, posted.sourceAmount.amountMinor)
        assertEquals(125L, posted.convertedMinor)
        assertEquals("manual", posted.sourceId)
    }

    @Test
    fun noteOnlyEditKeepsArchivedCategoryAndFrozenQuote() {
        seedReady()
        val kwd = CurrencyCode.resolve("KWD").value()
        val usd = CurrencyCode.resolve("USD").value()
        val quote = ExchangeRateQuote.create(
            kwd,
            usd,
            DecimalRate.parse("1.004").value(),
            at,
            "synthetic:cached"
        ).value()
        val conversion = ConversionSnapshot.convert(Money.ofMinor(1245, kwd), usd, quote).value()
        runBlocking {
            store.transactions.postManual(
                ManualEntry.create(
                    id(10),
                    EntryType.EXPENSE,
                    id(1),
                    conversion,
                    at,
                    "UTC",
                    "Original",
                    at
                ).value()
            ).value()
            store.categories.archive(id(1), at).value()
        }
        render()
        waitTag("home_balance")
        compose.onNodeWithTag("nav_TRANSACTIONS").performClick()
        waitTag("edit_${id(10).text}")
        click("edit_${id(10).text}")
        val external = runBlocking { store.transactions.find(id(10)).value() }
        runBlocking {
            store.transactions.updateManual(
                ManualEntry.create(
                    external.entry.id,
                    external.entry.type,
                    external.entry.categoryId,
                    external.entry.conversion,
                    external.entry.occurredAt,
                    external.entry.displayZoneId,
                    "External change",
                    external.entry.createdAt
                ).value(),
                external.revision,
                at + 1.seconds
            ).value()
        }
        text("editor_note", "Unsaved user change")
        click("editor_save")
        compose.onNodeWithText("Данные уже изменены. Загрузите запись заново.").assertIsDisplayed()
        assertEquals(
            "External change",
            runBlocking {
                store.transactions.find(id(10)).value().entry.note
            }
        )
        click("editor_reload")
        waitTag("editor_note")
        text("editor_note", "Changed synthetic note")
        click("editor_save")
        waitTag("edit_${id(10).text}")
        val saved = runBlocking { store.transactions.find(id(10)).value() }
        assertEquals(125L, saved.entry.conversion.convertedMinor)
        assertEquals("synthetic:cached", saved.entry.conversion.sourceId)
        assertEquals(at, saved.entry.conversion.effectiveAt)
        assertEquals("Changed synthetic note", saved.entry.note)
        assertEquals(2L, saved.revision)
    }

    @Test
    fun corruptedSnapshotDoesNotKeepStaleRowsAndRetryRecovers() {
        seedReady()
        val usd = CurrencyCode.resolve("USD").value()
        val conversion = ConversionSnapshot.identity(Money.ofMinor(100, usd), at).value()
        runBlocking {
            store.transactions.postManual(
                ManualEntry.create(
                    id(10),
                    EntryType.EXPENSE,
                    id(1),
                    conversion,
                    at,
                    "UTC",
                    "Synthetic corruption fixture",
                    at
                ).value()
            ).value()
        }
        render()
        waitTag("home_balance")
        compose.onNodeWithTag("nav_TRANSACTIONS").performClick()
        waitTag("edit_${id(10).text}")
        fun update(amount: Long) {
            BundledSQLiteDriver().open(
                context.getDatabasePath(name).absolutePath
            ).use { connection ->
                connection.prepare(
                    "UPDATE ledger_transactions SET convertedMinor = ?, revision = revision + 1"
                ).use {
                    it.bindLong(1, amount)
                    it.step()
                }
            }
        }
        update(101)
        assertEquals(
            LedgerError.CORRUPT_DATA,
            (
                runBlocking {
                    store.transactions.find(id(10))
                } as LedgerResult.Failure
                ).error
        )
        compose.runOnUiThread { model.refresh() }
        compose.waitUntil(10000) { !model.state.value.busy }
        assertEquals(LedgerError.CORRUPT_DATA, model.state.value.historyError)
        assertEquals(LedgerError.CORRUPT_DATA, model.state.value.fatalError)
        compose.onNodeWithTag("home_balance").assertDoesNotExist()
        compose.onNodeWithTag("analytics_total").assertDoesNotExist()
        compose.onAllNodesWithTag("transaction_row").assertCountEquals(0)
        compose.onNodeWithTag("fatal_retry").assertIsDisplayed()
        update(100)
        click("fatal_retry")
        waitTag("edit_${id(10).text}")
        assertEquals(1L, runBlocking { store.transactions.overview().value().count })
        assertEquals(
            -100L,
            runBlocking {
                store.transactions.overview().value().balance.amountMinor
            }
        )
    }

    @Test
    fun historyPaginationAndFiltersUseRealRows() {
        seedReady()
        val usd = CurrencyCode.resolve("USD").value()
        val eur = CurrencyCode.resolve("EUR").value()
        runBlocking {
            store.categories.create(id(2), EntryType.INCOME, "Synthetic income").value()
            for (n in 1..35) {
                val type = if (n % 2 == 0) EntryType.INCOME else EntryType.EXPENSE
                val source = if (n % 3 == 0) eur else usd
                val money = Money.ofMinor(100, source)
                val snapshot = if (source ==
                    usd
                ) {
                    ConversionSnapshot.identity(money, at).value()
                } else {
                    ConversionSnapshot.convert(
                        money,
                        usd,
                        ExchangeRateQuote.create(
                            eur,
                            usd,
                            DecimalRate.parse("1.1").value(),
                            at,
                            "manual"
                        ).value()
                    ).value()
                }
                val occurred = if (n > 30) Instant.parse("2026-11-02T12:00:00Z") else at + n.seconds
                store.transactions.postManual(
                    ManualEntry.create(
                        id(100 + n),
                        type,
                        id(
                            if (type ==
                                EntryType.EXPENSE
                            ) {
                                1
                            } else {
                                2
                            }
                        ),
                        snapshot,
                        occurred,
                        "UTC",
                        "Fixture$n",
                        at
                    ).value()
                ).value()
            }
        }
        render()
        waitTag("home_balance")
        compose.onNodeWithTag("nav_TRANSACTIONS").performClick()
        compose.onNodeWithTag("history_list").performScrollToNode(hasTestTag("history_more"))
        click("history_more")
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.history.size == 35 }
        compose.onNodeWithTag("history_list").performScrollToNode(hasText("Fixture1"))
        compose.onNodeWithText("Fixture1").assertIsDisplayed()
        compose.onNodeWithTag("history_list").performScrollToNode(hasTestTag("filters_toggle"))
        click("filters_toggle")
        click("type_EXPENSE")
        click("filter_category")
        compose.onNode(hasText("Synthetic expense") and hasClickAction()).performClick()
        text("filter_currency", "EUR")
        text("filter_from", "2026-11-01")
        text("filter_through", "2026-11-01")
        click("filters_apply")
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.history.size == 5 }
        assertEquals(
            listOf(id(127), id(121), id(115), id(109), id(103)),
            model.state.value.history.map {
                it.transaction.entry.id
            }
        )
        compose.onNodeWithTag("history_list").performScrollToNode(hasText("Fixture27"))
        compose.onNodeWithText("Fixture27").assertIsDisplayed()
        // Applied dates must remain visible and effective after leaving the screen.
        click("nav_HOME")
        click("nav_TRANSACTIONS")
        click("filters_toggle")
        bring("filter_from")
        compose.onNodeWithTag("filter_from").assertTextContains("1 ноября 2026")
        bring("filter_through")
        compose.onNodeWithTag("filter_through").assertTextContains("1 ноября 2026")
        click("filters_apply")
        compose.waitUntil(10000) { !model.state.value.busy }
        assertEquals(5, model.state.value.history.size)
    }
}
