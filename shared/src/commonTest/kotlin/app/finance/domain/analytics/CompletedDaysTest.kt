package app.finance.domain.analytics

import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class CompletedDaysTest {
    private fun <T> LedgerResult<T>.value() = (this as LedgerResult.Success).value
    private fun window(at: String, zone: String = "UTC") =
        CompletedDays.create(Instant.parse(at), TimeZone.of(zone)).value()

    @Test fun leapNonLeapAndYearCrossingHaveThirtyLabels() {
        for ((at, expected) in listOf(
            "2024-03-01T12:00:00Z" to "2024-01-31",
            "2023-03-01T12:00:00Z" to "2023-01-30",
            "2027-01-01T12:00:00Z" to "2026-12-02"
        )) {
            val w = window(at)
            assertEquals(30, w.dates.size)
            assertEquals(expected, w.startDate.toString())
            assertEquals(w.startDate, w.dates.first())
            assertTrue(w.dates.last() < w.today)
            assertEquals(30, w.dates.toSet().size)
        }
    }

    @Test fun dstChangesUtcHoursWithoutChangingThirtyCalendarDates() {
        for ((at, hours) in listOf(
            "2024-03-20T12:00:00Z" to 719L,
            "2024-11-15T12:00:00Z" to 721L
        )) {
            val w = window(at, "America/New_York")
            assertEquals(hours * 3600, w.untilExclusive.epochSeconds - w.start.epochSeconds)
            assertEquals(30, w.dates.size)
        }
    }

    @Test fun selectedZoneMidnightChangesWindowAtExactNanosecond() {
        val before = window("2026-11-01T15:59:59.999999999Z", "Asia/Manila")
        val after = window("2026-11-01T16:00:00Z", "Asia/Manila")
        assertEquals("2026-11-01", before.today.toString())
        assertEquals("2026-11-02", after.today.toString())
        assertEquals("2026-10-02T16:00:00Z", after.start.toString())
        assertEquals("2026-11-01T16:00:00Z", after.untilExclusive.toString())
    }

    @Test fun intervalIsHalfOpenAndExcludesAllOfToday() {
        val w = window("2026-11-02T12:00:00Z")
        assertTrue(w.contains(w.start))
        assertFalse(w.contains(Instant.fromEpochSeconds(w.start.epochSeconds, -1)))
        assertTrue(w.contains(Instant.fromEpochSeconds(w.untilExclusive.epochSeconds, -1)))
        assertFalse(w.contains(w.untilExclusive))
        assertFalse(w.contains(Instant.parse("2026-11-02T12:00:00Z")))
    }

    @Test fun skippedCivilDateRemainsAnExplicitZeroDayLabel() {
        val w = window("2011-12-31T12:00:00Z", "Pacific/Apia")
        assertEquals(LocalDate(2012, 1, 1), w.today)
        assertEquals(30, w.dates.size)
        assertTrue(LocalDate(2011, 12, 30) in w.dates)
        assertEquals(29L * 86400, w.untilExclusive.epochSeconds - w.start.epochSeconds)
    }

    @Test fun unsupportedReferenceRangeIsTypedFailure() {
        for (at in listOf("0001-01-01T00:00:00Z", "+10000-01-01T00:00:00Z")) {
            assertEquals(
                LedgerResult.Failure(LedgerError.INVALID_FILTER),
                CompletedDays.create(Instant.parse(at), TimeZone.UTC)
            )
        }
    }
}
