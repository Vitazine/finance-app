package app.finance.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.finance.core.money.ConversionSnapshot
import app.finance.core.money.CurrencyCode
import app.finance.core.money.DecimalRate
import app.finance.core.money.ExchangeRateQuote
import app.finance.core.money.Money
import app.finance.core.money.MoneyError
import app.finance.core.money.MoneyResult
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoneyRuntimeSmokeTest {
    private fun <T> MoneyResult<T>.value(): T = when (this) {
        is MoneyResult.Success -> value
        is MoneyResult.Failure -> error("Synthetic Android fixture rejected: $error")
    }

    private fun currency(code: String) = CurrencyCode.resolve(code).value()

    private fun quote(source: CurrencyCode, base: CurrencyCode, text: String) =
        ExchangeRateQuote.create(
            source,
            base,
            DecimalRate.parse(text).value(),
            Instant.parse("2026-11-01T00:00:00Z"),
            "synthetic:android"
        ).value()

    @Test
    fun exactRoundingAndLargeIntermediateWorkOnAndroidRuntime() {
        val kwd = currency("KWD")
        val usd = currency("USD")
        val jpy = currency("JPY")
        val source = Money.parsePositive("1.245", kwd).value()
        assertEquals(
            125L,
            ConversionSnapshot.convert(source, usd, quote(kwd, usd, "1.004")).value().convertedMinor
        )
        val maximum = Money.ofMinor(Long.MAX_VALUE, jpy)
        assertEquals(
            Long.MAX_VALUE,
            ConversionSnapshot.convert(maximum, usd, quote(jpy, usd, "0.01")).value().convertedMinor
        )
    }

    @Test
    fun seededAndroidFxMatchesPlatformBigDecimal() {
        val random = Random(9009)
        val currencies = listOf("JPY", "USD", "KWD", "CLF").map(::currency)
        var successes = 0
        var overflows = 0
        repeat(250) { index ->
            val sourceCurrency = currencies[index % 4]
            val baseCurrency = currencies[(index % 4 + 1 + index / 4 % 3) % 4]
            val source = Money.ofMinor(random.nextLong(1, Long.MAX_VALUE), sourceCurrency)
            val rate = "${random.nextInt(
                0,
                10000
            )}.${random.nextInt(1, 1000000).toString().padStart(6, '0')}"
            val expected = BigDecimal.valueOf(source.amountMinor, sourceCurrency.exponent)
                .multiply(BigDecimal(rate)).setScale(baseCurrency.exponent, RoundingMode.HALF_EVEN)
                .unscaledValue().toString().toLongOrNull()
            val actual = ConversionSnapshot.convert(
                source,
                baseCurrency,
                quote(sourceCurrency, baseCurrency, rate)
            )
            if (expected == null) {
                assertTrue(
                    actual is MoneyResult.Failure && actual.error == MoneyError.AMOUNT_OVERFLOW
                )
                overflows++
            } else {
                assertEquals(expected, actual.value().convertedMinor)
                successes++
            }
        }
        assertTrue(successes > 0 && overflows > 0)
    }
}
