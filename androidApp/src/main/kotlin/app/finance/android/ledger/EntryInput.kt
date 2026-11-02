package app.finance.android.ledger

import app.finance.core.money.CurrencyCode
import app.finance.core.money.DecimalRate
import app.finance.core.money.ExchangeRateQuote
import app.finance.core.money.Money
import app.finance.core.money.MoneyError
import app.finance.core.money.MoneyResult
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import app.finance.domain.ledger.ManualEntry
import app.finance.domain.ledger.StoredTransaction
import app.finance.domain.ledger.manualConversion
import kotlinx.datetime.Instant

internal data class EntryFields(
    val type: EntryType,
    val amount: String,
    val currency: String,
    val categoryId: LedgerId?,
    val rate: String,
    val localTime: String,
    val zoneId: String,
    val offset: String? = null,
    val note: String = "",
    val selectedQuote: ExchangeRateQuote? = null
) {
    override fun toString() = "EntryFields"
    fun withRate(text: String): EntryFields = copy(rate = text, selectedQuote = null)
    fun withLocalTime(text: String): EntryFields = copy(
        localTime = text,
        offset = null,
        selectedQuote = null,
        rate = if (selectedQuote != null) "" else rate
    )
    fun withCurrency(text: String): EntryFields = copy(
        currency = text,
        rate = if (text.trim().uppercase() == currency.trim().uppercase()) rate else "",
        selectedQuote = if (text.trim().uppercase() ==
            currency.trim().uppercase()
        ) {
            selectedQuote
        } else {
            null
        }
    )
}
internal fun buildEntry(
    fields: EntryFields,
    settings: LedgerSettings,
    existing: StoredTransaction?,
    newId: LedgerId,
    now: Instant
): InputResult<ManualEntry> {
    val category = fields.categoryId ?: return InputResult.Failure(InputIssue.MISSING_CATEGORY)
    if (fields.currency.length > 8) return InputResult.Failure(InputIssue.INVALID_CURRENCY)
    val currency = when (val parsed = CurrencyCode.resolve(fields.currency.trim().uppercase())) {
        is MoneyResult.Success -> parsed.value
        is MoneyResult.Failure -> return InputResult.Failure(InputIssue.INVALID_CURRENCY)
    }
    if (fields.amount.length > 64) return InputResult.Failure(InputIssue.INVALID_AMOUNT)
    val amount = when (
        val parsed = Money.parsePositive(
            fields.amount.trim().replace(',', '.'),
            currency
        )
    ) {
        is MoneyResult.Success -> parsed.value
        is MoneyResult.Failure -> return InputResult.Failure(
            if (parsed.error ==
                MoneyError.AMOUNT_OVERFLOW
            ) {
                InputIssue.AMOUNT_OVERFLOW
            } else {
                InputIssue.INVALID_AMOUNT
            }
        )
    }
    val occurredAt = when (
        val result = parseOccurrence(
            fields.localTime,
            fields.zoneId,
            fields.offset
        )
    ) {
        is InputResult.Success -> result.value
        is InputResult.Failure -> return result
    }
    val rate = if (currency == settings.primaryCurrency) {
        null
    } else {
        if (fields.rate.length > 128) return InputResult.Failure(InputIssue.INVALID_RATE)
        if (fields.rate.isBlank()) return InputResult.Failure(InputIssue.MISSING_RATE)
        when (val parsed = DecimalRate.parse(fields.rate.trim().replace(',', '.'))) {
            is MoneyResult.Success -> parsed.value
            is MoneyResult.Failure -> return InputResult.Failure(InputIssue.INVALID_RATE)
        }
    }
    val conversion = when (
        val result =
            manualConversion(
                amount,
                settings.primaryCurrency,
                rate,
                occurredAt,
                existing?.entry?.conversion,
                fields.selectedQuote
            )
    ) {
        is MoneyResult.Success -> result.value
        is MoneyResult.Failure -> return InputResult.Failure(
            if (result.error ==
                MoneyError.AMOUNT_OVERFLOW
            ) {
                InputIssue.AMOUNT_OVERFLOW
            } else {
                InputIssue.INVALID_RATE
            }
        )
    }
    return when (
        val result = ManualEntry.create(
            existing?.entry?.id ?: newId,
            fields.type,
            category,
            conversion,
            occurredAt,
            fields.zoneId,
            fields.note.ifEmpty {
                null
            },
            existing?.entry?.createdAt ?: now
        )
    ) {
        is LedgerResult.Success -> InputResult.Success(result.value)
        is LedgerResult.Failure -> InputResult.Failure(InputIssue.INVALID_NOTE)
    }
}
