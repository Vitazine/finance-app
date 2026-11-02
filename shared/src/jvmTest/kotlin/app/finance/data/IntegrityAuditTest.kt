package app.finance.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.finance.core.money.ConversionSnapshot
import app.finance.core.money.CurrencyCode
import app.finance.core.money.Money
import app.finance.core.money.MoneyResult
import app.finance.data.database.FinanceDatabase
import app.finance.data.repository.LedgerStore
import app.finance.data.repository.buildLedgerDatabase
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
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

class IntegrityAuditTest {
    private val dir = Files.createTempDirectory("fin021-integrity-")
    private val path = dir.resolve("db").toString()
    private var store = open()
    private fun open() =
        LedgerStore(buildLedgerDatabase(Room.databaseBuilder<FinanceDatabase>(name = path)))
    private fun id(n: Int) = (
        LedgerId.parse(
            "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
        ) as LedgerResult.Success
        ).value
    private val at = Instant.parse("2026-11-02T12:00:00Z")
    private suspend fun setup() {
        val usd = (CurrencyCode.resolve("USD") as MoneyResult.Success).value
        store.settings.initialize((LedgerSettings.create(usd, "UTC") as LedgerResult.Success).value)
        store.categories.create(id(1), EntryType.EXPENSE, "Synthetic")
        val fx = (
            ConversionSnapshot.identity(
                Money.ofMinor(100, usd),
                at
            ) as MoneyResult.Success
            ).value
        store.transactions.postManual(
            (
                ManualEntry.create(
                    id(10),
                    EntryType.EXPENSE,
                    id(1),
                    fx,
                    at,
                    "UTC",
                    null,
                    at
                ) as LedgerResult.Success
                ).value
        )
    }
    private fun corrupt(sql: String) {
        store.close()
        BundledSQLiteDriver().open(path).use { c ->
            c.execSQL("PRAGMA foreign_keys=OFF")
            c.execSQL("DROP TRIGGER IF EXISTS transaction_guard_UPDATE")
            c.execSQL(sql)
        }
        store = open()
    }
    private fun corruptResult(r: LedgerResult<*>) =
        assertEquals(LedgerError.CORRUPT_DATA, assertIs<LedgerResult.Failure>(r).error)

    @AfterTest fun close() {
        store.close()
        dir.toFile().deleteRecursively()
    }

    @Test fun fractionalStorageCannotBecomePlausibleLegacyAmounts() = runBlocking {
        setup()
        corrupt("UPDATE ledger_transactions SET convertedMinor=100.25, revision=revision+1")
        corruptResult(store.transactions.find(id(10)))
        corruptResult(store.transactions.overview())
        corruptResult(store.transactions.history())
        corruptResult(store.budgets.current(at))
        corruptResult(store.settings.current())
    }

    @Test fun orphanCannotDisappearThroughHistoryJoinOrLeavePlausibleTotals() = runBlocking {
        setup()
        corrupt("DELETE FROM categories")
        corruptResult(store.transactions.history())
        corruptResult(store.transactions.overview())
        corruptResult(store.settings.current())
        corruptResult(store.budgets.current(at))
    }

    @Test fun noncanonicalCategoryIsDetectedEvenByUnrelatedSettingsRead() = runBlocking {
        setup()
        store.close()
        BundledSQLiteDriver().open(path).use { c ->
            c.execSQL("DROP TRIGGER IF EXISTS category_guard_UPDATE")
            c.execSQL("UPDATE categories SET archivedAt='not-an-instant'")
        }
        store = open()
        corruptResult(store.settings.current())
    }
}
