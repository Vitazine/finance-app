package app.finance.data.database

import androidx.room.Dao
import androidx.room.Query

@Dao internal interface BackupDao {
    @Query("SELECT * FROM categories ORDER BY id LIMIT 10001")
    suspend fun categories(): List<CategoryEntity>

    @Query("SELECT * FROM ledger_transactions ORDER BY id LIMIT 10001")
    suspend fun transactions(): List<TransactionEntity>

    @Query(
        "SELECT * FROM exchange_rates ORDER BY sourceCurrency,baseCurrency,effectiveAtSeconds,effectiveAtNanos,sourceId LIMIT 10001"
    )
    suspend fun rates(): List<RateEntity>

    @Query("SELECT * FROM monthly_budgets ORDER BY yearMonth LIMIT 10001")
    suspend fun budgets(): List<BudgetEntity>

    @Query("SELECT * FROM ai_confirmations ORDER BY draftId,proposalIndex LIMIT 10001")
    suspend fun confirmations(): List<AiConfirmationEntity>

    @Query("DELETE FROM ledger_transactions")
    suspend fun clearTransactions()

    @Query("DELETE FROM ai_confirmations")
    suspend fun clearConfirmations()

    @Query("DELETE FROM categories")
    suspend fun clearCategories()

    @Query("DELETE FROM exchange_rates")
    suspend fun clearRates()

    @Query("DELETE FROM monthly_budgets")
    suspend fun clearBudgets()

    @Query("DELETE FROM ledger_settings")
    suspend fun clearSettings()
}
