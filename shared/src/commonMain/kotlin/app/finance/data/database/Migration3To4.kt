package app.finance.data.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal object Migration3To4 : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS monthly_budgets (
                yearMonth TEXT NOT NULL, currency TEXT NOT NULL,
                limitMinor INTEGER NOT NULL, updatedAt TEXT NOT NULL,
                PRIMARY KEY(yearMonth)
            )
            """.trimIndent()
        )
        BudgetGuards.install(connection)
    }
}
