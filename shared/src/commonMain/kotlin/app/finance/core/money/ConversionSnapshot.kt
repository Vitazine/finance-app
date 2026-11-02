package app.finance.core.money

import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlinx.datetime.Instant

enum class RoundingPolicy { HALF_EVEN }

class ConversionSnapshot private constructor(
    val sourceAmount: Money,
    val baseAmount: Money,
    val rateDecimal: String,
    val effectiveAt: Instant,
    val sourceId: String,
    val roundingPolicy: RoundingPolicy
) {
    val sourceCurrency: CurrencyCode get() = sourceAmount.currency
    val baseCurrency: CurrencyCode get() = baseAmount.currency
    val convertedMinor: Long get() = baseAmount.amountMinor

    override fun toString(): String = "ConversionSnapshot"

    companion object {
        fun identity(source: Money, effectiveAt: Instant): MoneyResult<ConversionSnapshot> {
            if (source.amountMinor <= 0) return MoneyResult.Failure(MoneyError.NON_POSITIVE_AMOUNT)
            return MoneyResult.Success(
                ConversionSnapshot(
                    source,
                    source,
                    "1",
                    effectiveAt,
                    "identity",
                    RoundingPolicy.HALF_EVEN
                )
            )
        }

        /** 1 source major unit = quote.rate base major units. Rounds only the final minor units. */
        fun convert(
            source: Money,
            baseCurrency: CurrencyCode,
            quote: ExchangeRateQuote?
        ): MoneyResult<ConversionSnapshot> {
            if (source.amountMinor <= 0) return MoneyResult.Failure(MoneyError.NON_POSITIVE_AMOUNT)
            if (source.currency == baseCurrency) {
                return MoneyResult.Failure(MoneyError.INVALID_RATE_PAIR)
            }
            if (quote == null) return MoneyResult.Failure(MoneyError.MISSING_RATE)
            if (quote.sourceCurrency != source.currency || quote.baseCurrency != baseCurrency) {
                return MoneyResult.Failure(MoneyError.INVALID_RATE_PAIR)
            }

            val numerator = BigInteger.fromLong(source.amountMinor) * quote.rate.coefficient *
                BigInteger.TEN.pow(baseCurrency.exponent)
            val denominator = BigInteger.TEN.pow(source.currency.exponent + quote.rate.scale)
            val (whole, remainder) = numerator.divideAndRemainder(denominator)
            val comparison = (remainder * BigInteger.TWO).compareTo(denominator)
            val odd = whole.remainder(BigInteger.TWO) != BigInteger.ZERO
            val roundUp = comparison > 0 || (comparison == 0 && odd)
            val rounded = if (roundUp) whole + BigInteger.ONE else whole
            val converted = rounded.toString().toLongOrNull()
                ?: return MoneyResult.Failure(MoneyError.AMOUNT_OVERFLOW)
            return MoneyResult.Success(
                ConversionSnapshot(
                    source,
                    Money.ofMinor(converted, baseCurrency),
                    quote.rate.canonicalText,
                    quote.effectiveAt,
                    quote.sourceId,
                    RoundingPolicy.HALF_EVEN
                )
            )
        }
    }
}
