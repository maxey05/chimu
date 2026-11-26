package com.chimu

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** The four tabs on Home's Expenditures chart. */
enum class SpendPeriod(val label: String) {
    WEEK("Week"),
    MONTH("Month"),
    SIX_MONTHS("6 Months"),
    YEAR("Year"),
}

/**
 * One point on the chart. [spent] is centavos, or null when the point is still in the future
 * (the chart draws nothing there and says "no data yet").
 */
data class SpendPoint(val label: String, val longLabel: String, val spent: Long?)

data class SpendSeries(
    val period: SpendPeriod,
    /** "This week", "November", "Jun – Nov 2026" or "2026", shown beside the title. */
    val caption: String,
    val points: List<SpendPoint>,
    /** The point that holds today; selected by default. */
    val currentIndex: Int,
) {
    /** Everything spent in the period so far, in centavos. */
    val total: Long get() = points.sumOf { it.spent ?: 0L }
}

/**
 * The numbers behind Home's Expenditures chart. Pure, so it is tested without a phone.
 *
 * Same rules as [TopCategories]: only the user's own rows, deleted rows and unconfirmed Inbox items
 * never count, and only expenses count (income, and transfers between wallets, are not spending).
 * Money stays integer centavos (RULES 7); the date is the stored `YYYY-MM-DD` text, compared as text.
 */
object ExpenditureSeries {
    private val en = Locale.ENGLISH
    private val monthShort = DateTimeFormatter.ofPattern("MMM", en)
    private val monthYear = DateTimeFormatter.ofPattern("MMM yyyy", en)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", en)
    private val weekdayDayMonth = DateTimeFormatter.ofPattern("EEE d MMM", en)

    fun build(txns: List<Txn>, ownerId: String, period: SpendPeriod, today: LocalDate): SpendSeries {
        val spending = txns.filter {
            it.ownerId == ownerId && !it.deleted && it.status != "inbox" && it.type == TxnType.EXPENSE
        }
        fun sum(from: LocalDate, to: LocalDate): Long {
            val a = from.toString()
            val b = to.toString()
            return spending.filter { it.date >= a && it.date <= b }.sumOf { it.amount }
        }
        /** A point that starts after today has no data yet. */
        fun point(label: String, longLabel: String, from: LocalDate, to: LocalDate) =
            SpendPoint(label, longLabel, if (from.isAfter(today)) null else sum(from, to))

        return when (period) {
            SpendPeriod.WEEK -> {
                val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val points = (0..6).map { i ->
                    val day = monday.plusDays(i.toLong())
                    point(day.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }, day.format(weekdayDayMonth), day, day)
                }
                SpendSeries(period, "This week", points, today.dayOfWeek.value - 1)
            }

            SpendPeriod.MONTH -> {
                val month = YearMonth.from(today)
                val last = month.lengthOfMonth()
                val starts = if (last > 28) listOf(1, 8, 15, 22, 29) else listOf(1, 8, 15, 22)
                val points = starts.mapIndexed { i, start ->
                    val end = if (i == starts.lastIndex) last else start + 6
                    val from = month.atDay(start)
                    val to = month.atDay(end)
                    point("Week ${i + 1}", "${from.dayOfMonth}–${to.format(dayMonth)}", from, to)
                }
                val current = starts.indexOfLast { it <= today.dayOfMonth }
                SpendSeries(period, month.atDay(1).format(DateTimeFormatter.ofPattern("MMMM", en)), points, current)
            }

            SpendPeriod.SIX_MONTHS -> {
                val thisMonth = YearMonth.from(today)
                val months = (5 downTo 0).map { thisMonth.minusMonths(it.toLong()) }
                val points = months.map { m -> point(m.format(monthShort), m.format(monthYear), m.atDay(1), m.atEndOfMonth()) }
                val first = months.first()
                val caption = if (first.year == thisMonth.year) {
                    "${first.format(monthShort)} – ${thisMonth.format(monthShort)} ${thisMonth.year}"
                } else {
                    "${first.format(monthYear)} – ${thisMonth.format(monthYear)}"
                }
                SpendSeries(period, caption, points, 5)
            }

            SpendPeriod.YEAR -> {
                val points = (1..12).map { m ->
                    val ym = YearMonth.of(today.year, m)
                    point(ym.format(monthShort), ym.format(monthYear), ym.atDay(1), ym.atEndOfMonth())
                }
                SpendSeries(period, today.year.toString(), points, today.monthValue - 1)
            }
        }
    }
}
