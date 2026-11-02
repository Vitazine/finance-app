package app.finance.domain.backup

sealed interface BackupResult<out T> {
    class Success<T>(val value: T) : BackupResult<T> {
        override fun toString() = "BackupResult.Success"
    }
    data class Failure(val error: BackupError) : BackupResult<Nothing>
}
enum class BackupError {
    INVALID_FORMAT,
    BAD_CHECKSUM,
    TOO_LARGE,
    INVALID_RECORD,
    STALE_DATA,
    STORAGE_FAILURE
}
class ImportPreview internal constructor(
    internal val archive: String,
    internal val baselineDigest: String,
    val transactionCount: Int,
    val categoryCount: Int,
    val incomingCurrency: String?,
    val currentCurrency: String?
) {
    override fun toString() = "ImportPreview"
}
interface BackupRepository {
    suspend fun exportJson(): BackupResult<String>
    suspend fun exportCsv(): BackupResult<String>
    suspend fun previewImport(json: String): BackupResult<ImportPreview>

    /** Entire dataset replacement. Caller must obtain explicit informed confirmation. */
    suspend fun replace(preview: ImportPreview): BackupResult<Unit>
}
