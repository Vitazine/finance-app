package app.finance.domain.analytics

import app.finance.core.money.CurrencyCode
import app.finance.core.money.MoneyError
import app.finance.core.money.MoneyResult
import app.finance.core.money.RoundingPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateDisplayTest {
    private fun rate(n: String, code: String = "USD") =
        ExactSpendingRate(n, 30, (CurrencyCode.resolve(code) as MoneyResult.Success).value)
    private fun minor(n: String) = (
        rate(
            n
        ).roundedForDisplay(RoundingPolicy.HALF_EVEN) as MoneyResult.Success
        ).value.amountMinor

    @Test fun minorUnitTiesUseExplicitHalfEven() {
        for ((n, expected) in listOf(
            "15" to 0L,
            "45" to 2L,
            "75" to 2L,
            "105" to 4L
        )) {
            assertEquals(expected, minor(n))
        }
    }

    @Test fun fractionalMinorIsMarkedApproximateWithoutChangingExactRate() {
        val value = rate("1")
        assertFalse(value.isExactMinor)
        assertEquals(
            0L,
            (
                value.roundedForDisplay(
                    RoundingPolicy.HALF_EVEN
                ) as MoneyResult.Success
                ).value.amountMinor
        )
        assertEquals("1", value.numeratorMinor)
        assertEquals(30, value.denominator)
    }

    @Test fun exactAndZeroRatesAreNotApproximate() {
        for (n in listOf("0", "7800")) assertTrue(rate(n).isExactMinor)
        assertEquals(260L, minor("7800"))
        assertEquals(0L, minor("0"))
    }

    @Test fun largeWeeklyNumeratorAndCurrencyExponentStayExact() {
        assertEquals(2152120141932781022L, minor("64563604257983430649"))
        val jpy = (
            rate(
                "75",
                "JPY"
            ).roundedForDisplay(RoundingPolicy.HALF_EVEN) as MoneyResult.Success
            ).value
        val kwd = (
            rate(
                "75",
                "KWD"
            ).roundedForDisplay(RoundingPolicy.HALF_EVEN) as MoneyResult.Success
            ).value
        assertEquals("2", jpy.formatDecimal())
        assertEquals("0.002", kwd.formatDecimal())
    }

    @Test fun displayOverflowNeverWraps() {
        assertEquals(
            MoneyResult.Failure(MoneyError.AMOUNT_OVERFLOW),
            rate("276701161105643274240").roundedForDisplay(RoundingPolicy.HALF_EVEN)
        )
    }
}
