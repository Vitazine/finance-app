package app.finance.domain.ledger

import app.finance.core.money.CurrencyCode
import app.finance.core.money.Money
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

class LedgerSettings private constructor(val primaryCurrency: CurrencyCode, val zoneId: String) {
    override fun toString(): String = "LedgerSettings"
    companion object {
        fun create(currency: CurrencyCode, zoneId: String): LedgerResult<LedgerSettings> {
            if (zoneId !in
                TimeZone.availableZoneIds
            ) {
                return LedgerResult.Failure(LedgerError.INVALID_ZONE)
            }
            return LedgerResult.Success(LedgerSettings(currency, zoneId))
        }
    }
}

class LedgerFilter private constructor(
    val type: EntryType?,
    val categoryId: LedgerId?,
    val sourceCurrency: CurrencyCode?,
    val from: Instant?,
    val untilExclusive: Instant?
) {
    override fun toString(): String = "LedgerFilter"
    companion object {
        val ALL = LedgerFilter(null, null, null, null, null)
        fun create(
            type: EntryType? = null,
            categoryId: LedgerId? = null,
            sourceCurrency: CurrencyCode? = null,
            from: Instant? = null,
            untilExclusive: Instant? = null
        ): LedgerResult<LedgerFilter> {
            if (from != null && untilExclusive != null && from >= untilExclusive) {
                return LedgerResult.Failure(LedgerError.INVALID_FILTER)
            }
            return LedgerResult.Success(
                LedgerFilter(type, categoryId, sourceCurrency, from, untilExclusive)
            )
        }
    }
}

class HistoryCursor internal constructor(val occurredAt: Instant, val id: LedgerId) {
    override fun toString(): String = "HistoryCursor"
}
class HistoryItem internal constructor(val transaction: StoredTransaction, val category: Category) {
    override fun toString(): String = "HistoryItem"
}
class HistoryPage internal constructor(
    val items: List<HistoryItem>,
    val nextCursor: HistoryCursor?
) {
    override fun toString(): String = "HistoryPage"
}
class LedgerOverview internal constructor(
    val balance: Money,
    val count: Long,
    val recent: List<HistoryItem>
) {
    override fun toString(): String = "LedgerOverview"
}

interface SettingsRepository {
    suspend fun current(): LedgerResult<LedgerSettings?>
    suspend fun existingBasis(): LedgerResult<CurrencyCode?>
    suspend fun initialize(settings: LedgerSettings): LedgerResult<LedgerSettings>
}
