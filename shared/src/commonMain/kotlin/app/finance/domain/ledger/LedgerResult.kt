package app.finance.domain.ledger

sealed interface LedgerResult<out T> {
    data class Success<T>(val value: T) : LedgerResult<T>
    data class Failure(val error: LedgerError) : LedgerResult<Nothing>
}

enum class LedgerError {
    INVALID_MONTH,
    INVALID_BUDGET,
    INVALID_ID,
    INVALID_NAME,
    INVALID_NOTE,
    INVALID_ZONE,
    INVALID_PAGE,
    INVALID_FILTER,
    NOT_INITIALIZED,
    BASE_CURRENCY_MISMATCH,
    LEGACY_BASE_CONFLICT,
    AMOUNT_OVERFLOW,
    NOT_FOUND,
    CATEGORY_ARCHIVED,
    CATEGORY_IN_USE,
    TYPE_MISMATCH,
    CONFLICT,
    STORAGE_FAILURE,
    CORRUPT_DATA
}

enum class EntryType { INCOME, EXPENSE }
