package app.finance.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
internal interface LedgerDao {
    @Insert
    suspend fun insertTransaction(row: TransactionEntity)

    @Query("SELECT * FROM ledger_transactions WHERE id = :id")
    suspend fun transaction(id: String): TransactionEntity?

    @Insert
    suspend fun insertCategory(row: CategoryEntity)

    @Update
    suspend fun updateCategory(row: CategoryEntity)

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun category(id: String): CategoryEntity?

    @Query(
        "SELECT * FROM categories WHERE :includeArchived OR archivedAt IS NULL ORDER BY name, id LIMIT :limit OFFSET :offset"
    )
    suspend fun categories(includeArchived: Boolean, limit: Int, offset: Int): List<CategoryEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM ledger_transactions WHERE categoryId = :id)")
    suspend fun categoryInUse(id: String): Boolean

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteCategory(id: String)

    @Query("SELECT * FROM ledger_settings WHERE id = 1")
    suspend fun settings(): SettingsEntity?

    @Insert
    suspend fun insertSettings(row: SettingsEntity)

    @Update
    suspend fun updateTransaction(row: TransactionEntity)

    @Query("DELETE FROM ledger_transactions WHERE id = :id")
    suspend fun deleteTransaction(id: String)

    @Query(
        "SELECT * FROM ledger_transactions WHERE :afterId IS NULL OR id > :afterId ORDER BY id LIMIT :limit"
    )
    suspend fun transactionBatch(afterId: String?, limit: Int): List<TransactionEntity>

    @Query(
        """
        SELECT t.*, c.name AS categoryName, c.archivedAt AS categoryArchivedAt
        FROM ledger_transactions t JOIN categories c ON t.categoryId = c.id
        WHERE (:type IS NULL OR t.type = :type)
        AND (:categoryId IS NULL OR t.categoryId = :categoryId)
        AND (:currency IS NULL OR t.sourceCurrency = :currency)
        AND (:fromSeconds IS NULL OR t.occurredAtSeconds > :fromSeconds
             OR (t.occurredAtSeconds = :fromSeconds AND t.occurredAtNanos >= :fromNanos))
        AND (:untilSeconds IS NULL OR t.occurredAtSeconds < :untilSeconds
             OR (t.occurredAtSeconds = :untilSeconds AND t.occurredAtNanos < :untilNanos))
        AND (:cursorSeconds IS NULL OR t.occurredAtSeconds < :cursorSeconds
             OR (t.occurredAtSeconds = :cursorSeconds AND t.occurredAtNanos < :cursorNanos)
             OR (t.occurredAtSeconds = :cursorSeconds AND t.occurredAtNanos = :cursorNanos AND t.id < :cursorId))
        ORDER BY t.occurredAtSeconds DESC, t.occurredAtNanos DESC, t.id DESC LIMIT :limit
    """
    )
    suspend fun history(
        type: String?,
        categoryId: String?,
        currency: String?,
        fromSeconds: Long?,
        fromNanos: Int?,
        untilSeconds: Long?,
        untilNanos: Int?,
        cursorSeconds: Long?,
        cursorNanos: Int?,
        cursorId: String?,
        limit: Int
    ): List<HistoryRow>
}
