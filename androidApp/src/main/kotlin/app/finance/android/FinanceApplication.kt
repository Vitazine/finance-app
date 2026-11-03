package app.finance.android

import android.app.Application
import app.finance.data.database.openLedgerStore

class FinanceApplication : Application() {
    val ledgerStore by lazy { openLedgerStore(this) }
    internal val categoryBootstrap by lazy {
        app.finance.android.ledger.CategoryBootstrap(
            ledgerStore.categories,
            getSharedPreferences("default_categories", MODE_PRIVATE)
        )
    }
}
