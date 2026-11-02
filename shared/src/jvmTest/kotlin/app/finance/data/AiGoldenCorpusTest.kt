package app.finance.data

import app.finance.ai.contract.AiContext
import app.finance.ai.contract.AiResult
import app.finance.ai.parser.AiDraftParser
import app.finance.core.money.CurrencyCode
import app.finance.core.money.MoneyResult
import app.finance.domain.ledger.Category
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Checks corpus/expected-parser compatibility, never claims language-model accuracy. */
class AiGoldenCorpusTest {
    @Test fun all24SyntheticExpectedOutputsAreAcceptedWithoutPostingCapability() {
        val corpus = Json.parseToJsonElement(
            Path.of("../docs/testing/ai-golden-v1.json").toFile().readText()
        ).jsonObject
        val c = corpus.getValue("context").jsonObject
        fun text(key: String) = c.getValue(key).jsonPrimitive.content
        fun id(value: String) = (LedgerId.parse(value) as LedgerResult.Success).value
        val currency = (CurrencyCode.resolve(text("base_currency")) as MoneyResult.Success).value
        val settings = (LedgerSettings.create(currency, text("zone")) as LedgerResult.Success).value
        val categories = c.getValue("categories").jsonArray.map { row ->
            val obj = row.jsonObject
            (
                Category.create(
                    id(obj.getValue("id").jsonPrimitive.content),
                    if (obj.getValue("type").jsonPrimitive.content ==
                        "expense"
                    ) {
                        EntryType.EXPENSE
                    } else {
                        EntryType.INCOME
                    },
                    obj.getValue("name").jsonPrimitive.content
                ) as LedgerResult.Success
                ).value
        }
        val context = (
            AiContext.create(
                Instant.parse(text("reference")),
                settings,
                categories
            ) as AiResult.Success
            ).value
        val cases = corpus.getValue("cases").jsonArray
        assertEquals(24, cases.size)
        for (row in cases) {
            val expected = row.jsonObject.getValue("expected").jsonObject
            val draft = assertIs<AiResult.Success<*>>(
                AiDraftParser().parse(
                    expected.toString(),
                    context,
                    id("00000000-0000-4000-8000-000000000099")
                ),
                row.jsonObject.getValue("id").toString()
            ).value as app.finance.ai.contract.AiDraft
            assertEquals(expected.getValue("transactions").jsonArray.size, draft.proposals.size)
        }
    }
}
