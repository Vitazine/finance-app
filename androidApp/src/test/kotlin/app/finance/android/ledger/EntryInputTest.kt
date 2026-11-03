package app.finance.android.ledger

import app.finance.core.money.CurrencyCode
import app.finance.core.money.MoneyResult
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import app.finance.domain.ledger.StoredTransaction
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EntryInputTest {
    private fun <T> LedgerResult<T>.value() = (this as LedgerResult.Success).value
    private fun <T> MoneyResult<T>.value() = (this as MoneyResult.Success).value
    private fun <T> InputResult<T>.value() = (this as InputResult.Success).value
    private fun InputResult<*>.issue() = (this as InputResult.Failure).issue
    private val at = Instant.parse("2026-11-01T00:00:00Z")
    private val id = LedgerId.parse("00000000-0000-4000-8000-000000000010").value()
    private val categoryId = LedgerId.parse("00000000-0000-4000-8000-000000000001").value()
    private val usd = CurrencyCode.resolve("USD").value()
    private val settings = LedgerSettings.create(usd, "UTC").value()
    private fun fields() =
        EntryFields(EntryType.EXPENSE, "1,25", "usd", categoryId, "", "2026-11-01T00:00", "UTC")
    private fun build(fields: EntryFields, existing: StoredTransaction? = null) =
        buildEntry(fields, settings, existing, id, at)

    @Test fun readableDisplayAndNoteOnlyEditPreserveOriginalNanosecondInstant() {
        val original = build(fields().copy(localTime = "2026-11-02T22:15:03.123456789")).value()
        assertEquals("22:15 2 ноября 2026", displayTime(original))
        val edited = fieldsFor(original).copy(note = "Synthetic edited note")
        assertEquals(
            Instant.parse("2026-11-02T22:15:03.123456789Z"),
            build(edited).value().occurredAt
        )
        assertEquals(
            original.conversion.convertedMinor,
            build(edited).value().conversion.convertedMinor
        )
    }

    @Test
    fun localizedDecimalIsExactAndIdentityRequiresNoForeignRate() {
        val result = build(fields()).value()
        assertEquals(125L, result.conversion.sourceAmount.amountMinor)
        assertEquals(125L, result.conversion.convertedMinor)
        assertEquals("USD", result.conversion.sourceCurrency.code)
    }

    @Test
    fun foreignCurrencyNeedsExplicitPositiveRateAndRoundsOnlyAtEnd() {
        assertEquals(
            InputIssue.MISSING_RATE,
            build(fields().copy(currency = "KWD", amount = "1.245")).issue()
        )
        val result = build(
            fields().copy(currency = "KWD", amount = "1.245", rate = "1.004")
        ).value()
        assertEquals(125L, result.conversion.convertedMinor)
        assertEquals("manual", result.conversion.sourceId)
        assertEquals(
            InputIssue.INVALID_RATE,
            build(fields().copy(currency = "EUR", rate = "0")).issue()
        )
    }

    @Test
    fun invalidOrOverpreciseInputCannotBecomeEntry() {
        for (text in listOf(
            "0",
            "-1",
            "1e2",
            "1.001"
        )) {
            assertEquals(InputIssue.INVALID_AMOUNT, build(fields().copy(amount = text)).issue())
        }
        assertEquals(
            InputIssue.AMOUNT_OVERFLOW,
            build(fields().copy(amount = "92233720368547758.08")).issue()
        )
        assertEquals(InputIssue.INVALID_CURRENCY, build(fields().copy(currency = "XXX")).issue())
        assertEquals(InputIssue.MISSING_CATEGORY, build(fields().copy(categoryId = null)).issue())
        assertEquals(InputIssue.INVALID_NOTE, build(fields().copy(note = "x".repeat(2001))).issue())
    }

    @Test
    fun changingSourceCurrencyClearsPreviousPairRate() {
        assertEquals("", fields().copy(rate = "12.34").withCurrency("EUR").rate)
        assertEquals("12.34", fields().copy(rate = "12.34").withCurrency("USD").rate)
    }

    @Test
    fun inputObjectsAndFailuresDoNotExposeNotes() {
        val fields = fields().copy(note = "synthetic-private-note")
        assertFalse(fields.toString().contains("synthetic-private-note"))
        assertFalse(
            build(fields.copy(amount = "bad")).toString().contains("synthetic-private-note")
        )
    }
}
