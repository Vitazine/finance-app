package app.finance.android

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.finance.ai.contract.AiContext
import app.finance.ai.contract.AiResult
import app.finance.ai.contract.InferenceRequest
import app.finance.ai.contract.LocalModelRef
import app.finance.android.ai.GenerationOptions
import app.finance.android.ai.GenerationPhase
import app.finance.android.ai.GenerationProgress
import app.finance.android.ai.NativeInferenceEngine
import app.finance.core.money.CurrencyCode
import app.finance.core.money.MoneyResult
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/** Explicit local experiment, synthetic input, no ledger access and no raw text in the report. */
class DiagnosticInferenceRuntimeTest {
    @Test fun provisionedModelReportsMeasuredStagesAndBoundedOutcome() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue(
            "Explicit diagnostic model paths required",
            args.containsKey("diagnosticModel")
        )
        Assume.assumeTrue(BuildConfig.DEBUG && BuildConfig.NATIVE_AI)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = NativeInferenceEngine(
            File(args.getString("modelPath")!!),
            File(args.getString("systemPath")!!).readText()
        )
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
                "Купил шаурму за 250 RUB 2026-11-02",
                aiContext,
                256
            ) as AiResult.Success
            ).value
        val samples = JSONArray()
        val options = GenerationOptions(diagnostic = args.getString("ordinaryGeneration") != "true")
        val report = JSONObject().put("diagnostic", options.diagnostic)
            .put("deadline_ms", options.timeoutMs)
            .put("model_id", NativeInferenceEngine.KEY).put("sha256", NativeInferenceEngine.SHA256)
            .put("samples", samples).put("unconfirmed_writes", 0)
        val target = File(context.filesDir, "debug-inference-metrics.json")
        var last = GenerationProgress()
        var prefillObserved = false
        fun save() {
            target.writeText(report.toString(2))
        }
        try {
            val load = SystemClock.elapsedRealtime()
            assertTrue("Provisioned artifact must load", engine.loadModel(ref) is AiResult.Success)
            report.put("load_ms", SystemClock.elapsedRealtime() - load)
            save()
            val begun = SystemClock.elapsedRealtime()
            val result = engine.generate(request, options) { p ->
                last = p
                if (p.phase == GenerationPhase.PREFILL) prefillObserved = true
                if (samples.length() < 1210) {
                    samples.put(
                        JSONObject()
                            .put("phase", p.phase.name).put("elapsed_ms", p.elapsedMs)
                            .put(
                                "prompt_tokens",
                                p.promptTokens
                            ).put("processed_tokens", p.processedTokens)
                            .put("output_tokens", p.outputTokens).put(
                                "first_token_ms",
                                p.firstTokenMs ?: JSONObject.NULL
                            )
                            .put("pss_kib", p.pssKiB)
                    )
                }
                save()
            }
            val outcome = if (result is AiResult.Success) {
                "VALIDATED_OUTPUT"
            } else {
                (result as AiResult.Failure).error.name
            }
            report.put("wall_ms", SystemClock.elapsedRealtime() - begun)
                .put("outcome", outcome)
                .put("prefill_observed", prefillObserved)
            save()
            assertTrue("Native counters must identify the input context", last.promptTokens > 0)
            assertTrue("Elapsed time must be measured", last.elapsedMs > 0)
            if (!options.diagnostic) assertEquals("PSS is diagnostic-only", 0, last.pssKiB)
            assertTrue(
                "Live native phase must be observed",
                prefillObserved || last.outputTokens > 0
            )
            assertTrue(
                "No raw request/output is retained",
                !report.has("prompt") && !report.has("output")
            )
        } finally {
            engine.unload()
            report.put("unloaded", true)
            save()
        }
    }
}
