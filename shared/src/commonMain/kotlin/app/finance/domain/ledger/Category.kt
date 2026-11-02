package app.finance.domain.ledger

import kotlinx.datetime.Instant

class Category private constructor(
    val id: LedgerId,
    val type: EntryType,
    val name: String,
    val archivedAt: Instant?
) {
    override fun toString(): String = "Category"

    companion object {
        fun create(
            id: LedgerId,
            type: EntryType,
            name: String,
            archivedAt: Instant? = null
        ): LedgerResult<Category> {
            if (name.length > 120 || name.any { it.isISOControl() } || name.isBlank()) {
                return LedgerResult.Failure(LedgerError.INVALID_NAME)
            }
            return LedgerResult.Success(Category(id, type, name.trim(), archivedAt))
        }
    }
}
