package app.finance.android.ai

import androidx.annotation.StringRes
import app.finance.ai.contract.AiError
import app.finance.android.R

/** A rejected answer does not invalidate the loaded weights or the next fresh inference context. */
internal fun AiError.requiresModelReload(): Boolean = this in setOf(
    AiError.MODEL_UNAVAILABLE,
    AiError.OUT_OF_MEMORY,
    AiError.TIMEOUT,
    AiError.ENGINE_FAILURE
)

@StringRes
internal fun generationMessage(error: AiError): Int = when (error) {
    AiError.INVALID_CONTEXT -> R.string.ai_setup_required
    AiError.MODEL_UNAVAILABLE -> R.string.ai_generation_unavailable
    AiError.OUT_OF_MEMORY -> R.string.ai_generation_memory_error
    AiError.TIMEOUT -> R.string.ai_timeout_error
    AiError.INVALID_REQUEST -> R.string.ai_request_error
    AiError.INPUT_TOO_LONG -> R.string.ai_input_too_long
    AiError.OUTPUT_TOO_LONG -> R.string.ai_output_too_long
    AiError.INVALID_AMOUNT -> R.string.ai_output_amount
    AiError.INVALID_CURRENCY -> R.string.ai_output_currency
    AiError.INVALID_DATE -> R.string.ai_output_date
    AiError.ENGINE_FAILURE -> R.string.ai_generation_failed
    AiError.BUSY -> R.string.ai_generation_busy
    else -> R.string.ai_parse_error
}
