package app.finance.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
internal interface RateDao {
    @Query(
        """SELECT exchange_rates.*,
        typeof(sourceCurrency) || ':' || typeof(baseCurrency) || ':' ||
        typeof(effectiveAtSeconds) || ':' || typeof(effectiveAtNanos) || ':' ||
        typeof(sourceId) || ':' || typeof(rateDecimal) || ':' ||
        typeof(retrievedAtSeconds) || ':' || typeof(retrievedAtNanos) AS storageTypes
        FROM exchange_rates WHERE sourceCurrency = :source AND baseCurrency = :base
        AND effectiveAtSeconds = :seconds AND effectiveAtNanos = :nanos AND sourceId = :provider"""
    )
    suspend fun exact(
        source: String,
        base: String,
        seconds: Long,
        nanos: Long,
        provider: String
    ): RateReadRow?

    @Query(
        """SELECT exchange_rates.*,
        typeof(sourceCurrency) || ':' || typeof(baseCurrency) || ':' ||
        typeof(effectiveAtSeconds) || ':' || typeof(effectiveAtNanos) || ':' ||
        typeof(sourceId) || ':' || typeof(rateDecimal) || ':' ||
        typeof(retrievedAtSeconds) || ':' || typeof(retrievedAtNanos) AS storageTypes
        FROM exchange_rates WHERE sourceCurrency = :source AND baseCurrency = :base
        AND (effectiveAtSeconds < :seconds OR (effectiveAtSeconds = :seconds AND effectiveAtNanos <= :nanos))
        ORDER BY effectiveAtSeconds DESC, effectiveAtNanos DESC, retrievedAtSeconds DESC,
        retrievedAtNanos DESC, sourceId ASC LIMIT 1"""
    )
    suspend fun latest(source: String, base: String, seconds: Long, nanos: Long): RateReadRow?

    @Insert suspend fun insert(rate: RateEntity)

    @Update suspend fun update(rate: RateEntity): Int
}
