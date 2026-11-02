package app.finance.domain.analytics

import app.finance.core.money.ConversionSnapshot
import app.finance.core.money.CurrencyCode
import app.finance.core.money.DecimalRate
import app.finance.core.money.ExchangeRateQuote
import app.finance.core.money.Money
import app.finance.core.money.MoneyResult
import app.finance.domain.ledger.Category
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.HistoryItem
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.ManualEntry
import app.finance.domain.ledger.StoredTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

class SpendingAccumulatorTest {
    private fun <T> LedgerResult<T>.value() = (this as LedgerResult.Success).value
    private fun <T> MoneyResult<T>.value() = (this as MoneyResult.Success).value
    private fun currency(code: String = "USD") = CurrencyCode.resolve(code).value()
    private fun id(n: Int) =
        LedgerId.parse("00000000-0000-4000-8000-" + n.toString().padStart(12, '0')).value()
    private val at = Instant.parse("2026-11-02T12:00:00Z")
    private fun accumulator(reference: Instant = at, zone: String = "UTC") = SpendingAccumulator(
        reference,
        CompletedDays.create(reference, TimeZone.of(zone)).value(),
        currency()
    )
    private fun item(
        n: Int,
        minor: Long,
        time: Instant,
        cat: Int = 1,
        income: Boolean = false,
        base: String = "USD",
        archived: Boolean = false,
        zeroFx: Boolean = false
    ): HistoryItem {
        val type = if (income) EntryType.INCOME else EntryType.EXPENSE
        val category = Category.create(
            id(cat),
            type,
            "Same name",
            if (archived) at else null
        ).value()
        val amount = Money.ofMinor(minor, currency(if (zeroFx) "EUR" else base))
        val conversion = if (zeroFx) {
            val quote = ExchangeRateQuote.create(
                currency("EUR"),
                currency(),
                DecimalRate.parse("0.01").value(),
                time,
                "synthetic:tiny"
            ).value()
            ConversionSnapshot.convert(amount, currency(), quote).value()
        } else {
            ConversionSnapshot.identity(amount, time).value()
        }
        val entry = ManualEntry.create(
            id(n),
            type,
            category.id,
            conversion,
            time,
            "UTC",
            null,
            time
        ).value()
        return HistoryItem(StoredTransaction(entry, time), category)
    }

    @Test fun emptySnapshotIncludesAllThirtyZeroDays() {
        val view = accumulator().finish().value()
        assertEquals(30, view.daily.size)
        assertTrue(view.daily.all { it.amount.amountMinor == 0L })
        assertTrue(view.categories.isEmpty())
        assertEquals(0L, view.total.amountMinor)
        assertEquals("0", view.dailyAverage.numeratorMinor)
        assertEquals(30, view.dailyAverage.denominator)
        assertEquals(0L, view.currentMonthActual.amountMinor)
    }

    @Test fun independentThirtyDayGoldenSeparatesMonthActualAndExcludesTodayIncome() {
        val expected =
            listOf(
                100L,
                0L,
                200L,
                0L,
                0L,
                300L,
                0L,
                400L,
                0L,
                0L,
                500L,
                0L,
                0L,
                600L,
                0L,
                700L,
                0L,
                0L,
                800L,
                0L,
                0L,
                0L,
                900L,
                0L,
                1000L,
                0L,
                0L,
                1100L,
                0L,
                1200L
            )
        val acc = accumulator()
        val dates = CompletedDays.create(at, TimeZone.UTC).value().dates
        for ((index, minor) in expected.withIndex()) {
            if (minor >
                0
            ) {
                acc.add(
                    item(
                        index + 100,
                        minor,
                        Instant.parse(dates[index].toString() + "T12:00:00Z"),
                        cat = if (index %
                            2 ==
                            0
                        ) {
                            1
                        } else {
                            2
                        }
                    )
                ).value()
            }
        }
        acc.add(item(200, 500, at)).value()
        acc.add(item(201, 7000, Instant.parse("2026-11-20T12:00:00Z"))).value()
        acc.add(item(202, 999, Instant.parse("2026-10-02T23:59:59.999999999Z"))).value()
        acc.add(item(203, 30000, Instant.parse("2026-12-01T00:00:00Z"))).value()
        acc.add(
            item(204, 1000000, Instant.parse("2026-11-01T12:00:00Z"), cat = 3, income = true)
        ).value()
        val view = acc.finish().value()
        assertEquals(expected, view.daily.map { it.amount.amountMinor })
        assertEquals(7800L, view.total.amountMinor)
        assertEquals(listOf(4300L, 3500L), view.categories.map { it.amount.amountMinor })
        assertEquals("7800", view.dailyAverage.numeratorMinor)
        assertEquals("54600", view.weeklyEquivalent.numeratorMinor)
        assertEquals(30, view.weeklyEquivalent.denominator)
        assertEquals(7800L, view.normalized30Day.amountMinor)
        assertEquals("2026-11", view.currentMonth.key)
        assertEquals(8700L, view.currentMonthActual.amountMinor)
    }

    @Test fun fractionalCentAndMaximumWeeklyNumeratorNeverRoundOrOverflow() {
        for ((minor, weekly) in listOf(1L to "7", Long.MAX_VALUE to "64563604257983430649")) {
            val acc = accumulator()
            acc.add(item(100, minor, Instant.parse("2026-11-01T12:00:00Z"))).value()
            val view = acc.finish().value()
            assertEquals(minor.toString(), view.dailyAverage.numeratorMinor)
            assertEquals(30, view.dailyAverage.denominator)
            assertEquals(weekly, view.weeklyEquivalent.numeratorMinor)
            assertEquals(minor, view.normalized30Day.amountMinor)
            assertEquals("USD", view.dailyAverage.currency.code)
        }
    }

    @Test fun duplicateCategoryNamesDoNotMergeAndArchivedExpensesRemain() {
        val acc = accumulator()
        acc.add(
            item(100, 50, Instant.parse("2026-11-01T12:00:00Z"), cat = 2, archived = true)
        ).value()
        acc.add(item(101, 50, Instant.parse("2026-11-01T12:00:00Z"), cat = 1)).value()
        val categories = acc.finish().value().categories
        assertEquals(listOf(id(1), id(2)), categories.map { it.id })
        assertEquals(listOf("Same name", "Same name"), categories.map { it.name })
        assertEquals(at, categories.last().archivedAt)
    }

    @Test fun bothOccurrencesOfRepeatedHourBelongToOneLocalDay() {
        val acc = accumulator(Instant.parse("2024-11-04T12:00:00Z"), "America/New_York")
        acc.add(item(100, 100, Instant.parse("2024-11-03T05:30:00Z"))).value()
        acc.add(item(101, 200, Instant.parse("2024-11-03T06:30:00Z"))).value()
        val view = acc.finish().value()
        assertEquals("2024-11-03", view.daily.last().date.toString())
        assertEquals(300L, view.daily.last().amount.amountMinor)
        assertEquals(1, view.daily.count { it.amount.amountMinor > 0 })
    }

    @Test fun rollingOrMonthOverflowFailsWithoutPartialSnapshot() {
        for (time in listOf("2026-10-31T12:00:00Z", "2026-11-02T12:00:00Z")) {
            val acc = accumulator()
            acc.add(item(100, Long.MAX_VALUE, Instant.parse("2026-11-01T12:00:00Z"))).value()
            acc.add(item(101, 1, Instant.parse(time))).value()
            assertEquals(LedgerResult.Failure(LedgerError.AMOUNT_OVERFLOW), acc.finish())
        }
    }

    @Test fun currencyMismatchIsStickyFailure() {
        val acc = accumulator()
        assertEquals(
            LedgerResult.Failure(LedgerError.BASE_CURRENCY_MISMATCH),
            acc.add(item(100, 100, Instant.parse("2026-11-01T12:00:00Z"), base = "EUR"))
        )
        assertEquals(LedgerResult.Failure(LedgerError.BASE_CURRENCY_MISMATCH), acc.finish())
    }

    @Test fun tinyFrozenConversionRetainsZeroCategoryWithoutFakeSpend() {
        val acc = accumulator()
        acc.add(item(100, 1, Instant.parse("2026-11-01T12:00:00Z"), zeroFx = true)).value()
        val view = acc.finish().value()
        assertEquals(0L, view.total.amountMinor)
        assertEquals(1, view.categories.size)
        assertEquals(0L, view.categories.single().amount.amountMinor)
    }
}
