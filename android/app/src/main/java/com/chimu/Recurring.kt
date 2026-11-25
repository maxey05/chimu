package com.chimu

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * Recurring rules (RFC-010). Pure Kotlin, no Android types, so the rules can be tested without a phone.
 *
 * The server, not the phone, turns a rule into transactions: `generate_recurring()` (supabase/migrations/0003_recurring.sql)
 * runs every night and logs each due period. The phone only edits rules, shows when the next one is due, and reminds.
 * [RecurringDates.next] is a copy of the server's `recurring_next_date()`; both are tested against the same table of cases.
 */

/** How often a rule repeats. The slugs are what the `frequency` column holds. */
enum class RecurringFrequency(val slug: String, val label: String) {
    WEEKLY("weekly", "Every week"),
    MONTHLY("monthly", "Every month"),
    YEARLY("yearly", "Every year"),
    ;

    companion object {
        /** Null for a value this build does not know, so one odd row from the server cannot crash the screen. */
        fun fromSlug(slug: String): RecurringFrequency? = entries.firstOrNull { it.slug == slug }
    }
}

/**
 * One recurring rule. [type] is expense or income only (the server refuses transfers). [amount] is centavos.
 * [nextDate] is `YYYY-MM-DD`: the next period that has not been logged yet. [anchorDay] is the day of the month a monthly
 * or yearly rule is meant for, so Jan 31 goes to Feb 28 and then back to Mar 31 (null for weekly, and for rows written
 * before the column existed: the server then reads the day of [nextDate]). [remindDaysBefore] is 0 to 30.
 */
data class RecurringRule(
    val id: String,
    val ownerId: String,
    val type: TxnType,
    val walletId: String,
    val amount: Long,
    val category: String?,
    val merchant: String?,
    val note: String?,
    val frequency: RecurringFrequency,
    val nextDate: String,
    val remindDaysBefore: Int,
    val active: Boolean,
    val anchorDay: Int?,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)

/** The date arithmetic. Same results as the server's `recurring_next_date(freq, d, anchor)`. */
object RecurringDates {
    /** A guard against a loop that never reaches today (a start date centuries back). About 190 years of weekly dates. */
    private const val MAX_STEPS = 10_000

    /**
     * The date after [from]. Weekly: seven days later. Monthly and yearly: the [anchorDay] (the day of [from] when null) in the
     * next month or year, or that month's last day when it is shorter: Jan 31 -> Feb 28 (29 in a leap year) -> Mar 31.
     */
    fun next(frequency: RecurringFrequency, from: LocalDate, anchorDay: Int?): LocalDate {
        if (frequency == RecurringFrequency.WEEKLY) return from.plusDays(7)
        val want = (anchorDay ?: from.dayOfMonth).coerceIn(1, 31)
        val month = YearMonth.from(from).let {
            if (frequency == RecurringFrequency.MONTHLY) it.plusMonths(1) else it.plusYears(1)
        }
        return month.atDay(minOf(want, month.lengthOfMonth()))
    }

    /** The first due date on or after [today], walking forward from [from]. [from] itself when it is not in the past. */
    fun firstOnOrAfter(frequency: RecurringFrequency, from: LocalDate, anchorDay: Int?, today: LocalDate): LocalDate {
        var date = from
        var steps = 0
        while (date.isBefore(today) && steps < MAX_STEPS) {
            date = next(frequency, date, anchorDay)
            steps++
        }
        return date
    }

    /**
     * How many dates from [from] up to and including [today] the server would log in one night: 0 when [from] is in the
     * future. Stops counting at [cap] (the server logs at most 366 per rule per run and finishes on the next nights).
     */
    fun dueCount(frequency: RecurringFrequency, from: LocalDate, anchorDay: Int?, today: LocalDate, cap: Int = 366): Int {
        var date = from
        var count = 0
        while (!date.isAfter(today) && count < cap) {
            count++
            date = next(frequency, date, anchorDay)
        }
        return count
    }

    /** Whole days from [today] to [date]: 0 is today, negative is the past. */
    fun daysUntil(today: LocalDate, date: LocalDate): Int = ChronoUnit.DAYS.between(today, date).toInt()
}

object RecurringRules {
    const val NAME_MAX = 60

    /** The reminder choices the form offers, in days before the due date. A stored value outside the list is still shown. */
    val REMIND_CHOICES = listOf(0, 1, 2, 3, 7)

    /** Live rules of [me]. Other people's rules reach this phone through group sync; they are never shown here. */
    fun visible(me: String, rules: List<RecurringRule>): List<RecurringRule> =
        rules.filter { it.ownerId == me && !it.deleted }

    /** Active rules first, soonest due first; paused rules after them by name. Ties fall back to the id, so the list never jumps. */
    fun sorted(rules: List<RecurringRule>): List<RecurringRule> =
        rules.sortedWith(
            compareBy<RecurringRule>(
                { !it.active },
                { if (it.active) it.nextDate else "" },
                { RecurringText.title(it).lowercase() },
                { it.id },
            ),
        )

    /** The anchor a rule gets when the user picks [date] as its due date: the day of the month, or none for weekly. */
    fun anchorFor(frequency: RecurringFrequency, date: LocalDate): Int? =
        if (frequency == RecurringFrequency.WEEKLY) null else date.dayOfMonth

    /**
     * Resuming a paused rule. The periods missed while it was paused are skipped, not logged late: the rule continues with
     * the first date on or after [today] that fits its schedule (kept on its anchor day).
     */
    fun resume(rule: RecurringRule, today: LocalDate): RecurringRule {
        val date = LocalDate.parse(rule.nextDate)
        val anchor = rule.anchorDay ?: anchorFor(rule.frequency, date)
        val next = RecurringDates.firstOnOrAfter(rule.frequency, date, anchor, today)
        return rule.copy(active = true, nextDate = next.toString(), anchorDay = anchor)
    }
}

/** The SQL the recurring table needs, as plain text so it can be run against a real SQLite (see [RecurringStore]). */
object RecurringSql {
    /** The owner's rules the phone shows: live ones. Tombstones stay in the table for sync. Args: owner id. */
    const val LIVE_WHERE = "owner_id = ? AND deleted = 0"

    /** Tombstone one of the owner's own live rules (RULES 9). Args: updated_at (ms), id, owner id. */
    const val TOMBSTONE =
        "UPDATE recurring SET deleted = 1, updated_at = ?, dirty = 1 WHERE id = ? AND owner_id = ? AND deleted = 0"

    /** Guards an edit so it only touches the owner's own row. Args: id, owner id. */
    const val OWN_ROW_WHERE = "id = ? AND owner_id = ?"

    /** Rules that have a transaction dated one day (their due date has just been logged). Args: owner id, `YYYY-MM-DD`. */
    const val LOGGED_ON =
        "SELECT DISTINCT recurring_id FROM transactions " +
            "WHERE owner_id = ? AND source = 'recurring' AND deleted = 0 AND recurring_id IS NOT NULL AND date = ?"
}

/** The words the recurring screens use. Information, not advice (RULES 22). */
object RecurringText {
    const val EXPLAIN =
        "Rent, subscriptions and bills that repeat. Chimu logs each one for you on its due date, around 12:10 AM Philippine " +
            "time, and this phone shows it the next time it syncs. Pausing or deleting a rule stops it."
    const val EMPTY = "No recurring items yet. Add rent, a subscription or a bill and Chimu will log it for you each time it is due."

    /** The name shown for a rule: what the user called it, else its category, else the kind of rule. */
    fun title(rule: RecurringRule): String =
        rule.merchant?.trim()?.takeIf { it.isNotEmpty() }
            ?: rule.category?.let { Categories.label(it) }?.takeIf { it.isNotBlank() }
            ?: if (rule.type == TxnType.INCOME) "Recurring income" else "Recurring expense"

    /** "Expense ₱1,500.00 · Every month". The type is in words, never only a sign or a colour (RULES 21). */
    fun summary(rule: RecurringRule): String =
        "${rule.type.label} ${Money.format(rule.amount)} · ${rule.frequency.label}"

    /** "Paused", "Due today", "Due tomorrow", "Due in 5 days", or "Waiting to be logged" when the date has passed. */
    fun status(rule: RecurringRule, today: LocalDate): String {
        if (!rule.active) return "Paused"
        val days = RecurringDates.daysUntil(today, LocalDate.parse(rule.nextDate))
        return when {
            days < 0 -> "Waiting to be logged (was due ${Ledger.dateLabel(rule.nextDate)})"
            days == 0 -> "Due today"
            days == 1 -> "Due tomorrow"
            else -> "Due in $days days"
        }
    }

    /** "Next: Dec 5, 2026". Paused rules have no next date to show as a promise. */
    fun nextLine(rule: RecurringRule): String =
        if (rule.active) "Next: ${Ledger.dateLabel(rule.nextDate)}" else "Will continue from ${Ledger.dateLabel(rule.nextDate)} when resumed"

    /** "On the due day", "1 day before", "3 days before". */
    fun remindLabel(days: Int): String = when (days) {
        0 -> "On the due day"
        1 -> "1 day before"
        else -> "$days days before"
    }

    /** One sentence for a screen reader. */
    fun describe(rule: RecurringRule, walletLabel: String, today: LocalDate): String =
        "${title(rule)}. ${rule.type.label} of ${Money.format(rule.amount)}, ${rule.frequency.label.lowercase()}, in wallet $walletLabel. " +
            "${status(rule, today)}. ${nextLine(rule)}. Reminder: ${remindLabel(rule.remindDaysBefore).lowercase()}."
}
