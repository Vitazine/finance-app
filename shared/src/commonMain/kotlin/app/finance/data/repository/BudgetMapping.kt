package app.finance.data.repository

import app.finance.core.money.CurrencyCode
import app.finance.core.money.Money
import app.finance.core.money.MoneyResult
import app.finance.data.database.BudgetEntity
import app.finance.data.database.BudgetReadRow
import app.finance.domain.budget.BudgetMonth
import app.finance.domain.budget.MonthlyBudget
import app.finance.domain.ledger.LedgerResult
import kotlinx.datetime.Instant

internal fun MonthlyBudget.toEntity() =
    BudgetEntity(month.key, limit.currency.code, limit.amountMinor, updatedAt.toString())
internal fun BudgetReadRow.toDomain(): MonthlyBudget {
    if (storageTypes != "text:text:integer:text") throw CorruptRecord()
    val row = budget
    if (!Regex("[0-9]{4}-[0-9]{2}").matches(row.yearMonth)) throw CorruptRecord()
    val month =
        (
            BudgetMonth.create(
                row.yearMonth.take(4).toInt(),
                row.yearMonth.takeLast(2).toInt()
            ) as? LedgerResult.Success
            )?.value
            ?: throw CorruptRecord()
    val currency =
        (CurrencyCode.resolve(row.currency) as? MoneyResult.Success)?.value ?: throw CorruptRecord()
    val at = try {
        Instant.parse(row.updatedAt)
    } catch (
        _: IllegalArgumentException
    ) {
        throw CorruptRecord()
    }
    val result =
        (
            MonthlyBudget.create(
                month,
                Money.ofMinor(row.limitMinor, currency),
                at
            ) as? LedgerResult.Success
            )?.value
            ?: throw CorruptRecord()
    if (result.toEntity() != row) throw CorruptRecord()
    return result
}
