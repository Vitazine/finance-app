package app.finance.domain.analytics

import app.finance.core.money.CurrencyCode
import app.finance.core.money.Money
import app.finance.core.money.MoneyError
import app.finance.core.money.MoneyResult
import app.finance.core.money.RoundingPolicy
import app.finance.domain.budget.BudgetMonth
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

/** Exact minor-unit rational. No display rounding or binary floating point. */
class ExactSpendingRate internal constructor(
    val numeratorMinor: String,
    val denominator: Int,
    val currency: CurrencyCode
) {
    val isExactMinor: Boolean get() = BigInteger.parseString(
        numeratorMinor,
        10
    ).remainder(BigInteger.fromInt(denominator)) ==
        BigInteger.ZERO

    /** Presentation only; never feed this rounded value back into analytics or stored totals. */
    fun roundedForDisplay(policy: RoundingPolicy): MoneyResult<Money> {
        val numerator = BigInteger.parseString(numeratorMinor, 10)
        val divisor = BigInteger.fromInt(denominator)
        val (whole, remainder) = numerator.divideAndRemainder(divisor)
        val rounded = when (policy) {
            RoundingPolicy.HALF_EVEN -> {
                val comparison = (remainder * BigInteger.TWO).compareTo(divisor)
                if (comparison > 0 ||
                    (comparison == 0 && whole.remainder(BigInteger.TWO) != BigInteger.ZERO)
                ) {
                    whole +
                        BigInteger.ONE
                } else {
                    whole
                }
            }
        }
        val minor =
            rounded.toString().toLongOrNull()
                ?: return MoneyResult.Failure(MoneyError.AMOUNT_OVERFLOW)
        return MoneyResult.Success(Money.ofMinor(minor, currency))
    }
    override fun toString() = "ExactSpendingRate"
}
class DailySpending internal constructor(val date: LocalDate, val amount: Money) {
    override fun toString() = "DailySpending"
}
class CategorySpending internal constructor(
    val id: LedgerId,
    val name: String,
    val archivedAt: Instant?,
    val amount: Money
) {
    override fun toString() = "CategorySpending"
}
class SpendingSnapshot internal constructor(
    val reference: Instant,
    val completed: CompletedDays,
    val total: Money,
    daily: List<DailySpending>,
    categories: List<CategorySpending>,
    val currentMonth: BudgetMonth,
    val currentMonthActual: Money
) {
    val daily = daily.toList()
    val categories = categories.toList()
    val dailyAverage: ExactSpendingRate get() = ExactSpendingRate(
        total.amountMinor.toString(),
        30,
        total.currency
    )
    val weeklyEquivalent: ExactSpendingRate get() = ExactSpendingRate(
        (
            BigInteger.fromLong(total.amountMinor) *
                BigInteger.fromInt(7)
            ).toString(),
        30,
        total.currency
    )
    val normalized30Day: Money get() = total
    override fun toString() = "SpendingSnapshot"
}
interface AnalyticsRepository {
    suspend fun snapshot(reference: Instant): LedgerResult<SpendingSnapshot>
}
