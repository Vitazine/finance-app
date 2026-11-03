package app.finance.android.ledger

import android.content.SharedPreferences
import app.finance.domain.ledger.CategoryRepository
import app.finance.domain.ledger.DefaultCategories
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class CategoryBootstrap(
    private val repository: CategoryRepository,
    private val preferences: SharedPreferences
) {
    private val mutex = Mutex()
    private var durable = false

    /** Called before a confirmed replacement; abort it if the no-reseed marker cannot persist. */
    suspend fun preserveImportedCatalog() = mutex.withLock {
        if (!durable) {
            check(commitInstallation()) { "Unable to preserve imported category catalog" }
            durable = true
        }
    }

    // KTX edit discards the commit Boolean; a failed durable marker must be reported.
    @android.annotation.SuppressLint("UseKtx")
    private fun commitInstallation(): Boolean =
        preferences.edit().putBoolean("installed_v1", true).commit()
    suspend fun ensure(): LedgerResult<Unit> = try {
        mutex.withLock {
            if (durable) {
                return@withLock LedgerResult.Success(Unit)
            }
            // Even a true in-memory preference may come from a failed commit. Confirm it durably
            // once per bootstrap instance, and retry after failure before reporting completion.
            val installation = if (preferences.getBoolean("installed_v1", false)) {
                LedgerResult.Success(Unit)
            } else {
                DefaultCategories.install(repository)
            }
            when (val result = installation) {
                is LedgerResult.Failure -> result
                is LedgerResult.Success -> if (
                    commitInstallation()
                ) {
                    durable = true
                    LedgerResult.Success(Unit)
                } else {
                    LedgerResult.Failure(LedgerError.STORAGE_FAILURE)
                }
            }
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        LedgerResult.Failure(LedgerError.STORAGE_FAILURE)
    }
}
