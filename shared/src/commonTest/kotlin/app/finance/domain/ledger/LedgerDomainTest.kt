package app.finance.domain.ledger

import app.finance.core.money.ConversionSnapshot
import app.finance.core.money.CurrencyCode
import app.finance.core.money.Money
import app.finance.core.money.MoneyResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.datetime.Instant

class LedgerDomainTest {
    private val time = Instant.parse("2026-11-01T02:03:04.123456789Z")
    private fun <T> LedgerResult<T>.value(): T = (this as LedgerResult.Success).value
    private fun LedgerResult<*>.error() = (this as LedgerResult.Failure).error
    private fun id() = LedgerId.parse("00000000-0000-4000-8000-000000000001").value()

    @Test
    fun uuidIsValidatedAndCanonicalized() {
        val text = "ABCD0000-0000-4000-8000-000000000001"
        assertEquals(text.lowercase(), LedgerId.parse(text).value().text)
        listOf("", "x".repeat(36), "00000000-0000-4000-8000-000000000001 ").forEach {
            assertEquals(LedgerError.INVALID_ID, LedgerId.parse(it).error())
        }
    }

    @Test
    fun categoryNameIsTrimmedAndBoundedWithoutLosingUnicode() {
        assertEquals("Еда ☕", Category.create(id(), EntryType.EXPENSE, "  Еда ☕  ").value().name)
        assertEquals(
            120,
            Category.create(id(), EntryType.INCOME, "я".repeat(120)).value().name.length
        )
        listOf("", "  ", "x".repeat(121), "bad\nname", "bad\u0000name").forEach {
            assertEquals(
                LedgerError.INVALID_NAME,
                Category.create(id(), EntryType.EXPENSE, it).error()
            )
        }
    }

    private fun entry(
        zone: String = "Asia/Manila",
        note: String? = "Synthetic\nNote"
    ): LedgerResult<ManualEntry> {
        val usd = (CurrencyCode.resolve("USD") as MoneyResult.Success).value
        val snapshot = (
            ConversionSnapshot.identity(
                Money.ofMinor(123, usd),
                time
            ) as MoneyResult.Success
            ).value
        return ManualEntry.create(id(), EntryType.EXPENSE, id(), snapshot, time, zone, note, time)
    }

    @Test
    fun manualEntryKeepsExactInstantsZoneAndFrozenAmounts() {
        val saved = entry().value()
        assertEquals(time, saved.occurredAt)
        assertEquals(time, saved.createdAt)
        assertEquals("Asia/Manila", saved.displayZoneId)
        assertEquals(123L, saved.conversion.convertedMinor)
        assertEquals("Synthetic\nNote", saved.note)
    }

    @Test
    fun displayZoneMustBeKnownIanaZone() {
        listOf("", "Mars/Olympus", "Asia/Manila ", "+08:00").forEach {
            assertEquals(LedgerError.INVALID_ZONE, entry(zone = it).error())
        }
        assertEquals("UTC", entry(zone = "UTC").value().displayZoneId)
    }

    @Test
    fun noteIsBoundedAndRejectsUnsafeControls() {
        assertEquals(2000, entry(note = "x".repeat(2000)).value().note!!.length)
        listOf("x".repeat(2001), "bad\u0000note", "bad\u0001note").forEach {
            assertEquals(LedgerError.INVALID_NOTE, entry(note = it).error())
        }
        assertEquals(null, entry(note = null).value().note)
    }

    @Test
    fun domainObjectsAndErrorsDoNotRevealSensitiveText() {
        val input = "synthetic-private-name\u0000"
        val failure = Category.create(id(), EntryType.EXPENSE, input)
        assertFalse(failure.toString().contains(input))
        assertEquals(
            "Category",
            Category.create(id(), EntryType.EXPENSE, "Private name").value().toString()
        )
        assertEquals("ManualEntry", entry().value().toString())
    }
}
