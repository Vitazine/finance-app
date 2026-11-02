package app.finance.ai.parser

import app.finance.ai.contract.AiContext
import app.finance.ai.contract.AiDraft
import app.finance.ai.contract.AiError
import app.finance.ai.contract.AiProposal
import app.finance.ai.contract.AiResult
import app.finance.ai.contract.DraftField
import app.finance.ai.contract.DraftStatus
import app.finance.ai.contract.ProposalIssue
import app.finance.ai.contract.validAiText
import app.finance.core.money.CurrencyCode
import app.finance.core.money.Money
import app.finance.core.money.MoneyResult
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerId
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class AiDraftParser {
    fun parse(raw: String, context: AiContext, draftId: LedgerId): AiResult<AiDraft> {
        jsonBoundary(raw)?.let { return AiResult.Failure(it) }
        val root = try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return AiResult.Failure(AiError.INVALID_JSON)
        if (root.keys != setOf("schema_version", "status", "transactions", "missing_fields")) {
            return AiResult.Failure(AiError.INVALID_SCHEMA)
        }
        val version = root["schema_version"] as? JsonPrimitive
        if (version == null || version.isString || version.content != "1") {
            return AiResult.Failure(AiError.INVALID_SCHEMA)
        }
        val status = when (root["status"].string()) {
            "ready" -> DraftStatus.READY
            "needs_clarification" -> DraftStatus.NEEDS_CLARIFICATION
            "unsupported" -> DraftStatus.UNSUPPORTED
            else -> return AiResult.Failure(AiError.INVALID_STATUS)
        }
        val transactions = root["transactions"] as? JsonArray
            ?: return AiResult.Failure(AiError.INVALID_SCHEMA)
        val missing = root["missing_fields"] as? JsonArray
            ?: return AiResult.Failure(AiError.INVALID_SCHEMA)
        if (transactions.size > 10) return AiResult.Failure(AiError.TOO_MANY_TRANSACTIONS)
        if (missing.size > DraftField.entries.size) return AiResult.Failure(AiError.INVALID_SCHEMA)
        val fields = mutableListOf<DraftField>()
        for (field in missing) {
            val known = DraftField.entries.singleOrNull { it.wire == field.string() }
                ?: return AiResult.Failure(AiError.INVALID_SCHEMA)
            if (known in fields) return AiResult.Failure(AiError.INVALID_SCHEMA)
            fields.add(known)
        }
        when (status) {
            DraftStatus.READY -> if (transactions.isEmpty() || fields.isNotEmpty()) {
                return AiResult.Failure(AiError.INVALID_STATUS)
            }
            DraftStatus.NEEDS_CLARIFICATION -> if (transactions.isNotEmpty() || fields.isEmpty()) {
                return AiResult.Failure(AiError.INVALID_STATUS)
            }
            DraftStatus.UNSUPPORTED -> if (transactions.isNotEmpty() || fields.isNotEmpty()) {
                return AiResult.Failure(AiError.INVALID_STATUS)
            }
        }
        val proposals = mutableListOf<AiProposal>()
        for ((index, element) in transactions.withIndex()) {
            when (val result = proposal(element, context)) {
                is AiResult.Failure -> return AiResult.Failure(result.error, index)
                is AiResult.Success -> proposals.add(result.value)
            }
        }
        val validatedStatus = if (proposals.any { it.issues.isNotEmpty() }) {
            DraftStatus.NEEDS_CLARIFICATION
        } else {
            status
        }
        return AiResult.Success(AiDraft(draftId, context, validatedStatus, proposals, fields))
    }

    private fun proposal(element: JsonElement, context: AiContext): AiResult<AiProposal> {
        val obj = element as? JsonObject ?: return AiResult.Failure(AiError.INVALID_SCHEMA)
        if (obj.keys != DraftField.entries.map { it.wire }.toSet() ||
            obj.values.any { it.string() == null }
        ) {
            return AiResult.Failure(AiError.INVALID_SCHEMA)
        }
        val type = when (obj["type"].string()) {
            "income" -> EntryType.INCOME
            "expense" -> EntryType.EXPENSE
            else -> return AiResult.Failure(AiError.INVALID_SCHEMA)
        }
        val currency = CurrencyCode.resolve(obj["currency"].string()!!)
        if (currency !is MoneyResult.Success) return AiResult.Failure(AiError.INVALID_CURRENCY)
        val money = Money.parsePositive(obj["amount"].string()!!, currency.value)
        if (money !is MoneyResult.Success) return AiResult.Failure(AiError.INVALID_AMOUNT)
        val dateText = obj["date"].string()!!
        if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(dateText)) {
            return AiResult.Failure(AiError.INVALID_DATE)
        }
        val date = try {
            LocalDate.parse(dateText)
        } catch (_: IllegalArgumentException) {
            return AiResult.Failure(AiError.INVALID_DATE)
        }
        if (date.year !in 1..9999) return AiResult.Failure(AiError.INVALID_DATE)
        val hint = obj["category_hint"].string()!!
        val description = obj["description"].string()!!
        if (!validAiText(hint, 120) || !validAiText(description, 2000, multiline = true)) {
            return AiResult.Failure(AiError.INVALID_TEXT)
        }
        val matches = context.categories.filter {
            it.type == type && it.archivedAt == null && it.name == hint
        }
        val issues = mutableSetOf<ProposalIssue>()
        if (matches.isEmpty()) issues.add(ProposalIssue.CATEGORY_UNRESOLVED)
        if (matches.size > 1) issues.add(ProposalIssue.CATEGORY_AMBIGUOUS)
        if (currency.value !=
            context.settings.primaryCurrency
        ) {
            issues.add(ProposalIssue.RATE_REQUIRED)
        }
        return AiResult.Success(
            AiProposal(
                type,
                money.value,
                hint,
                matches.singleOrNull()?.id,
                date,
                description,
                issues
            )
        )
    }
    private fun JsonElement?.string(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
