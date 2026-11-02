package app.finance.android

import androidx.sqlite.SQLiteException
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
import app.finance.domain.exchange.CachedRate
import app.finance.domain.exchange.CurrencyPair
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import app.finance.domain.ledger.ManualEntry
import java.util.UUID
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RateCacheRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val at = Instant.parse("2026-11-01T01:02:03.123456789Z")
    private val fetched = Instant.parse("2026-11-02T02:03:04.987654321Z")
    private fun <T> LedgerResult<T>.value() = (this as LedgerResult.Success).value
    private fun <T> MoneyResult<T>.value() = (this as MoneyResult.Success).value
    private fun currency(code: String) = CurrencyCode.resolve(code).value()
    private fun observation(rate: String, instant: Instant = fetched) = CachedRate(
        ExchangeRateQuote.create(
            currency("KWD"),
            currency("USD"),
            DecimalRate.parse(rate).value(),
            at,
            "synthetic:android"
        ).value(),
        instant
    )
    private fun pair() = CurrencyPair.create(currency("KWD"), currency("USD")).value()

    @Test fun exactCacheSurvivesReopenAndUpdateKeepsFrozenLedger() = runBlocking<Unit> {
        val name = "fin012-runtime-" + UUID.randomUUID() + ".db"
        var store = openLedgerStore(context, name)
        try {
            store.settings.initialize(LedgerSettings.create(currency("USD"), "UTC").value()).value()
            val category = LedgerId.parse("00000000-0000-4000-8000-000000000001").value()
            val id = LedgerId.parse("00000000-0000-4000-8000-000000000010").value()
            store.categories.create(category, EntryType.EXPENSE, "Synthetic native").value()
            val original = observation("1.004")
            val snapshot = ConversionSnapshot.convert(
                Money.ofMinor(1245, currency("KWD")),
                currency("USD"),
                original.quote
            ).value()
            store.transactions.postManual(
                ManualEntry.create(
                    id,
                    EntryType.EXPENSE,
                    category,
                    snapshot,
                    at,
                    "UTC",
                    "Synthetic",
                    fetched
                ).value()
            ).value()
            store.rates.put(original).value()
            store.close()
            store = openLedgerStore(context, name)
            val restored = store.rates.latest(pair(), at).value()!!
            assertEquals("1.004", restored.quote.rate.canonicalText)
            assertEquals(at, restored.quote.effectiveAt)
            assertEquals(fetched, restored.retrievedAt)
            val precise = "1.23456789012345678901234567890123456789"
            store.rates.put(observation(precise, fetched + 1.nanoseconds)).value()
            assertEquals(precise, store.rates.latest(pair(), at).value()!!.quote.rate.canonicalText)
            assertEquals(125L, store.transactions.find(id).value().entry.conversion.convertedMinor)
            assertEquals(-125L, store.transactions.overview().value().balance.amountMinor)
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun nativeConcurrentRefreshAndGuardsRetainNewestQuote() = runBlocking<Unit> {
        val name = "fin012-runtime-" + UUID.randomUUID() + ".db"
        val store = openLedgerStore(context, name)
        try {
            coroutineScope {
                (0..15).map { n ->
                    async {
                        store.rates.put(
                            observation(
                                "1." + n.toString().padStart(2, '0'),
                                fetched + n.nanoseconds
                            )
                        ).value()
                    }
                }.awaitAll()
            }
            assertEquals("1.15", store.rates.latest(pair(), at).value()!!.quote.rate.canonicalText)
            var rejected = false
            BundledSQLiteDriver().open(
                context.getDatabasePath(name).absolutePath
            ).use { connection ->
                connection.execSQL("PRAGMA busy_timeout = 5000")
                try {
                    connection.execSQL(
                        "UPDATE exchange_rates SET rateDecimal = '0', retrievedAtSeconds = retrievedAtSeconds + 1"
                    )
                } catch (
                    failure: SQLiteException
                ) {
                    assertTrue(failure.message?.contains("rate_constraint") == true)
                    rejected = true
                }
            }
            assertTrue(rejected)
            assertEquals("1.15", store.rates.latest(pair(), at).value()!!.quote.rate.canonicalText)
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }
}
