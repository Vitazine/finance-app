package app.finance.domain.analytics

import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerResult
import kotlinx.datetime.DateTimeArithmeticException
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

class CompletedDays private constructor(
    val today: LocalDate,
    val startDate: LocalDate,
    val start: Instant,
    val untilExclusive: Instant,
    val zone: TimeZone
) {
    val zoneId: String get() = zone.id
    val dates: List<LocalDate> get() = List(30) { startDate.plus(it, DateTimeUnit.DAY) }
    fun contains(at: Instant): Boolean = at >= start && at < untilExclusive
    override fun toString() = "CompletedDays"
    companion object {
        fun create(reference: Instant, zone: TimeZone): LedgerResult<CompletedDays> {
            val today = try {
                reference.toLocalDateTime(zone).date
            } catch (
                _: DateTimeArithmeticException
            ) {
                return LedgerResult.Failure(LedgerError.INVALID_FILTER)
            }
            if (today.year !in 1..9998) return LedgerResult.Failure(LedgerError.INVALID_FILTER)
            val first = today.plus(-30, DateTimeUnit.DAY)
            if (first.year < 1) return LedgerResult.Failure(LedgerError.INVALID_FILTER)
            return LedgerResult.Success(
                CompletedDays(
                    today,
                    first,
                    first.atStartOfDayIn(zone),
                    today.atStartOfDayIn(zone),
                    zone
                )
            )
        }
    }
}
