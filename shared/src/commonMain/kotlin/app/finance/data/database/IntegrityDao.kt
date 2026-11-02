package app.finance.data.database

import androidx.room.Dao
import androidx.room.Query

@Dao internal interface IntegrityDao {
    @Query(
        "SELECT * FROM exchange_rates ORDER BY sourceCurrency,baseCurrency,effectiveAtSeconds,effectiveAtNanos,sourceId LIMIT 200 OFFSET :offset"
    )
    suspend fun rates(offset: Int): List<RateEntity>

    @Query("SELECT * FROM monthly_budgets ORDER BY yearMonth LIMIT 200 OFFSET :offset")
    suspend fun budgets(offset: Int): List<BudgetEntity>

    @Query("SELECT * FROM ai_confirmations ORDER BY draftId,proposalIndex LIMIT 200 OFFSET :offset")
    suspend fun confirmations(offset: Int): List<AiConfirmationEntity>

    @Query("SELECT * FROM ai_confirmations WHERE transactionId=:id")
    suspend fun receiptForTransaction(id: String): AiConfirmationEntity?
}
