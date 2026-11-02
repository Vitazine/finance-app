package app.finance.data.repository

import app.finance.core.hash.sha256
import app.finance.data.database.AiConfirmationEntity
import app.finance.domain.ai.AiConfirmation
import app.finance.domain.ai.AiConfirmationRepository
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.ManualEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

internal class RoomAiConfirmationRepository(private val access: DatabaseAccess) :
    AiConfirmationRepository {
    override suspend fun confirm(
        draftId: LedgerId,
        index: Int,
        entry: ManualEntry
    ): LedgerResult<AiConfirmation> {
        if (index !in 0..9) return LedgerResult.Failure(LedgerError.CONFLICT)
        val row = entry.toEntity().copy(source = "AI")
        val hash = sha256(
            JsonArray(
                listOf(
                    row.id,
                    row.type,
                    row.amountMinor.toString(),
                    row.sourceCurrency,
                    row.categoryId,
                    row.occurredAtSeconds.toString(),
                    row.occurredAtNanos.toString(),
                    row.displayZoneId,
                    row.note,
                    row.source, row.createdAt, row.updatedAt, row.baseCurrency,
                    row.convertedMinor.toString(),
                    row.rateDecimal, row.fxEffectiveAt,
                    row.fxSourceId, row.roundingPolicy, row.revision.toString()
                ).map { it?.let(::JsonPrimitive) ?: JsonNull }
            ).toString().encodeToByteArray()
        )
        return access.write {
            if (access.analyticsDao.hasInvalidStorage()) {
                return@write LedgerResult.Failure(
                    LedgerError.CORRUPT_DATA
                )
            }
            val settings =
                access.dao.settings()?.toDomain()
                    ?: return@write LedgerResult.Failure(LedgerError.NOT_INITIALIZED)
            if (settings.primaryCurrency !=
                entry.conversion.baseCurrency
            ) {
                return@write LedgerResult.Failure(LedgerError.BASE_CURRENCY_MISMATCH)
            }
            val old = access.aiDao.receipt(draftId.text, index)
            if (old != null) {
                if (old.storageTypes !=
                    "text|integer|text|text"
                ) {
                    return@write LedgerResult.Failure(LedgerError.CORRUPT_DATA)
                }
                return@write if (old.receipt.payloadHash == hash &&
                    old.receipt.transactionId == entry.id.text
                ) {
                    LedgerResult.Success(AiConfirmation(entry.id, true))
                } else {
                    LedgerResult.Failure(LedgerError.CONFLICT)
                }
            }
            if (access.integrityDao.receiptForTransaction(entry.id.text) !=
                null
            ) {
                return@write LedgerResult.Failure(LedgerError.CONFLICT)
            }
            if (access.dao.transaction(entry.id.text) !=
                null
            ) {
                return@write LedgerResult.Failure(LedgerError.CONFLICT)
            }
            val category =
                access.dao.category(entry.categoryId.text)?.toDomain()
                    ?: return@write LedgerResult.Failure(LedgerError.NOT_FOUND)
            if (category.type !=
                entry.type
            ) {
                return@write LedgerResult.Failure(LedgerError.TYPE_MISMATCH)
            }
            if (category.archivedAt !=
                null
            ) {
                return@write LedgerResult.Failure(LedgerError.CATEGORY_ARCHIVED)
            }
            access.aiDao.insert(AiConfirmationEntity(draftId.text, index, entry.id.text, hash))
            access.dao.insertTransaction(row)
            LedgerResult.Success(AiConfirmation(entry.id, false))
        }
    }
}
