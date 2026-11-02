package app.finance.data

import androidx.room.Room
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.finance.core.money.ConversionSnapshot
import app.finance.core.money.CurrencyCode
import app.finance.core.money.DecimalRate
import app.finance.core.money.ExchangeRateQuote
import app.finance.core.money.Money
import app.finance.core.money.MoneyResult
import app.finance.data.database.FinanceDatabase
import app.finance.data.repository.LedgerStore
import app.finance.data.repository.buildLedgerDatabase
import app.finance.domain.budget.BudgetMonth
import app.finance.domain.exchange.CachedRate
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import app.finance.domain.ledger.ManualEntry
import app.finance.domain.ledger.StoredTransaction
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

class BudgetRepositoryTest {
    private val directory = Files.createTempDirectory("fin013-synthetic-")
    private val path = directory.resolve("ledger.db").toString()
    private var store = open()
    private val at = Instant.parse("2026-11-15T00:00:00Z")
    private fun open() =
        LedgerStore(buildLedgerDatabase(Room.databaseBuilder<FinanceDatabase>(name = path)))
    private fun <T> LedgerResult<T>.value() = (this as LedgerResult.Success).value
    private fun <T> MoneyResult<T>.value() = (this as MoneyResult.Success).value
    private fun currency(code: String = "USD") = CurrencyCode.resolve(code).value()
    private fun month(m: Int = 11) = BudgetMonth.create(2026, m).value()
    private fun id(n: Int) = LedgerId.parse(
        "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
    ).value()
    private suspend fun setup(zone: String = "UTC") {
        store.settings.initialize(LedgerSettings.create(currency(), zone).value()).value()
        store.categories.create(id(1), EntryType.EXPENSE, "Synthetic expense").value()
        store.categories.create(id(2), EntryType.INCOME, "Synthetic income").value()
    }
    private suspend fun post(
        n: Int,
        minor: Long,
        time: String,
        income: Boolean = false,
        foreign: Boolean = false
    ): StoredTransaction {
        val instant = Instant.parse(time)
        val amount = Money.ofMinor(minor, currency(if (foreign) "EUR" else "USD"))
        val quote = if (foreign) {
            ExchangeRateQuote.create(
                currency("EUR"),
                currency(),
                DecimalRate.parse("2").value(),
                instant,
                "synthetic:fixed"
            ).value()
        } else {
            null
        }
        val conversion = (
            if (foreign) {
                ConversionSnapshot.convert(
                    amount,
                    currency(),
                    quote
                )
            } else {
                ConversionSnapshot.identity(amount, instant)
            }
            ).value()
        return store.transactions.postManual(
            ManualEntry.create(
                id(n),
                if (income) EntryType.INCOME else EntryType.EXPENSE,
                id(if (income) 2 else 1),
                conversion,
                instant,
                "UTC",
                null,
                instant
            ).value()
        ).value()
    }

    @AfterTest fun cleanup() {
        store.close()
        directory.toFile().deleteRecursively()
    }

    @Test fun requiresSettingsPositiveLimitAndMatchingBase() = runBlocking<Unit> {
        assertEquals(
            LedgerError.NOT_INITIALIZED,
            (store.budgets.current(at) as LedgerResult.Failure).error
        )
        setup()
        assertEquals(
            LedgerError.INVALID_BUDGET,
            (
                store.budgets.put(
                    month(),
                    Money.ofMinor(0, currency()),
                    at
                ) as LedgerResult.Failure
                ).error
        )
        assertEquals(
            LedgerError.BASE_CURRENCY_MISMATCH,
            (
                store.budgets.put(
                    month(),
                    Money.ofMinor(100, currency("EUR")),
                    at
                ) as LedgerResult.Failure
                ).error
        )
        assertNull(store.budgets.current(at).value().budget)
    }

    @Test fun limitsArePerMonthNoCarryAndSurviveReopen() = runBlocking<Unit> {
        setup()
        store.budgets.put(month(), Money.ofMinor(1000, currency()), at).value()
        store.budgets.put(month(10), Money.ofMinor(9999, currency()), at).value()
        store.close()
        store = open()
        assertEquals(1000L, store.budgets.current(at).value().budget!!.limit.amountMinor)
        assertNull(store.budgets.current(Instant.parse("2026-12-01T00:00:00Z")).value().budget)
        store.budgets.put(month(), Money.ofMinor(500, currency()), at).value()
        assertEquals(500L, store.budgets.current(at).value().remaining!!.amountMinor)
    }

    @Test fun halfOpenLocalMonthIncludesNanosAndIgnoresIncome() = runBlocking<Unit> {
        setup("Asia/Manila")
        post(10, 999, "2026-10-31T15:59:59.999999999Z")
        post(11, 100, "2026-10-31T16:00:00Z")
        post(12, 25, "2026-11-30T15:59:59.999999999Z")
        post(13, 999, "2026-11-30T16:00:00Z")
        post(14, 999999, "2026-11-10T00:00:00Z", income = true)
        val view = store.budgets.current(at).value()
        assertEquals("2026-11", view.month.key)
        assertEquals("Asia/Manila", view.zoneId)
        assertEquals(125L, view.spent.amountMinor)
        assertNull(view.remaining)
    }

    @Test fun frozenFxAndEditsDeletesUpdateBudgetWithoutNettingIncome() = runBlocking<Unit> {
        setup()
        store.budgets.put(month(), Money.ofMinor(150, currency()), at).value()
        val original = post(10, 100, at.toString(), foreign = true)
        post(11, 10000, at.toString(), income = true)
        assertEquals(200L, store.budgets.current(at).value().spent.amountMinor)
        val quote = ExchangeRateQuote.create(
            currency("EUR"),
            currency(),
            DecimalRate.parse("9").value(),
            at,
            "synthetic:cache"
        ).value()
        store.rates.put(CachedRate(quote, at)).value()
        assertEquals(50L, store.budgets.current(at).value().overrun!!.amountMinor)
        val e = original.entry
        val changed = ManualEntry.create(
            e.id,
            e.type,
            e.categoryId,
            e.conversion,
            Instant.parse("2026-12-01T00:00:00Z"),
            e.displayZoneId,
            e.note,
            e.createdAt
        ).value()
        val updated = store.transactions.updateManual(changed, original.revision, at).value()
        assertEquals(0L, store.budgets.current(at).value().spent.amountMinor)
        assertEquals(200L, store.budgets.current(changed.occurredAt).value().spent.amountMinor)
        store.transactions.deleteManual(e.id, updated.revision).value()
        assertEquals(0L, store.budgets.current(changed.occurredAt).value().spent.amountMinor)
    }

    @Test fun aggregatesBeyondFirstBatch() = runBlocking<Unit> {
        setup()
        for (n in 10..114) post(n, 1, at.toString())
        assertEquals(105L, store.budgets.current(at).value().spent.amountMinor)
    }

    @Test fun expenseOverflowFailsWithoutWrappedTotals() = runBlocking<Unit> {
        setup()
        post(10, Long.MAX_VALUE, at.toString())
        post(11, 1, at.toString())
        assertEquals(
            LedgerError.AMOUNT_OVERFLOW,
            (store.budgets.current(at) as LedgerResult.Failure).error
        )
    }

    @Test fun corruptBudgetStorageFailsClosedAndNativeGuardRejectsZero() = runBlocking<Unit> {
        setup()
        store.budgets.put(month(), Money.ofMinor(100, currency()), at).value()
        store.close()
        BundledSQLiteDriver().open(path).use { db ->
            val failure =
                assertFailsWith<SQLiteException> {
                    db.execSQL("UPDATE monthly_budgets SET limitMinor=0")
                }
            assertTrue(failure.message.orEmpty().contains("budget_constraint"))
            db.execSQL("DROP TRIGGER budget_guard_UPDATE_v4")
            db.execSQL("UPDATE monthly_budgets SET limitMinor='broken'")
        }
        store = open()
        assertEquals(
            LedgerError.CORRUPT_DATA,
            (store.budgets.current(at) as LedgerResult.Failure).error
        )
    }
}
