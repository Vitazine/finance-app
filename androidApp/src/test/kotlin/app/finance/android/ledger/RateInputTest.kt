package app.finance.android.ledger

import app.finance.core.money.CurrencyCode
import app.finance.core.money.DecimalRate
import app.finance.core.money.ExchangeRateQuote
import app.finance.core.money.MoneyResult
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RateInputTest {
    private fun <T> MoneyResult<T>.value() = (this as MoneyResult.Success).value
    private fun <T> LedgerResult<T>.value() = (this as LedgerResult.Success).value
    private fun <T> InputResult<T>.value() = (this as InputResult.Success).value
    private fun InputResult<*>.issue() = (this as InputResult.Failure).issue
    private val at = Instant.parse("2026-11-01T01:02:03.123456789Z")
    private val now = Instant.parse("2026-11-02T00:00:00Z")
    private val kwd = CurrencyCode.resolve("KWD").value()
    private val usd = CurrencyCode.resolve("USD").value()
    private val settings = LedgerSettings.create(usd, "UTC").value()
    private val id = LedgerId.parse("00000000-0000-4000-8000-000000000010").value()
    private val category = LedgerId.parse("00000000-0000-4000-8000-000000000001").value()
    private fun fields() = EntryFields(
        EntryType.EXPENSE,
        "",
        "KWD",
        null,
        "1,004",
        "2026-11-01T01:02:03.123456789",
        "UTC"
    )
    private fun quote() = ExchangeRateQuote.create(
        kwd,
        usd,
        DecimalRate.parse("1.004").value(),
        at,
        "synthetic:offline"
    ).value()

    @Test fun rateCanBeRememberedWithoutAmountOrCategory() {
        val saved = manualRateInput(fields(), settings, now).value()
        assertEquals("1.004", saved.quote.rate.canonicalText)
        assertEquals("manual", saved.quote.sourceId)
        assertEquals(at, saved.quote.effectiveAt)
        assertEquals(now, saved.retrievedAt)
    }

    @Test fun sourceRateAndTimeChangesInvalidateCachedChoice() {
        val chosen = fields().copy(selectedQuote = quote())
        assertNull(chosen.withCurrency("EUR").selectedQuote)
        assertEquals("", chosen.withCurrency("EUR").rate)
        assertNull(chosen.withRate("2").selectedQuote)
        assertNull(chosen.withLocalTime("2026-11-03T00:00").selectedQuote)
        assertEquals("", chosen.withLocalTime("2026-11-03T00:00").rate)
        assertEquals(
            chosen.selectedQuote,
            chosen.copy(amount = "2", note = "Synthetic").selectedQuote
        )
        assertEquals("1,004", fields().withLocalTime("2026-11-03T00:00").rate)
    }

    @Test fun entryUsesExplicitCachedMetadataAndExactRounding() {
        val selected = fields().copy(
            amount = "1.245",
            categoryId = category,
            selectedQuote = quote()
        )
        val saved = buildEntry(selected, settings, null, id, now).value()
        assertEquals(125L, saved.conversion.convertedMinor)
        assertEquals(at, saved.conversion.effectiveAt)
        assertEquals("synthetic:offline", saved.conversion.sourceId)
    }

    @Test fun mismatchedOrFutureSelectionCannotBecomeAnEntry() {
        val valid = fields().copy(amount = "1.245", categoryId = category, selectedQuote = quote())
        assertTrue(
            buildEntry(valid.copy(rate = "2"), settings, null, id, now) is InputResult.Failure
        )
        assertTrue(
            buildEntry(
                valid.copy(localTime = "2026-10-31T00:00"),
                settings,
                null,
                id,
                now
            ) is InputResult.Failure
        )
        assertTrue(
            buildEntry(valid.copy(currency = "EUR"), settings, null, id, now) is InputResult.Failure
        )
    }

    @Test fun invalidRatePairOrLocalTimeCannotBeRemembered() {
        assertEquals(
            InputIssue.INVALID_RATE,
            manualRateInput(fields().copy(rate = "0"), settings, now).issue()
        )
        assertEquals(
            InputIssue.INVALID_RATE,
            rateRequest(fields().copy(currency = "USD"), settings).issue()
        )
        assertEquals(
            InputIssue.TIME_GAP,
            rateRequest(
                fields().copy(localTime = "2026-03-29T02:30", zoneId = "Europe/Berlin"),
                settings
            ).issue()
        )
        assertEquals(
            InputIssue.TIME_AMBIGUOUS,
            rateRequest(
                fields().copy(localTime = "2026-10-25T02:30", zoneId = "Europe/Berlin"),
                settings
            ).issue()
        )
    }
}
