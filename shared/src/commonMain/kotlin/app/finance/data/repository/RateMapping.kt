package app.finance.data.repository

import app.finance.core.money.CurrencyCode
import app.finance.core.money.DecimalRate
import app.finance.core.money.ExchangeRateQuote
import app.finance.core.money.MoneyResult
import app.finance.data.database.RateEntity
import app.finance.data.database.RateReadRow
import app.finance.domain.exchange.CachedRate
import kotlinx.datetime.Instant

internal fun CachedRate.toEntity() = RateEntity(
    quote.sourceCurrency.code,
    quote.baseCurrency.code,
    quote.effectiveAt.epochSeconds,
    quote.effectiveAt.nanosecondsOfSecond.toLong(),
    quote.sourceId,
    quote.rate.canonicalText,
    retrievedAt.epochSeconds,
    retrievedAt.nanosecondsOfSecond.toLong()
)

internal fun RateReadRow.toDomain(): CachedRate {
    if (storageTypes != "text:text:integer:integer:text:text:integer:integer") throw CorruptRecord()
    return rate.checkedDomain()
}

private fun RateEntity.checkedDomain(): CachedRate {
    if (effectiveAtNanos !in 0L..999999999L || retrievedAtNanos !in 0L..999999999L) {
        throw CorruptRecord()
    }
    val source =
        (CurrencyCode.resolve(sourceCurrency) as? MoneyResult.Success)?.value
            ?: throw CorruptRecord()
    val base =
        (CurrencyCode.resolve(baseCurrency) as? MoneyResult.Success)?.value ?: throw CorruptRecord()
    val rate =
        (DecimalRate.parse(rateDecimal) as? MoneyResult.Success)?.value ?: throw CorruptRecord()
    val quote = (
        ExchangeRateQuote.create(
            source,
            base,
            rate,
            Instant.fromEpochSeconds(effectiveAtSeconds, effectiveAtNanos),
            sourceId
        ) as? MoneyResult.Success
        )?.value ?: throw CorruptRecord()
    val result = CachedRate(quote, Instant.fromEpochSeconds(retrievedAtSeconds, retrievedAtNanos))
    if (result.toEntity() != this) throw CorruptRecord()
    return result
}
