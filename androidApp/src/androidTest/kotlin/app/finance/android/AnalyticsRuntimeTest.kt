package app.finance.android

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import app.finance.core.money.ConversionSnapshot
import app.finance.core.money.CurrencyCode
import app.finance.core.money.DecimalRate
import app.finance.core.money.ExchangeRateQuote
import app.finance.core.money.Money
import app.finance.core.money.MoneyResult
import app.finance.data.database.openLedgerStore
import app.finance.data.repository.LedgerStore
import app.finance.domain.exchange.CachedRate
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import app.finance.domain.ledger.ManualEntry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AnalyticsRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "fin014-native-" + UUID.randomUUID() + ".db"
    private lateinit var store: LedgerStore
    private fun <T> LedgerResult<T>.value() = (this as LedgerResult.Success).value
    private fun <T> MoneyResult<T>.value() = (this as MoneyResult.Success).value
    private fun currency(code: String = "USD") = CurrencyCode.resolve(code).value()
    private fun id(n: Int) = LedgerId.parse(
        "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
    ).value()

    @Before fun open() {
        store = openLedgerStore(context, name)
    }

    @After fun cleanup() {
        store.close()
        context.deleteDatabase(name)
    }
    private suspend fun setup(zone: String) {
        store.settings.initialize(LedgerSettings.create(currency(), zone).value()).value()
        store.categories.create(id(1), EntryType.EXPENSE, "Synthetic native expense").value()
        store.categories.create(id(2), EntryType.INCOME, "Synthetic native income").value()
    }
    private suspend fun post(
        n: Int,
        minor: Long,
        time: String,
        income: Boolean = false,
        rate: String? = null
    ) {
        val at = Instant.parse(time)
        val amount = Money.ofMinor(minor, currency(if (rate == null) "USD" else "EUR"))
        val conversion = if (rate == null) {
            ConversionSnapshot.identity(amount, at).value()
        } else {
            val quote = ExchangeRateQuote.create(
                currency("EUR"),
                currency(),
                DecimalRate.parse(rate).value(),
                at,
                "synthetic:native"
            ).value()
            ConversionSnapshot.convert(amount, currency(), quote).value()
        }
        store.transactions.postManual(
            ManualEntry.create(
                id(n),
                if (income) EntryType.INCOME else EntryType.EXPENSE,
                id(if (income) 2 else 1),
                conversion,
                at,
                "UTC",
                null,
                at
            ).value()
        ).value()
    }

    @Test fun realAndroidDstWindowIncludesZerosAndExcludesTodayAndIncome() = runBlocking<Unit> {
        setup("America/New_York")
        post(100, 100, "2024-03-10T06:30:00Z")
        post(101, 200, "2024-03-10T07:30:00Z")
        post(102, 3000, "2024-03-10T12:00:00Z", income = true)
        post(103, 500, "2024-03-20T12:00:00Z")
        post(104, 999, "2024-02-19T04:59:59.999999999Z")
        val view = store.analytics.snapshot(Instant.parse("2024-03-20T12:00:00Z")).value()
        assertEquals(30, view.daily.size)
        assertEquals(
            719L * 3600,
            view.completed.untilExclusive.epochSeconds - view.completed.start.epochSeconds
        )
        assertEquals(300L, view.total.amountMinor)
        assertEquals(
            300L,
            view.daily.single {
                it.date.toString() == "2024-03-10"
            }.amount.amountMinor
        )
        assertEquals(29, view.daily.count { it.amount.amountMinor == 0L })
        assertEquals("2100", view.weeklyEquivalent.numeratorMinor)
        assertEquals(30, view.weeklyEquivalent.denominator)
        assertEquals(800L, view.currentMonthActual.amountMinor)
    }

    @Test fun exactSelectedZoneSnapshotSurvivesReopenAndCacheChanges() = runBlocking<Unit> {
        setup("Asia/Manila")
        post(100, 100, "2026-11-01T15:59:59.999999999Z", rate = "2")
        post(101, 500, "2026-11-01T16:00:00Z")
        val at = Instant.parse("2026-11-01T16:00:00Z")
        val quote = ExchangeRateQuote.create(
            currency("EUR"),
            currency(),
            DecimalRate.parse("9").value(),
            at,
            "synthetic:cache"
        ).value()
        store.rates.put(CachedRate(quote, at)).value()
        store.close()
        store = openLedgerStore(context, name)
        val view = store.analytics.snapshot(at).value()
        assertEquals(200L, view.total.amountMinor)
        assertEquals(700L, view.currentMonthActual.amountMinor)
        assertEquals(200L, view.daily.last().amount.amountMinor)
        assertEquals("200", view.dailyAverage.numeratorMinor)
        assertEquals(30, view.dailyAverage.denominator)
        assertEquals(2L, store.transactions.overview().value().count)
    }

    @Test fun sqliteFractionalCorruptionIsRejectedBeforeTypedRead() = runBlocking<Unit> {
        setup("UTC")
        post(100, 1, "2026-11-01T12:00:00Z", rate = "0.01")
        store.close()
        BundledSQLiteDriver().open(context.getDatabasePath(name).absolutePath).use { db ->
            db.execSQL("DROP TRIGGER transaction_guard_UPDATE")
            db.execSQL("UPDATE ledger_transactions SET convertedMinor=0.25,revision=revision+1")
        }
        store = openLedgerStore(context, name)
        assertEquals(
            LedgerResult.Failure(LedgerError.CORRUPT_DATA),
            store.analytics.snapshot(Instant.parse("2026-11-02T12:00:00Z"))
        )
    }
}
