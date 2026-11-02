package app.finance.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.finance.data.database.FinanceDatabase
import app.finance.data.database.FinanceDatabaseConstructor
import app.finance.data.database.Migration4To5
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule

class ConfirmationMigrationTest {
    private val dir = Files.createTempDirectory("fin017-migration-")

    @get:Rule val helper =
        MigrationTestHelper(
            Paths.get("schemas"),
            dir.resolve("db"),
            BundledSQLiteDriver(),
            FinanceDatabase::class,
            FinanceDatabaseConstructor::initialize
        )

    @AfterTest fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test fun schema4BudgetSettingsAndCategoriesSurviveAdditiveReceiptMigration() {
        helper.createDatabase(4).use { db ->
            db.execSQL("INSERT INTO ledger_settings VALUES(1,'USD','UTC')")
            db.execSQL(
                "INSERT INTO categories VALUES('00000000-0000-4000-8000-000000000001','EXPENSE','Synthetic',NULL)"
            )
            db.execSQL(
                "INSERT INTO monthly_budgets VALUES('2026-11','USD',12345,'2026-11-02T12:00:00Z')"
            )
        }
        helper.runMigrationsAndValidate(5, listOf(Migration4To5)).use { db ->
            db.prepare("SELECT limitMinor FROM monthly_budgets").use { r ->
                assertTrue(r.step())
                assertEquals(12345L, r.getLong(0))
            }
            for (table in listOf(
                "categories",
                "ledger_settings"
            )) {
                db.prepare("SELECT count(*) FROM $table").use { r ->
                    r.step()
                    assertEquals(1L, r.getLong(0))
                }
            }
            db.prepare("SELECT count(*) FROM ai_confirmations").use { r ->
                r.step()
                assertEquals(0L, r.getLong(0))
            }
        }
    }
}
