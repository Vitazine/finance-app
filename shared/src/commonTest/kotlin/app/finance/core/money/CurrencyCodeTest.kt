package app.finance.core.money

import kotlin.test.Test
import kotlin.test.assertEquals

class CurrencyCodeTest {
    @Test
    fun isoExponentsDriveExactMinorUnits() {
        listOf("JPY" to 1L, "USD" to 100L, "RUB" to 100L, "KWD" to 1000L, "CLF" to 10000L)
            .forEach { (code, expected) ->
                assertEquals(expected, amount("1", code).amountMinor, code)
            }
    }

    @Test
    fun unknownMalformedAndUndefinedCurrenciesFailWithoutDefaultScale() {
        listOf("usd", "US", "USDD", " USD", "ＵＳＤ").forEach {
            assertEquals(MoneyError.INVALID_CURRENCY_CODE, CurrencyCode.resolve(it).failureCode())
        }
        assertEquals(MoneyError.UNKNOWN_CURRENCY, CurrencyCode.resolve("ZZZ").failureCode())
        listOf("XAU", "XTS", "XXX").forEach {
            assertEquals(MoneyError.UNDEFINED_MINOR_UNIT, CurrencyCode.resolve(it).failureCode())
        }
    }

    @Test
    fun fullRegistryMatchesFrozenIsoSourceIncludingUndefinedAndUnknownCodes() {
        assertEquals(178, expectedIsoMinorUnits.size)
        for (first in 'A'..'Z') {
            for (second in 'A'..'Z') {
                for (third in 'A'..'Z') {
                    val code = "$first$second$third"
                    val expected = expectedIsoMinorUnits[code]
                    val actual = CurrencyCode.resolve(code)
                    when (expected) {
                        null -> assertEquals(
                            MoneyError.UNKNOWN_CURRENCY,
                            actual.failureCode(),
                            code
                        )
                        "N.A." -> assertEquals(
                            MoneyError.UNDEFINED_MINOR_UNIT,
                            actual.failureCode(),
                            code
                        )
                        else -> assertEquals(expected.toInt(), actual.successValue().exponent, code)
                    }
                }
            }
        }
    }

    @Test
    fun independentlyResolvedSameCurrencyCanBeAdded() {
        val left = Money.ofMinor(123, currency("USD"))
        val right = Money.ofMinor(456, currency("USD"))

        assertEquals(579, left.add(right).successValue().amountMinor)
    }
}
