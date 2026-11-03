package app.finance.android.ledger

import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OccurrenceInputTest {
    private fun <T> InputResult<T>.value() = (this as InputResult.Success).value
    private fun InputResult<*>.issue() = (this as InputResult.Failure).issue

    @Test fun humanDateUsesRussianMonthWithoutExposingZoneOrOffset() {
        assertEquals(
            "2 ноября 2026",
            displayRateTime(Instant.parse("2026-11-02T14:15:03.123456789Z"), "Asia/Manila")
        )
    }

    @Test fun humanInputResolvesTheSameInstantAsIsoWithoutChangingZone() {
        val parsed = parseOccurrence("22:15 2 ноября 2026", "Asia/Manila")
        assertTrue("Human-readable date must be accepted", parsed is InputResult.Success)
        assertEquals(
            Instant.parse("2026-11-02T14:15:00Z"),
            parsed.value()
        )
        assertEquals(
            InputIssue.INVALID_TIME,
            parseOccurrence("22:15 29 февраля 2026", "UTC").issue()
        )
        assertEquals(
            InputIssue.TIME_GAP,
            parseOccurrence("02:30 29 марта 2026", "Europe/Berlin").issue()
        )
        assertEquals(
            InputIssue.TIME_AMBIGUOUS,
            parseOccurrence("02:30 25 октября 2026", "Europe/Berlin").issue()
        )
    }

    @Test
    fun occurrenceKeepsNanosecondsAndExplicitZone() {
        assertEquals(
            Instant.parse("2026-11-01T01:02:03.123456789Z"),
            parseOccurrence("2026-11-01T09:02:03.123456789", "Asia/Manila").value()
        )
    }

    @Test
    fun nonexistentLocalTimeIsRejected() {
        assertEquals(
            InputIssue.TIME_GAP,
            parseOccurrence("2026-03-29T02:30", "Europe/Berlin").issue()
        )
    }

    @Test
    fun repeatedLocalTimeRequiresOffsetAndBothChoicesAreExact() {
        val failure = parseOccurrence("2026-10-25T02:30", "Europe/Berlin") as InputResult.Failure
        assertEquals(InputIssue.TIME_AMBIGUOUS, failure.issue)
        assertEquals(listOf("+02:00", "+01:00"), failure.offsets)
        assertEquals(
            Instant.parse("2026-10-25T00:30:00Z"),
            parseOccurrence("2026-10-25T02:30", "Europe/Berlin", "+02:00").value()
        )
        assertEquals(
            Instant.parse("2026-10-25T01:30:00Z"),
            parseOccurrence("2026-10-25T02:30", "Europe/Berlin", "+01:00").value()
        )
        assertEquals(
            InputIssue.INVALID_TIME,
            parseOccurrence("2026-10-25T02:30", "Europe/Berlin", "+03:00").issue()
        )
    }

    @Test
    fun badTimeZoneOrOversizedInputIsRejected() {
        for ((time, zone) in listOf(
            "bad" to "UTC",
            "2026-11-01T00:00" to "Mars/Unknown",
            "x".repeat(65) to "UTC"
        )) {
            assertEquals(InputIssue.INVALID_TIME, parseOccurrence(time, zone).issue())
        }
    }

    @Test
    fun dateFilterUsesCalendarBoundariesAcrossDst() {
        val result = parseDateRange("2026-03-29", "2026-03-29", "Europe/Berlin").value()
        assertEquals(Instant.parse("2026-03-28T23:00:00Z"), result.from)
        assertEquals(Instant.parse("2026-03-29T22:00:00Z"), result.untilExclusive)
        assertEquals("2026-03-29", filterDate(result.from, "Europe/Berlin"))
        assertEquals("2026-03-29", filterDate(result.untilExclusive, "Europe/Berlin", true))
    }

    @Test
    fun emptyDateFiltersAreUnboundedAndReversedRangeFails() {
        assertEquals(null, parseDateRange("", "", "UTC").value().from)
        assertEquals("", filterDate(null, "UTC"))
        assertEquals(
            InputIssue.INVALID_FILTER,
            parseDateRange("2026-11-02", "2026-11-01", "UTC").issue()
        )
        assertEquals(InputIssue.INVALID_FILTER, parseDateRange("2026-02-30", "", "UTC").issue())
    }
}
