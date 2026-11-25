package com.chimu

/**
 * A monthly spending limit (RFC-009). Pure Kotlin, no Android types, so the rules can be tested without a phone.
 *
 * [groupId] null = personal. [category] null = overall (every expense category). [monthlyLimit] is centavos.
 * A stored limit of 0 or less is "unset": it has no percent, is never over and never alerts (the server allows 0,
 * the forms do not).
 */
data class Budget(
    val id: String,
    val ownerId: String,
    val groupId: String?,
    val category: String?,
    val monthlyLimit: Long,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)

enum class BudgetLevel { OK, WARN, OVER }

/** One budget's month: what was spent, the floored percent of the limit, and the level that percent reaches. */
data class BudgetUsage(val budget: Budget, val spent: Long, val percent: Int, val level: BudgetLevel) {
    /** Limit minus spent, in centavos. Negative when over. */
    val remaining: Long get() = budget.monthlyLimit - spent

    /** 0f..1f for a progress bar. The bar stops at full; the text shows the true percent. */
    val barFraction: Float
        get() = if (budget.monthlyLimit <= 0) 0f else (spent.toDouble() / budget.monthlyLimit).coerceIn(0.0, 1.0).toFloat()
}

/** The SQL the budgets table needs, as plain text so it can be run against a real SQLite (see [Db]). */
object BudgetSql {
    /** Budgets the phone shows and sums: live ones. Tombstones stay in the table for sync. */
    const val LIVE_WHERE = "deleted = 0"

    /** Tombstone one of the owner's own live budgets. Args: updated_at (ms), id, owner id. */
    const val TOMBSTONE =
        "UPDATE budgets SET deleted = 1, updated_at = ?, dirty = 1 WHERE id = ? AND owner_id = ? AND deleted = 0"

    /** Guards an edit so it only touches the owner's own row. Args: id, owner id. */
    const val OWN_ROW_WHERE = "id = ? AND owner_id = ?"
}

object BudgetRules {
    const val WARN_PERCENT = 80
    const val OVER_PERCENT = 100

    private val expenseSlugs = Categories.expense.map { it.slug }.toSet()

    /** floor(spent * 100 / limit), so 79.9% is 79 and stays below [WARN_PERCENT]. 0 for an unset limit. */
    fun percent(spent: Long, limit: Long): Int {
        if (limit <= 0) return 0
        return (spent.coerceAtLeast(0) * 100 / limit).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** OK below 80%, WARN from exactly 80%, OVER from exactly 100%. Done in whole numbers, never with a rounded percent. */
    fun level(spent: Long, limit: Long): BudgetLevel = when {
        limit <= 0 -> BudgetLevel.OK
        spent >= limit -> BudgetLevel.OVER
        spent * 100 >= WARN_PERCENT.toLong() * limit -> BudgetLevel.WARN
        else -> BudgetLevel.OK
    }

    /**
     * The live budgets [me] may see: their own (when personal, or for a group they are in) and other members'
     * budgets for a group [me] is in. Others' personal budgets are never shown (the server lets group mates read
     * them; the phone must not). A group budget for a group [me] is not in is hidden too: it has no members to sum.
     *
     * Order: personal first, then each group by name; within a scope the overall budget first, then by category
     * label; then mine before others', then by id, so the list never jumps around.
     */
    fun visible(me: String, budgets: List<Budget>, snapshot: GroupSnapshot): List<Budget> {
        val groupsById = snapshot.groups.associateBy { it.id }
        return budgets
            .filter { b ->
                !b.deleted && when (b.groupId) {
                    null -> b.ownerId == me
                    else -> b.groupId in groupsById
                }
            }
            .sortedWith(
                compareBy<Budget>(
                    { it.groupId != null },
                    { it.groupId?.let { id -> groupsById.getValue(id).name.lowercase() } ?: "" },
                    { it.groupId ?: "" },
                    { it.category != null },
                    { it.category?.let { slug -> Categories.label(slug).lowercase() } ?: "" },
                    { it.category ?: "" },
                    { it.ownerId != me },
                    { it.ownerId },
                    { it.id },
                ),
            )
    }

    /**
     * [visible] budgets with their usage in [month] (`YYYY-MM`). A personal budget sums [ownTxns]; a group budget sums
     * the snapshot's transactions of every member of that group. Only live, confirmed expenses count (an unconfirmed
     * Inbox item never does), and a missing or unknown category counts as "other", exactly like [TopCategories].
     */
    fun usageList(
        me: String,
        budgets: List<Budget>,
        ownTxns: List<Txn>,
        snapshot: GroupSnapshot,
        month: String,
    ): List<BudgetUsage> = visible(me, budgets, snapshot).map { budget ->
        val txns = if (budget.groupId == null) {
            ownTxns.filter { it.ownerId == budget.ownerId }
        } else {
            val members = snapshot.memberships.filter { it.groupId == budget.groupId }.map { it.userId }.toSet()
            snapshot.txns.filter { it.ownerId in members }
        }
        val spent = spent(txns, month, budget.category)
        BudgetUsage(budget, spent, percent(spent, budget.monthlyLimit), level(spent, budget.monthlyLimit))
    }

    /** What [txns] add up to for one category (null = all) in [month]. The caller has already narrowed to the right people. */
    private fun spent(txns: List<Txn>, month: String, category: String?): Long {
        var total = 0L
        for (txn in txns) {
            if (txn.deleted || txn.status == "inbox" || txn.type != TxnType.EXPENSE || Ledger.monthOf(txn.date) != month) continue
            val slug = if (txn.category in expenseSlugs) txn.category!! else Categories.OTHER
            if (category == null || category == slug) total += txn.amount
        }
        return total
    }

    /**
     * Why a draft can't be saved, in words for the user, or null if it is fine. Checks, in order: the limit is above
     * zero, the category is an expense category (or overall), the group is one of [me]'s, and [me] has no other live
     * budget for the same group and category. [editingId] is the budget being edited, which doesn't clash with itself.
     */
    fun check(
        me: String,
        groupId: String?,
        category: String?,
        limitCentavos: Long?,
        editingId: String?,
        existing: List<Budget>,
        snapshot: GroupSnapshot,
    ): String? {
        if (limitCentavos == null || limitCentavos <= 0) return LIMIT_HELP
        if (category != null && category !in expenseSlugs) return CATEGORY_HELP
        if (groupId != null && snapshot.groups.none { it.id == groupId }) return GROUP_HELP
        val clash = existing.any {
            !it.deleted && it.ownerId == me && it.id != editingId && it.groupId == groupId && it.category == category
        }
        if (clash) {
            val what = if (category == null) "Overall" else Categories.label(category)
            val where = if (groupId == null) "Personal" else "group ${snapshot.groups.first { it.id == groupId }.name}"
            return "You already have a budget for $what ($where). Edit that one instead."
        }
        return null
    }

    /** The [n] fullest budgets by percent, biggest first (Home's card). Equal percents keep the order they came in. */
    fun top(usages: List<BudgetUsage>, n: Int = 3): List<BudgetUsage> =
        usages.sortedByDescending { it.percent }.take(n.coerceAtLeast(0))

    const val LIMIT_HELP = "Enter a monthly limit above ₱0.00, like 6,000."
    const val CATEGORY_HELP = "Pick one of the expense categories, or leave it on Overall."
    const val GROUP_HELP = "You can only set a budget for a group you are in."
}

/** The words the budget screens and alerts use. Information, not advice (RULES 22). */
object BudgetText {
    /** "Overall" or the category's label. */
    fun title(usage: BudgetUsage, snapshot: GroupSnapshot): String = categoryTitle(usage.budget)

    /** "Personal" or the group's name. */
    fun scope(budget: Budget, snapshot: GroupSnapshot): String =
        if (budget.groupId == null) "Personal" else snapshot.groups.firstOrNull { it.id == budget.groupId }?.name ?: "Group"

    /** "₱4,800.00 of ₱6,000.00 · 80% used". */
    fun summary(usage: BudgetUsage): String =
        if (usage.budget.monthlyLimit <= 0) "${Money.format(usage.spent)} spent · no limit set"
        else "${Money.format(usage.spent)} of ${Money.format(usage.budget.monthlyLimit)} · ${usage.percent}% used"

    /** Words, so the state never rests on color alone (RULES 21). */
    fun status(usage: BudgetUsage): String = when {
        usage.budget.monthlyLimit <= 0 -> "No limit set"
        usage.level == BudgetLevel.OVER && usage.remaining < 0 -> "Over the limit by ${Money.format(-usage.remaining)}"
        usage.level == BudgetLevel.OVER -> "At the limit"
        usage.level == BudgetLevel.WARN -> "Close to the limit"
        else -> "On track"
    }

    /** One sentence for a screen reader. */
    fun describe(usage: BudgetUsage, snapshot: GroupSnapshot): String {
        val who = if (usage.budget.groupId == null) "personal" else "group ${scope(usage.budget, snapshot)}"
        val head = "${title(usage, snapshot)} budget, $who."
        return if (usage.budget.monthlyLimit <= 0) "$head ${summary(usage)}."
        else "$head ${Money.format(usage.spent)} spent of ${Money.format(usage.budget.monthlyLimit)}, ${usage.percent} percent used. ${status(usage)}."
    }

    /** "Food & Dining budget", or "Group Family · Food & Dining budget" for a group's. */
    fun alertName(usage: BudgetUsage, snapshot: GroupSnapshot): String {
        val base = "${title(usage, snapshot)} budget"
        return if (usage.budget.groupId == null) base else "Group ${scope(usage.budget, snapshot)} · $base"
    }

    /** "Food & Dining budget: 80% used". */
    fun alertTitle(usage: BudgetUsage, snapshot: GroupSnapshot): String =
        "${alertName(usage, snapshot)}: ${usage.percent}% used"

    /** "₱4,800.00 of ₱6,000.00 spent in November 2026. Close to the limit." */
    fun alertBody(usage: BudgetUsage, month: String): String =
        "${Money.format(usage.spent)} of ${Money.format(usage.budget.monthlyLimit)} spent in ${Ledger.monthLabel(month)}. ${status(usage)}."

    private fun categoryTitle(budget: Budget): String =
        if (budget.category == null) "Overall" else Categories.label(budget.category)
}
