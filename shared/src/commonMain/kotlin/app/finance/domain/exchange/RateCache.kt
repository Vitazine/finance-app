package app.finance.domain.exchange

import app.finance.domain.ledger.LedgerResult
import kotlinx.datetime.Instant

interface RateCache {
    /** Ignore older retrievals; reject contradictory values with the same retrieval instant. */
    suspend fun put(rate: CachedRate): LedgerResult<CachedRate>

    /** Neutral bounded query. Returned observation is not automatically approved for posting. */
    suspend fun latest(pair: CurrencyPair, notAfter: Instant): LedgerResult<CachedRate?>
}
