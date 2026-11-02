package app.finance.data.database

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "monthly_budgets")
internal data class BudgetEntity(
    @PrimaryKey val yearMonth: String,
    val currency: String,
    val limitMinor: Long,
    val updatedAt: String
)
internal data class BudgetReadRow(@Embedded val budget: BudgetEntity, val storageTypes: String)
