package app.finance.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.finance.core.money.CurrencyCode
import app.finance.core.money.MoneyResult
import app.finance.data.database.FinanceDatabase
import app.finance.data.database.FinanceDatabaseConstructor
import app.finance.data.database.Migration1To2
import app.finance.data.database.Migration2To3
import app.finance.data.repository.LedgerStore
import app.finance.data.repository.buildLedgerDatabase
import app.finance.domain.exchange.CurrencyPair
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.Rule

class RateMigrationTest {
    private val directory = Files.createTempDirectory("fin012-migration-synthetic-")
    private val path = directory.resolve("old.db")
    private val at = Instant.parse("2026-11-01T01:02:03.123456789Z")
    private val category = "00000000-0000-4000-8000-000000000001"
    private val transaction = "00000000-0000-4000-8000-000000000010"

    @get:Rule val migration = MigrationTestHelper(
        Paths.get("schemas"),
        path,
        BundledSQLiteDriver(),
        FinanceDatabase::class,
        { FinanceDatabaseConstructor.initialize() }
    )
    private fun <T> LedgerResult<T>.value() = (this as LedgerResult.Success).value
    private fun <T> MoneyResult<T>.value() = (this as MoneyResult.Success).value
    private fun pair() = CurrencyPair.create(
        CurrencyCode.resolve("KWD").value(),
        CurrencyCode.resolve("USD").value()
    ).value()
    private fun open() = LedgerStore(
        buildLedgerDatabase(Room.databaseBuilder<FinanceDatabase>(name = path.toString()))
    )

    @AfterTest fun cleanup() {
        directory.toFile().deleteRecursively()
    }

    @Test fun v2SettingsRevisionArchivedCategoryAndFrozenQuoteArePreserved() = runBlocking<Unit> {
        migration.createDatabase(2).use { connection ->
            connection.execSQL(
                "INSERT INTO ledger_settings (id,primaryCurrency,zoneId) VALUES(1,'USD','Asia/Manila')"
            )
            connection.prepare(
                "INSERT INTO categories (id,type,name,archivedAt) VALUES(?,'EXPENSE','Synthetic legacy',?)"
            ).use {
                it.bindText(1, category)
                it.bindText(2, at.toString())
                it.step()
            }
            connection.prepare(
                """INSERT INTO ledger_transactions
                (id,type,amountMinor,sourceCurrency,categoryId,occurredAtSeconds,occurredAtNanos,displayZoneId,note,source,createdAt,updatedAt,baseCurrency,convertedMinor,rateDecimal,fxEffectiveAt,fxSourceId,roundingPolicy,revision)
                VALUES(?,'EXPENSE',1245,'KWD',?,?,123456789,'Asia/Manila','Synthetic legacy note','MANUAL',?,?,'USD',125,'1.004',?,'synthetic:legacy','HALF_EVEN',7)"""
            ).use {
                it.bindText(1, transaction)
                it.bindText(2, category)
                it.bindLong(3, at.epochSeconds)
                it.bindText(4, at.toString())
                it.bindText(5, at.toString())
                it.bindText(6, at.toString())
                it.step()
            }
        }
        migration.runMigrationsAndValidate(3, listOf(Migration2To3)).close()
        val store = open()
        try {
            assertEquals("USD", store.settings.current().value()!!.primaryCurrency.code)
            assertEquals("Asia/Manila", store.settings.current().value()!!.zoneId)
            val saved = store.transactions.find(LedgerId.parse(transaction).value()).value()
            assertEquals(7L, saved.revision)
            assertEquals(at, saved.entry.occurredAt)
            assertEquals("Synthetic legacy note", saved.entry.note)
            assertEquals(125L, saved.entry.conversion.convertedMinor)
            assertEquals("1.004", saved.entry.conversion.rateDecimal)
            assertEquals("synthetic:legacy", saved.entry.conversion.sourceId)
            assertEquals(
                at,
                store.categories.list(includeArchived = true).value().single().archivedAt
            )
            assertEquals(null, store.rates.latest(pair(), at).value())
            assertEquals(-125L, store.transactions.overview().value().balance.amountMinor)
        } finally {
            store.close()
        }
    }

    @Test fun emptyV2UpgradeDoesNotInventSettingsOrRates() = runBlocking<Unit> {
        migration.createDatabase(2).close()
        migration.runMigrationsAndValidate(3, listOf(Migration2To3)).close()
        val store = open()
        try {
            assertEquals(null, store.settings.current().value())
            assertEquals(null, store.rates.latest(pair(), at).value())
        } finally {
            store.close()
        }
    }

    @Test fun v1ToV3ChainRetainsUnconfiguredLedgerWithoutDestruction() = runBlocking<Unit> {
        migration.createDatabase(1).close()
        migration.runMigrationsAndValidate(
            3,
            listOf(Migration1To2, Migration2To3)
        ).use { connection ->
            connection.prepare("SELECT count(*) FROM ledger_transactions").use { row ->
                row.step()
                assertEquals(0L, row.getLong(0))
            }
        }
        val store = open()
        try {
            assertEquals(null, store.settings.current().value())
            assertEquals(null, store.rates.latest(pair(), at).value())
        } finally {
            store.close()
        }
    }
}
