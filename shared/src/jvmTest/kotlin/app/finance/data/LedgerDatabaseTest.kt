package app.finance.data

import androidx.room.Room
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
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
import app.finance.data.database.TransactionEntity
import app.finance.data.repository.CorruptRecord
import app.finance.data.repository.DatabaseAccess
import app.finance.data.repository.LedgerStore
import app.finance.data.repository.buildLedgerDatabase
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
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

class LedgerDatabaseTest {
    private lateinit var directory: java.nio.file.Path
    private lateinit var db: FinanceDatabase
    private lateinit var store: LedgerStore
    private val at = Instant.parse("2026-11-01T02:03:04.123456789Z")
    private val later = Instant.parse("2026-11-02T00:00:00Z")
    private val categoryId get() = id(1)
    private val path get() = directory.resolve("synthetic.db").toString()

    private fun id(value: Int) =
        LedgerId.parse("00000000-0000-4000-8000-${value.toString().padStart(12, '0')}").value()
    private fun <T> LedgerResult<T>.value(): T = (this as LedgerResult.Success).value
    private fun LedgerResult<*>.error() = (this as LedgerResult.Failure).error
    private fun <T> MoneyResult<T>.value(): T = (this as MoneyResult.Success).value

    @BeforeTest
    fun setup() {
        directory = Files.createTempDirectory("finance-synthetic-")
        reopen()
        runBlocking {
            store.settings.initialize(LedgerSettings.create(currencyUsd(), "UTC").value()).value()
        }
    }

    private fun currencyUsd() = CurrencyCode.resolve("USD").value()

    private fun reopen() {
        db = buildLedgerDatabase(Room.databaseBuilder<FinanceDatabase>(name = path))
        store = LedgerStore(db)
    }

    @AfterTest
    fun cleanup() {
        if (::store.isInitialized) store.close()
        // Only this test's newly owned temporary directory; never production storage.
        directory.toFile().deleteRecursively()
    }

    private suspend fun category(type: EntryType = EntryType.EXPENSE) =
        store.categories.create(categoryId, type, "Synthetic category").value()

    private fun entry(
        number: Int = 10,
        type: EntryType = EntryType.EXPENSE,
        rate: String = "0.01"
    ): ManualEntry {
        val jpy = CurrencyCode.resolve("JPY").value()
        val usd = CurrencyCode.resolve("USD").value()
        val quote = ExchangeRateQuote.create(
            jpy,
            usd,
            DecimalRate.parse(rate).value(),
            at,
            "synthetic:fixture"
        ).value()
        val snapshot = ConversionSnapshot.convert(
            Money.ofMinor(Long.MAX_VALUE, jpy),
            usd,
            quote
        ).value()
        return ManualEntry.create(
            id(number),
            type,
            categoryId,
            snapshot,
            at,
            "Asia/Manila",
            "Synthetic ☕\nNote",
            at
        ).value()
    }

    @Test
    fun categoryArchiveRenameRestorePreservesHistory() = runBlocking<Unit> {
        category()
        store.transactions.postManual(entry()).value()
        assertEquals(at, store.categories.archive(categoryId, at).value().archivedAt)
        assertEquals(at, store.categories.archive(categoryId, later).value().archivedAt)
        assertEquals(0, store.categories.list().value().size)
        assertEquals(1, store.categories.list(includeArchived = true).value().size)
        assertEquals("Renamed", store.categories.rename(categoryId, " Renamed ").value().name)
        assertEquals(categoryId, store.transactions.find(id(10)).value().entry.categoryId)
        assertEquals(
            Long.MAX_VALUE,
            store.transactions.find(id(10)).value().entry.conversion.convertedMinor
        )
        assertEquals(null, store.categories.restore(categoryId).value().archivedAt)
        assertEquals(1, store.categories.list().value().size)
        store.transactions.postManual(entry(number = 11)).value()
    }

    @Test
    fun usedCategoryCannotBeDeletedAndUnusedCategoryCan() = runBlocking<Unit> {
        category()
        store.transactions.postManual(entry()).value()
        assertEquals(LedgerError.CATEGORY_IN_USE, store.categories.deleteUnused(categoryId).error())
        store.categories.create(id(2), EntryType.INCOME, "Unused").value()
        store.categories.deleteUnused(id(2)).value()
        assertEquals(listOf(categoryId), store.categories.list().value().map { it.id })
        assertEquals(
            Long.MAX_VALUE,
            store.transactions.find(id(10)).value().entry.conversion.convertedMinor
        )
    }

    @Test
    fun missingWrongTypeAndArchivedCategoryWritesAreRejectedAtomically() = runBlocking<Unit> {
        assertEquals(LedgerError.NOT_FOUND, store.transactions.postManual(entry()).error())
        category()
        assertEquals(
            LedgerError.TYPE_MISMATCH,
            store.transactions.postManual(entry(type = EntryType.INCOME)).error()
        )
        store.categories.archive(categoryId, at).value()
        assertEquals(LedgerError.CATEGORY_ARCHIVED, store.transactions.postManual(entry()).error())
        assertEquals(LedgerError.NOT_FOUND, store.transactions.find(id(10)).error())
        assertEquals(0, countTransactions())
    }

    @Test
    fun diskReopenKeepsExactMoneyRateInstantsAndNotes() = runBlocking<Unit> {
        category()
        store.transactions.postManual(entry()).value()
        store.close()
        reopen()
        val result = store.transactions.find(id(10)).value()
        assertEquals(Long.MAX_VALUE, result.entry.conversion.sourceAmount.amountMinor)
        assertEquals(Long.MAX_VALUE, result.entry.conversion.convertedMinor)
        assertEquals("0.01", result.entry.conversion.rateDecimal)
        assertEquals("synthetic:fixture", result.entry.conversion.sourceId)
        assertEquals(at, result.entry.conversion.effectiveAt)
        assertEquals(at, result.entry.occurredAt)
        assertEquals(at, result.entry.createdAt)
        assertEquals(at, result.updatedAt)
        assertEquals("Asia/Manila", result.entry.displayZoneId)
        assertEquals("Synthetic ☕\nNote", result.entry.note)
    }

    @Test
    fun laterQuoteDoesNotRevaluePreviouslyPostedSnapshot() = runBlocking<Unit> {
        category()
        store.transactions.postManual(entry()).value()
        store.transactions.postManual(entry(number = 11, rate = "0.001")).value()
        assertEquals(
            Long.MAX_VALUE,
            store.transactions.find(id(10)).value().entry.conversion.convertedMinor
        )
        assertEquals(
            922337203685477581L,
            store.transactions.find(id(11)).value().entry.conversion.convertedMinor
        )
    }

    @Test
    fun concurrentIdenticalRetriesCreateOneRecordAndDifferentPayloadConflicts() =
        runBlocking<Unit> {
            category()
            val input = entry()
            coroutineScope {
                (1..24).map { async { store.transactions.postManual(input) } }.awaitAll()
            }
                .forEach {
                    assertEquals(Long.MAX_VALUE, it.value().entry.conversion.convertedMinor)
                }
            assertEquals(1, countTransactions())
            assertEquals(
                LedgerError.CONFLICT,
                store.transactions.postManual(entry(rate = "0.001")).error()
            )
            assertEquals(
                Long.MAX_VALUE,
                store.transactions.find(id(10)).value().entry.conversion.convertedMinor
            )
        }

    @Test
    fun categoryDuplicateIdNeverReplacesExistingCategory() = runBlocking<Unit> {
        category()
        assertEquals(
            LedgerError.CONFLICT,
            store.categories.create(categoryId, EntryType.INCOME, "Other").error()
        )
        assertEquals(EntryType.EXPENSE, store.categories.list().value().single().type)
        assertEquals("Synthetic category", store.categories.list().value().single().name)
    }

    private fun row(number: Int = 20) = TransactionEntity(
        id(number).text, "EXPENSE", 100L, "USD", categoryId.text,
        at.epochSeconds, at.nanosecondsOfSecond, "Asia/Manila", "Synthetic", "MANUAL",
        at.toString(), at.toString(), "USD", 100L, "1", at.toString(), "identity", "HALF_EVEN"
    )

    @Test
    fun databaseGuardsRejectInvalidAmountsEnumsNanosAndUnconfirmedAiRows() = runBlocking<Unit> {
        category()
        val valid = row()
        listOf(
            valid.copy(amountMinor = 0), valid.copy(amountMinor = -1),
            valid.copy(convertedMinor = -1), valid.copy(type = "UNKNOWN"),
            valid.copy(occurredAtNanos = -1), valid.copy(occurredAtNanos = 1000000000),
            valid.copy(source = "AI"), valid.copy(roundingPolicy = "HALF_UP"),
            valid.copy(sourceCurrency = "ZZ"), valid.copy(rateDecimal = "0")
        ).forEach { invalid ->
            assertFailsWith<SQLiteException> { db.ledgerDao().insertTransaction(invalid) }
        }
        assertEquals(0, countTransactions())
        db.ledgerDao().insertTransaction(valid)
        assertFailsWith<SQLiteException> {
            sql("UPDATE ledger_transactions SET amountMinor = 1.5, revision = revision + 1")
        }
        assertEquals(1, countTransactions())
    }

    @Test
    fun compositeForeignKeyAndImmutableCategoryTypeProtectReferences() = runBlocking<Unit> {
        category()
        assertFailsWith<SQLiteException> {
            db.ledgerDao().insertTransaction(row().copy(type = "INCOME"))
        }
        assertFailsWith<SQLiteException> {
            db.ledgerDao().insertTransaction(row().copy(categoryId = id(99).text))
        }
        store.transactions.postManual(entry()).value()
        assertFailsWith<SQLiteException> { sql("UPDATE categories SET type = 'INCOME'") }
        assertFailsWith<SQLiteException> { sql("DELETE FROM categories") }
        assertEquals(1, countTransactions())
    }

    @Test
    fun sqlCannotAssignArchivedCategoryAndGuardsSurviveReopen() = runBlocking<Unit> {
        category()
        store.categories.archive(categoryId, at).value()
        store.close()
        reopen()
        assertFailsWith<SQLiteException> { db.ledgerDao().insertTransaction(row()) }
        assertEquals(0, countTransactions())
    }

    @Test
    fun malformedFrozenSnapshotFailsClosedInsteadOfRecalculatingHistory() = runBlocking<Unit> {
        category()
        store.transactions.postManual(entry(rate = "0.001")).value()
        sql(
            "UPDATE ledger_transactions SET convertedMinor = convertedMinor + 1, revision = revision + 1"
        )
        assertEquals(LedgerError.CORRUPT_DATA, store.transactions.find(id(10)).error())
        assertEquals(1, countTransactions())
    }

    @Test
    fun missingMigrationFailsWithoutDroppingExistingRecords() = runBlocking<Unit> {
        category()
        store.transactions.postManual(entry()).value()
        store.close()
        val driver = BundledSQLiteDriver()
        driver.open(path).use { it.execSQL("PRAGMA user_version = 6") }
        reopen()
        assertEquals(LedgerError.STORAGE_FAILURE, store.transactions.find(id(10)).error())
        store.close()
        driver.open(path).use { connection ->
            connection.prepare("SELECT count(*) FROM ledger_transactions").use { statement ->
                assertTrue(statement.step())
                assertEquals(1L, statement.getLong(0))
            }
        }
    }

    @Test
    fun categoryPaginationIsBoundedAndDeterministic() = runBlocking<Unit> {
        category()
        store.categories.create(id(2), EntryType.EXPENSE, "A").value()
        assertEquals(listOf(id(2)), store.categories.list(limit = 1).value().map { it.id })
        assertEquals(
            listOf(categoryId),
            store.categories.list(limit = 1, offset = 1).value().map {
                it.id
            }
        )
        assertEquals(LedgerError.INVALID_PAGE, store.categories.list(limit = 0).error())
        assertEquals(LedgerError.INVALID_PAGE, store.categories.list(offset = -1).error())
    }

    @Test
    fun concurrentDifferentPayloadsWithSameIdHaveOneWinner() = runBlocking<Unit> {
        category()
        val results = coroutineScope {
            listOf(
                async {
                    store.transactions.postManual(entry())
                },
                async { store.transactions.postManual(entry(rate = "0.001")) }
            ).awaitAll()
        }
        assertEquals(1, results.count { it is LedgerResult.Success })
        assertEquals(
            1,
            results.count {
                it is LedgerResult.Failure &&
                    it.error == LedgerError.CONFLICT
            }
        )
        assertEquals(1, countTransactions())
    }

    @Test
    fun identityAndRoundedZeroSurviveDiskRoundTrip() = runBlocking<Unit> {
        category()
        val usd = CurrencyCode.resolve("USD").value()
        val jpy = CurrencyCode.resolve("JPY").value()
        val amount = Money.ofMinor(1, usd)
        val identity = ConversionSnapshot.identity(amount, at).value()
        val quote = ExchangeRateQuote.create(
            jpy,
            usd,
            DecimalRate.parse("0.0001").value(),
            at,
            "synthetic:tiny"
        ).value()
        val tiny = ConversionSnapshot.convert(Money.ofMinor(1, jpy), usd, quote).value()
        listOf(identity, tiny).forEachIndexed { index, snapshot ->
            store.transactions.postManual(
                ManualEntry.create(
                    id(30 + index),
                    EntryType.EXPENSE,
                    categoryId,
                    snapshot,
                    at,
                    "UTC",
                    null,
                    at
                ).value()
            ).value()
        }
        store.close()
        reopen()
        assertEquals(1L, store.transactions.find(id(30)).value().entry.conversion.convertedMinor)
        assertEquals("identity", store.transactions.find(id(30)).value().entry.conversion.sourceId)
        assertEquals(0L, store.transactions.find(id(31)).value().entry.conversion.convertedMinor)
        assertEquals(
            "USD",
            store.transactions.find(id(31)).value().entry.conversion.baseCurrency.code
        )
    }

    @Test
    fun failureAfterInsertRollsBackAndReturnsSanitizedError() = runBlocking<Unit> {
        category()
        val access = DatabaseAccess(db)
        val result = access.write<Unit> {
            access.dao.insertTransaction(row())
            throw CorruptRecord()
        }
        assertEquals(LedgerError.CORRUPT_DATA, result.error())
        assertEquals(0, countTransactions())
    }

    @Test
    fun cancellationAfterInsertPropagatesAndRollsBack() = runBlocking<Unit> {
        category()
        val access = DatabaseAccess(db)
        assertFailsWith<CancellationException> {
            access.write<Unit> {
                access.dao.insertTransaction(row())
                throw CancellationException("Synthetic cancellation")
            }
        }
        assertEquals(0, countTransactions())
    }

    private suspend fun countTransactions(): Int = db.useReaderConnection { connection ->
        connection.usePrepared("SELECT count(*) FROM ledger_transactions") {
            it.step()
            it.getLong(0).toInt()
        }
    }

    private suspend fun sql(statement: String) {
        db.useWriterConnection { connection -> connection.usePrepared(statement) { it.step() } }
    }
}
