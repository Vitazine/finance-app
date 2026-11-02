package app.finance.android

import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.finance.ai.contract.AiContext
import app.finance.ai.contract.AiError
import app.finance.ai.contract.AiResult
import app.finance.ai.contract.InferenceRequest
import app.finance.ai.contract.LocalModelRef
import app.finance.ai.parser.AiDraftParser
import app.finance.android.ai.NativeInferenceEngine
import app.finance.core.money.CurrencyCode
import app.finance.core.money.MoneyResult
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

class NativeInferenceTest {
    @Test fun missingArtifactIsSanitizedAndDoesNotRequireNativeLibrary() = runBlocking {
        val engine = NativeInferenceEngine(File("/nonexistent/finance-model.gguf"), "fixture")
        val ref = (LocalModelRef.create(NativeInferenceEngine.KEY) as AiResult.Success).value
        assertEquals(AiError.MODEL_UNAVAILABLE, (engine.loadModel(ref) as AiResult.Failure).error)
        engine.unload()
    }

    @Test fun actualProvisionedModelBenchmark() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue("Explicit research model path required", args.containsKey("modelPath"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val system = File(args.getString("systemPath")!!).readText()
        val engine = NativeInferenceEngine(File(args.getString("modelPath")!!), system)
        val ref = (LocalModelRef.create(NativeInferenceEngine.KEY) as AiResult.Success).value
        val usd = (CurrencyCode.resolve("USD") as MoneyResult.Success).value
        val settings = (LedgerSettings.create(usd, "UTC") as LedgerResult.Success).value
        val aiContext = (
            AiContext.create(
                Instant.parse("2026-11-02T12:00:00Z"),
                settings,
                emptyList()
            ) as AiResult.Success
            ).value
        val request = (
            InferenceRequest.create(
                "Кофе 250 рублей сегодня",
                aiContext,
                256
            ) as AiResult.Success
            ).value
        val report = JSONObject().put(
            "device",
            "AOSP API36 arm64 emulator, 8GiB; NOT physical qualification"
        )
            .put("sha256", NativeInferenceEngine.SHA256).put("runs", JSONArray())
        val file = File(context.filesDir, "day07-native-benchmark.json")
        fun save() {
            file.writeText(report.toString(2))
        }
        try {
            val start = SystemClock.elapsedRealtime()
            val loaded = engine.loadModel(ref)
            report.put(
                "load_ms",
                SystemClock.elapsedRealtime() - start
            ).put("loaded", loaded is AiResult.Success)
            save()
            assertTrue("Actual local model must load", loaded is AiResult.Success)
            var peakPss = 0
            val sampler = launch(Dispatchers.Default) {
                while (isActive) {
                    val info = Debug.MemoryInfo()
                    Debug.getMemoryInfo(info)
                    peakPss =
                        maxOf(peakPss, info.totalPss)
                    delay(200)
                }
            }
            try {
                repeat(args.getString("repeats")?.toInt() ?: 1) { index ->
                    val begun = SystemClock.elapsedRealtime()
                    val result = engine.generate(request)
                    val row = JSONObject().put("index", index).put(
                        "total_ms",
                        SystemClock.elapsedRealtime() - begun
                    )
                        .put("success", result is AiResult.Success)
                    if (result is AiResult.Success) {
                        val draftId = (
                            LedgerId.parse(
                                "00000000-0000-4000-8000-000000000099"
                            ) as LedgerResult.Success
                            ).value
                        val draft = AiDraftParser().parse(result.value.text, aiContext, draftId)
                        row.put("parsed", draft is AiResult.Success)
                        if (draft is AiResult.Success) {
                            row.put(
                                "proposals",
                                draft.value.proposals.size
                            )
                        }
                    } else {
                        row.put("error", (result as AiResult.Failure).error.name)
                    }
                    val native = engine.researchTimings()
                    row.put(
                        "first_token_us",
                        native[0]
                    ).put("native_total_us", native[1]).put("output_tokens", native[2])
                    report.getJSONArray("runs").put(row)
                    report.put("peak_pss_kib", peakPss)
                    save()
                    assertTrue(
                        "Real generation failed; see sanitized benchmark",
                        result is AiResult.Success
                    )
                }
                val job = launch(Dispatchers.Default) { engine.generate(request) }
                delay(1000)
                val cancelStart = SystemClock.elapsedRealtime()
                job.cancelAndJoin()
                report.put("cancel_ms", SystemClock.elapsedRealtime() - cancelStart)
                save()
            } finally {
                sampler.cancelAndJoin()
            }
        } finally {
            engine.unload()
            report.put("unloaded", true)
            save()
        }
    }
}
