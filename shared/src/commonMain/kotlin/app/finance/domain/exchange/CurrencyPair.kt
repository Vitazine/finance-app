package app.finance.domain.exchange

import app.finance.core.money.CurrencyCode
import app.finance.core.money.MoneyError
import app.finance.core.money.MoneyResult

class CurrencyPair private constructor(val source: CurrencyCode, val base: CurrencyCode) {
    override fun toString(): String = "CurrencyPair"
    companion object {
        fun create(source: CurrencyCode, base: CurrencyCode): MoneyResult<CurrencyPair> =
            if (source == base) {
                MoneyResult.Failure(MoneyError.INVALID_RATE_PAIR)
            } else {
                MoneyResult.Success(CurrencyPair(source, base))
            }
    }
}
