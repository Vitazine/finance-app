package app.finance.domain.ledger

import kotlinx.datetime.Instant

interface CategoryRepository {
    suspend fun create(id: LedgerId, type: EntryType, name: String): LedgerResult<Category>
    suspend fun rename(id: LedgerId, name: String): LedgerResult<Category>
    suspend fun archive(id: LedgerId, at: Instant): LedgerResult<Category>
    suspend fun restore(id: LedgerId): LedgerResult<Category>
    suspend fun deleteUnused(id: LedgerId): LedgerResult<Unit>
    suspend fun list(
        includeArchived: Boolean = false,
        limit: Int = 100,
        offset: Int = 0
    ): LedgerResult<List<Category>>
}

interface TransactionRepository {
    /** Retry with the same stable ID and canonical payload is safe; a different payload conflicts. */
    suspend fun postManual(entry: ManualEntry): LedgerResult<StoredTransaction>
    suspend fun find(id: LedgerId): LedgerResult<StoredTransaction>
    suspend fun updateManual(
        entry: ManualEntry,
        expectedRevision: Long,
        updatedAt: Instant
    ): LedgerResult<StoredTransaction>
    suspend fun deleteManual(id: LedgerId, expectedRevision: Long): LedgerResult<Unit>
    suspend fun history(
        filter: LedgerFilter = LedgerFilter.ALL,
        cursor: HistoryCursor? = null,
        limit: Int = 30
    ): LedgerResult<HistoryPage>
    suspend fun overview(): LedgerResult<LedgerOverview>
}
