package app.finance.data.database

import android.content.Context
import androidx.room.Room
import app.finance.data.repository.LedgerStore
import app.finance.data.repository.buildLedgerDatabase

private val databaseName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}\\.db")

/** Opens app-private storage; no caller-supplied path or external storage access. */
fun openLedgerStore(context: Context, name: String = "finance-ledger.db"): LedgerStore {
    require(name.length in 4..128 && databaseName.matches(name)) { "Invalid database name" }
    val application = context.applicationContext
    val path = application.getDatabasePath(name).absolutePath
    return LedgerStore(
        buildLedgerDatabase(Room.databaseBuilder<FinanceDatabase>(application, name = path))
    )
}
