package app.finance.android.ai

import app.finance.ai.contract.AiResult
import app.finance.ai.contract.InferenceRequest
import app.finance.ai.contract.InferenceText
import app.finance.ai.contract.LocalInferenceEngine

internal data class GenerationOptions(val diagnostic: Boolean = false) {
    val timeoutMs: Long get() = if (diagnostic) 600_000L else 120_000L
    fun effective(debug: Boolean) = if (debug && diagnostic) this else GenerationOptions()
}
internal enum class GenerationPhase { PREPARING, PREFILL, GENERATING, FINISHED }

/** Ephemeral numeric measurements only; no prompt, output, file paths or wall-clock identifiers. */
internal data class GenerationProgress(
    val phase: GenerationPhase = GenerationPhase.PREPARING,
    val promptTokens: Int = 0,
    val processedTokens: Int = 0,
    val outputTokens: Int = 0,
    val firstTokenMs: Long? = null,
    val elapsedMs: Long = 0,
    val pssKiB: Int = 0
) {
    val tokensPerSecond: Double? get() {
        val first = firstTokenMs ?: return null
        return if (outputTokens > 1 && elapsedMs > first) {
            (outputTokens - 1) * 1000.0 / (elapsedMs - first)
        } else {
            null
        }
    }
    companion object {
        fun fromNative(values: LongArray, pssKiB: Int): GenerationProgress {
            fun at(index: Int) = values.getOrElse(index) { 0L }.coerceAtLeast(0)
            val total = at(1).coerceAtMost(2048).toInt()
            return GenerationProgress(
                phase = when (at(0)) {
                    2L -> GenerationPhase.PREFILL
                    3L -> GenerationPhase.GENERATING
                    4L -> GenerationPhase.FINISHED
                    else -> GenerationPhase.PREPARING
                },
                promptTokens = total,
                processedTokens = at(2).coerceAtMost(total.toLong()).toInt(),
                firstTokenMs = at(3).takeIf { it > 0 }?.div(1000),
                outputTokens = at(5).coerceAtMost(256).toInt(),
                elapsedMs = at(6) / 1000,
                pssKiB = pssKiB.coerceAtLeast(0)
            )
        }
    }
}
internal interface DiagnosticInferenceEngine : LocalInferenceEngine {
    suspend fun generate(
        request: InferenceRequest,
        options: GenerationOptions,
        report: (GenerationProgress) -> Unit
    ): AiResult<InferenceText>
}
