package app.finance.data.database

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Query

@Entity(
    tableName = "ai_confirmations",
    primaryKeys = ["draftId", "proposalIndex"],
    indices = [Index(value = ["transactionId"], unique = true)]
)
internal data class AiConfirmationEntity(
    val draftId: String,
    val proposalIndex: Int,
    val transactionId: String,
    val payloadHash: String
)
internal data class AiConfirmationRead(
    @Embedded val receipt: AiConfirmationEntity,
    val storageTypes: String
)

@Dao
internal interface AiConfirmationDao {
    @Query(
        "SELECT *, typeof(draftId)||'|'||typeof(proposalIndex)||'|'||typeof(transactionId)||'|'||typeof(payloadHash) AS storageTypes FROM ai_confirmations WHERE draftId=:draftId AND proposalIndex=:index"
    )
    suspend fun receipt(draftId: String, index: Int): AiConfirmationRead?

    @Insert suspend fun insert(row: AiConfirmationEntity)
}
