package app.finance.data.repository

import app.finance.domain.analytics.AnalyticsRepository
import app.finance.domain.analytics.CompletedDays
import app.finance.domain.analytics.SpendingAccumulator
import app.finance.domain.analytics.SpendingSnapshot
import app.finance.domain.ledger.HistoryCursor
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerFilter
import app.finance.domain.ledger.LedgerResult
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

internal class RoomAnalyticsRepository(private val access: DatabaseAccess) : AnalyticsRepository {
    override suspend fun snapshot(reference: Instant): LedgerResult<SpendingSnapshot> =
        access.snapshot {
            if (access.analyticsDao.hasInvalidStorage()) {
                return@snapshot LedgerResult.Failure(
                    LedgerError.CORRUPT_DATA
                )
            }
            val settings =
                access.dao.settings()?.toDomain()
                    ?: return@snapshot LedgerResult.Failure(LedgerError.NOT_INITIALIZED)
            val window = when (
                val result = CompletedDays.create(
                    reference,
                    TimeZone.of(settings.zoneId)
                )
            ) {
                is LedgerResult.Failure -> return@snapshot result
                is LedgerResult.Success -> result.value
            }
            val accumulator = SpendingAccumulator(reference, window, settings.primaryCurrency)
            var cursor: HistoryCursor? = null
            while (true) {
                val rows = access.historyItems(LedgerFilter.ALL, cursor, 100)
                if (rows.isEmpty()) break
                for (row in rows) {
                    val result = accumulator.add(row)
                    if (result is LedgerResult.Failure) return@snapshot result
                }
                val last = rows.last().transaction.entry
                cursor = HistoryCursor(last.occurredAt, last.id)
            }
            accumulator.finish()
        }
}
