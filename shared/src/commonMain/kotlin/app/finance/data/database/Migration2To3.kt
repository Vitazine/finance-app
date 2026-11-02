package app.finance.data.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal object Migration2To3 : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS exchange_rates (
                sourceCurrency TEXT NOT NULL, baseCurrency TEXT NOT NULL,
                effectiveAtSeconds INTEGER NOT NULL, effectiveAtNanos INTEGER NOT NULL,
                sourceId TEXT NOT NULL, rateDecimal TEXT NOT NULL,
                retrievedAtSeconds INTEGER NOT NULL, retrievedAtNanos INTEGER NOT NULL,
                PRIMARY KEY(sourceCurrency, baseCurrency, effectiveAtSeconds, effectiveAtNanos, sourceId)
            )
            """.trimIndent()
        )
        RateGuards.install(connection)
    }
}
