package app.finance.domain.ledger

import app.finance.core.money.CurrencyCode
import app.finance.core.money.MoneyResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.Instant

class LedgerQueryTest {
    private fun <T> LedgerResult<T>.value(): T = (this as LedgerResult.Success).value

    @Test
    fun initialSettingsRequireKnownZoneAndPreserveExplicitCurrency() {
        val usd = (CurrencyCode.resolve("USD") as MoneyResult.Success).value
        assertEquals("USD", LedgerSettings.create(usd, "Asia/Manila").value().primaryCurrency.code)
        assertEquals(
            LedgerError.INVALID_ZONE,
            (LedgerSettings.create(usd, "Mars/Unknown") as LedgerResult.Failure).error
        )
    }

    @Test
    fun filterUsesNonEmptyHalfOpenInterval() {
        val at = Instant.parse("2026-11-01T00:00:00Z")
        val end = Instant.parse("2026-11-02T00:00:00Z")
        assertEquals(at, LedgerFilter.create(from = at, untilExclusive = end).value().from)
        for ((from, until) in listOf(at to at, end to at)) {
            assertEquals(
                LedgerError.INVALID_FILTER,
                (
                    LedgerFilter.create(
                        from = from,
                        untilExclusive = until
                    ) as LedgerResult.Failure
                    ).error
            )
        }
    }
}
