package app.finance.data.database

import androidx.room.Dao
import androidx.room.Query

/** Audit original SQLite storage and references before joined/typed reads can hide corruption. */
@Dao
internal interface AnalyticsDao {
    @Query(
        """
        SELECT EXISTS (
            SELECT 1 FROM ledger_transactions t
            WHERE typeof(t.id) != 'text' OR typeof(t.type) != 'text'
            OR typeof(t.amountMinor) != 'integer' OR typeof(t.convertedMinor) != 'integer'
            OR typeof(t.occurredAtSeconds) != 'integer' OR typeof(t.occurredAtNanos) != 'integer'
            OR t.occurredAtNanos NOT BETWEEN 0 AND 999999999
            OR typeof(t.revision) != 'integer' OR t.revision < 0
            OR typeof(t.sourceCurrency) != 'text' OR typeof(t.baseCurrency) != 'text'
            OR typeof(t.categoryId) != 'text' OR typeof(t.displayZoneId) != 'text'
            OR typeof(t.note) NOT IN ('text','null') OR typeof(t.source) != 'text'
            OR length(t.id)!=36 OR length(t.type)>16 OR length(t.sourceCurrency)!=3 OR length(t.baseCurrency)!=3
            OR length(t.categoryId)!=36 OR length(t.displayZoneId)>128 OR length(t.note)>2000
            OR length(t.createdAt)>64 OR length(t.updatedAt)>64 OR length(t.rateDecimal)>128
            OR length(t.fxEffectiveAt)>64 OR length(t.fxSourceId)>128
            OR length(t.source)>16 OR length(t.roundingPolicy)>32
            OR typeof(t.createdAt) != 'text' OR typeof(t.updatedAt) != 'text'
            OR typeof(t.rateDecimal) != 'text' OR typeof(t.fxEffectiveAt) != 'text'
            OR typeof(t.fxSourceId) != 'text' OR typeof(t.roundingPolicy) != 'text'
            OR NOT EXISTS (SELECT 1 FROM categories c WHERE c.id=t.categoryId AND c.type=t.type)
        ) OR EXISTS (
            SELECT 1 FROM categories c WHERE typeof(c.id) != 'text' OR typeof(c.type) != 'text'
            OR typeof(c.name) != 'text' OR typeof(c.archivedAt) NOT IN ('text','null')
            OR length(c.id)!=36 OR length(c.name)>120 OR length(c.type)>16 OR length(c.archivedAt)>64
        ) OR EXISTS (
            SELECT 1 FROM ledger_settings s WHERE typeof(s.id) != 'integer' OR s.id != 1
            OR typeof(s.primaryCurrency) != 'text' OR typeof(s.zoneId) != 'text'
        ) OR EXISTS (
            SELECT 1 FROM exchange_rates r WHERE typeof(r.sourceCurrency)!='text' OR typeof(r.baseCurrency)!='text'
            OR typeof(r.effectiveAtSeconds)!='integer' OR typeof(r.effectiveAtNanos)!='integer'
            OR typeof(r.retrievedAtSeconds)!='integer' OR typeof(r.retrievedAtNanos)!='integer'
            OR typeof(r.sourceId)!='text' OR typeof(r.rateDecimal)!='text'
            OR length(r.sourceCurrency)!=3 OR length(r.baseCurrency)!=3 OR length(r.sourceId)>128 OR length(r.rateDecimal)>128
        ) OR EXISTS (
            SELECT 1 FROM monthly_budgets b WHERE typeof(b.yearMonth)!='text' OR typeof(b.currency)!='text'
            OR typeof(b.limitMinor)!='integer' OR typeof(b.updatedAt)!='text'
            OR length(b.yearMonth)!=7 OR length(b.currency)!=3 OR length(b.updatedAt)>64
        ) OR EXISTS (
            SELECT 1 FROM ai_confirmations a WHERE typeof(a.draftId)!='text' OR typeof(a.proposalIndex)!='integer'
            OR typeof(a.transactionId)!='text' OR typeof(a.payloadHash)!='text'
            OR a.proposalIndex NOT BETWEEN 0 AND 9 OR length(a.draftId)!=36 OR length(a.transactionId)!=36
            OR length(a.payloadHash)!=64 OR a.payloadHash GLOB '*[^0-9a-f]*'
        ) OR EXISTS (
            SELECT 1 FROM ledger_transactions t WHERE
            (t.source='AI' AND NOT EXISTS(SELECT 1 FROM ai_confirmations a WHERE a.transactionId=t.id))
            OR (t.source!='AI' AND EXISTS(SELECT 1 FROM ai_confirmations a WHERE a.transactionId=t.id))
        )
    """
    )
    suspend fun hasInvalidStorage(): Boolean
}
