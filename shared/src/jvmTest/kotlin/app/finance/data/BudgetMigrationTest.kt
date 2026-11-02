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
import app.finance.data.database.Migration3To4
import app.finance.data.repository.LedgerStore
import app.finance.data.repository.buildLedgerDatabase
import app.finance.domain.exchange.CurrencyPair
import app.finance.domain.ledger.LedgerResult
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.Rule

class BudgetMigrationTest {
    private val directory = Files.createTempDirectory("fin013-migration-")
    private val path = directory.resolve("old.db")

    @get:Rule val migration =
        MigrationTestHelper(
            Paths.get("schemas"),
            path,
            BundledSQLiteDriver(),
            FinanceDatabase::class,
            FinanceDatabaseConstructor::initialize
        )

    @AfterTest fun cleanup() {
        directory.toFile().deleteRecursively()
    }
    private fun <T> LedgerResult<T>.value() = (this as LedgerResult.Success).value
    private fun <T> MoneyResult<T>.value() = (this as MoneyResult.Success).value
    private fun currency(code: String) = (CurrencyCode.resolve(code) as MoneyResult.Success).value

    @Test fun v3SettingsAndExactRateSurviveWithNoInventedBudget() = runBlocking<Unit> {
        migration.createDatabase(3).use { db ->
            db.execSQL("INSERT INTO ledger_settings VALUES(1,'USD','Asia/Manila')")
            db.execSQL(
                "INSERT INTO exchange_rates VALUES('EUR','USD',1793494800,123456789,'manual','1.004',1793494801,987654321)"
            )
        }
        migration.runMigrationsAndValidate(4, listOf(Migration3To4)).close()
        val store =
            LedgerStore(
                buildLedgerDatabase(Room.databaseBuilder<FinanceDatabase>(name = path.toString()))
            )
        try {
            val at = Instant.parse("2026-11-15T00:00:00Z")
            val view = store.budgets.current(at).value()
            assertEquals("Asia/Manila", view.zoneId)
            assertNull(view.budget)
            assertEquals(0L, view.spent.amountMinor)
            val rate = store.rates.latest(
                CurrencyPair.create(currency("EUR"), currency("USD")).value(),
                at
            ).value()!!
            assertEquals("1.004", rate.quote.rate.canonicalText)
            assertEquals(123456789, rate.quote.effectiveAt.nanosecondsOfSecond)
            assertEquals(987654321, rate.retrievedAt.nanosecondsOfSecond)
        } finally {
            store.close()
        }
    }

    @Test fun entireMigrationChainSupportsFreshV1WithoutSettings() {
        migration.createDatabase(1).close()
        migration.runMigrationsAndValidate(
            4,
            listOf(Migration1To2, Migration2To3, Migration3To4)
        ).use { db ->
            for (table in listOf(
                "ledger_transactions",
                "ledger_settings",
                "exchange_rates",
                "monthly_budgets"
            )) {
                db.prepare("SELECT count(*) FROM " + table).use { row ->
                    row.step()
                    assertEquals(0L, row.getLong(0))
                }
            }
        }
    }
}
