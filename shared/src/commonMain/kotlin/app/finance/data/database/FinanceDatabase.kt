package app.finance.data.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

@Database(
    entities = [
        CategoryEntity::class,
        TransactionEntity::class,
        SettingsEntity::class,
        RateEntity::class,
        AiConfirmationEntity::class,
        BudgetEntity::class
    ],
    version = 5,
    exportSchema = true
)
@ConstructedBy(FinanceDatabaseConstructor::class)
internal abstract class FinanceDatabase : RoomDatabase() {
    abstract fun integrityDao(): IntegrityDao
    abstract fun backupDao(): BackupDao
    abstract fun aiConfirmationDao(): AiConfirmationDao
    abstract fun analyticsDao(): AnalyticsDao
    abstract fun ledgerDao(): LedgerDao
    abstract fun budgetDao(): BudgetDao
    abstract fun rateDao(): RateDao
}

@Suppress("KotlinNoActualForExpect")
internal expect object FinanceDatabaseConstructor : RoomDatabaseConstructor<FinanceDatabase> {
    override fun initialize(): FinanceDatabase
}
