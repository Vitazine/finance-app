package app.finance.data.repository

import app.finance.core.money.Money
import app.finance.domain.budget.BudgetMonth
import app.finance.domain.budget.BudgetOverview
import app.finance.domain.budget.BudgetRepository
import app.finance.domain.budget.MonthlyBudget
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerResult
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

internal class RoomBudgetRepository(private val access: DatabaseAccess) : BudgetRepository {
    override suspend fun current(at: Instant): LedgerResult<BudgetOverview> = access.snapshot {
        val settings =
            access.dao.settings()?.toDomain()
                ?: return@snapshot LedgerResult.Failure(LedgerError.NOT_INITIALIZED)
        val zone = TimeZone.of(settings.zoneId)
        val month = when (val result = BudgetMonth.at(at, zone)) {
            is LedgerResult.Failure -> return@snapshot result
            is LedgerResult.Success -> result.value
        }
        val budget = access.budgetDao.find(month.key)?.toDomain()
        if (budget != null &&
            budget.limit.currency != settings.primaryCurrency
        ) {
            return@snapshot LedgerResult.Failure(LedgerError.BASE_CURRENCY_MISMATCH)
        }
        val start = month.start(zone)
        val end = month.nextStart(zone)
        var total = BigInteger.ZERO
        var after: String? = null
        while (true) {
            val rows = access.dao.transactionBatch(after, 100)
            if (rows.isEmpty()) break
            for (row in rows) {
                val entry = row.toDomain().entry
                if (entry.type != EntryType.EXPENSE ||
                    entry.occurredAt < start ||
                    entry.occurredAt >= end
                ) {
                    continue
                }
                if (entry.conversion.baseCurrency !=
                    settings.primaryCurrency
                ) {
                    return@snapshot LedgerResult.Failure(LedgerError.BASE_CURRENCY_MISMATCH)
                }
                total += BigInteger.fromLong(entry.conversion.convertedMinor)
            }
            after = rows.last().id
        }
        val minor =
            total.toString().toLongOrNull()
                ?: return@snapshot LedgerResult.Failure(LedgerError.AMOUNT_OVERFLOW)
        LedgerResult.Success(
            BudgetOverview(
                month,
                settings.zoneId,
                Money.ofMinor(minor, settings.primaryCurrency),
                budget
            )
        )
    }
    override suspend fun put(
        month: BudgetMonth,
        limit: Money,
        updatedAt: Instant
    ): LedgerResult<MonthlyBudget> = access.write {
        val settings =
            access.dao.settings()?.toDomain()
                ?: return@write LedgerResult.Failure(LedgerError.NOT_INITIALIZED)
        if (limit.currency !=
            settings.primaryCurrency
        ) {
            return@write LedgerResult.Failure(LedgerError.BASE_CURRENCY_MISMATCH)
        }
        val budget = when (val result = MonthlyBudget.create(month, limit, updatedAt)) {
            is LedgerResult.Failure -> return@write result
            is LedgerResult.Success -> result.value
        }
        access.budgetDao.find(month.key)?.toDomain()
        access.budgetDao.put(budget.toEntity())
        LedgerResult.Success(budget)
    }
}
