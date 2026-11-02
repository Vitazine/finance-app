package app.finance.android.ledger

import app.finance.domain.ledger.LedgerFilter
import app.finance.domain.ledger.LedgerResult
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.datetime.Instant

internal fun parseOccurrence(
    text: String,
    zone: String,
    offset: String? = null
): InputResult<Instant> {
    if (text.length > 64 ||
        zone.length > 128 ||
        (offset?.length ?: 0) > 16
    ) {
        return InputResult.Failure(InputIssue.INVALID_TIME)
    }
    return try {
        val local = LocalDateTime.parse(text)
        val offsets = ZoneId.of(zone).rules.getValidOffsets(local)
        if (offsets.isEmpty()) return InputResult.Failure(InputIssue.TIME_GAP)
        if (offsets.size > 1 &&
            offset == null
        ) {
            return InputResult.Failure(InputIssue.TIME_AMBIGUOUS, offsets.map { it.id })
        }
        val selected = if (offset == null) offsets.single() else offsets.find { it.id == offset }
        if (selected == null) return InputResult.Failure(InputIssue.INVALID_TIME)
        val instant = local.toInstant(selected)
        InputResult.Success(Instant.fromEpochSeconds(instant.epochSecond, instant.nano))
    } catch (_: DateTimeException) {
        InputResult.Failure(InputIssue.INVALID_TIME)
    }
}

internal fun parseDateRange(
    from: String,
    through: String,
    zone: String
): InputResult<LedgerFilter> {
    if (from.length > 10 ||
        through.length > 10 ||
        zone.length > 128
    ) {
        return InputResult.Failure(InputIssue.INVALID_FILTER)
    }
    return try {
        val selectedZone = ZoneId.of(zone)
        val first = from.takeIf {
            it.isNotEmpty()
        }?.let { LocalDate.parse(it).atStartOfDay(selectedZone).toInstant() }
        val next = through.takeIf {
            it.isNotEmpty()
        }?.let { LocalDate.parse(it).plusDays(1).atStartOfDay(selectedZone).toInstant() }
        val result = LedgerFilter.create(
            from = first?.let { Instant.fromEpochSeconds(it.epochSecond, it.nano) },
            untilExclusive = next?.let { Instant.fromEpochSeconds(it.epochSecond, it.nano) }
        )
        when (result) {
            is LedgerResult.Success -> InputResult.Success(result.value)
            is LedgerResult.Failure -> InputResult.Failure(InputIssue.INVALID_FILTER)
        }
    } catch (_: DateTimeException) {
        InputResult.Failure(InputIssue.INVALID_FILTER)
    }
}
