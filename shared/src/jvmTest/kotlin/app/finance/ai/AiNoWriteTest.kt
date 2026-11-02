package app.finance.ai

import androidx.room.Room
import app.finance.ai.contract.AiResult
import app.finance.ai.contract.InferenceRequest
import app.finance.ai.contract.InferenceText
import app.finance.ai.contract.LocalModelRef
import app.finance.ai.inference.FakeInferenceEngine
import app.finance.ai.parser.AiDraftParser
import app.finance.data.database.FinanceDatabase
import app.finance.data.repository.LedgerStore
import app.finance.data.repository.buildLedgerDatabase
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class AiNoWriteTest {
    @Test fun successfulAndRejectedDraftsLeaveRealRoomLedgerUnchanged() = runBlocking {
        val directory = Files.createTempDirectory("fin016-synthetic-")
        val store = LedgerStore(
            buildLedgerDatabase(
                Room.databaseBuilder<FinanceDatabase>(
                    name = directory.resolve("ledger.db").toString()
                )
            )
        )
        try {
            assertIs<LedgerResult.Success<*>>(store.settings.initialize(settings()))
            assertIs<LedgerResult.Success<*>>(
                store.categories.create(id(1), EntryType.EXPENSE, "CafeFixture")
            )
            val c = context()
            val request = (InferenceRequest.create("Synthetic input", c) as AiResult.Success).value
            for (raw in listOf(ready(transaction()), ready(transaction("0")), "{broken")) {
                val engine = FakeInferenceEngine(raw)
                engine.loadModel((LocalModelRef.create("fixture") as AiResult.Success).value)
                val output = assertIs<AiResult.Success<InferenceText>>(
                    engine.generate(request)
                ).value
                val parsed = AiDraftParser().parse(output.text, c, id(99))
                if (raw == ready(transaction())) {
                    assertIs<AiResult.Success<*>>(parsed)
                } else {
                    assertIs<AiResult.Failure>(parsed)
                }
                engine.unload()
                val overview = (store.transactions.overview() as LedgerResult.Success).value
                assertEquals(0L, overview.count)
                assertEquals(0L, overview.balance.amountMinor)
                assertEquals(
                    0,
                    (store.transactions.history() as LedgerResult.Success).value.items.size
                )
            }
        } finally {
            store.close()
            directory.toFile().deleteRecursively()
        }
    }
}
