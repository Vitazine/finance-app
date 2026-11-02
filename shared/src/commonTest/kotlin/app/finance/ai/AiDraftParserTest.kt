package app.finance.ai

import app.finance.ai.contract.AiDraft
import app.finance.ai.contract.AiError
import app.finance.ai.contract.AiResult
import app.finance.ai.contract.DraftField
import app.finance.ai.contract.DraftStatus
import app.finance.ai.contract.ProposalIssue
import app.finance.ai.parser.AiDraftParser
import app.finance.domain.ledger.EntryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiDraftParserTest {
    private val parser = AiDraftParser()
    private fun parse(raw: String) = parser.parse(raw, context(), id(99))
    private fun success(raw: String): AiDraft =
        assertIs<AiResult.Success<AiDraft>>(parse(raw)).value
    private fun failure(raw: String, error: AiError? = null) {
        val failure = assertIs<AiResult.Failure>(parse(raw))
        if (error != null) assertEquals(error, failure.error)
        assertTrue(!failure.toString().contains("SyntheticNote"))
    }

    @Test fun readyMultipleProposalsKeepExactAmountsDatesTypesAndAppOwnedIdentity() {
        val raw = ready(transaction(), transaction("1.234", "KWD", type = "income"))
        val c = context(listOf(category(), category(2, type = EntryType.INCOME)))
        val draft = assertIs<AiResult.Success<AiDraft>>(parser.parse(raw, c, id(99))).value
        assertEquals(id(99), draft.id)
        assertEquals(listOf(1250L, 1234L), draft.proposals.map { it.money.amountMinor })
        assertEquals(listOf(EntryType.EXPENSE, EntryType.INCOME), draft.proposals.map { it.type })
        assertEquals("2026-11-02", draft.proposals.first().date.toString())
        assertEquals(id(1), draft.proposals.first().categoryId)
        assertEquals("SyntheticNote", draft.proposals.first().description)
        assertEquals(setOf(ProposalIssue.RATE_REQUIRED), draft.proposals.last().issues)
        assertEquals(DraftStatus.NEEDS_CLARIFICATION, draft.status)
        assertEquals("AiDraft", draft.toString())
        assertEquals("AiProposal", draft.proposals.first().toString())
        assertEquals("AiResult.Success", AiResult.Success(draft).toString())
    }

    @Test fun sameCurrencyUniqueActiveCategoryProducesReadyUncommittedProposal() {
        val d = success(ready(transaction()))
        assertEquals(DraftStatus.READY, d.status)
        assertEquals(emptySet(), d.proposals.single().issues)
    }

    @Test fun unknownArchivedWrongTypeAndAmbiguousCategoriesAreFlaggedNeverGuessed() {
        val cases = listOf(
            emptyList(),
            listOf(category(archived = true)),
            listOf(category(type = EntryType.INCOME)),
            listOf(category(), category(2))
        )
        for (cats in cases) {
            val d = assertIs<AiResult.Success<AiDraft>>(
                parser.parse(ready(transaction()), context(cats), id(99))
            ).value
            assertEquals(DraftStatus.NEEDS_CLARIFICATION, d.status)
            assertNull(d.proposals.single().categoryId)
            val issue = if (cats.size == 2) {
                ProposalIssue.CATEGORY_AMBIGUOUS
            } else {
                ProposalIssue.CATEGORY_UNRESOLVED
            }
            assertEquals(setOf(issue), d.proposals.single().issues)
        }
        assertNull(success(ready(transaction(hint = "cafefixture"))).proposals.single().categoryId)
    }

    @Test fun strictPositiveDecimalAndFrozenIsoExponentsHaveNoSilentRounding() {
        for (amount in listOf("0", "-1", "+1", "1e3", "1,20", "NaN", " 1", "1.001")) {
            failure(ready(transaction(amount)), AiError.INVALID_AMOUNT)
        }
        failure(ready(transaction("92233720368547758.08")), AiError.INVALID_AMOUNT)
        failure(ready(transaction("1.1", "JPY")), AiError.INVALID_AMOUNT)
        assertEquals(
            1L,
            success(ready(transaction("1", "JPY"))).proposals.single().money.amountMinor
        )
        assertEquals(
            1234L,
            success(ready(transaction("1.234", "KWD"))).proposals.single().money.amountMinor
        )
        assertEquals(
            Long.MAX_VALUE,
            success(ready(transaction("92233720368547758.07"))).proposals.single().money.amountMinor
        )
        for (code in listOf("usd", "ZZZ", "XAU", "USDD", "")) {
            failure(ready(transaction(code = code)), AiError.INVALID_CURRENCY)
        }
        failure(ready(transaction().replace("\"12.50\"", "12.50")), AiError.INVALID_SCHEMA)
    }

    @Test fun absoluteCanonicalDatesOnlyIncludingLeapYearNoInferredTimeOfDay() {
        assertEquals(
            "2024-02-29",
            success(ready(transaction(date = "2024-02-29"))).proposals.single().date.toString()
        )
        for (date in listOf(
            "2023-02-29",
            "2026-11-31",
            "2026-1-01",
            "yesterday",
            "0000-01-01",
            "+10000-01-01",
            "2026-11-02T12:00:00Z"
        )) {
            failure(ready(transaction(date = date)), AiError.INVALID_DATE)
        }
    }

    @Test fun versionAndClosedEnumsAndEveryRequiredFieldAreEnforced() {
        val raw = ready(transaction())
        for (version in listOf("2", "\"1\"", "1.0", "1e0", "null", "true")) {
            failure(raw.replace("\"schema_version\":1", "\"schema_version\":$version"))
        }
        for (type in listOf(
            "EXPENSE",
            "transfer",
            "refund",
            ""
        )) {
            failure(ready(transaction(type = type)))
        }
        failure(raw.replace("\"ready\"", "\"success\""), AiError.INVALID_STATUS)
        for (field in listOf(
            "type",
            "amount",
            "currency",
            "category_hint",
            "date",
            "description"
        )) {
            val regex = Regex("\\\"$field\\\":\\\"[^\\\"]*\\\",?")
            failure(raw.replace(regex, "").replace(Regex(",\\s*}"), "}"))
        }
        failure(raw.replace("\"description\":\"SyntheticNote\"", "\"description\":null"))
    }

    @Test fun modelCannotInjectIdRateConfirmedToolOrUnknownFields() {
        for (extra in listOf("id", "rate", "confirmed", "tool_calls", "source")) {
            failure(ready(transaction().replace("{", "{\"$extra\":\"untrusted\",")))
        }
        failure(
            ready(
                transaction()
            ).replace("{\"schema_version", "{\"command\":\"untrusted\",\"schema_version")
        )
    }

    @Test fun batchIsAtomicAndBoundedAtTen() {
        assertEquals(10, success(ready(*Array(10) { transaction() })).proposals.size)
        failure(ready(*Array(11) { transaction() }), AiError.TOO_MANY_TRANSACTIONS)
        failure(ready(), AiError.INVALID_STATUS)
        val f = assertIs<AiResult.Failure>(parse(ready(transaction(), transaction("0"))))
        assertEquals(1, f.transactionIndex)
        assertEquals(AiError.INVALID_AMOUNT, f.error)
    }

    @Test fun explicitAbstentionRequiresConsistentEmptyTransactionsAndKnownMissingFields() {
        val raw = """{"schema_version":1,"status":"needs_clarification",""" +
            """"transactions":[],"missing_fields":["amount","date"]}"""
        val d = success(raw)
        assertEquals(DraftStatus.NEEDS_CLARIFICATION, d.status)
        assertEquals(listOf(DraftField.AMOUNT, DraftField.DATE), d.missingFields)
        assertTrue(d.proposals.isEmpty())
        assertEquals(
            DraftStatus.UNSUPPORTED,
            success(
                raw.replace(
                    "needs_clarification",
                    "unsupported"
                ).replace("[\"amount\",\"date\"]", "[]")
            ).status
        )
        for (missing in listOf("[]", "[\"bogus\"]", "[\"amount\",\"amount\"]", "[null]")) {
            failure(raw.replace("[\"amount\",\"date\"]", missing))
        }
        failure(raw.replace("needs_clarification", "ready"))
        failure(raw.replace("needs_clarification", "unsupported"))
        failure(raw.replace("\"transactions\":[]", "\"transactions\":[${transaction()}]"))
    }

    @Test fun duplicatesIncludingEscapedDecodedKeysAreRejectedAtEveryObject() {
        val raw = ready(transaction())
        failure(
            raw.replace("\"schema_version\":1", "\"schema_version\":2,\"schema_version\":1"),
            AiError.DUPLICATE_KEY
        )
        failure(
            raw.replace("\"amount\":\"12.50\"", "\"amount\":\"0\",\"\\u0061mount\":\"12.50\""),
            AiError.DUPLICATE_KEY
        )
        failure(
            raw.replace("\"status\":\"ready\"", "\"status\":\"ready\",\"st\\u0061tus\":\"ready\""),
            AiError.DUPLICATE_KEY
        )
    }

    @Test fun noProseMarkdownConcatenationCommentsOrTrailingCommaRecovery() {
        val raw = ready(transaction())
        for (bad in listOf(
            "prefix$raw", "$raw suffix", "$raw$raw", "```json\n$raw\n```",
            raw.dropLast(
                1
            ),
            "[]", "null", "/*x*/$raw",
            raw.dropLast(1) + ",}"
        )) {
            failure(bad)
        }
        assertEquals(DraftStatus.READY, success(" \n$raw\t ").status)
    }

    @Test fun limitsDepthTextUnicodeAndAdversarialBracesInsideStrings() {
        failure(" ".repeat(32769), AiError.OUTPUT_TOO_LONG)
        failure("[".repeat(9) + "0" + "]".repeat(9), AiError.TOO_DEEP)
        failure(ready(transaction(description = "a".repeat(2001))), AiError.INVALID_TEXT)
        failure(ready(transaction(hint = "a".repeat(121))), AiError.INVALID_TEXT)
        for (escape in listOf("\\u0000", "\\u202e", "\\ud800", "\\udc00")) {
            failure(ready(transaction(description = escape)), AiError.INVALID_TEXT)
        }
        failure(ready(transaction(description = "raw\nnewline")))
        val escaped = "Text { [ \\\"amount\\\":1 ] } \\ud83d\\ude00"
        assertTrue(
            success(
                ready(transaction(description = escaped))
            ).proposals.single().description.endsWith("\uD83D\uDE00")
        )
        assertEquals(
            "line\nnext",
            success(ready(transaction(description = "line\\nnext"))).proposals.single().description
        )
        failure(ready(transaction(description = "x")).replace("\"amount\"", "amount"))
    }
}
