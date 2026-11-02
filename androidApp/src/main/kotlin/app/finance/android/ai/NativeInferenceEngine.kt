package app.finance.android.ai

import android.os.Debug
import app.finance.ai.contract.AiError
import app.finance.ai.contract.AiResult
import app.finance.ai.contract.InferenceRequest
import app.finance.ai.contract.InferenceText
import app.finance.ai.contract.LocalModelRef
import app.finance.ai.parser.Day07Protocol
import app.finance.android.BuildConfig
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Research adapter. Only explicitly provisioned, exact-hash local weights are accepted. */
internal class NativeInferenceEngine(private val file: File, private val systemPrompt: String) :
    ConnectionReportingEngine,
    DiagnosticInferenceEngine {
    private val mutex = Mutex()
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Any()

    @Volatile private var handle = 0L
    private fun cancelNative() = synchronized(gate) {
        if (handle !=
            0L
        ) {
            NativeBridge.cancel(handle)
        }
    }
    private fun closeNative() = synchronized(gate) {
        if (handle !=
            0L
        ) {
            NativeBridge.close(handle)
            handle = 0L
        }
    }
    private suspend fun <T> nativeWork(timeoutMs: Long = 120_000, block: () -> T): T {
        val job = worker.async { block() }
        return try {
            withTimeout(timeoutMs) { job.await() }
        } catch (cancel: CancellationException) {
            cancelNative()
            withContext(NonCancellable) { job.join() }
            throw cancel
        }
    }
    override suspend fun loadModel(
        model: LocalModelRef,
        report: (ModelConnection) -> Unit
    ): AiResult<Unit> {
        var stage = ConnectionStage.VERIFY
        fun failed(reason: ConnectionFailure, error: AiError): AiResult<Unit> {
            report(ModelConnection(stage, failure = reason))
            return AiResult.Failure(error)
        }
        if (!mutex.tryLock()) return AiResult.Failure(AiError.BUSY)
        try {
            if (model.key !=
                KEY
            ) {
                return failed(ConnectionFailure.LOAD_FAILED, AiError.MODEL_UNAVAILABLE)
            }
            if (handle != 0L) return AiResult.Success(Unit)
            val invalid = withContext(Dispatchers.IO) {
                verifyModelFile(file, BYTES, SHA256, report)
            }
            if (invalid != null) return failed(invalid, AiError.MODEL_UNAVAILABLE)
            stage = ConnectionStage.LOAD
            report(ModelConnection(stage))
            val h = NativeBridge.create()
            if (h == 0L) return failed(ConnectionFailure.MEMORY, AiError.OUT_OF_MEMORY)
            synchronized(gate) { handle = h }
            val loaded =
                nativeWork { NativeBridge.load(h, file.absolutePath.toByteArray(Charsets.UTF_8)) }
            if (!loaded) {
                closeNative()
                return failed(ConnectionFailure.LOAD_FAILED, AiError.ENGINE_FAILURE)
            }
            return AiResult.Success(Unit)
        } catch (timeout: TimeoutCancellationException) {
            closeNative()
            return failed(ConnectionFailure.TIMEOUT, AiError.TIMEOUT)
        } catch (cancel: CancellationException) {
            closeNative()
            throw cancel
        } catch (
            _: OutOfMemoryError
        ) {
            return failed(ConnectionFailure.MEMORY, AiError.OUT_OF_MEMORY)
        } catch (
            _: LinkageError
        ) {
            return failed(ConnectionFailure.RUNTIME_MISSING, AiError.MODEL_UNAVAILABLE)
        } catch (
            _: Exception
        ) {
            return failed(
                if (stage ==
                    ConnectionStage.VERIFY
                ) {
                    ConnectionFailure.FILE_ACCESS
                } else {
                    ConnectionFailure.LOAD_FAILED
                },
                AiError.ENGINE_FAILURE
            )
        } finally {
            mutex.unlock()
        }
    }
    override suspend fun generate(request: InferenceRequest): AiResult<InferenceText> =
        generate(request, GenerationOptions()) {}

    private fun progress(expectedGeneration: Long): GenerationProgress? = synchronized(gate) {
        if (handle == 0L) return@synchronized null
        val raw = NativeBridge.progress(handle)
        if (raw.getOrNull(7) != expectedGeneration) return@synchronized null
        GenerationProgress.fromNative(
            raw,
            Debug.getPss().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        )
    }

    override suspend fun generate(
        request: InferenceRequest,
        options: GenerationOptions,
        report: (GenerationProgress) -> Unit
    ): AiResult<InferenceText> {
        if (!mutex.tryLock()) return AiResult.Failure(AiError.BUSY)
        try {
            val h = handle
            if (h == 0L) return AiResult.Failure(AiError.MODEL_UNAVAILABLE)
            if (systemPrompt.length !in 1..12000 || systemPrompt.contains("<|")) {
                return AiResult.Failure(AiError.INVALID_CONTEXT)
            }
            val effective = options.effective(BuildConfig.DEBUG)
            val nextGeneration = if (effective.diagnostic) {
                synchronized(gate) { NativeBridge.progress(h)[7] + 1 }
            } else {
                0L
            }
            val prompt = Day07Protocol().prompt(request, systemPrompt)
            val raw = coroutineScope {
                val monitor: Job? = if (effective.diagnostic) {
                    launch(Dispatchers.IO) {
                        while (isActive) {
                            progress(nextGeneration)?.let(report)
                            delay(500)
                        }
                    }
                } else {
                    null
                }
                try {
                    nativeWork(effective.timeoutMs) {
                        NativeBridge.generate(
                            h,
                            prompt.toByteArray(Charsets.UTF_8),
                            minOf(256, request.maxOutputTokens)
                        )
                    }
                } finally {
                    withContext(NonCancellable) {
                        monitor?.cancelAndJoin()
                        if (effective.diagnostic) progress(nextGeneration)?.let(report)
                    }
                }
            } ?: return AiResult.Failure(AiError.ENGINE_FAILURE)
            val text = try {
                raw.toString(Charsets.UTF_8)
            } finally {
                raw.fill(0)
            }
            return when (val result = Day07Protocol().toV1(text, request.context)) {
                is AiResult.Failure -> result
                is AiResult.Success -> InferenceText.create(result.value)
            }
        } catch (timeout: TimeoutCancellationException) {
            return AiResult.Failure(AiError.TIMEOUT)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: OutOfMemoryError) {
            return AiResult.Failure(AiError.OUT_OF_MEMORY)
        } catch (_: Exception) {
            return AiResult.Failure(AiError.ENGINE_FAILURE)
        } finally {
            mutex.unlock()
        }
    }
    override suspend fun unload() {
        cancelNative()
        withContext(NonCancellable + Dispatchers.IO) {
            mutex.withLock {
                closeNative()
            }
        }
    }
    fun researchTimings(): LongArray = synchronized(gate) {
        if (handle ==
            0L
        ) {
            longArrayOf(0, 0, 0)
        } else {
            NativeBridge.timings(handle)
        }
    }
    companion object {
        const val KEY = "day07-iteration4-q4-k-m"
        const val BYTES = 2497279040L
        const val SHA256 = "11bb8bdadec9baf9d0a1a91e0ef312c787cd967f95ac261d82ee283fdf10d699"
    }
}
internal object NativeBridge {
    init {
        System.loadLibrary("finance_inference")
    }
    external fun timings(handle: Long): LongArray
    external fun progress(handle: Long): LongArray
    external fun create(): Long
    external fun load(handle: Long, path: ByteArray): Boolean
    external fun generate(handle: Long, prompt: ByteArray, limit: Int): ByteArray?
    external fun cancel(handle: Long)
    external fun close(handle: Long)
}
