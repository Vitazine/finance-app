package app.finance.android.ledger

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale

private val russian = Locale.forLanguageTag("ru")
private val humanTime = DateTimeFormatterBuilder().parseCaseInsensitive()
    .appendPattern("HH:mm d MMMM uuuu").toFormatter(russian)
    .withResolverStyle(ResolverStyle.STRICT)
private val humanDay = DateTimeFormatterBuilder().parseCaseInsensitive()
    .appendPattern("d MMMM uuuu").toFormatter(russian).withResolverStyle(ResolverStyle.STRICT)

internal fun parseLocalTime(text: String): LocalDateTime = try {
    LocalDateTime.parse(text)
} catch (_: DateTimeException) {
    LocalDateTime.parse(text, humanTime)
}

internal fun parseLocalDate(text: String): LocalDate = try {
    LocalDate.parse(text)
} catch (_: DateTimeException) {
    LocalDate.parse(text, humanDay)
}

internal fun displayLocalTime(text: String): String = try {
    parseLocalTime(text).format(humanTime)
} catch (_: DateTimeException) {
    // A date-only AI proposal must still require the owner to choose a time.
    if (text.endsWith("T")) displayLocalDate(text.dropLast(1)) else text
}

internal fun displayLocalDate(text: String): String = try {
    parseLocalDate(text).format(humanDay)
} catch (_: DateTimeException) {
    text
}

internal fun displayMonth(key: String): String = try {
    java.time.YearMonth.parse(key).format(DateTimeFormatter.ofPattern("LLLL uuuu", russian))
} catch (_: DateTimeException) {
    key
}

internal fun shortDay(date: kotlinx.datetime.LocalDate): String =
    LocalDate.of(date.year, date.monthNumber, date.dayOfMonth)
        .format(DateTimeFormatter.ofPattern("d MMM", russian))
