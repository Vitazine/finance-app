package app.finance.core.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.Instant

class ConversionSnapshotTest {
    @Test
    fun identityNeedsNoFxAndFreezesItsMetadata() {
        val source = amount("12.34")
        val saved = ConversionSnapshot.identity(source, fixtureTime).successValue()

        assertEquals(source, saved.sourceAmount)
        assertEquals(source, saved.baseAmount)
        assertEquals("1", saved.rateDecimal)
        assertEquals("identity", saved.sourceId)
        assertEquals(fixtureTime, saved.effectiveAt)
        assertEquals(RoundingPolicy.HALF_EVEN, saved.roundingPolicy)
    }

    @Test
    fun finalHalfEvenRoundsExactTiesBothDirections() {
        listOf(
            "0.5" to 0L,
            "1.5" to 2L,
            "2.5" to 2L,
            "3.5" to 4L,
            "2.499999" to 2L,
            "2.500001" to 3L
        ).forEach { (rate, expected) ->
            val saved = ConversionSnapshot.convert(
                amount("1"),
                currency("JPY"),
                quote("USD", "JPY", rate)
            ).successValue()
            assertEquals(expected, saved.convertedMinor, rate)
        }
    }

    @Test
    fun sourceAndBaseExponentsAreBothApplied() {
        listOf(
            listOf("JPY", "USD", "1", "0.015", "2"),
            listOf("KWD", "USD", "1.005", "1", "100"),
            listOf("KWD", "USD", "1.015", "1", "102"),
            listOf("CLF", "KWD", "0.0001", "50", "5")
        ).forEach { (source, base, text, rate, expected) ->
            val saved = ConversionSnapshot.convert(
                amount(text, source),
                currency(base),
                quote(source, base, rate)
            ).successValue()
            assertEquals(expected.toLong(), saved.convertedMinor)
            assertEquals(currency(source), saved.sourceCurrency)
            assertEquals(currency(base), saved.baseCurrency)
        }
    }

    @Test
    fun neverRoundsSourceOrIntermediateProduct() {
        val saved = ConversionSnapshot.convert(
            amount("1.245", "KWD"),
            currency("USD"),
            quote("KWD", "USD", "1.004")
        ).successValue()

        assertEquals(125L, saved.convertedMinor)
        assertEquals("1.245", saved.sourceAmount.formatDecimal())
    }

    @Test
    fun intermediateBeyondLongStillProducesCorrectBoundedResult() {
        val source = Money.ofMinor(Long.MAX_VALUE, currency("JPY"))
        val exact = ConversionSnapshot.convert(
            source,
            currency("USD"),
            quote("JPY", "USD", "0.01")
        ).successValue()
        val rounded = ConversionSnapshot.convert(
            source,
            currency("USD"),
            quote("JPY", "USD", "0.001")
        ).successValue()

        assertEquals(Long.MAX_VALUE, exact.convertedMinor)
        assertEquals(922337203685477581L, rounded.convertedMinor)
    }

    @Test
    fun checksOverflowAfterRoundingWithoutDroppingRateDigits() {
        val source = Money.ofMinor(Long.MAX_VALUE, currency("JPY"))
        val overflow = ConversionSnapshot.convert(
            source,
            currency("USD"),
            quote("JPY", "USD", "0.010000000000000000001")
        )
        val fits = ConversionSnapshot.convert(
            source,
            currency("USD"),
            quote("JPY", "USD", "0.0100000000000000000001")
        )

        assertEquals(MoneyError.AMOUNT_OVERFLOW, overflow.failureCode())
        assertEquals(Long.MAX_VALUE, fits.successValue().convertedMinor)
        assertEquals(
            MoneyError.AMOUNT_OVERFLOW,
            ConversionSnapshot.convert(
                amount("1", "JPY"),
                currency("USD"),
                quote("JPY", "USD", "9".repeat(128))
            ).failureCode()
        )
    }

    @Test
    fun missingAndMismatchedQuotesNeverBecomeIdentityRate() {
        assertEquals(
            MoneyError.MISSING_RATE,
            ConversionSnapshot.convert(amount("1"), currency("JPY"), null).failureCode()
        )
        assertEquals(
            MoneyError.INVALID_RATE_PAIR,
            ConversionSnapshot.convert(
                amount("1"),
                currency("JPY"),
                quote("USD", "KWD", "1")
            ).failureCode()
        )
        assertEquals(
            MoneyError.INVALID_RATE_PAIR,
            ConversionSnapshot.convert(
                amount("1"),
                currency("JPY"),
                quote("JPY", "USD", "1")
            ).failureCode()
        )
        assertEquals(
            MoneyError.INVALID_RATE_PAIR,
            ExchangeRateQuote.create(
                currency("USD"),
                currency("USD"),
                DecimalRate.parse("1").successValue(),
                fixtureTime,
                "synthetic"
            ).failureCode()
        )
    }

    @Test
    fun transactionSnapshotRequiresPositiveSourceAmount() {
        listOf(0L, -1L, Long.MIN_VALUE).forEach { minor ->
            val source = Money.ofMinor(minor, currency("USD"))
            assertEquals(
                MoneyError.NON_POSITIVE_AMOUNT,
                ConversionSnapshot.identity(source, fixtureTime).failureCode()
            )
            assertEquals(
                MoneyError.NON_POSITIVE_AMOUNT,
                ConversionSnapshot.convert(
                    source,
                    currency("JPY"),
                    quote("USD", "JPY", "1")
                ).failureCode()
            )
        }
    }

    @Test
    fun validatesBoundedSourceIdentifier() {
        listOf("", " ", "bad\nsource", "x".repeat(129)).forEach { id ->
            assertEquals(
                MoneyError.INVALID_SOURCE_ID,
                ExchangeRateQuote.create(
                    currency("USD"),
                    currency("JPY"),
                    DecimalRate.parse("1").successValue(),
                    fixtureTime,
                    id
                ).failureCode()
            )
        }
        assertEquals(
            128,
            ExchangeRateQuote.create(
                currency("USD"),
                currency("JPY"),
                DecimalRate.parse("1").successValue(),
                fixtureTime,
                "x".repeat(128)
            ).successValue().sourceId.length
        )
    }

    @Test
    fun savedSnapshotDoesNotReadAnUpdatedQuoteCache() {
        val cache = mutableMapOf("JPY/USD" to quote("JPY", "USD", "0.015"))
        val source = amount("3", "JPY")
        val saved = ConversionSnapshot.convert(
            source,
            currency("USD"),
            cache["JPY/USD"]
        ).successValue()
        cache["JPY/USD"] = ExchangeRateQuote.create(
            currency("JPY"),
            currency("USD"),
            DecimalRate.parse("0.025").successValue(),
            Instant.parse("2026-11-02T00:00:00Z"),
            "synthetic:updated"
        ).successValue()
        val next = ConversionSnapshot.convert(
            source,
            currency("USD"),
            cache["JPY/USD"]
        ).successValue()

        assertEquals(4L, saved.convertedMinor)
        assertEquals(8L, next.convertedMinor)
        assertEquals("0.015", saved.rateDecimal)
        assertEquals(fixtureTime, saved.effectiveAt)
        assertEquals("synthetic:fixture", saved.sourceId)
    }
}
