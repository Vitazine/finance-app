package app.finance.ai.parser

import app.finance.ai.contract.AiContext
import app.finance.ai.contract.AiError
import app.finance.ai.contract.AiResult
import app.finance.ai.contract.InferenceRequest
import app.finance.core.money.CurrencyCode
import app.finance.core.money.Money
import app.finance.core.money.MoneyResult
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Strict bridge for the owner's expense-only day-07 model. Never invents category mappings. */
class Day07Protocol {
    /** Qwen ChatML, using the training template's generation suffix. Escape control-token starts in data. */
    fun prompt(request: InferenceRequest, system: String): String {
        val input = buildJsonObject {
            put("text", request.userText)
            put("current_date", request.context.localDate.toString())
        }.toString().replace("<", "\\u003c")
        return "<|im_start|>system\n" + system.trim() + "<|im_end|>\n<|im_start|>user\n" + input +
            "<|im_end|>\n<|im_start|>assistant\n"
    }

    fun toV1(raw: String, context: AiContext): AiResult<String> {
        jsonBoundary(raw)?.let { return AiResult.Failure(it) }
        val obj =
            try {
                Json.parseToJsonElement(raw) as? JsonObject
            } catch (
                _: IllegalArgumentException
            ) {
                null
            }
                ?: return AiResult.Failure(AiError.INVALID_JSON)
        if (obj.keys !=
            setOf(
                "category",
                "date",
                "payment_method",
                "clarification_reason",
                "amount",
                "status",
                "currency"
            ) ||
            obj.values.any { it != JsonNull && (it !is JsonPrimitive || !it.isString) }
        ) {
            return AiResult.Failure(AiError.INVALID_SCHEMA)
        }
        fun value(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun failure() = AiResult.Failure(AiError.INVALID_SCHEMA)
        val category = value("category")
        val date = value("date")
        val payment = value("payment_method")
        val amount = value("amount")
        val currency = value("currency")
        val reason = value("clarification_reason")
        if (category != null &&
            category !in
            setOf(
                "health", "utilities", "other", "shopping", "housing", "groceries", "transport",
                "education", "entertainment", "cafes"
            )
        ) {
            return failure()
        }
        if (payment != null && payment !in setOf("cash", "card", "transfer")) return failure()
        if (currency != null &&
            currency !in setOf("RUB", "USD", "EUR", "PHP")
        ) {
            return AiResult.Failure(AiError.INVALID_CURRENCY)
        }
        if (amount != null) {
            if (!Regex(
                    "(?:0|[1-9][0-9]*)\\.[0-9]{2}"
                ).matches(amount)
            ) {
                return AiResult.Failure(AiError.INVALID_AMOUNT)
            }
            val c = (CurrencyCode.resolve(currency ?: "RUB") as MoneyResult.Success).value
            if (Money.parsePositive(
                    amount,
                    c
                ) !is MoneyResult.Success
            ) {
                return AiResult.Failure(AiError.INVALID_AMOUNT)
            }
        }
        if (date != null) {
            val d = try {
                LocalDate.parse(date)
            } catch (_: IllegalArgumentException) {
                null
            }
            if (d == null ||
                d.year !in 1..9999 ||
                d.toString() != date ||
                d > context.localDate
            ) {
                return AiResult.Failure(AiError.INVALID_DATE)
            }
        }
        val status: String
        val fields = mutableListOf<String>()
        val transactions = mutableListOf<JsonElement>()
        when (value("status")) {
            "ok" -> {
                if (listOf(category, date, amount, currency).any { it == null } ||
                    reason != null
                ) {
                    return failure()
                }
                status = "ready"
                transactions.add(
                    buildJsonObject {
                        put("type", "expense")
                        put("amount", amount!!)
                        put("currency", currency!!)
                        put("category_hint", category!!)
                        put("date", date!!)
                        put("description", "")
                    }
                )
            }
            "ignored" -> {
                if (listOf(category, date, payment, amount, currency, reason).any {
                        it != null
                    }
                ) {
                    return failure()
                }
                status = "unsupported"
            }
            "needs_clarification" -> {
                status = "needs_clarification"
                when (reason) {
                    "missing_context", "multiple_expenses" -> {
                        if (listOf(category, date, payment, amount, currency).any {
                                it != null
                            }
                        ) {
                            return failure()
                        }
                        fields.add("description")
                    }
                    "missing_amount", "ambiguous_amount" -> {
                        if (amount !=
                            null
                        ) {
                            return failure()
                        }
                        fields.add("amount")
                    }
                    "ambiguous_currency" -> {
                        if (currency !=
                            null
                        ) {
                            return failure()
                        }
                        fields.add("currency")
                    }
                    "ambiguous_date" -> {
                        if (date != null) return failure()
                        fields.add("date")
                    }
                    else -> return failure()
                }
            }
            else -> return AiResult.Failure(AiError.INVALID_STATUS)
        }
        return AiResult.Success(
            buildJsonObject {
                put("schema_version", 1)
                put("status", status)
                put("transactions", JsonArray(transactions))
                put("missing_fields", JsonArray(fields.map(::JsonPrimitive)))
            }.toString()
        )
    }
}
