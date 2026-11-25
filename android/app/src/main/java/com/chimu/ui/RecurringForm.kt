package com.chimu.ui

import com.chimu.Categories
import com.chimu.Money
import com.chimu.RecurringDates
import com.chimu.RecurringFrequency
import com.chimu.RecurringRule
import com.chimu.RecurringRules
import com.chimu.RecurringText
import com.chimu.TxnType
import java.time.LocalDate

/*
 * The pure side of the Recurring screens (RFC-010): what the form offers, how typed text becomes a saved rule, and which
 * anchor day a rule gets. No Compose in here, so it can be tested without a phone.
 */

/** One entry in a form dropdown. */
data class RecurringOption(val key: String?, val label: String)

/** What the form made of what was typed: a rule that can be saved, or the sentence to show. */
sealed interface RecurringDraft {
    data class Ok(
        val type: TxnType,
        val name: String,
        val amount: Long,
        val category: String?,
        val walletId: String,
        val frequency: RecurringFrequency,
        val nextDate: LocalDate,
        val remindDaysBefore: Int,
        val note: String?,
    ) : RecurringDraft

    data class Invalid(val message: String) : RecurringDraft
}

object RecurringFormRules {
    const val NAME_HELP = "What it is, like Rent or Netflix."
    const val NAME_INVALID = "Enter a name, up to 60 characters."
    const val AMOUNT_HELP = "The amount each time, like 15,000 or 149.50."
    const val AMOUNT_INVALID = "Enter the amount as a number like 15,000 or 149.50, above zero, with at most 2 decimals."
    const val WALLET_INVALID = "Choose the wallet it is paid from. Add one under More, then Wallets, if you have none."
    const val DATE_INVALID = "Choose the date it is next due."
    const val REMIND_INVALID = "Choose when to be reminded."
    const val RESUME_NOTE = "Resuming skips the dates that passed while it was paused."
    const val ALERTS_OFF =
        "Reminders are off: Chimu can't send notifications. Allow them in the phone's settings to be reminded before something is due."
    const val NOT_SYNCED_NOTE = "Chimu logs a rule only after this phone has synced it, so open the app online once after saving."

    val typeOptions = listOf(RecurringOption("expense", "Expense"), RecurringOption("income", "Income"))

    val frequencyOptions = RecurringFrequency.entries.map { RecurringOption(it.slug, it.label) }

    fun typeLabel(type: TxnType): String = if (type == TxnType.INCOME) "Income" else "Expense"

    /** "No category" first, then the type's own list, in the app's order. Transfers have no rules. */
    fun categoryOptions(type: TxnType): List<RecurringOption> =
        listOf(RecurringOption(null, "No category")) + Categories.forType(type).map { RecurringOption(it.slug, it.label) }

    fun categoryLabel(category: String?): String = if (category == null) "No category" else Categories.label(category)

    /** The usual choices, plus the stored value when it is not one of them (a rule written elsewhere). */
    fun remindOptions(current: Int): List<RecurringOption> =
        (RecurringRules.REMIND_CHOICES + current).distinct().sorted().map { RecurringOption(it.toString(), RecurringText.remindLabel(it)) }

    /**
     * Turns the form's fields into something to save. [activeWalletIds] are the wallets that can be used for new entries.
     * [dateText] is `YYYY-MM-DD` as the date picker leaves it; [remindDays] is the chosen reminder, 0 to 30.
     */
    fun draft(
        typeSlug: String,
        nameText: String,
        amountText: String,
        category: String?,
        walletId: String?,
        activeWalletIds: Set<String>,
        frequencySlug: String,
        dateText: String?,
        remindDays: Int?,
        noteText: String,
    ): RecurringDraft {
        val type = when (typeSlug) {
            "expense" -> TxnType.EXPENSE
            "income" -> TxnType.INCOME
            else -> return RecurringDraft.Invalid("Choose expense or income.")
        }
        val name = nameText.trim()
        if (name.isEmpty() || name.length > RecurringRules.NAME_MAX) return RecurringDraft.Invalid(NAME_INVALID)
        val amount = Money.parse(amountText)?.takeIf { it > 0 } ?: return RecurringDraft.Invalid(AMOUNT_INVALID)
        if (category != null && Categories.forType(type).none { it.slug == category }) {
            return RecurringDraft.Invalid("Pick one of the ${type.label.lowercase()} categories, or leave it on No category.")
        }
        if (walletId == null || walletId !in activeWalletIds) return RecurringDraft.Invalid(WALLET_INVALID)
        val frequency = RecurringFrequency.fromSlug(frequencySlug) ?: return RecurringDraft.Invalid("Choose how often it repeats.")
        val date = dateText?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return RecurringDraft.Invalid(DATE_INVALID)
        val days = remindDays?.takeIf { it in 0..30 } ?: return RecurringDraft.Invalid(REMIND_INVALID)
        return RecurringDraft.Ok(type, name, amount, category, walletId, frequency, date, days, noteText.trim().ifEmpty { null })
    }

    /**
     * The rule to save for [draft]. A new rule is active. An edited one keeps its id, owner and active flag. The anchor day
     * follows the date the user picked: it only stays as it was when neither the date nor the frequency was touched, so
     * editing the amount of a rule that has been on Feb 28 for a "31st" keeps its 31.
     */
    fun toRule(draft: RecurringDraft.Ok, existing: RecurringRule?, id: String, owner: String): RecurringRule {
        val untouched = existing != null && existing.frequency == draft.frequency && existing.nextDate == draft.nextDate.toString()
        val fromDate = RecurringRules.anchorFor(draft.frequency, draft.nextDate)
        val anchor = if (untouched) (existing!!.anchorDay ?: fromDate) else fromDate
        return RecurringRule(
            id = existing?.id ?: id,
            ownerId = existing?.ownerId ?: owner,
            type = draft.type,
            walletId = draft.walletId,
            amount = draft.amount,
            category = draft.category,
            merchant = draft.name,
            note = draft.note,
            frequency = draft.frequency,
            nextDate = draft.nextDate.toString(),
            remindDaysBefore = draft.remindDaysBefore,
            active = existing?.active ?: true,
            anchorDay = anchor,
            updatedAt = existing?.updatedAt ?: 0,
            deleted = false,
        )
    }

    /**
     * A heads-up when the chosen due date is in the past: the server logs every period from that date up to today. Null for
     * today or the future. "At least 366" because the server does at most that many per night and finishes on the next ones.
     */
    fun pastNote(frequency: RecurringFrequency, date: LocalDate, today: LocalDate): String? {
        if (!date.isBefore(today)) return null
        val anchor = RecurringRules.anchorFor(frequency, date)
        val count = RecurringDates.dueCount(frequency, date, anchor, today)
        val many = if (count >= 366) "at least 366 entries" else if (count == 1) "1 entry" else "$count entries"
        return "This date has already passed. The next time Chimu's server runs it logs every date from then until today: $many."
    }
}
