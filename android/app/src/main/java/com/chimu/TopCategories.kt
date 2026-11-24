package com.chimu

/** One category's spending in one month. [slug] is always one of the expense category slugs. */
data class CategorySpend(val slug: String, val label: String, val spent: Long)

/**
 * "Top categories this month" for Home (RFC-008). Pure, so it can be tested without a phone.
 *
 * The rules are the same as [Ledger.monthTotals]: only the user's own rows, deleted rows and
 * unconfirmed Inbox items never count, transfers and income never count, and the month is the
 * first 7 characters of the stored date. A missing or unknown category slug counts as "Other",
 * so the categories always add up to the month's spent total.
 */
object TopCategories {
    /** How many bars Home shows. */
    const val HOME_LIMIT = 5

    private val expenseSlugs = Categories.expense.map { it.slug }.toSet()

    /**
     * Everything spent in [month] (`YYYY-MM`) by [ownerId], one entry per category, biggest first.
     * Ties are ordered by label (ignoring case), then by slug, so the order never jumps around.
     * A category with nothing spent (a scan that has not been read yet is logged as 0) is left out.
     */
    fun spentByCategory(txns: List<Txn>, month: String, ownerId: String): List<CategorySpend> {
        val byCategory = HashMap<String, Long>()
        for (txn in txns) {
            if (txn.ownerId != ownerId || txn.deleted || txn.status == "inbox") continue
            if (txn.type != TxnType.EXPENSE || Ledger.monthOf(txn.date) != month) continue
            val slug = if (txn.category in expenseSlugs) txn.category!! else Categories.OTHER
            byCategory[slug] = (byCategory[slug] ?: 0L) + txn.amount
        }
        return byCategory
            .filterValues { it > 0 }
            .map { (slug, spent) -> CategorySpend(slug, Categories.label(slug), spent) }
            .sortedWith(compareByDescending<CategorySpend> { it.spent }.thenBy { it.label.lowercase() }.thenBy { it.slug })
    }

    /** The first [limit] entries of [spentByCategory]: what Home draws. */
    fun top(txns: List<Txn>, month: String, ownerId: String, limit: Int = HOME_LIMIT): List<CategorySpend> =
        spentByCategory(txns, month, ownerId).take(limit)

    /** The share of [total] that [spent] is, as a whole percent, rounded half up. 0 when there is no total. */
    fun percent(spent: Long, total: Long): Int =
        if (total <= 0) 0 else ((spent * 100 + total / 2) / total).toInt()

    /** What a screen reader says for one bar. The amount is written out, so the bar's length is not the only way to read it. */
    fun describe(entry: CategorySpend, total: Long): String =
        "${entry.label}, ${Money.format(entry.spent)}, ${percent(entry.spent, total)} percent of this month's spending"
}
