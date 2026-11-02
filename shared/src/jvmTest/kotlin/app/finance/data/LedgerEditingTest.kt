package app.finance.data

import androidx.room.Room
import app.finance.core.money.ConversionSnapshot
import app.finance.core.money.CurrencyCode
import app.finance.core.money.DecimalRate
import app.finance.core.money.ExchangeRateQuote
import app.finance.core.money.Money
import app.finance.core.money.MoneyResult
import app.finance.data.database.FinanceDatabase
import app.finance.data.repository.LedgerStore
import app.finance.data.repository.buildLedgerDatabase
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerFilter
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import app.finance.domain.ledger.ManualEntry
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

class LedgerEditingTest {
    private lateinit var directory: java.nio.file.Path
    private lateinit var store: LedgerStore
    private val at = Instant.parse("2026-11-01T00:00:00.123456789Z")
    private fun id(n: Int) =
        LedgerId.parse("00000000-0000-4000-8000-${n.toString().padStart(12, '0')}").value()
    private fun <T> LedgerResult<T>.value(): T = assertIs<LedgerResult.Success<T>>(this).value
    private fun LedgerResult<*>.error(): LedgerError = assertIs<LedgerResult.Failure>(this).error
    private fun <T> MoneyResult<T>.value(): T = (this as MoneyResult.Success).value
    private fun currency(code: String = "USD") = CurrencyCode.resolve(code).value()

    @BeforeTest
    fun setup() {
        directory = Files.createTempDirectory("fin011-synthetic-")
        store =
            LedgerStore(
                buildLedgerDatabase(
                    Room.databaseBuilder<FinanceDatabase>(
                        name = directory.resolve("test.db").toString()
                    )
                )
            )
    }

    @AfterTest
    fun close() {
        store.close()
        directory.toFile().deleteRecursively()
    }

    private suspend fun ready() {
        store.settings.initialize(LedgerSettings.create(currency(), "UTC").value()).value()
        store.categories.create(id(1), EntryType.EXPENSE, "Expense").value()
        store.categories.create(id(2), EntryType.INCOME, "Income").value()
    }

    private fun entry(
        n: Int = 10,
        type: EntryType = EntryType.EXPENSE,
        minor: Long = 100,
        time: Instant = at,
        note: String? = "Synthetic",
        category: Int = if (type == EntryType.EXPENSE) 1 else 2,
        source: String = "USD",
        base: String = "USD"
    ): ManualEntry {
        val money = Money.ofMinor(minor, currency(source))
        val snapshot = if (source == base) {
            ConversionSnapshot.identity(money, at).value()
        } else {
            val rate = ExchangeRateQuote.create(
                currency(source),
                currency(base),
                DecimalRate.parse("1.10").value(),
                at,
                "manual"
            ).value()
            ConversionSnapshot.convert(money, currency(base), rate).value()
        }
        return ManualEntry.create(
            id(n),
            type,
            id(category),
            snapshot,
            time,
            "UTC",
            note,
            at
        ).value()
    }

    @Test
    fun postingRequiresExplicitInitialSettings() = runBlocking<Unit> {
        store.categories.create(id(1), EntryType.EXPENSE, "Expense").value()
        assertEquals(LedgerError.NOT_INITIALIZED, store.transactions.postManual(entry()).error())
    }

    @Test
    fun setupIsIdempotentButCurrencyChangeAndWrongBaseAreBlocked() = runBlocking<Unit> {
        ready()
        assertEquals(
            "USD",
            store.settings.initialize(
                LedgerSettings.create(currency(), "UTC").value()
            ).value().primaryCurrency.code
        )
        assertEquals(
            LedgerError.CONFLICT,
            store.settings.initialize(LedgerSettings.create(currency("JPY"), "UTC").value()).error()
        )
        assertEquals(
            LedgerError.BASE_CURRENCY_MISMATCH,
            store.transactions.postManual(entry(base = "JPY")).error()
        )
        assertEquals(0L, store.transactions.overview().value().count)
    }

    @Test
    fun historyCursorHandlesEqualInstantsAndNanosecondsWithoutDuplicates() = runBlocking<Unit> {
        ready()
        for (n in listOf(10, 11, 12)) store.transactions.postManual(entry(n = n)).value()
        store.transactions.postManual(
            entry(n = 13, time = Instant.parse("2026-11-01T00:00:00.123456790Z"))
        ).value()
        val first = store.transactions.history(limit = 2).value()
        assertEquals(listOf(id(13), id(12)), first.items.map { it.transaction.entry.id })
        val second = store.transactions.history(cursor = first.nextCursor, limit = 2).value()
        assertEquals(listOf(id(11), id(10)), second.items.map { it.transaction.entry.id })
        assertEquals(null, second.nextCursor)
        assertEquals("Expense", first.items.first().category.name)
    }

    @Test
    fun filtersIntersectAndUseInclusiveStartExclusiveEnd() = runBlocking<Unit> {
        ready()
        store.transactions.postManual(entry(n = 10)).value()
        store.transactions.postManual(entry(n = 11, type = EntryType.INCOME)).value()
        store.transactions.postManual(entry(n = 12, source = "EUR")).value()
        store.transactions.postManual(entry(n = 13, time = at + 1.seconds)).value()
        val filter = LedgerFilter.create(
            type = EntryType.EXPENSE,
            categoryId = id(1),
            sourceCurrency = currency(),
            from = at,
            untilExclusive =
            at + 1.seconds
        ).value()
        assertEquals(
            listOf(id(10)),
            store.transactions.history(filter).value().items.map {
                it.transaction.entry.id
            }
        )
        assertEquals(4L, store.transactions.overview().value().count)
    }

    @Test
    fun editAndDeleteRefreshBalanceAndRejectStaleRevision() = runBlocking<Unit> {
        ready()
        store.transactions.postManual(entry(type = EntryType.INCOME, minor = 500)).value()
        val expense = store.transactions.postManual(entry(n = 11, minor = 200)).value()
        assertEquals(300L, store.transactions.overview().value().balance.amountMinor)
        val changed = store.transactions.updateManual(
            entry(n = 11, minor = 300),
            expense.revision,
            at + 1.seconds
        ).value()
        assertEquals(1L, changed.revision)
        assertEquals(at, changed.entry.createdAt)
        assertEquals(200L, store.transactions.overview().value().balance.amountMinor)
        assertEquals(
            LedgerError.CONFLICT,
            store.transactions.updateManual(
                entry(n = 11, minor = 400),
                0,
                at + 2.seconds
            ).error()
        )
        assertEquals(LedgerError.CONFLICT, store.transactions.deleteManual(id(11), 0).error())
        store.transactions.deleteManual(id(11), 1).value()
        store.transactions.deleteManual(id(11), 1).value()
        assertEquals(500L, store.transactions.overview().value().balance.amountMinor)
        assertEquals(1L, store.transactions.overview().value().count)
    }

    @Test
    fun noteEditKeepsFrozenFxMetadata() = runBlocking<Unit> {
        ready()
        val before = store.transactions.postManual(entry(source = "EUR")).value()
        val original = before.entry
        val replacement = ManualEntry.create(
            original.id,
            original.type,
            original.categoryId,
            original.conversion,
            at + 1.seconds,
            original.displayZoneId,
            "Changed",
            original.createdAt
        ).value()
        val after = store.transactions.updateManual(replacement, 0, at + 2.seconds).value()
        assertEquals(110L, after.entry.conversion.convertedMinor)
        assertEquals("1.1", after.entry.conversion.rateDecimal)
        assertEquals(at, after.entry.conversion.effectiveAt)
        assertEquals("manual", after.entry.conversion.sourceId)
    }

    @Test
    fun archivedCategoryCanBeKeptButCannotBeNewlyAssigned() = runBlocking<Unit> {
        ready()
        store.transactions.postManual(entry()).value()
        store.categories.archive(id(1), at).value()
        store.transactions.updateManual(entry(note = "Edited"), 0, at + 1.seconds).value()
        store.categories.create(id(3), EntryType.EXPENSE, "Other").value()
        store.transactions.postManual(entry(n = 11, category = 3)).value()
        assertEquals(
            LedgerError.CATEGORY_ARCHIVED,
            store.transactions.updateManual(
                entry(n = 11, category = 1),
                0,
                at + 1.seconds
            ).error()
        )
    }

    @Test
    fun competingUpdatesHaveOneWinnerAndOneConflict() = runBlocking<Unit> {
        ready()
        store.transactions.postManual(entry()).value()
        val results =
            coroutineScope {
                listOf(
                    async {
                        store.transactions.updateManual(
                            entry(minor = 200),
                            0,
                            at + 1.seconds
                        )
                    },
                    async {
                        store.transactions.updateManual(
                            entry(minor = 300),
                            0,
                            at + 1.seconds
                        )
                    }
                ).awaitAll()
            }
        assertEquals(1, results.count { it is LedgerResult.Success })
        assertEquals(
            1,
            results.count {
                it is LedgerResult.Failure &&
                    it.error == LedgerError.CONFLICT
            }
        )
        assertEquals(1L, store.transactions.find(id(10)).value().revision)
    }

    @Test
    fun balanceCancelsHugeIntermediateExactlyAndFailsOnlyFinalOverflow() = runBlocking<Unit> {
        ready()
        store.transactions.postManual(
            entry(n = 10, type = EntryType.INCOME, minor = Long.MAX_VALUE)
        ).value()
        store.transactions.postManual(
            entry(n = 11, type = EntryType.INCOME, minor = Long.MAX_VALUE)
        ).value()
        assertEquals(LedgerError.AMOUNT_OVERFLOW, store.transactions.overview().error())
        store.transactions.postManual(entry(n = 12, minor = Long.MAX_VALUE)).value()
        assertEquals(Long.MAX_VALUE, store.transactions.overview().value().balance.amountMinor)
    }

    @Test
    fun historyPageSizeIsBounded() = runBlocking<Unit> {
        ready()
        for (limit in listOf(
            0,
            101
        )) {
            assertEquals(
                LedgerError.INVALID_PAGE,
                store.transactions.history(limit = limit).error()
            )
        }
    }
}
