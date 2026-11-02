package app.finance.core.money

sealed interface MoneyResult<out T> {
    data class Success<T>(val value: T) : MoneyResult<T>
    data class Failure(val error: MoneyError) : MoneyResult<Nothing>
}

enum class MoneyError {
    INVALID_CURRENCY_CODE,
    UNKNOWN_CURRENCY,
    UNDEFINED_MINOR_UNIT,
    INVALID_DECIMAL,
    INPUT_TOO_LONG,
    NON_POSITIVE_AMOUNT,
    EXCESS_PRECISION,
    AMOUNT_OVERFLOW,
    CURRENCY_MISMATCH,
    NON_POSITIVE_RATE,
    MISSING_RATE,
    INVALID_RATE_PAIR,
    INVALID_SOURCE_ID
}
