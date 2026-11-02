package app.finance.data.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal object Migration1To2 : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE ledger_transactions ADD COLUMN revision INTEGER NOT NULL DEFAULT 0"
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ledger_settings (
                id INTEGER NOT NULL, primaryCurrency TEXT NOT NULL, zoneId TEXT NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent()
        )
    }
}
