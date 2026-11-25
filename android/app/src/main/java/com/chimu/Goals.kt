package com.chimu

import java.time.LocalDate

/**
 * A savings goal (RFC-011). Pure Kotlin, no Android types, so the rules can be tested without a phone.
 *
 * A goal points at one of the owner's `savings` wallets. Its progress is never stored: it is that wallet's balance
 * ([Balances]) against [target]. [target] is centavos. A stored target of 0 or less is "no target set": the server
 * allows it, the forms do not. [deadline] is `YYYY-MM-DD`, or null for "no deadline".
 */
data class Goal(
    val id: String,
    val ownerId: String,
    val name: String,
    val target: Long,
    val deadline: String?,
    val walletId: String,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)

/** What the goal asks of the person from here on. Computed by [GoalRules.plan]; the screen and the web say it in words. */
sealed interface GoalPlan {
    /** The savings wallet is archived or unknown, so there is no balance to measure. */
    object WalletUnavailable : GoalPlan

    /** The target is 0 or less (only possible from outside the app). */
    object NoTarget : GoalPlan

    /** The wallet holds the target or more. */
    object Reached : GoalPlan

    /** Not reached and no deadline: nothing to pace against. */
    object NoDeadline : GoalPlan

    /** Not reached and the deadline is before today. */
    object Overdue : GoalPlan

    /** Not reached, with a deadline today or later: [perMonth] centavos (rounded up) for each of [months] whole months. */
    data class Monthly(val perMonth: Long, val months: Int) : GoalPlan
}

/**
 * One goal's progress. [saved] is the savings wallet's balance (it can be negative, or 0 when the wallet is unavailable),
 * [left] is what is still missing (never negative), [percent] is the floored whole percent of the target (0 when the
 * balance is not above zero), [reached] says the balance is at least the target.
 */
data class GoalProgress(
    val goal: Goal,
    val saved: Long,
    val left: Long,
    val percent: Int,
    val reached: Boolean,
    val plan: GoalPlan,
) {
    /** 0f..1f for a progress bar. The bar stops at full; the text shows the true percent. */
    val barFraction: Float
        get() = if (goal.target <= 0) 0f else (saved.toDouble() / goal.target).coerceIn(0.0, 1.0).toFloat()
}

/** The SQL the goals table needs, as plain text so it can be run against a real SQLite (see GoalsDb.kt). */
object GoalSql {
    /** The owner's live goals. Tombstones stay in the table for sync, and other members' cached goals are not the user's to list. */
    const val OWN_LIVE_WHERE = "owner_id = ? AND deleted = 0"

    /** Tombstone one of the owner's own live goals. Args: updated_at (ms), id, owner id. */
    const val TOMBSTONE =
        "UPDATE goals SET deleted = 1, updated_at = ?, dirty = 1 WHERE id = ? AND owner_id = ? AND deleted = 0"

    /** Guards an edit so it only touches the owner's own row. Args: id, owner id. */
    const val OWN_ROW_WHERE = "id = ? AND owner_id = ?"
}

object GoalRules {
    private val DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

    /** True for a real calendar date written `YYYY-MM-DD`. */
    fun isDate(text: String?): Boolean {
        if (text == null || !DATE.matches(text)) return false
        return runCatching { LocalDate.parse(text) }.isSuccess
    }

    /**
     * floor(saved * 100 / target): 79.9% is 79. 0 when [saved] is not above zero or the target is 0 or less. It can pass 100
     * (a goal saved past its target); the bar stops at full, the text keeps the true number.
     */
    fun percent(saved: Long, target: Long): Int {
        if (target <= 0 || saved <= 0) return 0
        return (saved * 100 / target).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** What is still missing: target minus saved, never below 0. 0 when there is no target. */
    fun left(saved: Long, target: Long): Long = if (target <= 0) 0 else (target - saved).coerceAtLeast(0)

    /** The wallet holds the whole target or more. A goal with no target is never reached. */
    fun reached(saved: Long, target: Long): Boolean = target > 0 && saved >= target

    /**
     * Whole months from [today] to [deadline] (both `YYYY-MM-DD`): the difference in calendar months, minus one when the
     * deadline's day of the month is earlier than today's (that last month is not complete), and never below 1, so a
     * deadline this month, or today, counts as 1. Jan 25 to Apr 25 is 3; Jan 25 to Apr 24 is 2; Jan 25 to Jan 31 is 1.
     * Null when either date is not a real `YYYY-MM-DD` date. A deadline before today is the caller's business ([plan]).
     */
    fun monthsLeft(today: String, deadline: String): Int? {
        if (!isDate(today) || !isDate(deadline)) return null
        val t = DATE.matchEntire(today)!!.groupValues
        val d = DATE.matchEntire(deadline)!!.groupValues
        var months = (d[1].toInt() - t[1].toInt()) * 12 + (d[2].toInt() - t[2].toInt())
        if (d[3].toInt() < t[3].toInt()) months -= 1
        return months.coerceAtLeast(1)
    }

    /** [left] split over [months], rounded UP to the centavo so that [months] payments always reach the goal. */
    fun neededPerMonth(left: Long, months: Int): Long {
        if (left <= 0 || months <= 0) return 0
        return (left + months - 1) / months
    }

    /**
     * What the goal asks from here, in this order: wallet unavailable ([saved] null), no target, reached, no (or
     * unreadable) deadline, deadline before [today] (overdue), else the monthly amount. [today] is `YYYY-MM-DD`.
     */
    fun plan(target: Long, saved: Long?, deadline: String?, today: String): GoalPlan {
        if (saved == null) return GoalPlan.WalletUnavailable
        if (target <= 0) return GoalPlan.NoTarget
        if (reached(saved, target)) return GoalPlan.Reached
        if (!isDate(deadline) || !isDate(today)) return GoalPlan.NoDeadline
        if (deadline!! < today) return GoalPlan.Overdue
        val months = monthsLeft(today, deadline) ?: return GoalPlan.NoDeadline
        return GoalPlan.Monthly(neededPerMonth(left(saved, target), months), months)
    }

    /** One goal against its wallet's [balance] (null when the wallet is archived or not known). */
    fun progress(goal: Goal, balance: Long?, today: String): GoalProgress {
        val saved = balance ?: 0L
        return GoalProgress(
            goal = goal,
            saved = saved,
            left = if (balance == null) goal.target.coerceAtLeast(0) else left(saved, goal.target),
            percent = percent(saved, goal.target),
            reached = balance != null && reached(saved, goal.target),
            plan = plan(goal.target, balance, goal.deadline, today),
        )
    }

    /**
     * Progress for each of [goals], in the order given. Balances come from [Balances.forWallets] over [wallets] and [txns], so
     * the goal's wallet needs to be among [wallets] and live: an archived or unknown wallet gives [GoalPlan.WalletUnavailable].
     */
    fun progressList(goals: List<Goal>, wallets: List<Wallet>, txns: List<Txn>, today: String): List<GoalProgress> {
        val balances = Balances.forWallets(wallets, txns)
        return goals.map { progress(it, balances[it.walletId], today) }
    }

    /**
     * The live goals [me] owns, soonest deadline first (goals with no deadline last), then by name, then id, so the list never
     * jumps around. Other members' goals can be in the cache (sync pulls the rows of everyone in a group): they are not listed
     * on the phone.
     */
    fun visible(me: String, goals: List<Goal>): List<Goal> =
        goals
            .filter { !it.deleted && it.ownerId == me }
            .sortedWith(compareBy<Goal>({ it.deadline == null }, { it.deadline ?: "" }, { it.name.lowercase() }, { it.id }))

    /**
     * Why [walletId] can't back a goal, in words for the user, or null if it can. A new wallet ([newWallet]) must be a savings
     * wallet with that id; an existing one must be among [wallets], live, and of type savings.
     */
    fun checkWallet(walletId: String, newWallet: Wallet?, wallets: List<Wallet>): String? {
        if (newWallet != null) {
            return if (newWallet.id == walletId && newWallet.type == WalletType.SAVINGS) null else WALLET_HELP
        }
        val wallet = wallets.firstOrNull { it.id == walletId }
        return if (wallet != null && !wallet.deleted && wallet.type == WalletType.SAVINGS) null else WALLET_HELP
    }

    const val WALLET_HELP = "Pick one of your savings wallets, or let Chimu make a new one."
}

/** The words the goal screens use. Information, not advice (RULES 22). The web dashboard's goals.js says the same things. */
object GoalText {
    /** "₱2,900.00 of ₱12,000.00 · 24% saved". */
    fun summary(p: GoalProgress): String = when {
        p.plan == GoalPlan.WalletUnavailable -> "Target ${Money.format(p.goal.target)}"
        p.goal.target <= 0 -> "${Money.format(p.saved)} saved · no target set"
        else -> "${Money.format(p.saved)} of ${Money.format(p.goal.target)} · ${p.percent}% saved"
    }

    /** The state in words, so it never rests on colour (RULES 21). */
    fun status(p: GoalProgress): String = when (p.plan) {
        GoalPlan.WalletUnavailable -> "Savings wallet unavailable"
        GoalPlan.NoTarget -> "No target set"
        GoalPlan.Reached -> "Goal reached"
        else -> "${Money.format(p.left)} left"
    }

    /** "Needed per month: ₱3,033.34 (3 months left)", "No deadline", ...; null when there is nothing to add (goal reached). */
    fun plan(p: GoalProgress): String? = when (val plan = p.plan) {
        GoalPlan.WalletUnavailable -> "Edit this goal to choose another savings wallet."
        GoalPlan.NoTarget -> null
        GoalPlan.Reached -> null
        GoalPlan.NoDeadline -> "No deadline"
        GoalPlan.Overdue -> "Deadline passed (${Ledger.dateLabel(p.goal.deadline ?: "")})"
        is GoalPlan.Monthly ->
            "Needed per month: ${Money.format(plan.perMonth)} (${plan.months} ${if (plan.months == 1) "month" else "months"} left)"
    }

    /** "Deadline: Apr 20, 2027", or null when the goal has none. */
    fun deadline(goal: Goal): String? = goal.deadline?.takeIf { GoalRules.isDate(it) }?.let { "Deadline: ${Ledger.dateLabel(it)}" }

    /** One sentence for a screen reader. */
    fun describe(p: GoalProgress, walletName: String?): String {
        val parts = mutableListOf("${p.goal.name} goal.")
        parts += summary(p).replace(" · ", ", ") + "."
        parts += status(p) + "."
        plan(p)?.let { parts += "$it." }
        deadline(p.goal)?.let { parts += "$it." }
        if (walletName != null) parts += "Savings wallet: $walletName."
        return parts.joinToString(" ")
    }
}
