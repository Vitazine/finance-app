package app.finance.android.ai

import app.finance.ai.contract.AiError
import app.finance.ai.contract.AiResult
import app.finance.ai.contract.InferenceRequest
import app.finance.ai.contract.InferenceText
import app.finance.ai.contract.LocalInferenceEngine
import app.finance.ai.contract.LocalModelRef
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLocalModelEngineTest {
    private val root = Files.createTempDirectory("autoconnect-").toFile()
    private val reference = (
        LocalModelRef.create(
            NativeInferenceEngine.KEY
        ) as AiResult.Success
        ).value
    private val prompt = "Synthetic fixture instructions"
    private val promptHash = MessageDigest.getInstance(
        "SHA-256"
    ).digest(prompt.toByteArray()).joinToString("") {
        "%02x".format(it)
    }
    private val engine = RecordingEngine()
    private var factories = 0
    private fun adapter(ram: Long = 8L * 1024 * 1024 * 1024) =
        AutoLocalModelEngine(root, { ram }, { file, text ->
            assertEquals("model.gguf", file.name)
            assertEquals(prompt, text)
            factories++
            engine
        }, promptHash)
    private fun provision() {
        File(root, "models/day09-iteration1-q4-k-m").mkdirs()
        File(
            root,
            "models/day09-iteration1-q4-k-m/model.gguf"
        ).writeText("Test-only delegated artifact")
        File(root, "models/day09-iteration1-q4-k-m/system.txt").writeText(prompt)
    }
    private fun failure(result: AiResult<*>, error: AiError) =
        assertEquals(error, (result as AiResult.Failure).error)

    @After fun cleanup() {
        root.deleteRecursively()
    }

    @Test fun discoversProvisionedAssetsAndDelegatesRealLoadPortWithoutGenerating() = runBlocking {
        provision()
        val adapter = adapter()
        assertTrue(adapter.loadModel(reference) is AiResult.Success)
        assertEquals(1, factories)
        assertEquals(1, engine.loads)
        assertEquals(0, engine.generations)
        adapter.unload()
        assertEquals(1, engine.unloads)
    }

    @Test fun legacyDay07AssetsAreNotSilentlyUsedForDay09() = runBlocking {
        File(root, "research").mkdirs()
        File(root, "research/day07.gguf").writeText("Old weights")
        File(root, "research/system.txt").writeText(prompt)
        failure(adapter().loadModel(reference), AiError.MODEL_UNAVAILABLE)
        assertEquals(0, factories)
    }

    @Test fun missingAssetsAreUnavailableAndDoNotCreateEngine() = runBlocking {
        failure(adapter().loadModel(reference), AiError.MODEL_UNAVAILABLE)
        assertEquals(0, factories)
    }

    @Test fun wrongPromptIdentityAndOversizedPromptFailBeforeNativeLoad() = runBlocking {
        provision()
        File(root, "models/day09-iteration1-q4-k-m/system.txt").writeText("altered")
        failure(adapter().loadModel(reference), AiError.MODEL_UNAVAILABLE)
        File(root, "models/day09-iteration1-q4-k-m/system.txt").writeText("x".repeat(32769))
        failure(adapter().loadModel(reference), AiError.MODEL_UNAVAILABLE)
        assertEquals(0, factories)
    }

    @Test fun insufficientRamDoesNotAttemptNativeAllocation() = runBlocking {
        provision()
        failure(adapter(4L * 1024 * 1024 * 1024).loadModel(reference), AiError.OUT_OF_MEMORY)
        assertEquals(0, factories)
    }

    @Test fun symlinkOutsidePrivateRootIsRejected() = runBlocking {
        provision()
        val outside = Files.createTempFile("outside-model-", ".gguf")
        try {
            val model = File(root, "models/day09-iteration1-q4-k-m/model.gguf")
            model.delete()
            Files.createSymbolicLink(model.toPath(), outside)
            failure(adapter().loadModel(reference), AiError.MODEL_UNAVAILABLE)
            assertEquals(0, factories)
        } finally {
            Files.deleteIfExists(outside)
        }
    }

    @Test fun delegateLoadFailureIsNotReportedReadyAndResourcesAreReleased() = runBlocking {
        provision()
        engine.fail = true
        failure(adapter().loadModel(reference), AiError.ENGINE_FAILURE)
        assertEquals(1, engine.unloads)
    }

    @Test fun reportsMissingModelAndPromptSeparately() = runBlocking {
        val adapter = adapter()
        val statuses = mutableListOf<ModelConnection>()
        adapter.loadModel(reference, statuses::add)
        assertEquals(ConnectionFailure.MODEL_MISSING, statuses.last().failure)
        provision()
        File(root, "models/day09-iteration1-q4-k-m/system.txt").delete()
        statuses.clear()
        adapter.loadModel(reference, statuses::add)
        assertEquals(ConnectionFailure.PROMPT_MISSING, statuses.last().failure)
        assertEquals(0, factories)
    }

    @Test fun reportsPromptMismatchAndOrderedStagesBeforeDelegating() = runBlocking {
        provision()
        val adapter = adapter()
        val statuses = mutableListOf<ModelConnection>()
        File(root, "models/day09-iteration1-q4-k-m/system.txt").writeText("altered")
        adapter.loadModel(reference, statuses::add)
        assertEquals(ConnectionFailure.PROMPT_INVALID, statuses.last().failure)
        assertEquals(0, factories)
        File(root, "models/day09-iteration1-q4-k-m/system.txt").writeText(prompt)
        statuses.clear()
        adapter.loadModel(reference, statuses::add)
        assertEquals(
            listOf(
                ConnectionStage.DEVICE,
                ConnectionStage.FILES,
                ConnectionStage.PROMPT,
                ConnectionStage.LOAD
            ),
            statuses.map { it.stage }
        )
        assertEquals(1, factories)
    }

    @Test fun nativeAdapterReportsWrongSizeBeforeLoadingAnyLibrary() = runBlocking {
        val file = File(root, "wrong.gguf").apply { writeText("fixture") }
        val statuses = mutableListOf<ModelConnection>()
        val adapter = NativeInferenceEngine(file, prompt)
        failure(adapter.loadModel(reference, statuses::add), AiError.MODEL_UNAVAILABLE)
        assertEquals(ConnectionFailure.MODEL_SIZE, statuses.last().failure)
        adapter.unload()
    }

    @Test fun verifiesEveryByteAndReportsRealPercentAndHashMismatch() = runBlocking {
        val file = File(root, "hash.gguf").apply { writeBytes(ByteArray(3 * 1024 * 1024) { 42 }) }
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        val statuses = mutableListOf<ModelConnection>()
        assertEquals(null, verifyModelFile(file, file.length(), sha, statuses::add))
        assertEquals(listOf(0, 33, 66, 100), statuses.map { it.percent })
        file.outputStream().use { it.write(ByteArray(3 * 1024 * 1024) { 43 }) }
        assertEquals(ConnectionFailure.MODEL_HASH, verifyModelFile(file, file.length(), sha) {})
    }

    @Test fun cancelWhileHashingDoesNotReportCompletion() = runBlocking {
        val file = File(root, "cancel.gguf").apply { writeBytes(ByteArray(3 * 1024 * 1024)) }
        val statuses = mutableListOf<ModelConnection>()
        val job = launch {
            verifyModelFile(file, file.length(), "unused") {
                statuses.add(it)
                if (it.percent == 33) cancel()
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf(0, 33), statuses.map { it.percent })
    }

    private class RecordingEngine : LocalInferenceEngine {
        var loads = 0
        var unloads = 0
        var generations = 0
        var fail = false
        override suspend fun loadModel(model: LocalModelRef): AiResult<Unit> {
            loads++
            return if (fail) AiResult.Failure(AiError.ENGINE_FAILURE) else AiResult.Success(Unit)
        }
        override suspend fun generate(request: InferenceRequest): AiResult<InferenceText> {
            generations++
            return AiResult.Failure(AiError.ENGINE_FAILURE)
        }
        override suspend fun unload() {
            unloads++
        }
    }
}
