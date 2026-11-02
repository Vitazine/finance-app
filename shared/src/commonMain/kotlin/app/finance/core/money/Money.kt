package app.finance.core.money

class Money private constructor(val amountMinor: Long, val currency: CurrencyCode) {
    fun formatDecimal(): String {
        val digits = amountMinor.toString().removePrefix("-")
            .padStart(currency.exponent + 1, '0')
        val unsigned = if (currency.exponent == 0) {
            digits
        } else {
            val point = digits.length - currency.exponent
            digits.substring(0, point) + "." + digits.substring(point)
        }
        return if (amountMinor < 0) "-$unsigned" else unsigned
    }

    fun add(other: Money): MoneyResult<Money> {
        if (currency != other.currency) return MoneyResult.Failure(MoneyError.CURRENCY_MISMATCH)
        val a = amountMinor
        val b = other.amountMinor
        if ((b > 0 && a > Long.MAX_VALUE - b) || (b < 0 && a < Long.MIN_VALUE - b)) {
            return MoneyResult.Failure(MoneyError.AMOUNT_OVERFLOW)
        }
        return MoneyResult.Success(Money(a + b, currency))
    }

    fun subtract(other: Money): MoneyResult<Money> {
        if (currency != other.currency) return MoneyResult.Failure(MoneyError.CURRENCY_MISMATCH)
        val a = amountMinor
        val b = other.amountMinor
        if ((b > 0 && a < Long.MIN_VALUE + b) || (b < 0 && a > Long.MAX_VALUE + b)) {
            return MoneyResult.Failure(MoneyError.AMOUNT_OVERFLOW)
        }
        return MoneyResult.Success(Money(a - b, currency))
    }

    override fun equals(other: Any?): Boolean =
        other is Money && amountMinor == other.amountMinor && currency == other.currency

    override fun hashCode(): Int = 31 * amountMinor.hashCode() + currency.hashCode()

    override fun toString(): String = "Money"

    companion object {
        /** Signed values support totals; transaction input must use parsePositive/domain validation. */
        fun ofMinor(amountMinor: Long, currency: CurrencyCode): Money = Money(amountMinor, currency)

        /** Exact canonical protocol input, not localized UI input; never rounds an entered amount. */
        fun parsePositive(text: String, currency: CurrencyCode): MoneyResult<Money> {
            if (text.length > 64) return MoneyResult.Failure(MoneyError.INPUT_TOO_LONG)
            if (!isUnsignedDecimal(text)) return MoneyResult.Failure(MoneyError.INVALID_DECIMAL)
            val integer = text.substringBefore('.')
            val fraction = text.substringAfter('.', "")
            if (fraction.drop(currency.exponent).any { it != '0' }) {
                return MoneyResult.Failure(MoneyError.EXCESS_PRECISION)
            }
            val minorDigits = (
                integer +
                    fraction.take(currency.exponent).padEnd(currency.exponent, '0')
                )
                .trimStart('0').ifEmpty { "0" }
            val minor = minorDigits.toLongOrNull()
                ?: return MoneyResult.Failure(MoneyError.AMOUNT_OVERFLOW)
            if (minor == 0L) return MoneyResult.Failure(MoneyError.NON_POSITIVE_AMOUNT)
            return MoneyResult.Success(Money(minor, currency))
        }
    }
}
