package app.finance.data

import androidx.room.Room
import app.finance.core.hash.sha256
import app.finance.core.money.ConversionSnapshot
import app.finance.core.money.CurrencyCode
import app.finance.core.money.DecimalRate
import app.finance.core.money.ExchangeRateQuote
import app.finance.core.money.Money
import app.finance.core.money.MoneyResult
import app.finance.core.money.quote
import app.finance.data.database.FinanceDatabase
import app.finance.data.repository.LedgerStore
import app.finance.data.repository.buildLedgerDatabase
import app.finance.domain.backup.BackupError
import app.finance.domain.backup.BackupResult
import app.finance.domain.budget.BudgetMonth
import app.finance.domain.exchange.CachedRate
import app.finance.domain.ledger.EntrySource
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import app.finance.domain.ledger.ManualEntry
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class BackupRepositoryTest {
    private val dir = Files.createTempDirectory("fin020-backup-")
    private var store = open()
    private fun open() = LedgerStore(
        buildLedgerDatabase(
            Room.databaseBuilder<FinanceDatabase>(name = dir.resolve("db").toString())
        )
    )
    private val at = Instant.parse("2026-11-02T12:00:00.123456789Z")
    private fun id(n: Int) = (
        LedgerId.parse(
            "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
        ) as LedgerResult.Success
        ).value
    private fun cur(c: String) = (CurrencyCode.resolve(c) as MoneyResult.Success).value
    private fun entry(n: Int = 10, note: String = "=SyntheticFormula", amount: Long = 125) = (
        ManualEntry.create(
            id(n),
            EntryType.EXPENSE,
            id(1),
            (
                ConversionSnapshot.identity(
                    Money.ofMinor(amount, cur("USD")),
                    at
                ) as MoneyResult.Success
                ).value,
            at,
            "UTC",
            note,
            at
        ) as LedgerResult.Success
        ).value
    private fun <T> BackupResult<T>.value() = (this as BackupResult.Success).value
    private suspend fun seed() {
        store.settings.initialize(
            (LedgerSettings.create(cur("USD"), "UTC") as LedgerResult.Success).value
        )
        store.categories.create(id(1), EntryType.EXPENSE, "@SyntheticCategory")
        store.aiConfirmations.confirm(id(50), 0, entry())
        val quote = (
            ExchangeRateQuote.create(
                cur("EUR"),
                cur("USD"),
                (DecimalRate.parse("1.2345") as MoneyResult.Success).value,
                at,
                "synthetic"
            ) as MoneyResult.Success
            ).value
        store.rates.put(CachedRate(quote, at))
        store.budgets.put(
            (BudgetMonth.create(2026, 11) as LedgerResult.Success).value,
            Money.ofMinor(2000, cur("USD")),
            at
        )
    }

    @AfterTest fun close() {
        store.close()
        dir.toFile().deleteRecursively()
    }

    @Test fun roundTripAllCanonicalRecordsAndReceiptTombstonesSurvive() = runBlocking {
        seed()
        val before = store.backups.exportJson().value()
        store.transactions.deleteManual(id(10), 0)
        val preview = store.backups.previewImport(before).value()
        assertEquals(1, preview.transactionCount)
        assertIs<BackupResult.Success<*>>(store.backups.replace(preview))
        assertEquals(before, store.backups.exportJson().value())
        store.close()
        store = open()
        assertEquals(before, store.backups.exportJson().value())
        assertEquals(
            EntrySource.AI,
            (store.transactions.find(id(10)) as LedgerResult.Success).value.source
        )
        store.transactions.deleteManual(id(10), 0)
        val tombstone = store.backups.exportJson().value()
        val p = store.backups.previewImport(tombstone).value()
        store.backups.replace(p)
        assertTrue(
            (
                store.aiConfirmations.confirm(
                    id(50),
                    0,
                    entry()
                ) as LedgerResult.Success
                ).value.replayed
        )
        assertEquals(0L, (store.transactions.overview() as LedgerResult.Success).value.count)
    }

    @Test fun previewAndInvalidArchivesNeverMutateExistingLedger() = runBlocking {
        seed()
        val before = store.backups.exportJson().value()
        assertIs<BackupResult.Success<*>>(store.backups.previewImport(before))
        assertEquals(before, store.backups.exportJson().value())
        val corrupted = before.replace("125", "126")
        for (bad in listOf(
            "{bad",
            before + before,
            corrupted,
            "[]",
            before.replace("\"format_version\":1", "\"format_version\":2")
        )) {
            assertIs<BackupResult.Failure>(store.backups.previewImport(bad))
            assertEquals(before, store.backups.exportJson().value())
        }
        assertEquals(
            BackupError.TOO_LARGE,
            (
                store.backups.previewImport(
                    " ".repeat(8 * 1024 * 1024 + 1)
                ) as BackupResult.Failure
                ).error
        )
    }

    @Test fun staleConsentCannotReplaceNewlyEditedDataAndSameArchiveRetryIsNoOp() = runBlocking {
        seed()
        val archive = store.backups.exportJson().value()
        val preview = store.backups.previewImport(archive).value()
        store.transactions.postManual(entry(11))
        val changed = store.backups.exportJson().value()
        assertEquals(
            BackupError.STALE_DATA,
            (store.backups.replace(preview) as BackupResult.Failure).error
        )
        assertEquals(changed, store.backups.exportJson().value())
        val fresh = store.backups.previewImport(archive).value()
        assertIs<BackupResult.Success<*>>(store.backups.replace(fresh))
        assertIs<BackupResult.Success<*>>(store.backups.replace(fresh))
        assertEquals(archive, store.backups.exportJson().value())
    }

    @Test fun checksummedArchiveStillRejectsOrphansDuplicatesAndInvalidMoney() = runBlocking {
        seed()
        val archive = store.backups.exportJson().value()
        val root = Json.parseToJsonElement(archive).jsonObject
        fun broken(payload: JsonObject): String = JsonObject(
            root +
                mapOf(
                    "payload" to payload,
                    "sha256" to JsonPrimitive(sha256(payload.toString().encodeToByteArray()))
                )
        ).toString()
        val payload = root.getValue("payload").jsonObject
        val transaction = payload.getValue("transactions").jsonArray.single().jsonArray
        val invalid = JsonArray(transaction.toMutableList().also { it[2] = JsonPrimitive("0") })
        val variations = listOf(
            payload + mapOf("transactions" to JsonArray(listOf(invalid))),
            payload + mapOf("categories" to JsonArray(emptyList())),
            payload + mapOf("transactions" to JsonArray(listOf(transaction, transaction)))
        )
        for (v in variations) {
            assertIs<BackupResult.Failure>(store.backups.previewImport(broken(JsonObject(v))))
            assertEquals(archive, store.backups.exportJson().value())
        }
    }

    @Test fun csvQuotesValuesAndNeutralizesSpreadsheetFormulaPrefixes() = runBlocking {
        seed()
        val csv = store.backups.exportCsv().value()
        assertTrue(csv.contains("\"'=SyntheticFormula\""))
        assertTrue(csv.contains("\"'@SyntheticCategory\""))
        assertTrue(csv.contains("\"1.25\""))
        assertTrue(csv.contains("\"AI\""))
        assertTrue(csv.contains("2026-11-02T12:00:00.123456789Z"))
    }

    @Test fun archiveRestoresArchivedCategoryAndHistoricalOperationsAtomically() = runBlocking {
        seed()
        store.categories.archive(id(1), at)
        val archive = store.backups.exportJson().value()
        store.categories.rename(id(1), "RenamedSynthetic")
        val preview = store.backups.previewImport(archive).value()
        assertIs<BackupResult.Success<*>>(store.backups.replace(preview))
        assertEquals(archive, store.backups.exportJson().value())
        assertEquals(1L, (store.transactions.overview() as LedgerResult.Success).value.count)
    }

    @Test fun emptyUninitializedDatasetCanExportAndRestoreWithoutInventingCurrency() = runBlocking {
        val archive = store.backups.exportJson().value()
        val p = store.backups.previewImport(archive).value()
        assertEquals(null, p.incomingCurrency)
        assertIs<BackupResult.Success<*>>(store.backups.replace(p))
        assertEquals(archive, store.backups.exportJson().value())
    }

    @Test fun lateInsertFailureRollsBackEveryTableAfterClear() = runBlocking {
        seed()
        val archive = store.backups.exportJson().value()
        store.transactions.postManual(entry(11))
        val before = store.backups.exportJson().value()
        val preview = store.backups.previewImport(archive).value()
        store.close()
        androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(
            dir.resolve("db").toString()
        ).use { connection ->
            connection.prepare(
                "CREATE TRIGGER fixture_abort BEFORE INSERT ON ledger_transactions BEGIN SELECT RAISE(ABORT, 'synthetic'); END"
            ).use {
                it.step()
            }
        }
        store = open()
        assertIs<BackupResult.Failure>(store.backups.replace(preview))
        assertEquals(before, store.backups.exportJson().value())
    }

    @Test fun maximumRecordArchiveCanReadItsOwnExportAndOverLimitFailsBeforeExport() = runBlocking {
        seed()
        val snapshot = app.finance.data.backup.decodeArchive(store.backups.exportJson().value())
        val row = snapshot.transactions.single().copy(source = "MANUAL")
        val maximum = snapshot.copy(
            confirmations = emptyList(),
            rates = emptyList(),
            budgets = emptyList(),
            transactions = (10..10007).map { row.copy(id = id(it).text) }
        )
        app.finance.data.backup.validateSnapshot(maximum)
        val archive = maximum.archive()
        assertEquals(9998, app.finance.data.backup.decodeArchive(archive).transactions.size)
        val excessive = maximum.copy(
            transactions =
            maximum.transactions + row.copy(id = id(10008).text)
        )
        val fault = assertFailsWith<app.finance.data.backup.ArchiveFault> {
            app.finance.data.backup.validateSnapshot(excessive)
        }
        assertEquals(BackupError.TOO_LARGE, fault.reason)
    }
}
