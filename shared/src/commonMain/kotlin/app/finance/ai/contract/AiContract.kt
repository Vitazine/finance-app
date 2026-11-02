package app.finance.ai.contract

import app.finance.core.money.Money
import app.finance.domain.ledger.Category
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerSettings
import kotlinx.datetime.DateTimeArithmeticException
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

sealed interface AiResult<out T> {
    class Success<T>(val value: T) : AiResult<T> {
        override fun toString() = "AiResult.Success"
    }
    data class Failure(val error: AiError, val transactionIndex: Int? = null) : AiResult<Nothing>
}
enum class AiError {
    INVALID_CONTEXT,
    INVALID_REQUEST,
    INPUT_TOO_LONG,
    OUTPUT_TOO_LONG,
    INVALID_JSON,
    DUPLICATE_KEY,
    TOO_DEEP,
    INVALID_SCHEMA,
    INVALID_STATUS,
    INVALID_AMOUNT,
    INVALID_CURRENCY,
    INVALID_DATE,
    INVALID_TEXT,
    TOO_MANY_TRANSACTIONS,
    MODEL_UNAVAILABLE,
    BUSY,
    OUT_OF_MEMORY,
    TIMEOUT,
    ENGINE_FAILURE
}
enum class DraftStatus { READY, NEEDS_CLARIFICATION, UNSUPPORTED }
enum class DraftField(val wire: String) {
    TYPE("type"),
    AMOUNT("amount"),
    CURRENCY("currency"),
    CATEGORY("category_hint"),
    DATE("date"),
    DESCRIPTION("description")
}
enum class ProposalIssue { CATEGORY_UNRESOLVED, CATEGORY_AMBIGUOUS, RATE_REQUIRED }

class AiContext private constructor(
    val reference: Instant,
    val settings: LedgerSettings,
    categories: List<Category>,
    val localDate: LocalDate
) {
    val categories = categories.toList()
    override fun toString() = "AiContext"
    companion object {
        fun create(
            reference: Instant,
            settings: LedgerSettings,
            categories: List<Category>
        ): AiResult<AiContext> {
            if (categories.size > 200 || categories.map { it.id }.toSet().size != categories.size) {
                return AiResult.Failure(AiError.INVALID_CONTEXT)
            }
            val date = try {
                reference.toLocalDateTime(TimeZone.of(settings.zoneId)).date
            } catch (_: DateTimeArithmeticException) {
                return AiResult.Failure(AiError.INVALID_CONTEXT)
            }
            if (date.year !in 1..9999) return AiResult.Failure(AiError.INVALID_CONTEXT)
            return AiResult.Success(AiContext(reference, settings, categories, date))
        }
    }
}
class AiProposal internal constructor(
    val type: EntryType,
    val money: Money,
    val categoryHint: String,
    val categoryId: LedgerId?,
    val date: LocalDate,
    val description: String,
    issues: Set<ProposalIssue>
) {
    val issues = issues.toSet()
    override fun toString() = "AiProposal"
}

/** Transient proposals only. No posting or confirmation authority. */
class AiDraft internal constructor(
    val id: LedgerId,
    val context: AiContext,
    val status: DraftStatus,
    proposals: List<AiProposal>,
    missingFields: List<DraftField>
) {
    val proposals = proposals.toList()
    val missingFields = missingFields.toList()
    override fun toString() = "AiDraft"
}
