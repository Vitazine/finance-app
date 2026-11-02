package app.finance.core.money

import com.ionspin.kotlin.bignum.integer.BigInteger

class DecimalRate private constructor(
    val canonicalText: String,
    internal val coefficient: BigInteger,
    internal val scale: Int
) {
    override fun equals(other: Any?): Boolean =
        other is DecimalRate && canonicalText == other.canonicalText
    override fun hashCode(): Int = canonicalText.hashCode()
    override fun toString(): String = "DecimalRate"

    companion object {
        fun parse(text: String): MoneyResult<DecimalRate> {
            if (text.length > 128) return MoneyResult.Failure(MoneyError.INPUT_TOO_LONG)
            if (!isUnsignedDecimal(text)) return MoneyResult.Failure(MoneyError.INVALID_DECIMAL)
            val integer = text.substringBefore('.').trimStart('0').ifEmpty { "0" }
            val fraction = text.substringAfter('.', "").trimEnd('0')
            val coefficient = BigInteger.parseString(integer + fraction, 10)
            if (coefficient ==
                BigInteger.ZERO
            ) {
                return MoneyResult.Failure(MoneyError.NON_POSITIVE_RATE)
            }
            val canonical = if (fraction.isEmpty()) integer else "$integer.$fraction"
            return MoneyResult.Success(DecimalRate(canonical, coefficient, fraction.length))
        }
    }
}
