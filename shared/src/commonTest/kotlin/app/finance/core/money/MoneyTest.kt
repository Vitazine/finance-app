package app.finance.core.money

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MoneyTest {
    @Test
    fun parsesPositiveAmountsExactlyAndCanonicalizesOnlyZeroes() {
        listOf("1" to 100L, "001.20" to 120L, "0.01" to 1L, "1.2300" to 123L)
            .forEach { (text, expected) -> assertEquals(expected, amount(text).amountMinor) }
        assertEquals(1, amount("1.000", "JPY").amountMinor)
        assertEquals(1234, amount("1.234", "KWD").amountMinor)
        assertEquals(1, amount("0.0001", "CLF").amountMinor)
    }

    @Test
    fun doesNotRoundUnrepresentableInput() {
        listOf("USD" to "1.001", "JPY" to "1.1", "KWD" to "0.0001", "CLF" to "0.00001")
            .forEach { (code, text) ->
                assertEquals(
                    MoneyError.EXCESS_PRECISION,
                    Money.parsePositive(text, currency(code)).failureCode()
                )
            }
    }

    @Test
    fun rejectsNonPositiveAndNonCanonicalDecimalSyntax() {
        listOf("0", "0.00", "000.000").forEach {
            assertEquals(
                MoneyError.NON_POSITIVE_AMOUNT,
                Money.parsePositive(it, currency("USD")).failureCode()
            )
        }
        listOf("", "-1", "+1", " 1", "1 ", ".1", "1.", "1,00", "1e2", "NaN", "١٢", "1_000")
            .forEach {
                assertEquals(
                    MoneyError.INVALID_DECIMAL,
                    Money.parsePositive(it, currency("USD")).failureCode()
                )
            }
    }

    @Test
    fun checksLongAndInputLengthBoundaries() {
        assertEquals(Long.MAX_VALUE, amount("92233720368547758.07").amountMinor)
        assertEquals(Long.MAX_VALUE, amount("9223372036854775807", "JPY").amountMinor)
        assertEquals(Long.MAX_VALUE, amount("9223372036854775.807", "KWD").amountMinor)
        listOf(
            "USD" to "92233720368547758.08",
            "JPY" to "9223372036854775808"
        ).forEach { (code, text) ->
            assertEquals(
                MoneyError.AMOUNT_OVERFLOW,
                Money.parsePositive(text, currency(code)).failureCode()
            )
        }
        assertEquals(100, amount("0".repeat(63) + "1").amountMinor)
        assertEquals(
            MoneyError.INPUT_TOO_LONG,
            Money.parsePositive("0".repeat(64) + "1", currency("USD")).failureCode()
        )
    }

    @Test
    fun formatsSignedTotalsIncludingLongMinWithoutAbsOverflow() {
        listOf(
            Triple(0L, "USD", "0.00"),
            Triple(-1L, "USD", "-0.01"),
            Triple(Long.MIN_VALUE, "USD", "-92233720368547758.08"),
            Triple(Long.MIN_VALUE, "JPY", "-9223372036854775808"),
            Triple(Long.MAX_VALUE, "CLF", "922337203685477.5807")
        ).forEach { (minor, code, expected) ->
            assertEquals(expected, Money.ofMinor(minor, currency(code)).formatDecimal())
        }
    }

    @Test
    fun arithmeticPreservesCurrencyAndDetectsOverflow() {
        val usd = currency("USD")
        assertEquals(-75, amount("1.00").subtract(amount("1.75")).successValue().amountMinor)
        assertEquals(
            Long.MAX_VALUE,
            Money.ofMinor(
                -1,
                usd
            ).subtract(Money.ofMinor(Long.MIN_VALUE, usd)).successValue().amountMinor
        )
        assertEquals(
            Long.MIN_VALUE,
            Money.ofMinor(
                Long.MIN_VALUE,
                usd
            ).subtract(Money.ofMinor(0, usd)).successValue().amountMinor
        )
        listOf(
            Money.ofMinor(Long.MAX_VALUE, usd).add(Money.ofMinor(1, usd)),
            Money.ofMinor(Long.MIN_VALUE, usd).add(Money.ofMinor(-1, usd)),
            Money.ofMinor(0, usd).subtract(Money.ofMinor(Long.MIN_VALUE, usd)),
            Money.ofMinor(Long.MIN_VALUE, usd).subtract(Money.ofMinor(1, usd))
        ).forEach { assertEquals(MoneyError.AMOUNT_OVERFLOW, it.failureCode()) }
        assertEquals(
            MoneyError.CURRENCY_MISMATCH,
            amount("1").add(amount("1", "JPY")).failureCode()
        )
        assertEquals(
            MoneyError.CURRENCY_MISMATCH,
            amount("1").subtract(amount("1", "KWD")).failureCode()
        )
    }

    @Test
    fun seededPositiveRoundTripPreservesAllMinorUnits() {
        val random = Random(9009)
        val currencies = listOf("JPY", "USD", "KWD", "CLF").map(::currency)
        repeat(5000) { index ->
            val original = Money.ofMinor(random.nextLong(1, Long.MAX_VALUE), currencies[index % 4])
            val restored = Money.parsePositive(
                original.formatDecimal(),
                original.currency
            ).successValue()
            assertEquals(original.amountMinor, restored.amountMinor)
            assertEquals(original.currency, restored.currency)
        }
    }

    @Test
    fun failuresDoNotRetainUntrustedInputText() {
        val input = "synthetic-private-input"
        val failure = Money.parsePositive(input, currency("USD"))
        assertEquals(MoneyError.INVALID_DECIMAL, failure.failureCode())
        assertFalse(failure.toString().contains(input))
    }
}
