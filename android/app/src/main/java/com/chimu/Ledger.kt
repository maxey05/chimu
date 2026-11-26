package com.chimu

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Transactions screen filters (DESIGN.md: month, wallet, category, type, search). Null / blank = no filter. */
data class TxnFilter(
    /** `YYYY-MM`. */
    val month: String? = null,
    val walletId: String? = null,
    val category: String? = null,
    val type: TxnType? = null,
    val search: String = "",
)

data class MonthTotals(val spent: Long, val income: Long)

/** The three filters that open a menu on the Transactions screen. */
enum class FilterPart { MONTH, TYPE, CATEGORY }

/** List and summary logic for the Home and Transactions screens, kept free of Android types. */
object Ledger {
    /** `2026-11-23` -> `2026-11`. */
    fun monthOf(date: String): String = date.take(7)

    /** `2026-11` -> `November 2026`. Anything unparseable is returned as it came. */
    fun monthLabel(month: String): String =
        runCatching { YearMonth.parse(month).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)) }
            .getOrDefault(month)

    /** `2026-11-03` -> `Nov 3, 2026`. Anything unparseable is returned as it came. */
    fun dateLabel(date: String): String =
        runCatching { LocalDate.parse(date).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)) }
            .getOrDefault(date)

    /** Non-deleted rows matching [filter], newest first. */
    fun filter(txns: List<Txn>, filter: TxnFilter): List<Txn> {
        val needle = filter.search.trim().lowercase()
        return txns
            .filter { !it.deleted }
            .filter { filter.month == null || monthOf(it.date) == filter.month }
            .filter {
                // A transfer shows up under both of its wallets.
                filter.walletId == null || it.walletId == filter.walletId || it.toWalletId == filter.walletId
            }
            .filter { filter.category == null || it.category == filter.category }
            .filter { filter.type == null || it.type == filter.type }
            .filter {
                needle.isEmpty() ||
                    (it.merchant?.lowercase()?.contains(needle) == true) ||
                    (it.note?.lowercase()?.contains(needle) == true)
            }
            .sortedWith(compareByDescending<Txn> { it.date }.thenByDescending { it.updatedAt })
    }

    /** Spent and income for one month. Transfers move money between wallets and count as neither. */
    fun monthTotals(txns: List<Txn>, month: String): MonthTotals {
        var spent = 0L
        var income = 0L
        for (txn in txns) {
            if (txn.deleted || monthOf(txn.date) != month) continue
            when (txn.type) {
                TxnType.EXPENSE -> spent += txn.amount
                TxnType.INCOME -> income += txn.amount
                TxnType.TRANSFER -> Unit
            }
        }
        return MonthTotals(spent, income)
    }

    /** Distinct `YYYY-MM` months that have at least one non-deleted row, newest first. */
    fun months(txns: List<Txn>): List<String> =
        txns.filter { !it.deleted }.map { monthOf(it.date) }.distinct().sortedDescending()

    /**
     * How many rows each choice of one filter would show right now, keeping the other filters (and the search) as they
     * are. The `null` key is "All". Choices with no rows are simply missing from the map.
     */
    fun optionCounts(txns: List<Txn>, current: TxnFilter, part: FilterPart): Map<String?, Int> {
        val base = when (part) {
            FilterPart.MONTH -> filter(txns, current.copy(month = null))
            FilterPart.TYPE -> filter(txns, current.copy(type = null))
            FilterPart.CATEGORY -> filter(txns, current.copy(category = null))
        }
        val counts = HashMap<String?, Int>()
        counts[null] = base.size
        for (t in base) {
            val key = when (part) {
                FilterPart.MONTH -> monthOf(t.date)
                FilterPart.TYPE -> t.type.slug
                FilterPart.CATEGORY -> t.category
            } ?: continue
            counts[key] = (counts[key] ?: 0) + 1
        }
        return counts
    }
}
