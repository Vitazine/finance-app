package app.finance.core.money

class CurrencyCode private constructor(val code: String, val exponent: Int) {
    override fun equals(other: Any?): Boolean =
        other is CurrencyCode && code == other.code && exponent == other.exponent

    override fun hashCode(): Int = 31 * code.hashCode() + exponent

    override fun toString(): String = code

    companion object {
        fun resolve(code: String): MoneyResult<CurrencyCode> {
            if (code.length != 3 || code.any { it !in 'A'..'Z' }) {
                return MoneyResult.Failure(MoneyError.INVALID_CURRENCY_CODE)
            }
            if (code in Iso4217MinorUnits.undefined) {
                return MoneyResult.Failure(MoneyError.UNDEFINED_MINOR_UNIT)
            }
            val exponent = Iso4217MinorUnits.byCode[code]
                ?: return MoneyResult.Failure(MoneyError.UNKNOWN_CURRENCY)
            return MoneyResult.Success(CurrencyCode(code, exponent))
        }
    }
}
