package app.finance.data.database

import androidx.room.Embedded
import androidx.room.Entity

@Entity(
    tableName = "exchange_rates",
    primaryKeys = [
        "sourceCurrency",
        "baseCurrency",
        "effectiveAtSeconds",
        "effectiveAtNanos",
        "sourceId"
    ]
)
internal data class RateEntity(
    val sourceCurrency: String,
    val baseCurrency: String,
    val effectiveAtSeconds: Long,
    val effectiveAtNanos: Long,
    val sourceId: String,
    val rateDecimal: String,
    val retrievedAtSeconds: Long,
    val retrievedAtNanos: Long
)

/** Read storage classes before SQLite's getLong/getText coercion loses that evidence. */
internal data class RateReadRow(@Embedded val rate: RateEntity, val storageTypes: String)
