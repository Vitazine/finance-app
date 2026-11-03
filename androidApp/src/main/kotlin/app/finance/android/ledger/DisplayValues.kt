package app.finance.android.ledger

import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerSettings
import app.finance.domain.ledger.ManualEntry
import java.time.DateTimeException
import java.time.ZoneId
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

private fun zoned(instant: Instant, zone: String) = java.time.Instant.ofEpochSecond(
    instant.epochSeconds,
    instant.nanosecondsOfSecond.toLong()
).atZone(ZoneId.of(zone))
internal fun newFields(settings: LedgerSettings, now: Instant = Clock.System.now()): EntryFields {
    val local = zoned(now, settings.zoneId)
    return EntryFields(
        EntryType.EXPENSE,
        "",
        settings.primaryCurrency.code,
        null,
        "",
        local.toLocalDateTime().toString(),
        settings.zoneId,
        local.offset.id
    )
}
internal fun fieldsFor(entry: ManualEntry): EntryFields {
    val local = try {
        zoned(entry.occurredAt, entry.displayZoneId)
    } catch (
        _: DateTimeException
    ) {
        null
    }
    return EntryFields(
        entry.type,
        entry.conversion.sourceAmount.formatDecimal(),
        entry.conversion.sourceCurrency.code,
        entry.categoryId,
        entry.conversion.rateDecimal,
        local?.toLocalDateTime()?.toString() ?: entry.occurredAt.toString(),
        entry.displayZoneId,
        local?.offset?.id,
        entry.note.orEmpty()
    )
}
internal fun displayTime(entry: ManualEntry): String = try {
    val local = zoned(entry.occurredAt, entry.displayZoneId)
    displayLocalTime(local.toLocalDateTime().toString())
} catch (_: DateTimeException) {
    "${entry.occurredAt} · ${entry.displayZoneId}"
}

internal fun filterDate(instant: Instant?, zone: String, exclusiveEnd: Boolean = false): String {
    if (instant == null) return ""
    val local = zoned(instant, zone).toLocalDate()
    return (if (exclusiveEnd) local.minusDays(1) else local).toString()
}

internal fun displayRateTime(instant: Instant, zone: String): String = try {
    displayLocalTime(zoned(instant, zone).toLocalDateTime().toString())
} catch (_: DateTimeException) {
    instant.toString() + " · " + zone
}
