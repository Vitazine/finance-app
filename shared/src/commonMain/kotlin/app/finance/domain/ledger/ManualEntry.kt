package app.finance.domain.ledger

import app.finance.core.money.ConversionSnapshot
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

class ManualEntry private constructor(
    val id: LedgerId,
    val type: EntryType,
    val categoryId: LedgerId,
    val conversion: ConversionSnapshot,
    val occurredAt: Instant,
    val displayZoneId: String,
    val note: String?,
    val createdAt: Instant
) {
    override fun toString(): String = "ManualEntry"

    companion object {
        fun create(
            id: LedgerId,
            type: EntryType,
            categoryId: LedgerId,
            conversion: ConversionSnapshot,
            occurredAt: Instant,
            displayZoneId: String,
            note: String?,
            createdAt: Instant
        ): LedgerResult<ManualEntry> {
            if (displayZoneId !in TimeZone.availableZoneIds) {
                return LedgerResult.Failure(LedgerError.INVALID_ZONE)
            }
            if (note != null &&
                (
                    note.length > 2000 ||
                        note.any {
                            it.isISOControl() && it != '\n' && it != '\t'
                        }
                    )
            ) {
                return LedgerResult.Failure(LedgerError.INVALID_NOTE)
            }
            return LedgerResult.Success(
                ManualEntry(
                    id,
                    type,
                    categoryId,
                    conversion,
                    occurredAt,
                    displayZoneId,
                    note,
                    createdAt
                )
            )
        }
    }
}

enum class EntrySource { MANUAL, AI }

class StoredTransaction internal constructor(
    val entry: ManualEntry,
    val updatedAt: Instant,
    val revision: Long = 0,
    val source: EntrySource = EntrySource.MANUAL
) {
    override fun toString(): String = "StoredTransaction"
}
