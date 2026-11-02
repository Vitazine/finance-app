package app.finance.ai

import app.finance.ai.contract.AiDraft
import app.finance.ai.contract.AiError
import app.finance.ai.contract.AiResult
import app.finance.ai.contract.DraftStatus
import app.finance.ai.parser.AiDraftParser
import app.finance.ai.parser.Day07Protocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Day07ProtocolTest {
    private val protocol = Day07Protocol()
    private val ok = """{"category":"cafes","date":"2026-11-02","payment_method":"card","clari""" +
"""fication_reason":null,"amount":"12.50","status":"ok","currency":"USD"}"""

    @Test fun exactExpenseRemainsUncommittedWithUnresolvedCategoryAndEmptyDescription() {
        val converted = assertIs<AiResult.Success<String>>(protocol.toV1(ok, context())).value
        val draft = assertIs<AiResult.Success<AiDraft>>(
            AiDraftParser().parse(converted, context(), id(99))
        ).value
        assertEquals(1250L, draft.proposals.single().money.amountMinor)
        assertNull(draft.proposals.single().categoryId)
        assertEquals("", draft.proposals.single().description)
        assertEquals("cafes", draft.proposals.single().categoryHint)
        assertEquals(DraftStatus.NEEDS_CLARIFICATION, draft.status)
    }

    @Test fun ignoredAndClarificationProduceNoProposals() {
        val ignored = """{"category":null,"date":null,"payment_method":null,"clarification_reas""" +
"""on":null,"amount":null,"status":"ignored","currency":null}"""
        val missing = ignored.replace(
            "\"ignored\"",
            "\"needs_clarification\""
        ).replace("\"clarification_reason\":null", "\"clarification_reason\":\"missing_context\"")
        for ((raw, status) in listOf(
            ignored to DraftStatus.UNSUPPORTED,
            missing to DraftStatus.NEEDS_CLARIFICATION
        )) {
            val json = assertIs<AiResult.Success<String>>(protocol.toV1(raw, context())).value
            val draft = assertIs<AiResult.Success<AiDraft>>(
                AiDraftParser().parse(json, context(), id(99))
            ).value
            assertEquals(status, draft.status)
            assertTrue(draft.proposals.isEmpty())
        }
    }

    @Test fun invalidMoneyDateEnumsTypesAndExtraKeysFailClosed() {
        for (raw in listOf(
            ok.replace(
                "12.50",
                "0.00"
            ),
            ok.replace("12.50", "9223372036854775808.00"), ok.replace("12.50", "12.5"),
            ok.replace(
                "2026-11-02",
                "2026-11-03"
            ),
            ok.replace("cafes", "income"), ok.replace("card", "bank"),
            ok.replace(
                "\"12.50\"",
                "12.50"
            ),
            ok.replace("\"USD\"", "\"JPY\""), ok.replace("\"ok\"", "\"ready\""),
            ok.dropLast(1) + ",\"extra\":null}"
        )) {
            assertIs<AiResult.Failure>(protocol.toV1(raw, context()))
        }
    }

    @Test fun duplicateKeysAndOversizeAreRejectedBeforeAst() {
        assertEquals(
            AiError.DUPLICATE_KEY,
            assertIs<AiResult.Failure>(
                protocol.toV1(
                    ok.dropLast(1) + ",\"status\":\"ok\"}",
                    context()
                )
            ).error
        )
        assertEquals(
            AiError.OUTPUT_TOO_LONG,
            assertIs<AiResult.Failure>(protocol.toV1(" ".repeat(32769), context())).error
        )
    }

    @Test fun contradictingStatusesFailClosed() {
        for (raw in listOf(
            ok.replace("\"ok\"", "\"ignored\""),
            ok.replace("\"ok\"", "\"needs_clarification\""),
            ok.replace(
                "\"ok\"",
                "\"needs_clarification\""
            ).replace(
                "\"clarification_reason\":null",
                "\"clarification_reason\":\"missing_amount\""
            )
        )) {
            assertIs<AiResult.Failure>(protocol.toV1(raw, context()))
        }
    }
}
