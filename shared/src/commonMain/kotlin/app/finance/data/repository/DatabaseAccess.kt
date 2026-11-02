package app.finance.data.repository

import androidx.room.deferredTransaction
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteException
import app.finance.data.database.FinanceDatabase
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerResult
import kotlinx.coroutines.CancellationException

internal class DatabaseAccess(private val database: FinanceDatabase) {
    val integrityDao = database.integrityDao()
    val backupDao = database.backupDao()
    val aiDao = database.aiConfirmationDao()
    val analyticsDao = database.analyticsDao()
    val dao = database.ledgerDao()
    val budgetDao = database.budgetDao()
    val rateDao = database.rateDao()

    suspend fun <T> read(block: suspend () -> LedgerResult<T>): LedgerResult<T> = snapshot(block)

    suspend fun <T> snapshot(block: suspend () -> LedgerResult<T>): LedgerResult<T> = guarded {
        database.useReaderConnection { connection ->
            connection.deferredTransaction {
                audit()
                block()
            }
        }
    }

    suspend fun <T> write(block: suspend () -> LedgerResult<T>): LedgerResult<T> = guarded {
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                audit()
                block()
            }
        }
    }

    private suspend fun audit() {
        if (analyticsDao.hasInvalidStorage()) throw CorruptRecord()
        dao.settings()?.toDomain()
        var offset = 0
        while (true) {
            val rows = dao.categories(true, 200, offset)
            rows.forEach { it.toDomain() }
            if (rows.size < 200) break
            offset += rows.size
        }
        var after: String? = null
        while (true) {
            val rows = dao.transactionBatch(after, 100)
            rows.forEach { it.toDomain() }
            if (rows.isEmpty()) break
            after = rows.last().id
        }
        offset = 0
        while (true) {
            val rows = integrityDao.rates(offset)
            rows.forEach {
                app.finance.data.database.RateReadRow(
                    it,
                    "text:text:integer:integer:text:text:integer:integer"
                ).toDomain()
            }
            if (rows.size < 200) break
            offset += rows.size
        }
        offset = 0
        while (true) {
            val rows = integrityDao.budgets(offset)
            rows.forEach {
                app.finance.data.database.BudgetReadRow(it, "text:text:integer:text").toDomain()
            }
            if (rows.size < 200) break
            offset += rows.size
        }
        offset = 0
        while (true) {
            val rows = integrityDao.confirmations(offset)
            rows.forEach {
                if ((
                        app.finance.domain.ledger.LedgerId.parse(
                            it.draftId
                        ) as? LedgerResult.Success
                        )?.value?.text !=
                    it.draftId ||
                    (
                        app.finance.domain.ledger.LedgerId.parse(
                            it.transactionId
                        ) as? LedgerResult.Success
                        )?.value?.text !=
                    it.transactionId
                ) {
                    throw CorruptRecord()
                }
            }
            if (rows.size < 200) break
            offset += rows.size
        }
    }
    private suspend fun <T> guarded(block: suspend () -> LedgerResult<T>): LedgerResult<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: CorruptRecord) {
        LedgerResult.Failure(LedgerError.CORRUPT_DATA)
    } catch (_: SQLiteException) {
        LedgerResult.Failure(LedgerError.STORAGE_FAILURE)
    } catch (_: IllegalStateException) {
        // Room rejects unsupported schema / closed storage. Do not expose SQL or paths.
        LedgerResult.Failure(LedgerError.STORAGE_FAILURE)
    }
}
