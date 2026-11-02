package app.finance.domain.budget

import app.finance.core.money.CurrencyCode
import app.finance.core.money.Money
import app.finance.core.money.MoneyResult
import app.finance.domain.ledger.LedgerResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

class BudgetTest {
    private fun <T> LedgerResult<T>.value() = (this as LedgerResult.Success).value
    private val usd = (CurrencyCode.resolve("USD") as MoneyResult.Success).value
    private val at = Instant.parse("2026-11-01T00:00:00Z")

    @Test fun invalidMonthAndNonpositiveLimitAreRejected() {
        for ((y, m) in listOf(0 to 1, 9999 to 1, 2026 to 0, 2026 to 13)) {
            assertIs<LedgerResult.Failure>(BudgetMonth.create(y, m))
        }
        val month = BudgetMonth.create(2026, 11).value()
        for (n in listOf(0L, -1L)) {
            assertIs<LedgerResult.Failure>(MonthlyBudget.create(month, Money.ofMinor(n, usd), at))
        }
    }

    @Test fun leapAndYearBoundariesUseCalendarDates() {
        val utc = TimeZone.UTC
        assertEquals(
            "2024-03-01T00:00:00Z",
            BudgetMonth.create(2024, 2).value().nextStart(utc).toString()
        )
        assertEquals(
            29L * 86400,
            BudgetMonth.create(2024, 2).value().let {
                it.nextStart(utc).epochSeconds -
                    it.start(utc).epochSeconds
            }
        )
        assertEquals(
            28L * 86400,
            BudgetMonth.create(2023, 2).value().let {
                it.nextStart(utc).epochSeconds -
                    it.start(utc).epochSeconds
            }
        )
        assertEquals(
            "2027-01-01T00:00:00Z",
            BudgetMonth.create(2026, 12).value().nextStart(utc).toString()
        )
    }

    @Test fun dstMonthsHaveUnequalUtcDurations() {
        val ny = TimeZone.of("America/New_York")
        for ((month, hours) in listOf(3 to 743L, 11 to 721L)) {
            val period = BudgetMonth.create(2024, month).value()
            assertEquals(
                hours * 3600,
                period.nextStart(ny).epochSeconds - period.start(ny).epochSeconds
            )
        }
    }

    @Test fun localMonthEdgeUsesZoneAndExactNanoseconds() {
        val zone = TimeZone.of("Asia/Manila")
        assertEquals(
            "2026-10",
            BudgetMonth.at(Instant.parse("2026-10-31T15:59:59.999999999Z"), zone).value().key
        )
        val month = BudgetMonth.at(Instant.parse("2026-10-31T16:00:00Z"), zone).value()
        assertEquals("2026-11", month.key)
        assertEquals("2026-11-30T16:00:00Z", month.nextStart(zone).toString())
    }

    @Test fun remainingAndOverrunAreExactAndMissingLimitStaysAbsent() {
        val month = BudgetMonth.create(2026, 11).value()
        val budget = MonthlyBudget.create(month, Money.ofMinor(100, usd), at).value()
        for ((spent, remaining, excess) in listOf(
            Triple(0L, 100L, 0L),
            Triple(100L, 0L, 0L),
            Triple(125L, -25L, 25L)
        )) {
            val view = BudgetOverview(month, "UTC", Money.ofMinor(spent, usd), budget)
            assertEquals(remaining, view.remaining!!.amountMinor)
            assertEquals(excess, view.overrun!!.amountMinor)
        }
        val missing = BudgetOverview(month, "UTC", Money.ofMinor(Long.MAX_VALUE, usd), null)
        assertEquals(null, missing.remaining)
        assertEquals(null, missing.overrun)
    }
}
