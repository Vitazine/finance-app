package app.finance.data.repository

import app.finance.core.money.ConversionSnapshot
import app.finance.core.money.CurrencyCode
import app.finance.core.money.DecimalRate
import app.finance.core.money.ExchangeRateQuote
import app.finance.core.money.Money
import app.finance.core.money.MoneyResult
import app.finance.data.database.CategoryEntity
import app.finance.data.database.TransactionEntity
import app.finance.domain.ledger.Category
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.ManualEntry
import app.finance.domain.ledger.StoredTransaction
import kotlinx.datetime.Instant

internal class CorruptRecord : RuntimeException("Invalid stored ledger record")

private fun <T> LedgerResult<T>.decoded(): T = when (this) {
    is LedgerResult.Success -> value
    is LedgerResult.Failure -> throw CorruptRecord()
}
private fun <T> MoneyResult<T>.decoded(): T = when (this) {
    is MoneyResult.Success -> value
    is MoneyResult.Failure -> throw CorruptRecord()
}
private inline fun <T> decode(block: () -> T): T = try {
    block()
} catch (_: IllegalArgumentException) {
    throw CorruptRecord()
}

internal fun Category.toEntity() = CategoryEntity(id.text, type.name, name, archivedAt?.toString())

internal fun CategoryEntity.toDomain(): Category = decode {
    val category = Category.create(
        LedgerId.parse(id).decoded(),
        EntryType.valueOf(type),
        name,
        archivedAt?.let(Instant::parse)
    ).decoded()
    if (category.toEntity() != this) throw CorruptRecord()
    category
}

internal fun ManualEntry.toEntity(
    updatedAt: Instant = createdAt,
    revision: Long = 0
): TransactionEntity = TransactionEntity(
    id = id.text,
    type = type.name,
    amountMinor = conversion.sourceAmount.amountMinor,
    sourceCurrency = conversion.sourceCurrency.code,
    categoryId = categoryId.text,
    occurredAtSeconds = occurredAt.epochSeconds,
    occurredAtNanos = occurredAt.nanosecondsOfSecond,
    displayZoneId = displayZoneId,
    note = note,
    source = "MANUAL",
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    baseCurrency = conversion.baseCurrency.code,
    convertedMinor = conversion.convertedMinor,
    rateDecimal = conversion.rateDecimal,
    fxEffectiveAt = conversion.effectiveAt.toString(),
    fxSourceId = conversion.sourceId,
    roundingPolicy = conversion.roundingPolicy.name,
    revision = revision
)

internal fun TransactionEntity.toDomain(): StoredTransaction = decode {
    val sourceCurrency = CurrencyCode.resolve(sourceCurrency).decoded()
    val baseCurrency = CurrencyCode.resolve(baseCurrency).decoded()
    val sourceAmount = Money.ofMinor(amountMinor, sourceCurrency)
    val effectiveAt = Instant.parse(fxEffectiveAt)
    val conversion = if (sourceCurrency == baseCurrency) {
        ConversionSnapshot.identity(sourceAmount, effectiveAt).decoded()
    } else {
        val quote = ExchangeRateQuote.create(
            sourceCurrency,
            baseCurrency,
            DecimalRate.parse(rateDecimal).decoded(),
            effectiveAt,
            fxSourceId
        ).decoded()
        ConversionSnapshot.convert(sourceAmount, baseCurrency, quote).decoded()
    }
    val entry = ManualEntry.create(
        LedgerId.parse(id).decoded(),
        EntryType.valueOf(type),
        LedgerId.parse(categoryId).decoded(),
        conversion,
        Instant.fromEpochSeconds(occurredAtSeconds, occurredAtNanos),
        displayZoneId,
        note,
        Instant.parse(createdAt)
    ).decoded()
    val updatedAt = Instant.parse(updatedAt)
    if (source !in setOf("MANUAL", "AI") ||
        revision < 0 ||
        entry.toEntity(updatedAt, revision).copy(source = source) != this
    ) {
        throw CorruptRecord()
    }
    StoredTransaction(
        entry,
        updatedAt,
        revision,
        app.finance.domain.ledger.EntrySource.valueOf(source)
    )
}
