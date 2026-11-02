package app.finance.android.ledger

internal enum class InputIssue {
    INVALID_AMOUNT,
    AMOUNT_OVERFLOW,
    INVALID_CURRENCY,
    MISSING_RATE,
    INVALID_RATE,
    MISSING_CATEGORY,
    INVALID_NOTE,
    INVALID_TIME,
    TIME_GAP,
    TIME_AMBIGUOUS,
    INVALID_FILTER
}
internal sealed interface InputResult<out T> {
    data class Success<T>(val value: T) : InputResult<T> {
        override fun toString() = "InputSuccess"
    }
    data class Failure(val issue: InputIssue, val offsets: List<String> = emptyList()) :
        InputResult<Nothing>
}
