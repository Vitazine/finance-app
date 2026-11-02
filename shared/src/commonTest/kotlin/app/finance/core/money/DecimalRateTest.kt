package app.finance.core.money

import kotlin.test.Test
import kotlin.test.assertEquals

class DecimalRateTest {
    @Test
    fun retainsExactDigitsAndCanonicalizesZeroes() {
        assertEquals("1.23", DecimalRate.parse("0001.23000").successValue().canonicalText)
        assertEquals(
            "0.00000000000000000001",
            DecimalRate.parse("0.00000000000000000001").successValue().canonicalText
        )
        assertEquals("1", DecimalRate.parse("1.000").successValue().canonicalText)
    }

    @Test
    fun rejectsZeroInvalidSyntaxAndOversizedRates() {
        assertEquals(MoneyError.NON_POSITIVE_RATE, DecimalRate.parse("0.000").failureCode())
        listOf("-1", "+1", "1e-3", "1,2", ".5", " 1", "1.", "NaN").forEach {
            assertEquals(MoneyError.INVALID_DECIMAL, DecimalRate.parse(it).failureCode())
        }
        assertEquals(MoneyError.INPUT_TOO_LONG, DecimalRate.parse("1".repeat(129)).failureCode())
    }

    @Test
    fun boundedHighPrecisionRateCanStillBeAppliedExactly() {
        val tiny = "0." + "0".repeat(125) + "1"
        val rate = quote("JPY", "USD", tiny)
        assertEquals(tiny, rate.rate.canonicalText)
        assertEquals(
            0,
            ConversionSnapshot.convert(
                amount("1", "JPY"),
                currency("USD"),
                rate
            ).successValue().convertedMinor
        )
    }
}
