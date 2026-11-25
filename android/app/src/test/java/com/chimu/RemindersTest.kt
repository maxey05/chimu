package com.chimu

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a reminder is posted and when it is not (RFC-010 acceptance: "Reminder notification fires the configured number
 * of days before"). The notification itself ([ReminderNotifier]) is Android; here it is a function that says yes or no.
 */
class RemindersTest {
    private val me = "me"
    private val today = LocalDate.parse("2026-11-25")

    private class MapLog : ReminderLog {
        val map = HashMap<String, String>()
        override fun lastDue(ruleId: String): String? = map[ruleId]
        override fun record(ruleId: String, dueDate: String) {
            map[ruleId] = dueDate
        }
    }

    private val log = MapLog()
    private val sent = mutableListOf<Reminder>()
    private var accept = true
    private val reminders = Reminders(log) { r ->
        if (accept) sent += r
        accept
    }

    private fun rule(
        id: String = "r1",
        next: String = "2026-11-27",
        remind: Int = 2,
        active: Boolean = true,
        owner: String = me,
        deleted: Boolean = false,
        name: String = "Rent",
        type: TxnType = TxnType.EXPENSE,
    ) = RecurringRule(
        id, owner, type, "w1", 1_500_000, "bills", name, null, RecurringFrequency.MONTHLY, next, remind, active, 27, 1000, deleted,
    )

    private fun plan(vararg rules: RecurringRule, logged: Set<String> = emptySet(), on: LocalDate = today) =
        ReminderPlanner.plan(me, rules.toList(), logged, on, log)

    // --- the window ------------------------------------------------------------

    @Test
    fun remindsExactlyTheConfiguredNumberOfDaysBefore() {
        // due Nov 27, remind 2 days before: first reminder on Nov 25
        assertEquals(emptyList<Reminder>(), plan(rule(remind = 2), on = LocalDate.parse("2026-11-24")))
        assertEquals(1, plan(rule(remind = 2), on = LocalDate.parse("2026-11-25")).size)
    }

    @Test
    fun oneDayBeforeMeansTheDayBeforeNotTwo() {
        val r = rule(next = "2026-11-27", remind = 1)
        assertEquals(emptyList<Reminder>(), plan(r, on = LocalDate.parse("2026-11-25")))
        val planned = plan(r, on = LocalDate.parse("2026-11-26"))
        assertEquals(1, planned.size)
        assertEquals(1, planned[0].daysUntil)
        assertEquals("Rent is due tomorrow", planned[0].title)
    }

    @Test
    fun aMissedDayIsCaughtUpOnTheNextRun() {
        // the phone was off on Nov 25 and 26; on the due day itself the reminder still comes
        val planned = plan(rule(next = "2026-11-27", remind = 2), on = LocalDate.parse("2026-11-27"))
        assertEquals(1, planned.size)
        assertEquals(0, planned[0].daysUntil)
        assertEquals("Rent is due today", planned[0].title)
    }

    @Test
    fun nothingForADatePast() {
        assertEquals(emptyList<Reminder>(), plan(rule(next = "2026-11-20", remind = 30)))
    }

    @Test
    fun remindZeroMeansOnTheDueDayOnly() {
        val r = rule(next = "2026-11-25", remind = 0)
        assertEquals(1, plan(r).size)
        assertEquals(emptyList<Reminder>(), plan(rule(next = "2026-11-26", remind = 0)))
    }

    @Test
    fun theLongestWindowIsThirtyDays() {
        assertEquals(1, plan(rule(next = "2026-12-25", remind = 30)).size)
        assertEquals(emptyList<Reminder>(), plan(rule(next = "2026-12-26", remind = 30)))
    }

    @Test
    fun aRuleAlreadyLoggedTodayStillGetsItsDueTodayReminder() {
        // the server logged today's period at 00:10 and moved next_date to next month
        val r = rule(next = "2026-12-25", remind = 1)
        val planned = plan(r, logged = setOf("r1"), on = today)
        assertEquals(1, planned.size)
        assertEquals(today.toString(), planned[0].dueDate)
        assertEquals("Rent is due today", planned[0].title)
    }

    @Test
    fun loggedTodayDoesNotMatterForAnotherRule() {
        assertEquals(emptyList<Reminder>(), plan(rule(id = "r2", next = "2026-12-25", remind = 1), logged = setOf("r1")))
    }

    // --- who and what ------------------------------------------------------------

    @Test
    fun pausedDeletedAndOtherPeoplesRulesAreSkipped() {
        assertEquals(emptyList<Reminder>(), plan(rule(active = false)))
        assertEquals(emptyList<Reminder>(), plan(rule(deleted = true)))
        assertEquals(emptyList<Reminder>(), plan(rule(owner = "someone-else")))
    }

    @Test
    fun aRowWithABrokenDateIsSkippedNotACrash() {
        assertEquals(emptyList<Reminder>(), plan(rule(next = "not-a-date")))
    }

    @Test
    fun incomeIsWordedAsExpected() {
        val planned = plan(rule(name = "Salary", type = TxnType.INCOME, next = "2026-11-28", remind = 3))
        assertEquals("Salary is expected in 3 days", planned[0].title)
    }

    @Test
    fun textHasTheAmountAndTheDateAndNoAdvice() {
        val planned = plan(rule())
        assertEquals("₱15,000.00 on Nov 27, 2026. Chimu logs it for you.", planned[0].text)
        assertTrue(!planned[0].text.lowercase().contains("deductible"))
    }

    @Test
    fun resultsAreInDueDateOrderThenTitle() {
        val planned = plan(
            rule(id = "a", name = "Zeta", next = "2026-11-26"),
            rule(id = "b", name = "Alpha", next = "2026-11-27"),
            rule(id = "c", name = "Beta", next = "2026-11-26"),
        )
        assertEquals(listOf("c", "a", "b"), planned.map { it.ruleId })
    }

    @Test
    fun oneNotificationSlotPerRule() {
        assertEquals(ReminderPlanner.notificationId("r1"), ReminderPlanner.notificationId("r1"))
        assertNotEquals(ReminderPlanner.notificationId("r1"), ReminderPlanner.notificationId("r2"))
        assertEquals("recurring_reminder_me_r1", ReminderPlanner.keyOf("me", "r1"))
    }

    // --- not twice -----------------------------------------------------------------

    @Test
    fun theSameRuleAndDueDateIsRemindedOnce() {
        val r = rule(next = "2026-11-27", remind = 2)
        assertEquals(1, reminders.run(me, listOf(r), emptySet(), today).size)
        assertEquals(0, reminders.run(me, listOf(r), emptySet(), today).size)
        assertEquals(0, reminders.run(me, listOf(r), emptySet(), LocalDate.parse("2026-11-26")).size)
        assertEquals(0, reminders.run(me, listOf(r), emptySet(), LocalDate.parse("2026-11-27")).size)
        assertEquals(1, sent.size)
    }

    @Test
    fun theNextDueDateOfTheSameRuleIsRemindedAgain() {
        reminders.run(me, listOf(rule(next = "2026-11-27")), emptySet(), today)
        val december = rule(next = "2026-12-27", remind = 2)
        assertEquals(0, reminders.run(me, listOf(december), emptySet(), LocalDate.parse("2026-12-20")).size)
        assertEquals(1, reminders.run(me, listOf(december), emptySet(), LocalDate.parse("2026-12-25")).size)
        assertEquals(listOf("2026-11-27", "2026-12-27"), sent.map { it.dueDate })
    }

    @Test
    fun dueTodayAfterTheServerMovedOnIsNotRemindedTwiceEither() {
        // remind 2 days before (Nov 25 for Nov 27), then on the 27th the rule is already logged and next_date is Dec 27
        reminders.run(me, listOf(rule(next = "2026-11-27", remind = 2)), emptySet(), today)
        val onTheDay = LocalDate.parse("2026-11-27")
        val advanced = rule(next = "2026-12-27", remind = 2)
        assertEquals(0, reminders.run(me, listOf(advanced), setOf("r1"), onTheDay).size)
        assertEquals(1, sent.size)
    }

    @Test
    fun changingTheDueDateAfterAReminderRemindsAboutTheNewDate() {
        reminders.run(me, listOf(rule(next = "2026-11-27", remind = 3)), emptySet(), today)
        assertEquals(1, reminders.run(me, listOf(rule(next = "2026-11-28", remind = 3)), emptySet(), today).size)
    }

    @Test
    fun aBlockedReminderIsNotRecordedSoItIsOwedLater() {
        val r = rule()
        accept = false
        assertEquals(0, reminders.run(me, listOf(r), emptySet(), today).size)
        assertEquals(null, log.lastDue("r1"))
        accept = true
        assertEquals(1, reminders.run(me, listOf(r), emptySet(), today).size)
        assertEquals("2026-11-27", log.lastDue("r1"))
    }

    @Test
    fun aThrowingNotifierDoesNotStopTheOtherReminders() {
        val posted = mutableListOf<String>()
        val flaky = Reminders(log) { r ->
            if (r.ruleId == "a") error("no notification service")
            posted += r.ruleId
            true
        }
        val out = flaky.run(me, listOf(rule(id = "a"), rule(id = "b")), emptySet(), today)
        assertEquals(listOf("b"), posted)
        assertEquals(listOf("b"), out.map { it.ruleId })
        assertEquals(null, log.lastDue("a"))
    }

    @Test
    fun twoThreadsRunningTogetherPostOnce() {
        val r = rule()
        val pool = Executors.newFixedThreadPool(8)
        val go = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) {
            pool.execute {
                go.await()
                reminders.run(me, listOf(r), emptySet(), today)
                done.countDown()
            }
        }
        go.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(1, sent.size)
    }

    // --- when the daily job first runs -------------------------------------------------

    @Test
    fun theFirstRunIsTheNextNineAm() {
        assertEquals(60L, ReminderPlanner.minutesUntilNext(9, LocalDateTime.parse("2026-11-25T08:00:00")))
        assertEquals(24 * 60L - 60, ReminderPlanner.minutesUntilNext(9, LocalDateTime.parse("2026-11-25T10:00:00")))
        assertEquals(24 * 60L, ReminderPlanner.minutesUntilNext(9, LocalDateTime.parse("2026-11-25T09:00:00")))
        assertEquals(1L, ReminderPlanner.minutesUntilNext(9, LocalDateTime.parse("2026-11-25T08:59:30")))
        assertEquals(9 * 60L, ReminderPlanner.minutesUntilNext(9, LocalDateTime.parse("2026-11-25T00:00:00")))
    }
}
