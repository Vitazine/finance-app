package app.finance.data.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
internal interface BudgetDao {
    @Query(
        "SELECT *, typeof(yearMonth)||':'||typeof(currency)||':'||typeof(limitMinor)||':'||typeof(updatedAt) AS storageTypes FROM monthly_budgets WHERE yearMonth = :month"
    )
    suspend fun find(month: String): BudgetReadRow?

    @Upsert suspend fun put(row: BudgetEntity)
}
