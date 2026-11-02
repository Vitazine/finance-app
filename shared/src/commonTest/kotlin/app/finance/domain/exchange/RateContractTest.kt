package app.finance.domain.exchange

import app.finance.core.money.CurrencyCode
import app.finance.core.money.DecimalRate
import app.finance.core.money.ExchangeRateQuote
import app.finance.core.money.MoneyError
import app.finance.core.money.MoneyResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.datetime.Instant

class RateContractTest {
    private fun <T> MoneyResult<T>.value() = assertIs<MoneyResult.Success<T>>(this).value
    private val usd = CurrencyCode.resolve("USD").value()
    private val eur = CurrencyCode.resolve("EUR").value()

    @Test
    fun pairKeepsDirectionWithoutComputingInverse() {
        val forward = CurrencyPair.create(eur, usd).value()
        val reverse = CurrencyPair.create(usd, eur).value()
        assertEquals(eur, forward.source)
        assertEquals(usd, forward.base)
        assertEquals(usd, reverse.source)
        assertEquals(eur, reverse.base)
        assertEquals("CurrencyPair", forward.toString())
    }

    @Test
    fun identityDoesNotNeedExternalOrCachedQuote() {
        assertEquals(
            MoneyError.INVALID_RATE_PAIR,
            assertIs<MoneyResult.Failure>(CurrencyPair.create(usd, usd)).error
        )
    }

    @Test
    fun cacheMetadataPreservesExactQuoteWithoutDecidingFreshness() {
        val effective = Instant.parse("2026-11-01T00:00:00.123456789Z")
        val retrieved = Instant.parse("2026-11-02T00:00:00.987654321Z")
        val quote = ExchangeRateQuote.create(
            eur,
            usd,
            DecimalRate.parse("1.2345678901234567890123456789").value(),
            effective,
            "synthetic:baseline"
        ).value()
        val rate = CachedRate(quote, retrieved)
        assertEquals("1.2345678901234567890123456789", rate.quote.rate.canonicalText)
        assertEquals(effective, rate.quote.effectiveAt)
        assertEquals(retrieved, rate.retrievedAt)
        assertEquals("CachedRate", rate.toString())
    }
}
