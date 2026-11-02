package app.finance.data

import androidx.room.Room
import app.finance.core.hash.sha256
import app.finance.core.money.ConversionSnapshot
import app.finance.core.money.CurrencyCode
import app.finance.core.money.Money
import app.finance.core.money.MoneyResult
import app.finance.data.database.FinanceDatabase
import app.finance.data.repository.LedgerStore
import app.finance.data.repository.buildLedgerDatabase
import app.finance.domain.ai.AiConfirmation
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import app.finance.domain.ledger.ManualEntry
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

class AiConfirmationTest {
    private val dir = Files.createTempDirectory("fin017-confirm-")
    private var store = open()
    private fun open() = LedgerStore(
        buildLedgerDatabase(
            Room.databaseBuilder<FinanceDatabase>(name = dir.resolve("db").toString())
        )
    )
    private val at = Instant.parse("2026-11-02T12:00:00Z")
    private fun id(n: Int) = (
        LedgerId.parse(
            "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
        ) as LedgerResult.Success
        ).value
    private val usd = (CurrencyCode.resolve("USD") as MoneyResult.Success).value

    @AfterTest fun close() {
        store.close()
        dir.toFile().deleteRecursively()
    }
    private suspend fun setup() {
        store.settings.initialize((LedgerSettings.create(usd, "UTC") as LedgerResult.Success).value)
        store.categories.create(id(1), EntryType.EXPENSE, "Synthetic")
    }
    private fun entry(amount: Long = 125) = (
        ManualEntry.create(
            id(10),
            EntryType.EXPENSE,
            id(1),
            (
                ConversionSnapshot.identity(
                    Money.ofMinor(amount, usd),
                    at
                ) as MoneyResult.Success
                ).value,
            at,
            "UTC",
            "Synthetic",
            at
        ) as LedgerResult.Success
        ).value

    @Test fun concurrentRetryPersistsOneAiRowAndStableReceiptAfterReopen() = runBlocking {
        setup()
        val results =
            coroutineScope {
                (1..12).map {
                    async(Dispatchers.Default) { store.aiConfirmations.confirm(id(50), 0, entry()) }
                }.awaitAll()
            }
        assertTrue(results.all { it is LedgerResult.Success })
        assertEquals(1, results.count { !(it as LedgerResult.Success).value.replayed })
        assertEquals(1L, (store.transactions.overview() as LedgerResult.Success).value.count)
        store.close()
        store = open()
        assertTrue(
            (
                store.aiConfirmations.confirm(
                    id(50),
                    0,
                    entry()
                ) as LedgerResult.Success
                ).value.replayed
        )
        val row = (store.transactions.find(id(10)) as LedgerResult.Success).value
        assertEquals("AI", row.source.name)
    }

    @Test fun payloadMismatchIsConflictAndDeletedRecordCannotBeResurrected() = runBlocking {
        setup()
        assertIs<LedgerResult.Success<*>>(store.aiConfirmations.confirm(id(50), 0, entry()))
        assertEquals(
            LedgerError.CONFLICT,
            (store.aiConfirmations.confirm(id(50), 0, entry(500)) as LedgerResult.Failure).error
        )
        store.transactions.deleteManual(id(10), 0)
        assertIs<LedgerResult.Success<*>>(store.aiConfirmations.confirm(id(50), 0, entry()))
        assertEquals(0L, (store.transactions.overview() as LedgerResult.Success).value.count)
    }

    @Test fun failedConfirmationRollsBackReceiptAndRevalidatesCategory() = runBlocking {
        setup()
        store.categories.archive(id(1), at)
        assertEquals(
            LedgerError.CATEGORY_ARCHIVED,
            (store.aiConfirmations.confirm(id(50), 0, entry()) as LedgerResult.Failure).error
        )
        store.categories.restore(id(1))
        assertFalse(
            (
                store.aiConfirmations.confirm(
                    id(50),
                    0,
                    entry()
                ) as LedgerResult.Success
                ).value.replayed
        )
    }

    @Test fun manualIdCollisionDoesNotCreateReceiptAndAiEditPreservesOrigin() = runBlocking {
        setup()
        store.transactions.postManual(entry())
        assertEquals(
            LedgerError.CONFLICT,
            (store.aiConfirmations.confirm(id(50), 0, entry()) as LedgerResult.Failure).error
        )
        store.transactions.deleteManual(id(10), 0)
        assertFalse(
            (
                store.aiConfirmations.confirm(
                    id(50),
                    0,
                    entry()
                ) as LedgerResult.Success
                ).value.replayed
        )
        assertIs<LedgerResult.Success<*>>(store.transactions.updateManual(entry(150), 0, at))
        assertEquals(
            "AI",
            (store.transactions.find(id(10)) as LedgerResult.Success).value.source.name
        )
    }

    @Test fun invalidIndexDoesNotWrite() = runBlocking {
        setup()
        for (i in listOf(
            -1,
            10
        )) {
            assertIs<LedgerResult.Failure>(store.aiConfirmations.confirm(id(50), i, entry()))
        }
        assertEquals(0L, (store.transactions.overview() as LedgerResult.Success).value.count)
    }

    @Test fun sha256MatchesPublishedVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256(byteArrayOf())
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".encodeToByteArray())
        )
    }

    @Test fun failedTransactionInsertRollsBackReceiptAndRetryIsNew() = runBlocking {
        setup()
        store.close()
        fun sql(statement: String) {
            androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(
                dir.resolve("db").toString()
            ).use { c ->
                c.prepare(statement).use { it.step() }
            }
        }
        sql(
            "CREATE TRIGGER fixture_abort BEFORE INSERT ON ledger_transactions BEGIN SELECT RAISE(ABORT, 'synthetic'); END"
        )
        store = open()
        assertIs<LedgerResult.Failure>(store.aiConfirmations.confirm(id(50), 0, entry()))
        assertEquals(0L, (store.transactions.overview() as LedgerResult.Success).value.count)
        store.close()
        sql("DROP TRIGGER fixture_abort")
        store = open()
        val retry = assertIs<LedgerResult.Success<AiConfirmation>>(
            store.aiConfirmations.confirm(id(50), 0, entry())
        ).value
        assertFalse(retry.replayed)
    }
}
