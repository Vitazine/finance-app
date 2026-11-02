package app.finance.ai

import app.finance.ai.contract.AiError
import app.finance.ai.contract.AiResult
import app.finance.ai.contract.InferenceRequest
import app.finance.ai.contract.InferenceText
import app.finance.ai.contract.LocalModelRef
import app.finance.ai.inference.FakeInferenceEngine
import app.finance.ai.parser.AiDraftParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

class InferenceContractTest {
    private fun request(text: String = "PrivateSyntheticPrompt") =
        (InferenceRequest.create(text, context()) as AiResult.Success).value
    private fun model() = (LocalModelRef.create("synthetic-fixture") as AiResult.Success).value
    private fun error(result: AiResult<*>, error: AiError) {
        assertEquals(error, assertIs<AiResult.Failure>(result).error)
    }

    @Test fun requestAndOutputAreBoundedAndNeverStringifySensitiveContents() {
        error(InferenceRequest.create("x".repeat(4097), context()), AiError.INPUT_TOO_LONG)
        for (text in listOf("", " \n", "bad\u0000", "bad\ud800", "bad\u202e")) {
            error(InferenceRequest.create(text, context()), AiError.INVALID_REQUEST)
        }
        for (tokens in listOf(0, -1, 2049, Int.MAX_VALUE)) {
            error(InferenceRequest.create("text", context(), tokens), AiError.INVALID_REQUEST)
        }
        assertIs<AiResult.Success<*>>(InferenceRequest.create("x".repeat(4096), context(), 2048))
        assertEquals("InferenceRequest", request().toString())
        assertEquals("LocalModelRef", model().toString())
        assertEquals(
            "InferenceText",
            (InferenceText.create("PrivateOutput") as AiResult.Success).value.toString()
        )
        assertEquals("AiResult.Success", AiResult.Success("PrivateOutput").toString())
        error(InferenceText.create("x".repeat(32769)), AiError.OUTPUT_TOO_LONG)
        assertIs<AiResult.Success<*>>(InferenceText.create("x".repeat(32768)))
    }

    @Test fun modelIdentityIsOpaqueNotAnArbitraryFilesystemPath() {
        for (key in listOf("", "x".repeat(129), "../../private", "file:///tmp/a", "has space")) {
            error(LocalModelRef.create(key), AiError.INVALID_REQUEST)
        }
        assertIs<AiResult.Success<*>>(LocalModelRef.create("x".repeat(128)))
    }

    @Test fun missingModelAndUnloadAreExplicitAndIdempotent() = runBlocking {
        val e = FakeInferenceEngine(ready(transaction()))
        error(e.generate(request()), AiError.MODEL_UNAVAILABLE)
        assertIs<AiResult.Success<*>>(e.loadModel(model()))
        e.unload()
        e.unload()
        error(e.generate(request()), AiError.MODEL_UNAVAILABLE)
    }

    @Test fun fixedFixtureFlowsThroughActualParserWithoutLanguageHeuristics() = runBlocking {
        val raw = ready(transaction())
        val e = FakeInferenceEngine(raw)
        e.loadModel(model())
        val result = assertIs<AiResult.Success<InferenceText>>(e.generate(request())).value
        assertEquals(raw, result.text)
        val d = assertIs<AiResult.Success<*>>(AiDraftParser().parse(result.text, context(), id(99)))
        assertEquals("AiDraft", d.value.toString())
        e.unload()
    }

    @Test fun loadAndGenerationFailuresRemainTypedAndDoNotExposePayload() = runBlocking {
        val e = FakeInferenceEngine("PrivateOutput", loadFailure = AiError.OUT_OF_MEMORY)
        error(e.loadModel(model()), AiError.OUT_OF_MEMORY)
        error(e.generate(request()), AiError.MODEL_UNAVAILABLE)
        e.unload()
        for (failure in listOf(AiError.OUT_OF_MEMORY, AiError.TIMEOUT, AiError.ENGINE_FAILURE)) {
            val f = FakeInferenceEngine("PrivateOutput", generateFailure = failure)
            f.loadModel(model())
            val result = f.generate(request())
            error(result, failure)
            assertTrue(!result.toString().contains("PrivateOutput"))
            f.unload()
        }
        val oversized = FakeInferenceEngine("x".repeat(32769))
        oversized.loadModel(model())
        error(oversized.generate(request()), AiError.OUTPUT_TOO_LONG)
        oversized.unload()
    }

    @Test fun parallelLoadAndGenerateReturnBusyWithoutInterruptingActiveRequest() = runBlocking {
        val e = FakeInferenceEngine("{}", delayMillis = 60000)
        e.loadModel(model())
        val first = async(start = CoroutineStart.UNDISPATCHED) { e.generate(request()) }
        error(e.generate(request()), AiError.BUSY)
        error(e.loadModel(model()), AiError.BUSY)
        assertTrue(first.isActive)
        first.cancelAndJoin()
        e.unload()
    }

    @Test fun unloadCancelsAndAwaitsGenerationBeforeReload() = runBlocking {
        val e = FakeInferenceEngine("{}", delayMillis = 60000)
        e.loadModel(model())
        val first = async(start = CoroutineStart.UNDISPATCHED) { e.generate(request()) }
        e.unload()
        assertTrue(first.isCompleted)
        assertFailsWith<CancellationException> { first.await() }
        error(e.generate(request()), AiError.MODEL_UNAVAILABLE)
        assertIs<AiResult.Success<*>>(e.loadModel(model()))
        e.unload()
    }

    @Test fun callerCancellationReleasesBusySlotWithoutUnloadingModel() = runBlocking {
        val e = FakeInferenceEngine("{}", delayMillis = 60000)
        e.loadModel(model())
        val first = async(start = CoroutineStart.UNDISPATCHED) { e.generate(request()) }
        first.cancelAndJoin()
        assertFailsWith<CancellationException> { first.await() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { e.generate(request()) }
        assertTrue(second.isActive)
        second.cancelAndJoin()
        assertIs<AiResult.Success<*>>(e.loadModel(model()))
        e.unload()
    }
}
