package app.finance.android.ledger

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class DateOnlyTest {
    @Test fun dateDisplayUsesTheSavedZoneDayAcrossUtcMidnight() {
        assertEquals(
            "2 ноября 2026",
            displayRateTime(
                kotlinx.datetime.Instant.parse("2026-11-01T23:00:00Z"),
                "Asia/Manila"
            )
        )
    }

    @Test fun displayNeverIncludesClockEvenWhenInternalClockIsInvalid() {
        assertEquals("2 ноября 2026", displayLocalTime("2026-11-02T22:15:03.123456789"))
        assertEquals("2 ноября 2026", displayLocalTime("2026-11-02T29:99"))
        assertEquals("2 ноября 2026", displayLocalTime("2026-11-02T"))
        assertEquals("", displayLocalTime("invalid"))
    }

    @Test fun changingOnlyDatePreservesAllInternalClockPrecision() {
        assertEquals(
            "2026-11-01T22:15:03.123456789",
            replaceOccurrenceDate("2026-11-02T22:15:03.123456789", LocalDate.of(2026, 11, 1))
        )
    }

    @Test fun sameDateIsAnExactNoOpAndUnresolvedTimeIsNotInvented() {
        val original = "2026-11-02T22:15:00"
        assertEquals(original, replaceOccurrenceDate(original, LocalDate.of(2026, 11, 2)))
        assertEquals("2026-11-01T", replaceOccurrenceDate("2026-11-02T", LocalDate.of(2026, 11, 1)))
    }

    @Test fun changingDateDoesNotSilentlyNormalizeDstGap() {
        val next = replaceOccurrenceDate("2026-03-07T02:30", LocalDate.of(2026, 3, 8))
        assertEquals(
            InputIssue.TIME_GAP,
            (parseOccurrence(next, "America/New_York") as InputResult.Failure).issue
        )
    }
}
