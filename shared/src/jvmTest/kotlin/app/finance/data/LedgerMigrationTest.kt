package app.finance.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.finance.core.money.CurrencyCode
import app.finance.core.money.MoneyResult
import app.finance.data.database.FinanceDatabase
import app.finance.data.database.FinanceDatabaseConstructor
import app.finance.data.database.Migration1To2
import app.finance.data.repository.LedgerStore
import app.finance.data.repository.buildLedgerDatabase
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.Rule

class LedgerMigrationTest {
    private val directory = Files.createTempDirectory("fin011-migration-synthetic-")
    private val path = directory.resolve("legacy.db")
    private val categoryId = "00000000-0000-4000-8000-000000000001"
    private val transactionId = "00000000-0000-4000-8000-000000000010"
    private val at = "2026-11-01T01:02:03.123456789Z"

    @get:Rule
    val migration =
        MigrationTestHelper(
            Paths.get(
                "schemas"
            ),
            path,
            BundledSQLiteDriver(),
            FinanceDatabase::class,
            {
                FinanceDatabaseConstructor.initialize()
            }
        )

    @AfterTest
    fun cleanup() {
        directory.toFile().deleteRecursively()
    }
    private fun <T> LedgerResult<T>.value(): T = (this as LedgerResult.Success).value
    private fun LedgerResult<*>.error() = (this as LedgerResult.Failure).error
    private fun settings(code: String) = LedgerSettings.create(
        (CurrencyCode.resolve(code) as MoneyResult.Success).value,
        "UTC"
    ).value()
    private fun id(text: String) = LedgerId.parse(text).value()

    private fun seed(connection: SQLiteConnection, mixed: Boolean = false) {
        connection.prepare(
            "INSERT INTO categories (id, type, name, archivedAt) VALUES (?, 'EXPENSE', 'Synthetic legacy', ?)"
        ).use {
            it.bindText(1, categoryId)
            it.bindText(2, at)
            it.step()
        }
        val sql = """INSERT INTO ledger_transactions
            (id,type,amountMinor,sourceCurrency,categoryId,occurredAtSeconds,occurredAtNanos,displayZoneId,note,source,createdAt,updatedAt,baseCurrency,convertedMinor,rateDecimal,fxEffectiveAt,fxSourceId,roundingPolicy)
            VALUES (?, 'EXPENSE', ?, 'JPY', ?, ?, 123456789, 'Asia/Manila', 'Synthetic legacy note', 'MANUAL', ?, ?, ?, ?, ?, ?, ?, 'HALF_EVEN')"""
        fun insert(
            key: String,
            base: String,
            amount: Long,
            converted: Long,
            rate: String,
            provider: String
        ) {
            connection.prepare(sql).use {
                it.bindText(1, key)
                it.bindLong(2, amount)
                it.bindText(3, categoryId)
                it.bindLong(4, Instant.parse(at).epochSeconds)
                it.bindText(5, at)
                it.bindText(6, at)
                it.bindText(7, base)
                it.bindLong(8, converted)
                it.bindText(9, rate)
                it.bindText(10, at)
                it.bindText(11, provider)
                it.step()
            }
        }
        insert(transactionId, "USD", Long.MAX_VALUE, Long.MAX_VALUE, "0.01", "synthetic:legacy")
        if (mixed) insert("00000000-0000-4000-8000-000000000011", "JPY", 100, 100, "1", "identity")
        // A real v1 trigger must survive the ALTER/add-table migration.
        connection.execSQL(
            """CREATE TRIGGER category_type_immutable BEFORE UPDATE OF type ON categories
            WHEN NEW.type != OLD.type BEGIN SELECT RAISE(ABORT, 'category_type_immutable'); END"""
        )
    }

    private fun store() = LedgerStore(
        buildLedgerDatabase(Room.databaseBuilder<FinanceDatabase>(name = path.toString()))
    )

    @Test
    fun v1RecordsMigrateExactlyAndRequireMatchingInitialBasis() = runBlocking<Unit> {
        migration.createDatabase(1).use { seed(it) }
        migration.runMigrationsAndValidate(2, listOf(Migration1To2)).use {
            assertFailsWith<SQLiteException> { it.execSQL("UPDATE categories SET type = 'INCOME'") }
            it.prepare("SELECT revision FROM ledger_transactions").use { row ->
                row.step()
                assertEquals(0L, row.getLong(0))
            }
        }
        val store = store()
        try {
            assertEquals(null, store.settings.current().value())
            assertEquals("USD", store.settings.existingBasis().value()!!.code)
            assertEquals(
                LedgerError.BASE_CURRENCY_MISMATCH,
                store.settings.initialize(settings("JPY")).error()
            )
            store.settings.initialize(settings("USD")).value()
            val saved = store.transactions.find(id(transactionId)).value()
            assertEquals(Long.MAX_VALUE, saved.entry.conversion.sourceAmount.amountMinor)
            assertEquals(Long.MAX_VALUE, saved.entry.conversion.convertedMinor)
            assertEquals("0.01", saved.entry.conversion.rateDecimal)
            assertEquals("synthetic:legacy", saved.entry.conversion.sourceId)
            assertEquals(Instant.parse(at), saved.entry.occurredAt)
            assertEquals("Synthetic legacy note", saved.entry.note)
            assertEquals(0L, saved.revision)
            assertEquals(
                Instant.parse(at),
                store.categories.list(includeArchived = true).value().single().archivedAt
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun mixedLegacyBasesArePreservedAndSetupBlocked() = runBlocking<Unit> {
        migration.createDatabase(1).use { seed(it, mixed = true) }
        migration.runMigrationsAndValidate(2, listOf(Migration1To2)).close()
        val store = store()
        try {
            assertEquals(LedgerError.LEGACY_BASE_CONFLICT, store.settings.existingBasis().error())
            assertEquals(
                LedgerError.LEGACY_BASE_CONFLICT,
                store.settings.initialize(settings("USD")).error()
            )
            assertEquals(null, store.settings.current().value())
            assertEquals(
                "USD",
                store.transactions.find(
                    id(transactionId)
                ).value().entry.conversion.baseCurrency.code
            )
            assertEquals(
                "JPY",
                store.transactions.find(
                    id("00000000-0000-4000-8000-000000000011")
                ).value().entry.conversion.baseCurrency.code
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun emptyV1MigratesWithoutInventingSettings() = runBlocking<Unit> {
        migration.createDatabase(1).close()
        migration.runMigrationsAndValidate(2, listOf(Migration1To2)).close()
        val store = store()
        try {
            assertEquals(null, store.settings.current().value())
            assertEquals(null, store.settings.existingBasis().value())
            store.settings.initialize(settings("RUB")).value()
            assertEquals("RUB", store.settings.current().value()!!.primaryCurrency.code)
        } finally {
            store.close()
        }
    }
}
