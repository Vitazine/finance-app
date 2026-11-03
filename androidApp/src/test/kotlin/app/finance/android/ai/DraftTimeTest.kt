package app.finance.android.ai

import app.finance.android.ledger.EntryFields
import app.finance.android.ledger.InputIssue
import app.finance.android.ledger.InputResult
import app.finance.android.ledger.parseOccurrence
import app.finance.domain.ledger.EntryType
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftTimeTest {
    @Test fun rangesAndUtcOffsetsDoNotBecomeOneArbitrarilySelectedTime() {
        for (text in listOf(
            "Synthetic 10:15-22:15",
            "Synthetic 10:15–22:15",
            "Synthetic 10:15+03:00",
            "Synthetic UTC+03:00",
            "Synthetic 10:15Z",
            "Synthetic 10:15 UTC",
            "Synthetic 10:15 GMT"
        )) {
            assertEquals("2026-11-02T", resolve(text).localTime)
        }
    }
    private val date = LocalDate.parse("2026-11-02")
    private val now = Instant.parse("2026-11-03T00:15:34.123456789Z")
    private fun fields(text: String = "2026-11-02T", zone: String = "Asia/Manila") =
        EntryFields(EntryType.EXPENSE, "1", "USD", null, "", text, zone)
    private fun resolve(
        text: String = "Synthetic purchase",
        fields: EntryFields = fields(),
        count: Int = 1
    ) = resolveDraftTime(fields, date, text, now, count)

    @Test fun missingTimeUsesRequestTimeInAppZoneWithoutReplacingProposalDate() {
        val f = resolve()
        assertEquals("2026-11-02T08:15:34.123456789", f.localTime)
        assertEquals(
            Instant.parse("2026-11-02T00:15:34.123456789Z"),
            (parseOccurrence(f.localTime, f.zoneId, f.offset) as InputResult.Success).value
        )
    }

    @Test fun explicitTimeAndFractionalSecondsHavePriorityOverCurrentTime() {
        assertEquals("2026-11-02T07:05", resolve("Synthetic purchase в 7:05").localTime)
        assertEquals(
            "2026-11-02T22:15:03.123456789",
            resolve("Synthetic purchase в 22:15:03.123456789").localTime
        )
    }

    @Test fun alreadyEditedFullTimeAndOffsetRemainUntouched() {
        val f = fields("2026-11-02T09:10:11.123456789").copy(offset = "+08:00")
        assertEquals(f, resolve("Synthetic at 22:15", f))
    }

    @Test fun editedDateOnlyKeepsSelectedDateAndGetsStableRequestTime() {
        assertEquals(
            "2026-11-01T08:15:34.123456789",
            resolve(fields = fields("1 ноября 2026")).localTime
        )
        val first = resolve()
        assertEquals(first, resolveDraftTime(first, date, "Synthetic", now, 1))
    }

    @Test fun invalidOrAmbiguousExplicitTimesAreNeverSilentlyReplacedByNow() {
        val bad = resolve("Synthetic purchase в 25:30")
        assertEquals(
            InputIssue.INVALID_TIME,
            (parseOccurrence(bad.localTime, bad.zoneId) as InputResult.Failure).issue
        )
        assertEquals("2026-11-02T", resolve("Synthetic в 10:15 и 22:15").localTime)
        assertEquals("2026-11-02T", resolve("Synthetic в 10:15", count = 2).localTime)
    }

    @Test fun defaultDuringCurrentDstFoldKeepsKnownOffsetButExplicitFoldNeedsChoice() {
        val original = fields("2026-10-25T", "Europe/Berlin")
        val fold = Instant.parse("2026-10-25T01:30:00Z")
        val f = resolveDraftTime(original, LocalDate.parse("2026-10-25"), "Synthetic", fold, 1)
        val parsed = parseOccurrence(f.localTime, f.zoneId, f.offset)
        assertTrue("Current time in a DST fold must remain valid", parsed is InputResult.Success)
        assertEquals(fold, (parsed as InputResult.Success).value)
        val explicit =
            resolveDraftTime(original, LocalDate.parse("2026-10-25"), "Synthetic 02:30", fold, 1)
        assertEquals(
            InputIssue.TIME_AMBIGUOUS,
            (
                parseOccurrence(
                    explicit.localTime,
                    explicit.zoneId,
                    explicit.offset
                ) as InputResult.Failure
                ).issue
        )
    }
}
