package app.finance.core.money

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class FxBigDecimalOracleTest {
    @Test
    fun seededConversionsMatchIndependentJvmDecimalOracle() {
        val random = Random(9009)
        val codes = listOf("JPY", "USD", "KWD", "CLF")
        repeat(2000) { index ->
            val sourceCode = codes[index % 4]
            val targetCode = codes[(index % 4 + 1 + index / 4 % 3) % 4]
            val maximum = if (index / 12 % 2 == 0) 1000000000000L else Long.MAX_VALUE
            val source = Money.ofMinor(random.nextLong(1, maximum), currency(sourceCode))
            val rate = "${random.nextInt(
                0,
                10000
            )}.${random.nextInt(1, 1000000).toString().padStart(6, '0')}"
            val fx = quote(sourceCode, targetCode, rate)
            val oracle = BigDecimal.valueOf(source.amountMinor, source.currency.exponent)
                .multiply(BigDecimal(rate))
                .setScale(currency(targetCode).exponent, RoundingMode.HALF_EVEN)
                .unscaledValue()
            val expected = oracle.toString().toLongOrNull()
            val actual = ConversionSnapshot.convert(source, currency(targetCode), fx)
            if (expected == null) {
                assertEquals(
                    MoneyError.AMOUNT_OVERFLOW,
                    actual.failureCode(),
                    "seed=9009 case=$index"
                )
            } else {
                assertEquals(
                    expected,
                    actual.successValue().convertedMinor,
                    "seed=9009 case=$index"
                )
            }
        }
    }

    @Test
    fun signedArithmeticMatchesCheckedJvmLongOperations() {
        val random = Random(9010)
        val usd = currency("USD")
        repeat(2000) {
            val a = random.nextLong()
            val b = random.nextLong()
            val left = Money.ofMinor(a, usd)
            val right = Money.ofMinor(b, usd)
            val expectedAdd = try {
                Math.addExact(a, b)
            } catch (_: ArithmeticException) {
                null
            }
            val expectedSubtract = try {
                Math.subtractExact(a, b)
            } catch (
                _: ArithmeticException
            ) {
                null
            }
            listOf(
                expectedAdd to left.add(right),
                expectedSubtract to left.subtract(right)
            ).forEach { (expected, actual) ->
                if (expected == null) {
                    assertEquals(MoneyError.AMOUNT_OVERFLOW, actual.failureCode())
                } else {
                    assertEquals(expected, actual.successValue().amountMinor)
                }
            }
        }
    }

    @Test
    fun signedFormattingMatchesJvmExactDecimal() {
        val random = Random(9011)
        val currencies = listOf("JPY", "USD", "KWD", "CLF").map(::currency)
        repeat(1000) { index ->
            val minor = random.nextLong()
            val code = currencies[index % 4]
            assertEquals(
                BigDecimal.valueOf(minor, code.exponent).toPlainString(),
                Money.ofMinor(minor, code).formatDecimal()
            )
        }
    }
}
