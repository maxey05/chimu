package com.chimu.ui

import com.chimu.Goal
import com.chimu.GoalRules
import com.chimu.Money
import com.chimu.Txn
import com.chimu.TxnType
import com.chimu.Wallet
import com.chimu.WalletType

/*
 * The pure side of the Goals screens (RFC-011): what the goal form offers, how typed text becomes a goal, and how the
 * "Add to goal" form becomes a transfer. No Compose in here, so it can be tested without a phone. The progress maths and
 * the wallet check live in Goals.kt ([GoalRules]); this file only calls them.
 */

/** One entry in the goal form's wallet dropdown. */
data class GoalOption(val key: String, val label: String)

/** What the goal form made of what was typed: a goal that can be saved, or the sentence to show. */
sealed interface GoalDraft {
    /**
     * [walletId] is the existing savings wallet to link, or null when [newWalletName] says a new one must be made (the screen
     * gives it the id it keeps for the form, so a retry never makes a second wallet).
     */
    data class Ok(
        val name: String,
        val target: Long,
        val deadline: String?,
        val walletId: String?,
        val newWalletName: String?,
    ) : GoalDraft

    data class Invalid(val message: String) : GoalDraft
}

/** What the "Add to goal" form made of what was typed: the transfer to save, or the sentence to show. */
sealed interface ContributionDraft {
    data class Ok(val fromWalletId: String, val amount: Long, val date: String) : ContributionDraft
    data class Invalid(val message: String) : ContributionDraft
}

object GoalFormRules {
    const val NAME_MAX = 60

    /** The key of the "make a new savings wallet" entry in the wallet dropdown. Real wallet ids are UUIDs, so it never clashes. */
    const val NEW_WALLET = "new-savings-wallet"

    const val NAME_INVALID = "Give the goal a name of 1 to 60 characters, like Baguio trip."
    const val TARGET_INVALID = "Enter the target as an amount above ₱0.00, like 20,000 or 20,000.50."
    const val DEADLINE_INVALID = "Pick a deadline that is today or later, or leave it empty."
    const val WALLET_NAME_INVALID = "Give the savings wallet a name of 1 to 60 characters, or leave it empty to use the goal's name."
    const val WALLET_HELP = "Progress is this wallet's whole balance. Money you move into it counts toward the goal."
    const val NO_SAVINGS_WALLET =
        "You have no savings wallet yet. Chimu will make one for this goal. Money you move into it counts toward the goal."
    const val EMPTY =
        "No goals yet. A goal is an amount you want to save, like ₱20,000 for a trip. " +
            "Pick a savings wallet for it, move money into that wallet, and the bar fills."
    const val NO_SOURCE_WALLET = "You need another wallet to move money from. Add one under More, then Wallets."
    const val GOAL_WALLET_GONE = "This goal's savings wallet was archived, so money can't be added. Edit the goal to choose another wallet."
    const val FROM_INVALID = "Choose the wallet the money comes from."
    const val AMOUNT_INVALID = "Enter an amount above ₱0.00 to add, like 1,500 or 1,500.50."
    const val DATE_INVALID = "Pick a date for this transfer."

    /** The user's live savings wallets, by name, for the form's dropdown. */
    fun savingsWallets(wallets: List<Wallet>): List<Wallet> =
        wallets.filter { !it.deleted && it.type == WalletType.SAVINGS }.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))

    /** The wallet choices: each savings wallet, then "New savings wallet" (the only choice when there is none). */
    fun walletChoices(wallets: List<Wallet>): List<GoalOption> =
        savingsWallets(wallets).map { GoalOption(it.id, it.name) } + GoalOption(NEW_WALLET, "New savings wallet")

    /** What the wallet dropdown starts on: the goal's own wallet when it still counts, else the first savings wallet, else a new one. */
    fun initialWalletKey(existing: Goal?, wallets: List<Wallet>): String {
        val usable = savingsWallets(wallets)
        return when {
            existing != null && usable.any { it.id == existing.walletId } -> existing.walletId
            usable.isNotEmpty() -> usable.first().id
            else -> NEW_WALLET
        }
    }

    /**
     * Turns the form's fields into something to save. Checks, in order: the name (1 to 60 once trimmed), the target (typed
     * amount above zero), the deadline (a real date, today or later; an edit may keep the deadline the goal already has,
     * even if it has passed), the wallet (an existing live savings wallet, or a new one named 1 to 60 characters, blank
     * meaning the goal's name). [today] is `YYYY-MM-DD`.
     */
    fun draft(
        name: String,
        targetText: String,
        deadline: String?,
        walletKey: String?,
        newWalletName: String,
        wallets: List<Wallet>,
        today: String,
        existing: Goal?,
    ): GoalDraft {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > NAME_MAX) return GoalDraft.Invalid(NAME_INVALID)
        val target = Money.parse(targetText)
        if (target == null || target <= 0) return GoalDraft.Invalid(TARGET_INVALID)
        if (deadline != null) {
            val keepsOld = existing != null && existing.deadline == deadline
            if (!GoalRules.isDate(deadline) || (deadline < today && !keepsOld)) return GoalDraft.Invalid(DEADLINE_INVALID)
        }
        if (walletKey == NEW_WALLET) {
            val walletName = newWalletName.trim().ifEmpty { trimmed }
            if (walletName.length > NAME_MAX) return GoalDraft.Invalid(WALLET_NAME_INVALID)
            return GoalDraft.Ok(trimmed, target, deadline, walletId = null, newWalletName = walletName)
        }
        val wallet = savingsWallets(wallets).firstOrNull { it.id == walletKey }
            ?: return GoalDraft.Invalid(GoalRules.WALLET_HELP)
        return GoalDraft.Ok(trimmed, target, deadline, walletId = wallet.id, newWalletName = null)
    }

    /** Wallets the money can come from: every live wallet except the goal's own, by name. */
    fun sourceWallets(goal: Goal, wallets: List<Wallet>): List<Wallet> =
        wallets.filter { !it.deleted && it.id != goal.walletId }.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))

    /** True when the goal's savings wallet is live, so money can still be moved into it. */
    fun canAdd(goal: Goal, wallets: List<Wallet>): Boolean = wallets.any { it.id == goal.walletId && !it.deleted }

    /**
     * Turns the "Add to goal" form into the transfer to save (from [fromWalletId] into the goal's wallet). Checks, in order:
     * the goal's wallet is live, the source wallet is one of [sourceWallets], the amount is above zero, the date is real.
     */
    fun contribution(
        goal: Goal,
        fromWalletId: String?,
        amountText: String,
        date: String?,
        wallets: List<Wallet>,
    ): ContributionDraft {
        if (!canAdd(goal, wallets)) return ContributionDraft.Invalid(GOAL_WALLET_GONE)
        val from = sourceWallets(goal, wallets).firstOrNull { it.id == fromWalletId }
            ?: return ContributionDraft.Invalid(FROM_INVALID)
        val amount = Money.parse(amountText)
        if (amount == null || amount <= 0) return ContributionDraft.Invalid(AMOUNT_INVALID)
        if (!GoalRules.isDate(date)) return ContributionDraft.Invalid(DATE_INVALID)
        return ContributionDraft.Ok(from.id, amount, date!!)
    }

    /** The note a goal transfer carries, so the Transactions list says what it was for. */
    fun contributionNote(goal: Goal): String = "Toward goal: ${goal.name}"

    /**
     * The entry "Add to goal" saves: an ordinary confirmed `transfer` from the chosen wallet into the goal's wallet, built the same
     * way the entry form builds one (no category or merchant, not business). [ownerId] is stamped again by the repo.
     */
    fun contributionTxn(goal: Goal, draft: ContributionDraft.Ok, id: String): Txn = Txn(
        id = id,
        ownerId = goal.ownerId,
        type = TxnType.TRANSFER,
        walletId = draft.fromWalletId,
        toWalletId = goal.walletId,
        amount = draft.amount,
        date = draft.date,
        category = null,
        merchant = null,
        note = contributionNote(goal),
        isBusiness = false,
        source = "manual",
        status = "confirmed",
    )
}
