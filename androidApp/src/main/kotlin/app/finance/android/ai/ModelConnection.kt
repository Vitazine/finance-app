package app.finance.android.ai

import app.finance.ai.contract.AiError
import app.finance.ai.contract.AiResult
import app.finance.ai.contract.LocalInferenceEngine
import app.finance.ai.contract.LocalModelRef
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Sanitized diagnostics only: never paths, prompts, native output or exception messages. */
internal enum class ConnectionStage { DEVICE, FILES, PROMPT, VERIFY, LOAD, STOPPING }
internal enum class ConnectionFailure {
    RUNTIME_MISSING,
    MEMORY,
    MODEL_MISSING,
    PROMPT_MISSING,
    FILE_ACCESS,
    PROMPT_INVALID,
    MODEL_SIZE,
    MODEL_HASH,
    LOAD_FAILED,
    TIMEOUT
}
internal data class ModelConnection(
    val stage: ConnectionStage = ConnectionStage.DEVICE,
    val percent: Int? = null,
    val failure: ConnectionFailure? = null
)
internal interface ConnectionReportingEngine : LocalInferenceEngine {
    suspend fun loadModel(model: LocalModelRef, report: (ModelConnection) -> Unit): AiResult<Unit>
    override suspend fun loadModel(model: LocalModelRef): AiResult<Unit> = loadModel(model) {}
}
internal fun connectionFailure(error: AiError): ConnectionFailure = when (error) {
    AiError.OUT_OF_MEMORY -> ConnectionFailure.MEMORY
    AiError.TIMEOUT -> ConnectionFailure.TIMEOUT
    else -> ConnectionFailure.LOAD_FAILED
}

/** Reads the complete identity, reporting only percentages actually hashed. Caller supplies IO context. */
internal suspend fun verifyModelFile(
    file: File,
    bytes: Long,
    sha256: String,
    report: (ModelConnection) -> Unit
): ConnectionFailure? {
    if (!file.isFile) return ConnectionFailure.MODEL_MISSING
    if (file.length() != bytes) return ConnectionFailure.MODEL_SIZE
    val hash = MessageDigest.getInstance("SHA-256")
    var readBytes = 0L
    var lastPercent = -1
    report(ModelConnection(ConnectionStage.VERIFY, 0))
    file.inputStream().use { stream ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = stream.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
            readBytes += count
            val percent = (readBytes * 100 / bytes).toInt().coerceIn(0, 100)
            if (percent != lastPercent) {
                lastPercent = percent
                report(ModelConnection(ConnectionStage.VERIFY, percent))
            }
        }
    }
    currentCoroutineContext().ensureActive()
    val actual = hash.digest().joinToString("") { "%02x".format(it) }
    return if (readBytes == bytes && actual == sha256) null else ConnectionFailure.MODEL_HASH
}
