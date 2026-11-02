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
import app.finance.domain.exchange.CachedRate
import app.finance.domain.exchange.CurrencyPair
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import app.finance.domain.ledger.ManualEntry
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

class RateCacheTest {
    private val directory = Files.createTempDirectory("fin012-cache-synthetic-")
    private val path get() = directory.resolve("cache.db").toString()
    private lateinit var store: LedgerStore
    private val at = Instant.parse("2026-11-01T00:00:00.123456789Z")
    private val retrieved = Instant.parse("2026-11-02T00:00:00.987654321Z")
    private fun <T> MoneyResult<T>.value() = assertIs<MoneyResult.Success<T>>(this).value
    private fun <T> LedgerResult<T>.value() = assertIs<LedgerResult.Success<T>>(this).value
    private fun LedgerResult<*>.error() = assertIs<LedgerResult.Failure>(this).error
    private fun currency(text: String) = CurrencyCode.resolve(text).value()
    private fun pair(source: String = "EUR", base: String = "USD") =
        CurrencyPair.create(currency(source), currency(base)).value()
    private fun observation(
        value: String = "1.2",
        effective: Instant = at,
        fetched: Instant = retrieved,
        source: String = "synthetic:manual",
        from: String = "EUR",
        base: String = "USD"
    ) = CachedRate(
        ExchangeRateQuote.create(
            currency(from),
            currency(base),
            DecimalRate.parse(value).value(),
            effective,
            source
        ).value(),
        fetched
    )
    private fun open() {
        store = LedgerStore(buildLedgerDatabase(Room.databaseBuilder<FinanceDatabase>(name = path)))
    }

    @BeforeTest fun setup() = open()

    @AfterTest fun cleanup() {
        store.close()
        directory.toFile().deleteRecursively()
    }

    @Test fun emptyOrOppositePairNeverInventsAQuote() = runBlocking<Unit> {
        assertEquals(null, store.rates.latest(pair(), at).value())
        store.rates.put(observation()).value()
        assertEquals(null, store.rates.latest(pair("USD", "EUR"), retrieved).value())
    }

    @Test fun exactDecimalAndNanosSurviveDiskReopen() = runBlocking<Unit> {
        val decimal = "1.23456789012345678901234567890123456789"
        store.rates.put(observation(value = decimal)).value()
        store.close()
        open()
        val actual = store.rates.latest(pair(), at).value()!!
        assertEquals(decimal, actual.quote.rate.canonicalText)
        assertEquals(at, actual.quote.effectiveAt)
        assertEquals(retrieved, actual.retrievedAt)
        assertEquals("synthetic:manual", actual.quote.sourceId)
        assertEquals("EUR", actual.quote.sourceCurrency.code)
        assertEquals("USD", actual.quote.baseCurrency.code)
    }

    @Test fun latestHonorsExplicitCutoffIncludingNanoseconds() = runBlocking<Unit> {
        store.rates.put(observation(value = "1.1")).value()
        store.rates.put(observation(value = "1.2", effective = at + 1.nanoseconds)).value()
        store.rates.put(observation(value = "1.9", effective = at + 1.seconds)).value()
        assertEquals(null, store.rates.latest(pair(), at - 1.nanoseconds).value())
        assertEquals("1.1", store.rates.latest(pair(), at).value()!!.quote.rate.canonicalText)
        assertEquals(
            "1.2",
            store.rates.latest(pair(), at + 2.nanoseconds).value()!!.quote.rate.canonicalText
        )
        assertEquals(
            "1.9",
            store.rates.latest(pair(), at + 1.seconds).value()!!.quote.rate.canonicalText
        )
    }

    @Test fun equalEffectiveTimeUsesRetrievalThenStableSourceTieBreak() = runBlocking<Unit> {
        store.rates.put(observation(value = "1.2", source = "synthetic:beta")).value()
        store.rates.put(observation(value = "1.1", source = "synthetic:alpha")).value()
        assertEquals("synthetic:alpha", store.rates.latest(pair(), at).value()!!.quote.sourceId)
        store.rates.put(
            observation(
                value = "1.3",
                source = "synthetic:beta",
                fetched =
                retrieved + 1.nanoseconds
            )
        ).value()
        assertEquals("synthetic:beta", store.rates.latest(pair(), at).value()!!.quote.sourceId)
    }

    @Test fun lateResponsesAndEqualClockConflictsKeepNewestQuote() = runBlocking<Unit> {
        store.rates.put(observation(value = "1.3")).value()
        assertEquals(
            "1.3",
            store.rates.put(
                observation(
                    value = "1.1",
                    fetched =
                    retrieved - 1.nanoseconds
                )
            ).value().quote.rate.canonicalText
        )
        assertEquals(LedgerError.CONFLICT, store.rates.put(observation(value = "1.9")).error())
        assertEquals("1.3", store.rates.latest(pair(), at).value()!!.quote.rate.canonicalText)
        store.rates.put(observation(value = "1.3000")).value()
        store.rates.put(observation(value = "1.4", fetched = retrieved + 1.nanoseconds)).value()
        assertEquals("1.4", store.rates.latest(pair(), at).value()!!.quote.rate.canonicalText)
    }

    @Test fun concurrentOutOfOrderResponsesKeepNewestObservation() = runBlocking<Unit> {
        coroutineScope {
            (0..15).map { n ->
                async {
                    store.rates.put(
                        observation(
                            value = "1." + n.toString().padStart(2, '0'),
                            fetched =
                            retrieved + n.nanoseconds
                        )
                    ).value()
                }
            }.awaitAll()
        }
        val actual = store.rates.latest(pair(), at).value()!!
        assertEquals("1.15", actual.quote.rate.canonicalText)
        assertEquals(retrieved + 15.nanoseconds, actual.retrievedAt)
    }

    @Test fun concurrentSameClockDifferentValuesYieldOneConflict() = runBlocking<Unit> {
        val results = coroutineScope {
            listOf("1.1", "1.2").map { value ->
                async { store.rates.put(observation(value = value)) }
            }.awaitAll()
        }
        assertEquals(1, results.count { it is LedgerResult.Success })
        assertEquals(
            listOf(LedgerError.CONFLICT),
            results.filterIsInstance<LedgerResult.Failure>().map {
                it.error
            }
        )
    }

    @Test fun refreshingCacheNeverRevaluesFrozenLedger() = runBlocking<Unit> {
        val usd = currency("USD")
        store.settings.initialize(LedgerSettings.create(usd, "UTC").value()).value()
        val category = LedgerId.parse("00000000-0000-4000-8000-000000000001").value()
        val id = LedgerId.parse("00000000-0000-4000-8000-000000000010").value()
        store.categories.create(category, EntryType.EXPENSE, "Synthetic").value()
        val initial = observation(value = "1.004", from = "KWD")
        val snapshot = ConversionSnapshot.convert(
            Money.ofMinor(1245, currency("KWD")),
            usd,
            initial.quote
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
                retrieved
            ).value()
        ).value()
        store.rates.put(initial).value()
        store.rates.put(
            observation(value = "2", from = "KWD", fetched = retrieved + 1.seconds)
        ).value()
        assertEquals(125L, store.transactions.find(id).value().entry.conversion.convertedMinor)
        assertEquals("1.004", store.transactions.find(id).value().entry.conversion.rateDecimal)
        assertEquals(-125L, store.transactions.overview().value().balance.amountMinor)
        assertEquals(1L, store.transactions.overview().value().count)
    }

    @Test fun noncanonicalPersistedRateReturnsTypedCorruption() = runBlocking<Unit> {
        store.rates.put(observation()).value()
        damageQuote("rateDecimal = '01.200', retrievedAtNanos = retrievedAtNanos + 1")
        assertEquals(LedgerError.CORRUPT_DATA, store.rates.latest(pair(), at).error())
    }

    @Test fun nativeGuardsRejectInvalidQuoteAndRetainValidValue() = runBlocking<Unit> {
        store.rates.put(observation()).value()
        BundledSQLiteDriver().open(path).use { connection ->
            connection.execSQL("PRAGMA busy_timeout = 5000")
            for (assignment in listOf(
                "rateDecimal = '0'",
                "rateDecimal = '-1'",
                "rateDecimal = '1e2'",
                "effectiveAtNanos = 1000000000",
                "sourceCurrency = baseCurrency",
                "retrievedAtSeconds = 'opaque'"
            )) {
                val failure = assertFailsWith<SQLiteException> {
                    connection.execSQL(
                        "UPDATE exchange_rates SET " + assignment +
                            ", retrievedAtNanos = retrievedAtNanos + 1"
                    )
                }
                assertTrue(failure.message?.contains("rate_") == true)
            }
        }
        assertEquals("1.2", store.rates.latest(pair(), at).value()!!.quote.rate.canonicalText)
    }

    @Test fun oversizedNanosInDamagedDatabaseCannotNarrowToAValidInstant() = runBlocking<Unit> {
        store.rates.put(observation()).value()
        damageQuote("effectiveAtNanos = 4294967296")
        assertEquals(LedgerError.CORRUPT_DATA, store.rates.latest(pair(), at + 1.seconds).error())
    }
    private fun damageQuote(assignment: String) {
        store.close()
        BundledSQLiteDriver().open(path).use { connection ->
            val guards = mutableListOf<String>()
            connection.prepare(
                "SELECT name FROM sqlite_master WHERE type = 'trigger' AND tbl_name = 'exchange_rates'"
            ).use { rows -> while (rows.step()) guards += rows.getText(0) }
            for (guard in guards) connection.execSQL("DROP TRIGGER " + guard)
            connection.execSQL("UPDATE exchange_rates SET " + assignment)
        }
        open()
    }

    @Test fun damagedTimestampStorageTypesAreRejectedAfterReopen() = runBlocking<Unit> {
        store.rates.put(observation()).value()
        damageQuote("retrievedAtSeconds = 'opaque', retrievedAtNanos = 345.75")
        assertEquals(LedgerError.CORRUPT_DATA, store.rates.latest(pair(), at).error())
    }

    @Test fun damagedTimestampIsNotSilentlyRepairedByPut() = runBlocking<Unit> {
        store.rates.put(observation()).value()
        damageQuote("retrievedAtSeconds = 'opaque', retrievedAtNanos = 345.75")
        assertEquals(
            LedgerError.CORRUPT_DATA,
            store.rates.put(observation(value = "1.9", fetched = retrieved + 1.seconds)).error()
        )
        BundledSQLiteDriver().open(path).use { connection ->
            connection.prepare(
                "SELECT typeof(retrievedAtSeconds), typeof(retrievedAtNanos), rateDecimal FROM exchange_rates"
            ).use { row ->
                row.step()
                assertEquals("text", row.getText(0))
                assertEquals("real", row.getText(1))
                assertEquals("1.2", row.getText(2))
            }
        }
    }

    @Test fun blobRateIsNotAcceptedAsCanonicalText() = runBlocking<Unit> {
        store.rates.put(observation()).value()
        damageQuote("rateDecimal = X'312e32'")
        assertEquals(LedgerError.CORRUPT_DATA, store.rates.latest(pair(), at).error())
    }
}
