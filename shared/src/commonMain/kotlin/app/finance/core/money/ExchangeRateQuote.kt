package app.finance.core.money

import kotlinx.datetime.Instant

class ExchangeRateQuote private constructor(
    val sourceCurrency: CurrencyCode,
    val baseCurrency: CurrencyCode,
    val rate: DecimalRate,
    val effectiveAt: Instant,
    val sourceId: String
) {
    override fun toString(): String = "ExchangeRateQuote"

    companion object {
        private val sourceIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")

        fun create(
            sourceCurrency: CurrencyCode,
            baseCurrency: CurrencyCode,
            rate: DecimalRate,
            effectiveAt: Instant,
            sourceId: String
        ): MoneyResult<ExchangeRateQuote> {
            if (sourceCurrency ==
                baseCurrency
            ) {
                return MoneyResult.Failure(MoneyError.INVALID_RATE_PAIR)
            }
            if (sourceId.length !in 1..128 || !sourceIdPattern.matches(sourceId)) {
                return MoneyResult.Failure(MoneyError.INVALID_SOURCE_ID)
            }
            return MoneyResult.Success(
                ExchangeRateQuote(sourceCurrency, baseCurrency, rate, effectiveAt, sourceId)
            )
        }
    }
}
