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
import app.finance.data.repository.LedgerStore
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import app.finance.domain.ledger.ManualEntry
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val at = Instant.parse("2026-11-01T01:02:03.123456789Z")
    private fun <T> LedgerResult<T>.value(): T = (this as LedgerResult.Success).value
    private fun <T> MoneyResult<T>.value(): T = (this as MoneyResult.Success).value
    private fun id(value: Int) =
        LedgerId.parse("00000000-0000-4000-8000-${value.toString().padStart(12, '0')}").value()

    private fun entry(): ManualEntry {
        val jpy = CurrencyCode.resolve("JPY").value()
        val usd = CurrencyCode.resolve("USD").value()
        val quote = ExchangeRateQuote.create(
            jpy,
            usd,
            DecimalRate.parse("0.01").value(),
            at,
            "synthetic:android"
        ).value()
        val frozen = ConversionSnapshot.convert(
            Money.ofMinor(Long.MAX_VALUE, jpy),
            usd,
            quote
        ).value()
        return ManualEntry.create(
            id(10),
            EntryType.EXPENSE,
            id(1),
            frozen,
            at,
            "Asia/Manila",
            "Synthetic ☕",
            at
        ).value()
    }

    private fun withStore(test: suspend (String, LedgerStore) -> Unit) = runBlocking<Unit> {
        val name = "fin010-synthetic-${UUID.randomUUID()}.db"
        var store: LedgerStore? = null
        try {
            val opened = openLedgerStore(context, name)
            store = opened
            opened.settings.initialize(
                LedgerSettings.create(CurrencyCode.resolve("USD").value(), "UTC").value()
            ).value()
            test(name, opened)
        } finally {
            store?.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun realAndroidStorePersistsExactSnapshotAndArchiveAcrossReopen() = withStore { name, store ->
        store.categories.create(id(1), EntryType.EXPENSE, "Synthetic").value()
        store.transactions.postManual(entry()).value()
        store.categories.archive(id(1), at).value()
        store.close()
        val reopened = openLedgerStore(context, name)
        try {
            val saved = reopened.transactions.find(id(10)).value()
            assertEquals(Long.MAX_VALUE, saved.entry.conversion.convertedMinor)
            assertEquals(Long.MAX_VALUE, saved.entry.conversion.sourceAmount.amountMinor)
            assertEquals("0.01", saved.entry.conversion.rateDecimal)
            assertEquals(at, saved.entry.occurredAt)
            assertEquals(at, saved.entry.conversion.effectiveAt)
            assertEquals("Asia/Manila", saved.entry.displayZoneId)
            assertEquals("Synthetic ☕", saved.entry.note)
            assertEquals(0, reopened.categories.list().value().size)
            assertEquals(
                at,
                reopened.categories.list(includeArchived = true).value().single().archivedAt
            )
            assertEquals(
                LedgerError.CATEGORY_IN_USE,
                (reopened.categories.deleteUnused(id(1)) as LedgerResult.Failure).error
            )
        } finally {
            reopened.close()
        }
    }

    @Test
    fun nativeDatabaseGuardsAndConcurrentRetryWorkOnAndroid() = withStore { name, store ->
        store.categories.create(id(1), EntryType.EXPENSE, "Synthetic").value()
        val input = entry()
        val results =
            coroutineScope {
                (1..16).map { async { store.transactions.postManual(input) } }.awaitAll()
            }
        results.forEach { assertEquals(Long.MAX_VALUE, it.value().entry.conversion.convertedMinor) }
        BundledSQLiteDriver().open(context.getDatabasePath(name).absolutePath).use { raw ->
            raw.execSQL("PRAGMA foreign_keys=ON")
            raw.prepare("SELECT count(*) FROM ledger_transactions").use {
                assertTrue(it.step())
                assertEquals(1L, it.getLong(0))
            }
            var rejected = false
            try {
                raw.execSQL(
                    "UPDATE ledger_transactions SET amountMinor = 0, revision = revision + 1"
                )
            } catch (_: SQLiteException) {
                rejected = true
            }
            assertTrue(rejected)
        }
        assertEquals(
            Long.MAX_VALUE,
            store.transactions.find(id(10)).value().entry.conversion.sourceAmount.amountMinor
        )
    }

    @Test
    fun storageNameRejectsPathsBeforeAnyDatabaseIsCreated() {
        val before = context.databaseList().toSet()
        for (name in listOf("../escape.db", "/tmp/escape.db", "", "bad/name.db")) {
            var rejected = false
            try {
                openLedgerStore(context, name).close()
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)
        }
        assertEquals(before, context.databaseList().toSet())
    }
}
