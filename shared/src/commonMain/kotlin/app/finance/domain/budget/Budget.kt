package app.finance.domain.budget

import app.finance.core.money.Money
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerResult
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

class BudgetMonth private constructor(val year: Int, val month: Int) {
    val key: String get() = year.toString().padStart(4, '0') + "-" +
        month.toString().padStart(2, '0')
    fun start(zone: TimeZone): Instant = LocalDate(year, month, 1).atStartOfDayIn(zone)
    fun nextStart(zone: TimeZone): Instant =
        LocalDate(year, month, 1).plus(1, DateTimeUnit.MONTH).atStartOfDayIn(zone)
    override fun equals(other: Any?) =
        other is BudgetMonth && year == other.year && month == other.month
    override fun hashCode() = year * 12 + month
    override fun toString() = "BudgetMonth"
    companion object {
        fun create(year: Int, month: Int): LedgerResult<BudgetMonth> =
            if (year in 1..9998 && month in 1..12) {
                LedgerResult.Success(BudgetMonth(year, month))
            } else {
                LedgerResult.Failure(LedgerError.INVALID_MONTH)
            }
        fun at(at: Instant, zone: TimeZone): LedgerResult<BudgetMonth> {
            val date = at.toLocalDateTime(zone).date
            return create(date.year, date.monthNumber)
        }
    }
}
class MonthlyBudget private constructor(
    val month: BudgetMonth,
    val limit: Money,
    val updatedAt: Instant
) {
    override fun toString() = "MonthlyBudget"
    companion object {
        fun create(
            month: BudgetMonth,
            limit: Money,
            updatedAt: Instant
        ): LedgerResult<MonthlyBudget> = if (limit.amountMinor > 0) {
            LedgerResult.Success(MonthlyBudget(month, limit, updatedAt))
        } else {
            LedgerResult.Failure(LedgerError.INVALID_BUDGET)
        }
    }
}
class BudgetOverview internal constructor(
    val month: BudgetMonth,
    val zoneId: String,
    val spent: Money,
    val budget: MonthlyBudget?
) {
    val remaining: Money? get() = budget?.let {
        Money.ofMinor(
            it.limit.amountMinor - spent.amountMinor,
            spent.currency
        )
    }
    val overrun: Money? get() = remaining?.let {
        Money.ofMinor(
            if (it.amountMinor <
                0
            ) {
                -it.amountMinor
            } else {
                0
            },
            spent.currency
        )
    }
    override fun toString() = "BudgetOverview"
}
interface BudgetRepository {
    suspend fun current(at: Instant): LedgerResult<BudgetOverview>
    suspend fun put(
        month: BudgetMonth,
        limit: Money,
        updatedAt: Instant
    ): LedgerResult<MonthlyBudget>
}
