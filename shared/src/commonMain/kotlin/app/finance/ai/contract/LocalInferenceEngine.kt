package app.finance.ai.contract

/** Opaque local asset identity; platform adapter owns SAF/file/hash/license validation. */
class LocalModelRef private constructor(val key: String) {
    override fun toString() = "LocalModelRef"
    companion object {
        fun create(key: String): AiResult<LocalModelRef> {
            if (key.length !in 1..128 || !Regex("[A-Za-z0-9._-]+").matches(key)) {
                return AiResult.Failure(AiError.INVALID_REQUEST)
            }
            return AiResult.Success(LocalModelRef(key))
        }
    }
}
class InferenceRequest private constructor(
    val userText: String,
    val context: AiContext,
    val maxOutputTokens: Int
) {
    override fun toString() = "InferenceRequest"
    companion object {
        fun create(
            userText: String,
            context: AiContext,
            maxOutputTokens: Int = 1024
        ): AiResult<InferenceRequest> {
            if (userText.length > 4096) return AiResult.Failure(AiError.INPUT_TOO_LONG)
            if (userText.isBlank() ||
                !validAiText(userText, 4096, multiline = true) ||
                maxOutputTokens !in 1..2048
            ) {
                return AiResult.Failure(AiError.INVALID_REQUEST)
            }
            return AiResult.Success(InferenceRequest(userText, context, maxOutputTokens))
        }
    }
}
class InferenceText private constructor(val text: String) {
    override fun toString() = "InferenceText"
    companion object {
        fun create(raw: String): AiResult<InferenceText> = if (raw.length > 32768) {
            AiResult.Failure(AiError.OUTPUT_TOO_LONG)
        } else {
            AiResult.Success(InferenceText(raw))
        }
    }
}

/**
 * All work is local. Implementations must not log/retain requests or raw output.
 * Cancellation propagates; unload cancels and awaits an active generation before releasing assets.
 * Typed failures are sanitized. No repository or posting capability is part of this port.
 */
interface LocalInferenceEngine {
    suspend fun loadModel(model: LocalModelRef): AiResult<Unit>
    suspend fun generate(request: InferenceRequest): AiResult<InferenceText>
    suspend fun unload()
}
