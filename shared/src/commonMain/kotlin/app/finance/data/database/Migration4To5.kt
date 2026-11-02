package app.finance.data.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal object Migration4To5 : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS ai_confirmations (draftId TEXT NOT NULL, proposalIndex INTEGER NOT NULL, transactionId TEXT NOT NULL, payloadHash TEXT NOT NULL, PRIMARY KEY(draftId, proposalIndex))"
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_ai_confirmations_transactionId ON ai_confirmations(transactionId)"
        )
    }
}
