package app.finance.android.ledger

import app.finance.core.money.ConversionSnapshot
import app.finance.core.money.CurrencyCode
import app.finance.core.money.DecimalRate
import app.finance.core.money.MoneyResult
import app.finance.domain.exchange.CachedRate
import app.finance.domain.exchange.CurrencyPair
import app.finance.domain.ledger.LedgerSettings
import app.finance.domain.ledger.manualQuote
import kotlinx.datetime.Instant

internal class RateRequest(val pair: CurrencyPair, val notAfter: Instant) {
    override fun toString() = "RateRequest"
}
internal fun rateRequest(fields: EntryFields, settings: LedgerSettings): InputResult<RateRequest> {
    if (fields.currency.length > 8) return InputResult.Failure(InputIssue.INVALID_CURRENCY)
    val currency =
        (CurrencyCode.resolve(fields.currency.trim().uppercase()) as? MoneyResult.Success)?.value
            ?: return InputResult.Failure(InputIssue.INVALID_CURRENCY)
    val pair =
        (CurrencyPair.create(currency, settings.primaryCurrency) as? MoneyResult.Success)?.value
            ?: return InputResult.Failure(InputIssue.INVALID_RATE)
    return when (val time = parseOccurrence(fields.localTime, fields.zoneId, fields.offset)) {
        is InputResult.Failure -> time
        is InputResult.Success -> InputResult.Success(RateRequest(pair, time.value))
    }
}
internal fun manualRateInput(
    fields: EntryFields,
    settings: LedgerSettings,
    now: Instant,
    previous: ConversionSnapshot? = null
): InputResult<CachedRate> {
    if (fields.selectedQuote != null) return InputResult.Failure(InputIssue.INVALID_RATE)
    val request = when (val parsed = rateRequest(fields, settings)) {
        is InputResult.Success -> parsed.value
        is InputResult.Failure -> return parsed
    }
    if (fields.rate.length > 128) return InputResult.Failure(InputIssue.INVALID_RATE)
    if (fields.rate.isBlank()) return InputResult.Failure(InputIssue.MISSING_RATE)
    val rate =
        (DecimalRate.parse(fields.rate.trim().replace(',', '.')) as? MoneyResult.Success)?.value
            ?: return InputResult.Failure(InputIssue.INVALID_RATE)
    return when (
        val quote =
            manualQuote(request.pair.source, request.pair.base, rate, request.notAfter, previous)
    ) {
        is MoneyResult.Failure -> InputResult.Failure(InputIssue.INVALID_RATE)
        is MoneyResult.Success -> InputResult.Success(CachedRate(quote.value, now))
    }
}
