package com.chimu

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Reminders for recurring rules (RFC-010): a notification a few days before a rule's due date. The decision is made here,
 * with no Android types. The notification itself and the daily WorkManager job are in `ReminderWorker.kt`.
 */

/** What to tell the user. [dueDate] is `YYYY-MM-DD`; [notificationId] is stable per rule, so a newer reminder replaces an older one. */
data class Reminder(
    val ruleId: String,
    val dueDate: String,
    val daysUntil: Int,
    val title: String,
    val text: String,
    val notificationId: Int,
)

/** Remembers, per rule, the due date it was last reminded about. A tiny view so the rules can be tested without a phone. */
interface ReminderLog {
    /** The `YYYY-MM-DD` due date of the last reminder that really went out for [ruleId], or null. */
    fun lastDue(ruleId: String): String?

    fun record(ruleId: String, dueDate: String)
}

object ReminderPlanner {
    /**
     * The reminders that are due now. A rule is reminded when it is live and active and a due date is between today and
     * [RecurringRule.remindDaysBefore] days away, and that due date is not the one already reminded (see [ReminderLog]).
     * "Between" rather than "exactly N days before", so a day the phone was off or the job did not run does not lose the
     * reminder: it comes on the next run, and only once per due date.
     *
     * A rule's due dates are its [RecurringRule.nextDate] and, when [loggedToday] holds its id, today: the server has
     * already logged today's period and moved `next_date` on, but the user still deserves the "due today" heads-up (this is
     * what makes "on the due day" work). At most one reminder per rule per run, the earliest date first.
     *
     * Only [me]'s rules count (group sync also caches other people's rules on this phone). The result is sorted by due
     * date, then title, then id.
     */
    fun plan(
        me: String,
        rules: List<RecurringRule>,
        loggedToday: Set<String>,
        today: LocalDate,
        log: ReminderLog,
    ): List<Reminder> {
        val planned = mutableListOf<Reminder>()
        for (rule in rules) {
            if (rule.ownerId != me || rule.deleted || !rule.active) continue
            val next = runCatching { LocalDate.parse(rule.nextDate) }.getOrNull() ?: continue
            val dueDates = if (rule.id in loggedToday) listOf(next, today).distinct().sorted() else listOf(next)
            val lastDue = log.lastDue(rule.id)
            val due = dueDates.firstOrNull { date ->
                val days = RecurringDates.daysUntil(today, date)
                days in 0..rule.remindDaysBefore && date.toString() != lastDue
            } ?: continue
            val days = RecurringDates.daysUntil(today, due)
            planned += Reminder(
                ruleId = rule.id,
                dueDate = due.toString(),
                daysUntil = days,
                title = ReminderText.title(rule, days),
                text = ReminderText.body(rule, due.toString()),
                notificationId = notificationId(rule.id),
            )
        }
        return planned.sortedWith(compareBy({ it.dueDate }, { it.title.lowercase() }, { it.ruleId }))
    }

    /** One notification slot per rule: a reminder for the next due date replaces the previous one instead of stacking. */
    fun notificationId(ruleId: String): Int = "recurring:$ruleId".hashCode()

    /** `recurring_reminder_<meId>_<ruleId>`: where the last reminded due date lives in the app's preferences. */
    fun keyOf(me: String, ruleId: String): String = "recurring_reminder_${me}_$ruleId"

    /**
     * Minutes from [now] to the next [hour]:00 o'clock (local time) that is still ahead: the first run of the daily job, so
     * reminders arrive in the morning and not at whatever minute the app was first opened. Always at least 1.
     */
    fun minutesUntilNext(hour: Int, now: LocalDateTime): Long {
        var target = now.toLocalDate().atTime(hour, 0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return java.time.Duration.between(now, target).toMinutes().coerceAtLeast(1)
    }
}

/**
 * Posts the reminders [ReminderPlanner.plan] asks for and remembers what went out. A reminder is recorded only when [post]
 * says the notification really was shown, so a blocked one (permission denied, notifications off) is still owed once that
 * changes. Safe to call from several threads: the daily job and a manual run can overlap.
 */
class Reminders(private val log: ReminderLog, private val post: (Reminder) -> Boolean) {
    private val lock = Any()

    /** Returns the reminders that went out. */
    fun run(me: String, rules: List<RecurringRule>, loggedToday: Set<String>, today: LocalDate): List<Reminder> =
        synchronized(lock) {
            val sent = mutableListOf<Reminder>()
            for (reminder in ReminderPlanner.plan(me, rules, loggedToday, today, log)) {
                val shown = try {
                    post(reminder)
                } catch (e: Exception) {
                    false // one reminder that can't be shown must not stop the others
                }
                if (shown) {
                    log.record(reminder.ruleId, reminder.dueDate)
                    sent += reminder
                }
            }
            sent
        }
}

/** The words of a reminder. Information, not advice (RULES 22). */
object ReminderText {
    /** "Rent is due tomorrow", "Salary is expected in 3 days", "Internet is due today". */
    fun title(rule: RecurringRule, daysUntil: Int): String {
        val name = RecurringText.title(rule)
        val verb = if (rule.type == TxnType.INCOME) "is expected" else "is due"
        val whenText = when (daysUntil) {
            0 -> "today"
            1 -> "tomorrow"
            else -> "in $daysUntil days"
        }
        return "$name $verb $whenText"
    }

    /** "₱15,000.00 on Dec 5, 2026. Chimu logs it for you." */
    fun body(rule: RecurringRule, dueDate: String): String =
        "${Money.format(rule.amount)} on ${Ledger.dateLabel(dueDate)}. Chimu logs it for you."
}
