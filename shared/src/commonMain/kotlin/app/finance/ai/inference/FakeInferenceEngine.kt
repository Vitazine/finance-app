package app.finance.ai.inference

import app.finance.ai.contract.AiError
import app.finance.ai.contract.AiResult
import app.finance.ai.contract.InferenceRequest
import app.finance.ai.contract.InferenceText
import app.finance.ai.contract.LocalInferenceEngine
import app.finance.ai.contract.LocalModelRef
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Explicit fixtures only; never a default production engine or a natural-language recognizer. */
class FakeInferenceEngine(
    private val response: String,
    private val loadFailure: AiError? = null,
    private val generateFailure: AiError? = null,
    private val delayMillis: Long = 0
) : LocalInferenceEngine {
    init {
        require(delayMillis in 0..60000)
    }
    private val state = Mutex()
    private val lifecycle = Mutex()
    private var loaded = false
    private var unloading = false
    private var active: Job? = null

    override suspend fun loadModel(model: LocalModelRef): AiResult<Unit> {
        currentCoroutineContext().ensureActive()
        return state.withLock {
            when {
                unloading || active != null -> AiResult.Failure(AiError.BUSY)
                loadFailure != null -> AiResult.Failure(loadFailure)
                else -> {
                    loaded = true
                    AiResult.Success(Unit)
                }
            }
        }
    }
    override suspend fun generate(request: InferenceRequest): AiResult<InferenceText> =
        coroutineScope {
            val job = currentCoroutineContext().job
            job.ensureActive()
            val error = state.withLock {
                when {
                    unloading || active != null -> AiError.BUSY
                    !loaded -> AiError.MODEL_UNAVAILABLE
                    else -> {
                        active = job
                        null
                    }
                }
            }
            if (error != null) return@coroutineScope AiResult.Failure(error)
            try {
                delay(delayMillis)
                job.ensureActive()
                generateFailure?.let { AiResult.Failure(it) } ?: InferenceText.create(response)
            } finally {
                withContext(NonCancellable) {
                    state.withLock { if (active === job) active = null }
                }
            }
        }
    override suspend fun unload(): Unit = withContext(NonCancellable) {
        lifecycle.withLock {
            val job = state.withLock {
                unloading = true
                loaded = false
                active?.also { it.cancel() }
            }
            try {
                job?.join()
            } finally {
                state.withLock { unloading = false }
            }
            Unit
        }
    }
}
