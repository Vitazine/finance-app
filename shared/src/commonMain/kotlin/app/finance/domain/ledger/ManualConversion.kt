package app.finance.domain.ledger

import app.finance.core.money.ConversionSnapshot
import app.finance.core.money.CurrencyCode
import app.finance.core.money.DecimalRate
import app.finance.core.money.ExchangeRateQuote
import app.finance.core.money.Money
import app.finance.core.money.MoneyError
import app.finance.core.money.MoneyResult
import kotlinx.datetime.Instant

fun manualConversion(
    source: Money,
    base: CurrencyCode,
    rate: DecimalRate?,
    occurredAt: Instant,
    previous: ConversionSnapshot? = null,
    selectedQuote: ExchangeRateQuote? = null
): MoneyResult<ConversionSnapshot> {
    if (selectedQuote != null) {
        if (rate != selectedQuote.rate || selectedQuote.effectiveAt > occurredAt) {
            return MoneyResult.Failure(MoneyError.INVALID_RATE_PAIR)
        }
        return ConversionSnapshot.convert(source, base, selectedQuote)
    }
    val samePair =
        previous != null &&
            previous.sourceCurrency == source.currency &&
            previous.baseCurrency == base
    if (source.currency == base) {
        if (samePair && previous!!.sourceAmount == source) return MoneyResult.Success(previous)
        return ConversionSnapshot.identity(
            source,
            if (samePair) previous!!.effectiveAt else occurredAt
        )
    }
    if (rate == null) return MoneyResult.Failure(MoneyError.MISSING_RATE)
    val sameQuote = samePair && previous!!.rateDecimal == rate.canonicalText
    if (sameQuote && previous!!.sourceAmount == source) return MoneyResult.Success(previous)
    val quote = when (
        val result = manualQuote(source.currency, base, rate, occurredAt, previous)
    ) {
        is MoneyResult.Success -> result.value
        is MoneyResult.Failure -> return result
    }
    return ConversionSnapshot.convert(source, base, quote)
}

fun manualQuote(
    source: CurrencyCode,
    base: CurrencyCode,
    rate: DecimalRate,
    occurredAt: Instant,
    previous: ConversionSnapshot? = null
): MoneyResult<ExchangeRateQuote> {
    val sameQuote = previous != null &&
        previous.sourceCurrency == source &&
        previous.baseCurrency == base &&
        previous.rateDecimal == rate.canonicalText
    return ExchangeRateQuote.create(
        source,
        base,
        rate,
        if (sameQuote) previous!!.effectiveAt else occurredAt,
        if (sameQuote) previous!!.sourceId else "manual"
    )
}
