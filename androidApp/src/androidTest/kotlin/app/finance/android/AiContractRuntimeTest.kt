package app.finance.android

import androidx.test.platform.app.InstrumentationRegistry
import app.finance.ai.contract.AiContext
import app.finance.ai.contract.AiDraft
import app.finance.ai.contract.AiError
import app.finance.ai.contract.AiResult
import app.finance.ai.contract.DraftStatus
import app.finance.ai.contract.InferenceRequest
import app.finance.ai.contract.InferenceText
import app.finance.ai.contract.LocalModelRef
import app.finance.ai.inference.FakeInferenceEngine
import app.finance.ai.parser.AiDraftParser
import app.finance.core.money.CurrencyCode
import app.finance.core.money.MoneyResult
import app.finance.data.database.openLedgerStore
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiContractRuntimeTest {
    private fun id(n: Int) = (
        LedgerId.parse(
            "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
        ) as LedgerResult.Success
        ).value
    private val raw = """
        {"schema_version":1,
        "status":"ready",
        "transactions":[{"type":"expense",
        "amount":"1.25",
        "currency":"USD",
        "category_hint":"NativeFixture",
        "date":"2026-11-02",
        "description":"SyntheticNativeNote"}],
        "missing_fields":[]}
    """.trimIndent()

    @Test fun nativeFakeParserCancellationAndNoWritesOnRealRoom() = runBlocking {
        val android = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "fin016-native-" + UUID.randomUUID() + ".db"
        val store = openLedgerStore(android, name)
        try {
            val usd = (CurrencyCode.resolve("USD") as MoneyResult.Success).value
            val settings = (LedgerSettings.create(usd, "Asia/Manila") as LedgerResult.Success).value
            store.settings.initialize(settings)
            val category = (
                store.categories.create(
                    id(1),
                    EntryType.EXPENSE,
                    "NativeFixture"
                ) as LedgerResult.Success
                ).value
            val context = (
                AiContext.create(
                    Instant.parse("2026-11-01T16:00:00Z"),
                    settings,
                    listOf(category)
                ) as AiResult.Success
                ).value
            assertEquals("2026-11-02", context.localDate.toString())
            val request = (
                InferenceRequest.create(
                    "Synthetic input",
                    context
                ) as AiResult.Success
                ).value
            val model = (LocalModelRef.create("native-fixture") as AiResult.Success).value
            val duplicate = raw.replace(
                "\"amount\":\"1.25\"",
                "\"amount\":\"0\",\"\\u0061mount\":\"1.25\""
            )
            for (output in listOf(raw, duplicate)) {
                val engine = FakeInferenceEngine(output)
                engine.loadModel(model)
                val text = (engine.generate(request) as AiResult.Success<InferenceText>).value
                val result = AiDraftParser().parse(text.text, context, id(99))
                if (output == raw) {
                    val draft = (result as AiResult.Success<AiDraft>).value
                    assertEquals(DraftStatus.READY, draft.status)
                    assertEquals(125L, draft.proposals.single().money.amountMinor)
                } else {
                    assertEquals(AiError.DUPLICATE_KEY, (result as AiResult.Failure).error)
                }
                engine.unload()
            }
            val delayed = FakeInferenceEngine(raw, delayMillis = 60000)
            delayed.loadModel(model)
            val active = async(start = CoroutineStart.UNDISPATCHED) { delayed.generate(request) }
            delayed.unload()
            assertTrue(active.isCancelled && active.isCompleted)
            assertEquals(
                AiError.MODEL_UNAVAILABLE,
                (delayed.generate(request) as AiResult.Failure).error
            )
            val overview = (store.transactions.overview() as LedgerResult.Success).value
            assertEquals(0L, overview.count)
            assertEquals(0L, overview.balance.amountMinor)
        } finally {
            store.close()
            android.deleteDatabase(name)
        }
    }
}
