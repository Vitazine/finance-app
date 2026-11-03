package app.finance.android.ai

import app.finance.android.ledger.EntryFields
import app.finance.android.ledger.parseLocalDate
import java.time.DateTimeException
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

private val suppliedTime = Regex("(?<![0-9:])[0-9]+:[0-9]+(?::[0-9]+(?:[.,][0-9]+)?)?(?![0-9:])")
private val zoneName = Regex("(?i)(?<![a-z])(?:UTC|GMT)(?![a-z])")

/** Defaults are frozen to the request reference; invalid/ambiguous explicit times stay editable. */
internal fun resolveDraftTime(
    fields: EntryFields,
    proposalDate: LocalDate,
    prompt: String,
    reference: Instant,
    proposalCount: Int
): EntryFields {
    val date = if (fields.localTime.isBlank()) {
        java.time.LocalDate.parse(proposalDate.toString())
    } else {
        try {
            parseLocalDate(fields.localTime.removeSuffix("T"))
        } catch (_: DateTimeException) {
            return fields // A complete or invalid explicit timestamp must not be overwritten.
        }
    }
    val candidates = suppliedTime.findAll(prompt).toList()
    if (candidates.any {
            val before = prompt.getOrNull(it.range.first - 1)
            val after = prompt.getOrNull(it.range.last + 1)
            before == '+' || before == '-' || after == 'Z' || after == 'z'
        } ||
        (candidates.isNotEmpty() && zoneName.containsMatchIn(prompt))
    ) {
        return fields.withLocalTime(date.toString() + "T")
    }
    val supplied = candidates.map { match ->
        val raw = match.value.replace(',', '.')
        raw.substringBefore(':').padStart(2, '0') + ":" + raw.substringAfter(':')
    }.toList()
    if (supplied.isNotEmpty()) {
        if (proposalCount != 1) return fields.withLocalTime(date.toString() + "T")
        val times = supplied.map { runCatching { LocalTime.parse(it) }.getOrNull() }
        val unique = times.filterNotNull().distinct()
        if (times.any { it == null }) {
            val invalid = supplied[times.indexOf(null)]
            return fields.withLocalTime(date.toString() + "T" + invalid)
        }
        if (unique.size != 1) return fields.withLocalTime(date.toString() + "T")
        return fields.withLocalTime(date.atTime(unique.single()).toString())
    }
    val current = java.time.Instant.ofEpochSecond(
        reference.epochSeconds,
        reference.nanosecondsOfSecond.toLong()
    ).atZone(ZoneId.of(fields.zoneId))
    return fields.withLocalTime(date.atTime(current.toLocalTime()).toString()).copy(
        offset = if (date == current.toLocalDate()) current.offset.id else null
    )
}
