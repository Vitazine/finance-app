package app.finance.data.database

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "categories", indices = [Index(value = ["id", "type"], unique = true)])
internal data class CategoryEntity(
    @PrimaryKey val id: String,
    val type: String,
    val name: String,
    val archivedAt: String?
)

@Entity(
    tableName = "ledger_transactions",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id", "type"],
            childColumns = ["categoryId", "type"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["categoryId", "type"]),
        Index(value = ["occurredAtSeconds", "occurredAtNanos", "id"]),
        Index(value = ["type", "occurredAtSeconds"])
    ]
)
internal data class TransactionEntity(
    @PrimaryKey val id: String,
    val type: String,
    val amountMinor: Long,
    val sourceCurrency: String,
    val categoryId: String,
    val occurredAtSeconds: Long,
    val occurredAtNanos: Int,
    val displayZoneId: String,
    val note: String?,
    val source: String,
    val createdAt: String,
    val updatedAt: String,
    val baseCurrency: String,
    val convertedMinor: Long,
    val rateDecimal: String,
    val fxEffectiveAt: String,
    val fxSourceId: String,
    val roundingPolicy: String,
    @ColumnInfo(defaultValue = "0") val revision: Long = 0
)

@Entity(tableName = "ledger_settings")
internal data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val primaryCurrency: String,
    val zoneId: String
)

internal data class HistoryRow(
    @Embedded val transaction: TransactionEntity,
    val categoryName: String,
    val categoryArchivedAt: String?
)
