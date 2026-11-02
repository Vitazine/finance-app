package app.finance.core.money

import kotlin.test.assertIs
import kotlin.test.fail
import kotlinx.datetime.Instant

internal fun <T> MoneyResult<T>.successValue(): T = when (this) {
    is MoneyResult.Success -> value
    is MoneyResult.Failure -> fail("Synthetic fixture rejected: $error")
}

internal fun MoneyResult<*>.failureCode(): MoneyError = assertIs<MoneyResult.Failure>(this).error

internal fun currency(code: String): CurrencyCode = CurrencyCode.resolve(code).successValue()

internal fun amount(text: String, code: String = "USD"): Money =
    Money.parsePositive(text, currency(code)).successValue()

internal val fixtureTime: Instant = Instant.parse("2026-11-01T00:00:00Z")

internal fun quote(source: String, base: String, rate: String): ExchangeRateQuote =
    ExchangeRateQuote.create(
        currency(source),
        currency(base),
        DecimalRate.parse(rate).successValue(),
        fixtureTime,
        "synthetic:fixture"
    ).successValue()
