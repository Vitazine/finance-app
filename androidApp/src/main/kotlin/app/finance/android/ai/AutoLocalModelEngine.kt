package app.finance.android.ai

import app.finance.ai.contract.AiError
import app.finance.ai.contract.AiResult
import app.finance.ai.contract.InferenceRequest
import app.finance.ai.contract.InferenceText
import app.finance.ai.contract.LocalInferenceEngine
import app.finance.ai.contract.LocalModelRef
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Only pre-provisioned, app-private owner-selected assets; never downloads or searches external storage. */
internal class AutoLocalModelEngine(
    private val privateRoot: File,
    private val totalRam: () -> Long,
    private val factory: (
        File,
        String
    ) -> LocalInferenceEngine = { file, system -> NativeInferenceEngine(file, system) },
    private val systemSha256: String = SYSTEM_SHA256
) : ConnectionReportingEngine,
    DiagnosticInferenceEngine {
    private val mutex = Mutex()

    @Volatile private var delegate: LocalInferenceEngine? = null
    override suspend fun loadModel(
        model: LocalModelRef,
        report: (ModelConnection) -> Unit
    ): AiResult<Unit> {
        var stage = ConnectionStage.DEVICE
        fun failed(
            reason: ConnectionFailure,
            code: AiError = AiError.MODEL_UNAVAILABLE
        ): AiResult<Unit> {
            report(ModelConnection(stage, failure = reason))
            return AiResult.Failure(code)
        }
        if (!mutex.tryLock()) return AiResult.Failure(AiError.BUSY)
        try {
            report(ModelConnection(stage))
            if (model.key !=
                NativeInferenceEngine.KEY
            ) {
                return failed(ConnectionFailure.LOAD_FAILED)
            }
            if (totalRam() < MIN_RAM) return failed(ConnectionFailure.MEMORY, AiError.OUT_OF_MEMORY)
            stage = ConnectionStage.FILES
            report(ModelConnection(stage))
            val root = privateRoot.canonicalFile
            val paths = listOf(
                "models/day07.gguf" to "models/system.txt",
                "research/day07.gguf" to "research/system.txt"
            )
            val pair = paths.map { File(root, it.first) to File(root, it.second) }
                .firstOrNull { it.first.exists() || it.second.exists() }
                ?: return failed(ConnectionFailure.MODEL_MISSING)
            if (!pair.first.exists()) return failed(ConnectionFailure.MODEL_MISSING)
            if (!pair.second.exists()) return failed(ConnectionFailure.PROMPT_MISSING)
            for (file in listOf(pair.first, pair.second)) {
                if (!file.isFile ||
                    !file.canRead() ||
                    !file.canonicalFile.toPath().startsWith(root.toPath()) ||
                    file.canonicalFile == root
                ) {
                    return failed(ConnectionFailure.FILE_ACCESS)
                }
            }
            stage = ConnectionStage.PROMPT
            report(ModelConnection(stage))
            val bytes = pair.second.inputStream().use { stream ->
                val buffer = ByteArray(32769)
                var count = 0
                while (count < buffer.size) {
                    val read = stream.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                buffer.copyOf(count)
            }
            if (bytes.size > 32768 ||
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                    "%02x".format(it)
                } !=
                systemSha256
            ) {
                return failed(ConnectionFailure.PROMPT_INVALID)
            }
            val system = bytes.decodeToString(throwOnInvalidSequence = true).trim()
            val engine =
                delegate ?: factory(pair.first.canonicalFile, system).also { delegate = it }
            // Native adapter checks the complete model SHA-256 and length.
            stage = ConnectionStage.LOAD
            report(ModelConnection(stage))
            var detailedFailure = false
            val result = if (engine is ConnectionReportingEngine) {
                engine.loadModel(model) { status ->
                    stage = status.stage
                    detailedFailure = status.failure != null
                    report(status)
                }
            } else {
                engine.loadModel(model)
            }
            if (result is AiResult.Failure && !detailedFailure) {
                report(ModelConnection(stage, failure = connectionFailure(result.error)))
            }
            if (result is AiResult.Failure && result.error != AiError.BUSY) {
                engine.unload()
                delegate = null
            }
            return result
        } catch (
            cancel: CancellationException
        ) {
            throw cancel
        } catch (
            _: OutOfMemoryError
        ) {
            return failed(ConnectionFailure.MEMORY, AiError.OUT_OF_MEMORY)
        } catch (
            _: LinkageError
        ) {
            return failed(ConnectionFailure.RUNTIME_MISSING)
        } catch (
            _: Exception
        ) {
            return failed(
                if (stage ==
                    ConnectionStage.LOAD
                ) {
                    ConnectionFailure.LOAD_FAILED
                } else {
                    ConnectionFailure.FILE_ACCESS
                }
            )
        } finally {
            mutex.unlock()
        }
    }
    override suspend fun generate(request: InferenceRequest): AiResult<InferenceText> =
        generate(request, GenerationOptions()) {}
    override suspend fun generate(
        request: InferenceRequest,
        options: GenerationOptions,
        report: (GenerationProgress) -> Unit
    ): AiResult<InferenceText> {
        if (!mutex.tryLock()) return AiResult.Failure(AiError.BUSY)
        try {
            val engine = delegate ?: return AiResult.Failure(AiError.MODEL_UNAVAILABLE)
            return if (engine is DiagnosticInferenceEngine) {
                engine.generate(request, options, report)
            } else {
                engine.generate(request)
            }
        } finally {
            mutex.unlock()
        }
    }
    override suspend fun unload() {
        if (mutex.isLocked) delegate?.unload()
        mutex.withLock {
            delegate?.unload()
            delegate = null
        }
    }
    companion object {
        const val MIN_RAM = 7L * 1024 * 1024 * 1024
        const val SYSTEM_SHA256 = "a9776636b7e4be302ded791753834bd2adc368a87aa1c83bad7eb0587b6ee260"
    }
}
