package com.chimu

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The numbers behind Home's Expenditures chart. Today is Thursday 26 Nov 2026 in every test. */
class ExpenditureSeriesTest {
    private val me = "user-me"
    private val today = LocalDate.of(2026, 11, 26)

    private fun txn(
        id: String,
        amount: Long,
        date: String,
        type: TxnType = TxnType.EXPENSE,
        owner: String = me,
        status: String = "confirmed",
        deleted: Boolean = false,
    ) = Txn(
        id = id, ownerId = owner, type = type, walletId = "w", toWalletId = if (type == TxnType.TRANSFER) "w2" else null,
        amount = amount, date = date, category = "food", merchant = null, note = null, isBusiness = false,
        status = status, deleted = deleted,
    )

    private val sample = listOf(
        txn("a", 35_000, "2026-11-23"), // Mon
        txn("b", 61_250, "2026-11-24"), // Tue
        txn("c", 184_575, "2026-11-25"), // Wed
        txn("d", 28_900, "2026-11-26"), // Thu (today)
        txn("e", 100_000, "2026-11-03"), // Week 1 of the month
        txn("f", 500_000, "2026-10-15"),
        txn("g", 700_000, "2026-06-01"),
        txn("h", 900_000, "2026-01-31"),
    )

    private fun series(period: SpendPeriod, txns: List<Txn> = sample) = ExpenditureSeries.build(txns, me, period, today)

    @Test
    fun week_isMondayToSunday_withFutureDaysEmpty() {
        val s = series(SpendPeriod.WEEK)
        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), s.points.map { it.label })
        assertEquals(listOf<Long?>(35_000, 61_250, 184_575, 28_900, null, null, null), s.points.map { it.spent })
        assertEquals(3, s.currentIndex)
        assertEquals("Thu 26 Nov", s.points[3].longLabel)
        assertEquals("This week", s.caption)
    }

    @Test
    fun week_onASunday_stillStartsOnThePreviousMonday() {
        val s = ExpenditureSeries.build(sample, me, SpendPeriod.WEEK, LocalDate.of(2026, 11, 29))
        assertEquals("Mon 23 Nov", s.points.first().longLabel)
        assertEquals(6, s.currentIndex)
        assertEquals(35_000L + 61_250 + 184_575 + 28_900, s.total)
    }

    @Test
    fun month_isWeeklyBuckets_lastOneRunsToTheEndOfTheMonth() {
        val s = series(SpendPeriod.MONTH)
        assertEquals(listOf("Week 1", "Week 2", "Week 3", "Week 4", "Week 5"), s.points.map { it.label })
        assertEquals(listOf("1–7 Nov", "8–14 Nov", "15–21 Nov", "22–28 Nov", "29–30 Nov"), s.points.map { it.longLabel })
        assertEquals(listOf<Long?>(100_000, 0, 0, 309_725, null), s.points.map { it.spent })
        assertEquals(3, s.currentIndex)
        assertEquals("November", s.caption)
    }

    @Test
    fun month_ofTwentyEightDays_hasFourBuckets() {
        val s = ExpenditureSeries.build(sample, me, SpendPeriod.MONTH, LocalDate.of(2027, 2, 10))
        assertEquals(4, s.points.size)
        assertEquals("22–28 Feb", s.points.last().longLabel)
    }

    @Test
    fun sixMonths_endsThisMonth_andCrossesTheYearBoundary() {
        val s = series(SpendPeriod.SIX_MONTHS)
        assertEquals(listOf("Jun", "Jul", "Aug", "Sep", "Oct", "Nov"), s.points.map { it.label })
        assertEquals(listOf<Long?>(700_000, 0, 0, 0, 500_000, 409_725), s.points.map { it.spent })
        assertEquals("Jun – Nov 2026", s.caption)
        assertEquals(5, s.currentIndex)

        val jan = ExpenditureSeries.build(sample, me, SpendPeriod.SIX_MONTHS, LocalDate.of(2027, 1, 10))
        assertEquals("Aug 2026 – Jan 2027", jan.caption)
    }

    @Test
    fun year_isJanToDec_withFutureMonthsEmpty() {
        val s = series(SpendPeriod.YEAR)
        assertEquals(12, s.points.size)
        assertEquals(900_000L, s.points[0].spent)
        assertEquals(409_725L, s.points[10].spent)
        assertNull(s.points[11].spent)
        assertEquals(10, s.currentIndex)
        assertEquals("2026", s.caption)
    }

    @Test
    fun total_isTheSumOfItsPoints_forEveryPeriod() {
        for (p in SpendPeriod.values()) {
            val s = series(p)
            assertEquals(p.name, s.points.sumOf { it.spent ?: 0L }, s.total)
        }
    }

    @Test
    fun monthTotal_matchesTheHomeBalanceCard() {
        val monthTotal = series(SpendPeriod.MONTH).total
        assertEquals(Ledger.monthTotals(sample, "2026-11").spent, monthTotal)
    }

    @Test
    fun onlyMyConfirmedExpensesCount() {
        val noise = listOf(
            txn("x1", 99_900, "2026-11-25", type = TxnType.INCOME),
            txn("x2", 99_900, "2026-11-25", type = TxnType.TRANSFER),
            txn("x3", 99_900, "2026-11-25", owner = "user-ana"),
            txn("x4", 99_900, "2026-11-25", status = "inbox"),
            txn("x5", 99_900, "2026-11-25", deleted = true),
        )
        assertEquals(series(SpendPeriod.WEEK).points.map { it.spent }, series(SpendPeriod.WEEK, sample + noise).points.map { it.spent })
    }

    @Test
    fun noRows_givesZeroForPastAndNullForFuture() {
        val s = series(SpendPeriod.WEEK, emptyList())
        assertEquals(listOf<Long?>(0, 0, 0, 0, null, null, null), s.points.map { it.spent })
        assertEquals(0L, s.total)
    }
}
