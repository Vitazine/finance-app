package app.finance.data.repository

import app.finance.domain.exchange.CachedRate
import app.finance.domain.exchange.CurrencyPair
import app.finance.domain.exchange.RateCache
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerResult
import kotlinx.datetime.Instant

internal class RoomRateCache(private val access: DatabaseAccess) : RateCache {
    override suspend fun put(rate: CachedRate): LedgerResult<CachedRate> = access.write {
        val row = rate.toEntity()
        val existing = access.rateDao.exact(
            row.sourceCurrency,
            row.baseCurrency,
            row.effectiveAtSeconds,
            row.effectiveAtNanos,
            row.sourceId
        )?.toDomain()
        if (existing != null) {
            if (rate.retrievedAt < existing.retrievedAt) return@write LedgerResult.Success(existing)
            if (rate.retrievedAt == existing.retrievedAt) {
                return@write if (rate.quote.rate ==
                    existing.quote.rate
                ) {
                    LedgerResult.Success(existing)
                } else {
                    LedgerResult.Failure(LedgerError.CONFLICT)
                }
            }
            check(access.rateDao.update(row) == 1)
        } else {
            access.rateDao.insert(row)
        }
        LedgerResult.Success(rate)
    }

    override suspend fun latest(pair: CurrencyPair, notAfter: Instant): LedgerResult<CachedRate?> =
        access.read {
            LedgerResult.Success(
                access.rateDao.latest(
                    pair.source.code,
                    pair.base.code,
                    notAfter.epochSeconds,
                    notAfter.nanosecondsOfSecond.toLong()
                )?.toDomain()
            )
        }
}
