package app.finance.domain.ledger

import app.finance.core.money.ConversionSnapshot
import app.finance.core.money.CurrencyCode
import app.finance.core.money.DecimalRate
import app.finance.core.money.ExchangeRateQuote
import app.finance.core.money.Money
import app.finance.core.money.MoneyError
import app.finance.core.money.MoneyResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.datetime.Instant

class ManualConversionTest {
    private fun <T> MoneyResult<T>.value() = (this as MoneyResult.Success).value
    private val at = Instant.parse("2026-11-01T00:00:00Z")
    private val later = Instant.parse("2026-11-02T00:00:00Z")
    private val kwd = CurrencyCode.resolve("KWD").value()
    private val usd = CurrencyCode.resolve("USD").value()
    private val rate = DecimalRate.parse("1.004").value()
    private val source = Money.ofMinor(1245, kwd)
    private fun previous() = ConversionSnapshot.convert(
        source,
        usd,
        ExchangeRateQuote.create(kwd, usd, rate, at, "synthetic:cached").value()
    ).value()

    @Test
    fun unchangedMoneyKeepsAppliedQuoteEvenWhenOccurrenceChanges() {
        val saved = manualConversion(source, usd, rate, later, previous()).value()
        assertEquals(125L, saved.convertedMinor)
        assertEquals(at, saved.effectiveAt)
        assertEquals("synthetic:cached", saved.sourceId)
    }

    @Test
    fun amountOnlyEditUsesSameQuoteAndNewRateGetsExplicitManualMetadata() {
        val amountChanged = manualConversion(
            Money.ofMinor(1255, kwd),
            usd,
            rate,
            later,
            previous()
        ).value()
        assertEquals(126L, amountChanged.convertedMinor)
        assertEquals(at, amountChanged.effectiveAt)
        assertEquals("synthetic:cached", amountChanged.sourceId)
        val rateChanged = manualConversion(
            source,
            usd,
            DecimalRate.parse("1.01").value(),
            later,
            previous()
        ).value()
        assertEquals(126L, rateChanged.convertedMinor)
        assertEquals(later, rateChanged.effectiveAt)
        assertEquals("manual", rateChanged.sourceId)
    }

    @Test
    fun missingForeignRateIsRejectedAndIdentityNeedsNoQuote() {
        assertEquals(
            MoneyError.MISSING_RATE,
            (manualConversion(source, usd, null, at) as MoneyResult.Failure).error
        )
        assertEquals(
            1L,
            manualConversion(Money.ofMinor(1, usd), usd, null, at).value().convertedMinor
        )
    }

    @Test
    fun explicitCachedChoiceOverridesEqualRateHistoricalMetadata() {
        val selected = ExchangeRateQuote.create(kwd, usd, rate, later, "synthetic:chosen").value()
        val saved = manualConversion(source, usd, rate, later, previous(), selected).value()
        assertEquals(125L, saved.convertedMinor)
        assertEquals(later, saved.effectiveAt)
        assertEquals("synthetic:chosen", saved.sourceId)
    }

    @Test
    fun selectedQuoteMustMatchPairNumericFieldAndOccurrenceCutoff() {
        val selected = ExchangeRateQuote.create(kwd, usd, rate, later, "synthetic:chosen").value()
        assertIs<MoneyResult.Failure>(
            manualConversion(source, usd, rate, at, selectedQuote = selected)
        )
        assertIs<MoneyResult.Failure>(
            manualConversion(
                source,
                usd,
                DecimalRate.parse("2").value(),
                later,
                selectedQuote = selected
            )
        )
        val reverse = ExchangeRateQuote.create(usd, kwd, rate, at, "synthetic:reverse").value()
        assertIs<MoneyResult.Failure>(
            manualConversion(source, usd, rate, later, selectedQuote = reverse)
        )
    }

    @Test
    fun rememberingManualQuotePreservesPriorProvenanceUnlessRateChanges() {
        val kept = manualQuote(kwd, usd, rate, later, previous()).value()
        assertEquals(at, kept.effectiveAt)
        assertEquals("synthetic:cached", kept.sourceId)
        val changed = manualQuote(
            kwd,
            usd,
            DecimalRate.parse("2").value(),
            later,
            previous()
        ).value()
        assertEquals(later, changed.effectiveAt)
        assertEquals("manual", changed.sourceId)
    }
}
