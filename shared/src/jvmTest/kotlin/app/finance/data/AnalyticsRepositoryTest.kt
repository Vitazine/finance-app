package app.finance.data

import androidx.room.Room
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
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

class AnalyticsRepositoryTest {
    private val directory = Files.createTempDirectory("fin014-synthetic-")
    private val path = directory.resolve("ledger.db").toString()
    private var store = open()
    private val at = Instant.parse("2026-11-02T12:00:00Z")
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

    @Test fun emptyUninitializedAndClosedScopeCancellationAreExplicit() = runBlocking<Unit> {
        assertEquals(
            LedgerError.NOT_INITIALIZED,
            (store.analytics.snapshot(at) as LedgerResult.Failure).error
        )
        setup()
        val view = store.analytics.snapshot(at).value()
        assertEquals(30, view.daily.size)
        assertEquals(0L, view.total.amountMinor)
        assertTrue(view.categories.isEmpty())
        store.close()
        assertFailsWith<CancellationException> { store.analytics.snapshot(at) }
    }

    @Test fun keysetTiesBeyondTwoBatchesNeverDropOrDuplicateTransactions() = runBlocking<Unit> {
        setup()
        for (n in 10..214) post(n, 1, "2026-11-01T12:00:00Z")
        post(215, 100000, "2026-11-01T12:00:00Z", income = true)
        post(216, 100, at.toString())
        val view = store.analytics.snapshot(at).value()
        assertEquals(205L, view.total.amountMinor)
        assertEquals(205L, view.daily.last().amount.amountMinor)
        assertEquals(205L, view.categories.single().amount.amountMinor)
        assertEquals(305L, view.currentMonthActual.amountMinor)
    }

    @Test fun frozenFxArchiveRenameAndLedgerMutationsAreReflectedOnNextRead() = runBlocking<Unit> {
        setup()
        val original = post(10, 100, "2026-11-01T12:00:00Z", foreign = true)
        store.categories.rename(id(1), "Renamed fixture").value()
        store.categories.archive(id(1), at).value()
        val quote = ExchangeRateQuote.create(
            currency("EUR"),
            currency(),
            DecimalRate.parse("9").value(),
            at,
            "synthetic:cache"
        ).value()
        store.rates.put(CachedRate(quote, at)).value()
        val before = store.analytics.snapshot(at).value()
        assertEquals(200L, before.total.amountMinor)
        assertEquals("Renamed fixture", before.categories.single().name)
        assertEquals(at, before.categories.single().archivedAt)
        val e = original.entry
        val changed = ManualEntry.create(
            e.id,
            e.type,
            e.categoryId,
            e.conversion,
            at,
            e.displayZoneId,
            e.note,
            e.createdAt
        ).value()
        val updated = store.transactions.updateManual(changed, original.revision, at).value()
        val moved = store.analytics.snapshot(at).value()
        assertEquals(0L, moved.total.amountMinor)
        assertEquals(200L, moved.currentMonthActual.amountMinor)
        store.transactions.deleteManual(e.id, updated.revision).value()
        assertEquals(0L, store.analytics.snapshot(at).value().currentMonthActual.amountMinor)
    }

    @Test fun selectedZoneMonthMatchesBudgetAndDataSurvivesReopenWithoutAnalyticsWrites() =
        runBlocking<Unit> {
            setup("Asia/Manila")
            val reference = Instant.parse("2026-11-01T16:00:00Z")
            post(10, 200, "2026-11-01T15:59:59.999999999Z")
            post(11, 300, "2026-11-01T16:00:00Z")
            store.budgets.put(month(), Money.ofMinor(3000, currency()), reference).value()
            val view = store.analytics.snapshot(reference).value()
            assertEquals(200L, view.total.amountMinor)
            assertEquals(500L, view.currentMonthActual.amountMinor)
            assertEquals(store.budgets.current(reference).value().spent, view.currentMonthActual)
            store.close()
            store = open()
            val reopened = store.analytics.snapshot(reference).value()
            assertEquals(
                view.daily.map {
                    it.amount.amountMinor
                },
                reopened.daily.map { it.amount.amountMinor }
            )
            assertEquals(view.currentMonthActual, reopened.currentMonthActual)
            assertEquals(2L, store.transactions.overview().value().count)
            assertEquals(0L, store.transactions.find(id(10)).value().revision)
            assertEquals(3000L, store.budgets.current(reference).value().budget!!.limit.amountMinor)
        }

    @Test fun fractionalSqliteStorageCannotBecomePlausibleRoundedZero() = runBlocking<Unit> {
        setup()
        val instant = Instant.parse("2026-11-01T12:00:00Z")
        val amount = Money.ofMinor(1, currency("EUR"))
        val quote = ExchangeRateQuote.create(
            currency("EUR"),
            currency(),
            DecimalRate.parse("0.01").value(),
            instant,
            "synthetic:tiny"
        ).value()
        store.transactions.postManual(
            ManualEntry.create(
                id(10),
                EntryType.EXPENSE,
                id(1),
                ConversionSnapshot.convert(amount, currency(), quote).value(),
                instant,
                "UTC",
                null,
                instant
            ).value()
        ).value()
        store.close()
        BundledSQLiteDriver().open(path).use { db ->
            db.execSQL("DROP TRIGGER transaction_guard_UPDATE")
            db.execSQL("UPDATE ledger_transactions SET convertedMinor=0.25,revision=revision+1")
            db.prepare("SELECT typeof(convertedMinor) FROM ledger_transactions").use { row ->
                row.step()
                assertEquals("real", row.getText(0))
            }
        }
        store = open()
        assertEquals(LedgerResult.Failure(LedgerError.CORRUPT_DATA), store.analytics.snapshot(at))
    }

    @Test fun orphanedExpenseCannotDisappearThroughHistoryJoin() = runBlocking<Unit> {
        setup()
        post(10, 100, "2026-11-01T12:00:00Z")
        store.close()
        BundledSQLiteDriver().open(path).use { db ->
            db.execSQL("PRAGMA foreign_keys=OFF")
            db.execSQL("DELETE FROM categories")
        }
        store = open()
        assertEquals(LedgerResult.Failure(LedgerError.CORRUPT_DATA), store.analytics.snapshot(at))
    }
}
