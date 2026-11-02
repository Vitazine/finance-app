package app.finance.data.backup

import app.finance.core.money.Money
import app.finance.data.repository.CorruptRecord
import app.finance.data.repository.DatabaseAccess
import app.finance.data.repository.toDomain
import app.finance.domain.backup.BackupError
import app.finance.domain.backup.BackupRepository
import app.finance.domain.backup.BackupResult
import app.finance.domain.backup.ImportPreview
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerResult

internal class RoomBackupRepository(private val access: DatabaseAccess) : BackupRepository {
    private suspend fun snapshot(): LedgerSnapshot {
        if (access.analyticsDao.hasInvalidStorage()) throw CorruptRecord()
        val s =
            LedgerSnapshot(
                access.dao.settings(),
                access.backupDao.categories(),
                access.backupDao.transactions(),
                access.backupDao.rates(),
                access.backupDao.budgets(),
                access.backupDao.confirmations()
            )
        validateSnapshot(s)
        return s
    }
    private suspend fun <T> read(block: suspend () -> T): BackupResult<T> = try {
        when (val r = access.snapshot { LedgerResult.Success(block()) }) {
            is LedgerResult.Success -> BackupResult.Success(r.value)
            is LedgerResult.Failure -> BackupResult.Failure(
                if (r.error ==
                    LedgerError.CORRUPT_DATA
                ) {
                    BackupError.INVALID_RECORD
                } else {
                    BackupError.STORAGE_FAILURE
                }
            )
        }
    } catch (f: ArchiveFault) {
        BackupResult.Failure(f.reason)
    }
    override suspend fun exportJson(): BackupResult<String> = read { snapshot().archive() }
    override suspend fun previewImport(json: String): BackupResult<ImportPreview> = try {
        val incoming = decodeArchive(json)
        read {
            val current = snapshot()
            ImportPreview(
                incoming.archive(),
                current.digest(),
                incoming.transactions.size,
                incoming.categories.size,
                incoming.settings?.primaryCurrency,
                current.settings?.primaryCurrency
            )
        }
    } catch (f: ArchiveFault) {
        BackupResult.Failure(f.reason)
    }
    override suspend fun replace(preview: ImportPreview): BackupResult<Unit> = try {
        val incoming = decodeArchive(preview.archive)
        val r = access.write {
            val current = snapshot()
            val digest = current.digest()
            if (digest == incoming.digest()) return@write LedgerResult.Success(Unit)
            if (digest != preview.baselineDigest) throw ArchiveFault(BackupError.STALE_DATA)
            access.backupDao.clearTransactions()
            access.backupDao.clearConfirmations()
            access.backupDao.clearRates()
            access.backupDao.clearBudgets()
            access.backupDao.clearCategories()
            access.backupDao.clearSettings()
            incoming.settings?.let { access.dao.insertSettings(it) }
            incoming.confirmations.forEach { access.aiDao.insert(it) }
            incoming.categories.forEach { access.dao.insertCategory(it.copy(archivedAt = null)) }
            incoming.transactions.forEach { access.dao.insertTransaction(it) }
            incoming.categories.filter {
                it.archivedAt != null
            }.forEach { access.dao.updateCategory(it) }
            incoming.rates.forEach { access.rateDao.insert(it) }
            incoming.budgets.forEach { access.budgetDao.put(it) }
            if (snapshot().digest() != incoming.digest()) throw CorruptRecord()
            LedgerResult.Success(Unit)
        }
        when (r) {
            is LedgerResult.Success -> BackupResult.Success(Unit)
            is LedgerResult.Failure -> BackupResult.Failure(BackupError.STORAGE_FAILURE)
        }
    } catch (f: ArchiveFault) {
        BackupResult.Failure(f.reason)
    }
    override suspend fun exportCsv(): BackupResult<String> = read {
        val s = snapshot()
        val names = s.categories.associate { it.id to it.name }
        fun cell(value: String): String {
            val safe = if (value.trimStart().firstOrNull() in
                listOf('=', '+', '-', '@')
            ) {
                "'" + value
            } else {
                value
            }
            return "\"" + safe.replace("\"", "\"\"") + "\""
        }
        val lines =
            mutableListOf(
                "id,type,amount,currency,base_amount,base_currency,category,occurred_at,zone,note,source,rate,effective_at,rate_source"
            )
        s.transactions.forEach { row ->
            val t = row.toDomain().entry
            val fx = t.conversion
            lines.add(
                listOf(
                    t.id.text, t.type.name, fx.sourceAmount.formatDecimal(), fx.sourceCurrency.code,
                    Money.ofMinor(
                        fx.convertedMinor,
                        fx.baseCurrency
                    ).formatDecimal(),
                    fx.baseCurrency.code,
                    names[t.categoryId.text].orEmpty(),
                    t.occurredAt.toString(),
                    t.displayZoneId,
                    t.note.orEmpty(),
                    row.source,
                    fx.rateDecimal,
                    fx.effectiveAt.toString(),
                    fx.sourceId
                ).joinToString(
                    ",",
                    transform = ::cell
                )
            )
        }
        val csv = lines.joinToString("\r\n", postfix = "\r\n")
        if (csv.encodeToByteArray().size > MAX_ARCHIVE_BYTES) invalid(BackupError.TOO_LARGE)
        csv
    }
}
