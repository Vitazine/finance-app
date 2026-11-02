package app.finance.android

import android.app.Application
import app.finance.data.database.openLedgerStore

class FinanceApplication : Application() {
    val ledgerStore by lazy { openLedgerStore(this) }
}
