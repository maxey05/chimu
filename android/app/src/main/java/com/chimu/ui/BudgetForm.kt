package com.chimu.ui

import com.chimu.Budget
import com.chimu.BudgetRules
import com.chimu.BudgetText
import com.chimu.BudgetUsage
import com.chimu.Categories
import com.chimu.GroupSnapshot
import com.chimu.GroupText
import com.chimu.Money

/*
 * The pure side of the Budgets screens (RFC-009): what the form offers, how a typed limit becomes a
 * saved budget, who may edit a row, and how the list is grouped. No Compose in here, so it can be
 * tested without a phone. The usage maths and the duplicate/limit checks live in Budgets.kt
 * ([BudgetRules]); this file only calls them.
 */

/** One entry in a form dropdown. A null [key] is "Personal" for the scope and "Overall" for the category. */
data class BudgetOption(val key: String?, val label: String)

/** What the form made of what was typed: a budget that can be saved, or the sentence to show. */
sealed interface BudgetDraft {
    data class Ok(val groupId: String?, val category: String?, val limit: Long) : BudgetDraft
    data class Invalid(val message: String) : BudgetDraft
}

/** One heading of the Budgets list with its rows. [groupId] null is the Personal section. */
data class BudgetSection(val groupId: String?, val title: String, val usages: List<BudgetUsage>)

object BudgetFormRules {
    const val LIMIT_HELP = "The most you plan to spend each month, like 6,000 or 6,000.50."
    const val LIMIT_INVALID = "Enter the monthly limit as an amount like 6,000 or 6,000.50, with at most 2 decimals."
    const val PERSONAL_NOTE = "Counts only what you spend."
    const val GROUP_NOTE = "Counts what every member of the group spends."
    const val EMPTY =
        "No budgets yet. A budget is a monthly limit for one category, or for everything you spend. " +
            "Chimu tells you when you reach 80% and again at 100% of it."
    const val ALERTS_OFF =
        "Alerts are off: Chimu can't send notifications. Allow them in the phone's settings to get warned at 80% and 100%."
    const val NOT_YOURS = "Only the person who set this budget can change or delete it."

    /** Personal first, then each of the user's groups (the snapshot already has them by name). */
    fun scopeOptions(snapshot: GroupSnapshot): List<BudgetOption> =
        listOf(BudgetOption(null, "Personal")) + snapshot.groups.map { BudgetOption(it.id, "Group: ${it.name}") }

    /** Overall first, then every expense category in the app's own order. */
    fun categoryOptions(): List<BudgetOption> =
        listOf(BudgetOption(null, "Overall (all categories)")) + Categories.expense.map { BudgetOption(it.slug, it.label) }

    fun scopeLabel(groupId: String?, snapshot: GroupSnapshot): String =
        if (groupId == null) "Personal" else "Group: ${snapshot.groups.firstOrNull { it.id == groupId }?.name ?: "a group"}"

    fun categoryLabel(category: String?): String =
        if (category == null) "Overall (all categories)" else Categories.label(category)

    /** The line under the scope choice: says what the budget counts. Information, not advice. */
    fun scopeNote(groupId: String?): String = if (groupId == null) PERSONAL_NOTE else GROUP_NOTE

    /** Only the person who set a budget can change it (the server's rule too). */
    fun isMine(budget: Budget, me: String): Boolean = budget.ownerId == me

    /** "Set by Kim" for a budget someone else made; names come from the group snapshot. */
    fun setBy(budget: Budget, snapshot: GroupSnapshot): String =
        "Set by ${GroupText.personLabel(snapshot.names[budget.ownerId], isMe = false)}"

    /** What TalkBack reads for a list row: the full usage sentence, plus who set it when it is not the user's. */
    fun rowDescription(usage: BudgetUsage, snapshot: GroupSnapshot, me: String): String {
        val base = BudgetText.describe(usage, snapshot)
        return if (isMine(usage.budget, me)) base else "${base.trimEnd('.', ' ')}. ${setBy(usage.budget, snapshot)}. You can't change it."
    }

    /**
     * Turns the form's fields into something to save. The typed limit goes through [Money.parse]; the
     * rest (limit above zero, a real category, one of my groups, no duplicate) is [BudgetRules.check]'s
     * message, shown as it is. [editingId] is the budget being edited, which is not its own duplicate.
     */
    fun draft(
        me: String,
        groupId: String?,
        category: String?,
        limitText: String,
        editingId: String?,
        existing: List<Budget>,
        snapshot: GroupSnapshot,
    ): BudgetDraft {
        val limit = Money.parse(limitText) ?: return BudgetDraft.Invalid(LIMIT_INVALID)
        BudgetRules.check(me, groupId, category, limit, editingId, existing, snapshot)?.let { return BudgetDraft.Invalid(it) }
        return BudgetDraft.Ok(groupId, category, limit)
    }

    /**
     * The list's sections in the order [usages] came in (Personal, then each group by name), one per scope.
     * Grouped by group id, not by name, so two groups with the same name stay apart.
     */
    fun sections(usages: List<BudgetUsage>, snapshot: GroupSnapshot): List<BudgetSection> {
        val byScope = LinkedHashMap<String?, MutableList<BudgetUsage>>()
        for (usage in usages) byScope.getOrPut(usage.budget.groupId) { mutableListOf() }.add(usage)
        return byScope.map { (groupId, rows) ->
            BudgetSection(groupId, BudgetText.scope(rows.first().budget, snapshot), rows)
        }
    }
}
