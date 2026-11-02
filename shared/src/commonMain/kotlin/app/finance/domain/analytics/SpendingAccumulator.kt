package app.finance.domain.analytics

import app.finance.core.money.CurrencyCode
import app.finance.core.money.Money
import app.finance.domain.budget.BudgetMonth
import app.finance.domain.ledger.Category
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.HistoryItem
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlinx.datetime.Instant
import kotlinx.datetime.toLocalDateTime

internal class SpendingAccumulator(
    private val reference: Instant,
    private val completed: CompletedDays,
    private val currency: CurrencyCode
) {
    private class Bucket(val category: Category, var amount: BigInteger) {
        override fun toString() = "CategoryBucket"
    }
    private val month = (
        BudgetMonth.create(
            completed.today.year,
            completed.today.monthNumber
        ) as LedgerResult.Success
        ).value
    private val monthStart = month.start(completed.zone)
    private val monthEnd = month.nextStart(completed.zone)
    private val daily = completed.dates.associateWith { BigInteger.ZERO }.toMutableMap()
    private val categories = mutableMapOf<LedgerId, Bucket>()
    private var total = BigInteger.ZERO
    private var actual = BigInteger.ZERO
    private var problem: LedgerError? = null
    private fun fail(error: LedgerError): LedgerResult.Failure {
        problem = error
        return LedgerResult.Failure(error)
    }
    fun add(item: HistoryItem): LedgerResult<Unit> {
        problem?.let { return LedgerResult.Failure(it) }
        val entry = item.transaction.entry
        if (entry.type != EntryType.EXPENSE) return LedgerResult.Success(Unit)
        val rolling = completed.contains(entry.occurredAt)
        val monthly = entry.occurredAt >= monthStart && entry.occurredAt < monthEnd
        if (!rolling && !monthly) return LedgerResult.Success(Unit)
        if (entry.conversion.baseCurrency != currency) {
            return fail(
                LedgerError.BASE_CURRENCY_MISMATCH
            )
        }
        if (item.category.id != entry.categoryId ||
            item.category.type != EntryType.EXPENSE
        ) {
            return fail(LedgerError.CORRUPT_DATA)
        }
        val value = BigInteger.fromLong(entry.conversion.convertedMinor)
        if (monthly) actual += value
        if (rolling) {
            total += value
            val date = entry.occurredAt.toLocalDateTime(completed.zone).date
            val old = daily[date] ?: return fail(LedgerError.CORRUPT_DATA)
            daily[date] = old + value
            val category = categories.getOrPut(item.category.id) {
                Bucket(item.category, BigInteger.ZERO)
            }
            category.amount += value
        }
        return LedgerResult.Success(Unit)
    }
    fun finish(): LedgerResult<SpendingSnapshot> {
        problem?.let { return LedgerResult.Failure(it) }
        val totalMinor = total.toString().toLongOrNull() ?: return fail(LedgerError.AMOUNT_OVERFLOW)
        val actualMinor = actual.toString().toLongOrNull() ?: return fail(
            LedgerError.AMOUNT_OVERFLOW
        )
        val days = completed.dates.map {
            DailySpending(it, Money.ofMinor(daily.getValue(it).toString().toLong(), currency))
        }
        val groups = categories.values.map {
            CategorySpending(
                it.category.id,
                it.category.name,
                it.category.archivedAt,
                Money.ofMinor(it.amount.toString().toLong(), currency)
            )
        }
            .sortedWith(
                compareByDescending<CategorySpending> {
                    it.amount.amountMinor
                }.thenBy { it.id.text }
            )
        return LedgerResult.Success(
            SpendingSnapshot(
                reference,
                completed,
                Money.ofMinor(totalMinor, currency),
                days,
                groups,
                month,
                Money.ofMinor(actualMinor, currency)
            )
        )
    }
}
