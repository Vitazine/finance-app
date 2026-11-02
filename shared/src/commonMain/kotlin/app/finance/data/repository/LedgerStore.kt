package app.finance.data.repository

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.finance.data.database.DatabaseGuards
import app.finance.data.database.FinanceDatabase
import app.finance.data.database.Migration1To2
import app.finance.data.database.Migration2To3
import app.finance.data.database.Migration3To4
import app.finance.data.database.Migration4To5
import app.finance.domain.exchange.RateCache
import app.finance.domain.ledger.CategoryRepository
import app.finance.domain.ledger.SettingsRepository
import app.finance.domain.ledger.TransactionRepository
import kotlinx.coroutines.Dispatchers

class LedgerStore internal constructor(private val database: FinanceDatabase) {
    private val access = DatabaseAccess(database)
    val settings: SettingsRepository = RoomSettingsRepository(access)
    val categories: CategoryRepository = RoomCategoryRepository(access)
    val transactions: TransactionRepository = RoomTransactionRepository(access)
    val budgets: app.finance.domain.budget.BudgetRepository = RoomBudgetRepository(access)
    val analytics: app.finance.domain.analytics.AnalyticsRepository =
        RoomAnalyticsRepository(access)
    val aiConfirmations: app.finance.domain.ai.AiConfirmationRepository =
        RoomAiConfirmationRepository(access)
    val backups: app.finance.domain.backup.BackupRepository =
        app.finance.data.backup.RoomBackupRepository(access)
    val rates: RateCache = RoomRateCache(access)
    fun close() = database.close()
}

internal fun buildLedgerDatabase(builder: RoomDatabase.Builder<FinanceDatabase>): FinanceDatabase =
    builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addCallback(DatabaseGuards)
        .addMigrations(Migration1To2, Migration2To3, Migration3To4, Migration4To5)
        .build()
