package app.finance.data.repository

import app.finance.data.database.CategoryEntity
import app.finance.domain.ledger.HistoryCursor
import app.finance.domain.ledger.HistoryItem
import app.finance.domain.ledger.LedgerFilter

internal suspend fun DatabaseAccess.historyItems(
    filter: LedgerFilter,
    cursor: HistoryCursor?,
    limit: Int
): List<HistoryItem> = dao.history(
    filter.type?.name,
    filter.categoryId?.text,
    filter.sourceCurrency?.code,
    filter.from?.epochSeconds,
    filter.from?.nanosecondsOfSecond,
    filter.untilExclusive?.epochSeconds,
    filter.untilExclusive?.nanosecondsOfSecond,
    cursor?.occurredAt?.epochSeconds,
    cursor?.occurredAt?.nanosecondsOfSecond,
    cursor?.id?.text,
    limit
).map { row ->
    val transaction = row.transaction.toDomain()
    val category = CategoryEntity(
        transaction.entry.categoryId.text,
        transaction.entry.type.name,
        row.categoryName,
        row.categoryArchivedAt
    ).toDomain()
    HistoryItem(transaction, category)
}
