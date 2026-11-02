package app.finance.data.repository

import app.finance.core.money.Money
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.HistoryCursor
import app.finance.domain.ledger.HistoryPage
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerFilter
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerOverview
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.ManualEntry
import app.finance.domain.ledger.StoredTransaction
import app.finance.domain.ledger.TransactionRepository
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlinx.datetime.Instant

internal class RoomTransactionRepository(private val access: DatabaseAccess) :
    TransactionRepository {
    private suspend fun validateBase(entry: ManualEntry): LedgerError? {
        val settings = access.dao.settings()?.toDomain() ?: return LedgerError.NOT_INITIALIZED
        return if (settings.primaryCurrency !=
            entry.conversion.baseCurrency
        ) {
            LedgerError.BASE_CURRENCY_MISMATCH
        } else {
            null
        }
    }
    private suspend fun validateCategory(
        entry: ManualEntry,
        existing: ManualEntry? = null
    ): LedgerError? {
        val category =
            access.dao.category(entry.categoryId.text)?.toDomain() ?: return LedgerError.NOT_FOUND
        if (category.type != entry.type) return LedgerError.TYPE_MISMATCH
        val kept =
            existing != null &&
                existing.categoryId == entry.categoryId &&
                existing.type == entry.type
        return if (category.archivedAt != null && !kept) LedgerError.CATEGORY_ARCHIVED else null
    }

    override suspend fun postManual(entry: ManualEntry): LedgerResult<StoredTransaction> =
        access.write {
            validateBase(entry)?.let { return@write LedgerResult.Failure(it) }
            val row = entry.toEntity()
            val existing = access.dao.transaction(entry.id.text)
            if (existing != null) {
                val decoded = existing.toDomain()
                return@write if (existing ==
                    row
                ) {
                    LedgerResult.Success(decoded)
                } else {
                    LedgerResult.Failure(LedgerError.CONFLICT)
                }
            }
            validateCategory(entry)?.let { return@write LedgerResult.Failure(it) }
            access.dao.insertTransaction(row)
            LedgerResult.Success(row.toDomain())
        }

    override suspend fun find(id: LedgerId): LedgerResult<StoredTransaction> = access.read {
        val row =
            access.dao.transaction(id.text)
                ?: return@read LedgerResult.Failure(LedgerError.NOT_FOUND)
        LedgerResult.Success(row.toDomain())
    }

    override suspend fun updateManual(
        entry: ManualEntry,
        expectedRevision: Long,
        updatedAt: Instant
    ): LedgerResult<StoredTransaction> = access.write {
        validateBase(entry)?.let { return@write LedgerResult.Failure(it) }
        val old =
            access.dao.transaction(entry.id.text)?.toDomain()
                ?: return@write LedgerResult.Failure(LedgerError.NOT_FOUND)
        if (expectedRevision < 0 ||
            old.revision != expectedRevision ||
            old.revision == Long.MAX_VALUE ||
            old.entry.createdAt != entry.createdAt
        ) {
            return@write LedgerResult.Failure(LedgerError.CONFLICT)
        }
        validateCategory(entry, old.entry)?.let { return@write LedgerResult.Failure(it) }
        val row = entry.toEntity(updatedAt, old.revision + 1).copy(source = old.source.name)
        access.dao.updateTransaction(row)
        LedgerResult.Success(row.toDomain())
    }

    override suspend fun deleteManual(id: LedgerId, expectedRevision: Long): LedgerResult<Unit> =
        access.write {
            val settings =
                access.dao.settings()?.toDomain()
                    ?: return@write LedgerResult.Failure(LedgerError.NOT_INITIALIZED)
            val existing =
                access.dao.transaction(id.text)?.toDomain()
                    ?: return@write LedgerResult.Success(Unit)
            if (existing.entry.conversion.baseCurrency !=
                settings.primaryCurrency
            ) {
                return@write LedgerResult.Failure(LedgerError.BASE_CURRENCY_MISMATCH)
            }
            if (expectedRevision < 0 ||
                existing.revision != expectedRevision
            ) {
                return@write LedgerResult.Failure(LedgerError.CONFLICT)
            }
            access.dao.deleteTransaction(id.text)
            LedgerResult.Success(Unit)
        }

    override suspend fun history(
        filter: LedgerFilter,
        cursor: HistoryCursor?,
        limit: Int
    ): LedgerResult<HistoryPage> {
        if (limit !in 1..100) return LedgerResult.Failure(LedgerError.INVALID_PAGE)
        return access.snapshot {
            val settings =
                access.dao.settings()?.toDomain()
                    ?: return@snapshot LedgerResult.Failure(LedgerError.NOT_INITIALIZED)
            val rows = access.historyItems(filter, cursor, limit + 1)
            if (rows.any {
                    it.transaction.entry.conversion.baseCurrency != settings.primaryCurrency
                }
            ) {
                return@snapshot LedgerResult.Failure(LedgerError.BASE_CURRENCY_MISMATCH)
            }
            val visible = rows.take(limit)
            val last = visible.lastOrNull()?.transaction?.entry
            val next = if (rows.size > limit &&
                last != null
            ) {
                HistoryCursor(last.occurredAt, last.id)
            } else {
                null
            }
            LedgerResult.Success(HistoryPage(visible, next))
        }
    }

    override suspend fun overview(): LedgerResult<LedgerOverview> = access.snapshot {
        val settings =
            access.dao.settings()?.toDomain()
                ?: return@snapshot LedgerResult.Failure(LedgerError.NOT_INITIALIZED)
        var sum = BigInteger.ZERO
        var count = 0L
        var after: String? = null
        while (true) {
            val rows = access.dao.transactionBatch(after, 100)
            if (rows.isEmpty()) break
            for (row in rows) {
                val entry = row.toDomain().entry
                if (entry.conversion.baseCurrency !=
                    settings.primaryCurrency
                ) {
                    return@snapshot LedgerResult.Failure(LedgerError.BASE_CURRENCY_MISMATCH)
                }
                val value = BigInteger.fromLong(entry.conversion.convertedMinor)
                sum = if (entry.type == EntryType.INCOME) sum + value else sum - value
            }
            count += rows.size
            after = rows.last().id
        }
        val minor =
            sum.toString().toLongOrNull()
                ?: return@snapshot LedgerResult.Failure(LedgerError.AMOUNT_OVERFLOW)
        LedgerResult.Success(
            LedgerOverview(
                Money.ofMinor(minor, settings.primaryCurrency),
                count,
                access.historyItems(LedgerFilter.ALL, null, 5)
            )
        )
    }
}
