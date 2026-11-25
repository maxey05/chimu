package com.chimu

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The date arithmetic and rules behind recurring items (RFC-010). The server's `recurring_next_date()` is the one that
 * really generates transactions; [RecurringDates.next] must agree with it, so [VECTOR] is the same table as the one in
 * `supabase/check_recurring.py`. Change one, change both.
 */
class RecurringTest {
    private fun d(s: String) = LocalDate.parse(s)

    private fun rule(
        id: String = "r1",
        owner: String = "me",
        type: TxnType = TxnType.EXPENSE,
        merchant: String? = "Rent",
        category: String? = "bills",
        amount: Long = 1_500_000,
        frequency: RecurringFrequency = RecurringFrequency.MONTHLY,
        next: String = "2026-12-05",
        remind: Int = 1,
        active: Boolean = true,
        anchor: Int? = 5,
        deleted: Boolean = false,
    ) = RecurringRule(id, owner, type, "w1", amount, category, merchant, null, frequency, next, remind, active, anchor, 1000, deleted)

    // --- the shared vector ---------------------------------------------------

    private class Case(val freq: String, val from: String, val anchor: Int?, val expected: String)

    private val VECTOR = listOf(
        Case("monthly", "2026-01-31", null, "2026-02-28"),
        Case("monthly", "2026-02-28", 31, "2026-03-31"),
        Case("monthly", "2026-02-28", null, "2026-03-28"),
        Case("monthly", "2028-01-31", null, "2028-02-29"),
        Case("monthly", "2028-02-29", null, "2028-03-29"),
        Case("monthly", "2026-03-31", null, "2026-04-30"),
        Case("monthly", "2026-04-30", 31, "2026-05-31"),
        Case("monthly", "2026-01-15", 31, "2026-02-28"),
        Case("monthly", "2026-12-15", null, "2027-01-15"),
        Case("monthly", "2026-12-31", null, "2027-01-31"),
        Case("monthly", "2026-11-30", 30, "2026-12-30"),
        Case("yearly", "2024-02-29", null, "2025-02-28"),
        Case("yearly", "2025-02-28", 29, "2026-02-28"),
        Case("yearly", "2027-02-28", 29, "2028-02-29"),
        Case("yearly", "2026-06-15", null, "2027-06-15"),
        Case("weekly", "2026-11-23", null, "2026-11-30"),
        Case("weekly", "2026-12-28", null, "2027-01-04"),
        Case("weekly", "2026-01-31", 15, "2026-02-07"),
    )

    @Test
    fun nextMatchesTheSharedVector() {
        for (c in VECTOR) {
            val got = RecurringDates.next(RecurringFrequency.fromSlug(c.freq)!!, d(c.from), c.anchor)
            assertEquals("${c.freq} from ${c.from} anchor ${c.anchor}", c.expected, got.toString())
        }
    }

    // --- dates ---------------------------------------------------------------

    @Test
    fun monthEndGoesBackToTheAnchorDayInsteadOfDrifting() {
        var date = d("2026-01-31")
        val seen = mutableListOf<String>()
        repeat(5) {
            seen += date.toString()
            date = RecurringDates.next(RecurringFrequency.MONTHLY, date, 31)
        }
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30", "2026-05-31"), seen)
    }

    @Test
    fun withoutAnAnchorTheDayOfTheDateIsUsed() {
        assertEquals(d("2026-03-28"), RecurringDates.next(RecurringFrequency.MONTHLY, d("2026-02-28"), null))
    }

    @Test
    fun anAnchorOutsideOneToThirtyOneIsClamped() {
        assertEquals(d("2026-03-31"), RecurringDates.next(RecurringFrequency.MONTHLY, d("2026-02-10"), 99))
        assertEquals(d("2026-03-01"), RecurringDates.next(RecurringFrequency.MONTHLY, d("2026-02-10"), 0))
    }

    @Test
    fun firstOnOrAfterKeepsAFutureDateAndWalksAPastOne() {
        assertEquals(d("2026-12-05"), RecurringDates.firstOnOrAfter(RecurringFrequency.MONTHLY, d("2026-12-05"), 5, d("2026-11-25")))
        assertEquals(d("2026-11-25"), RecurringDates.firstOnOrAfter(RecurringFrequency.MONTHLY, d("2026-11-25"), 25, d("2026-11-25")))
        assertEquals(d("2026-12-05"), RecurringDates.firstOnOrAfter(RecurringFrequency.MONTHLY, d("2026-01-05"), 5, d("2026-11-25")))
        assertEquals(d("2026-11-27"), RecurringDates.firstOnOrAfter(RecurringFrequency.WEEKLY, d("2026-01-02"), null, d("2026-11-25"))) // Jan 2 2026 is a Friday
        // a 31st rule that was paused over a short month still lands on the 31st afterwards
        assertEquals(d("2026-03-31"), RecurringDates.firstOnOrAfter(RecurringFrequency.MONTHLY, d("2026-01-31"), 31, d("2026-03-01")))
    }

    @Test
    fun dueCountCountsEveryPeriodUpToTodayAndStopsAtTheCap() {
        assertEquals(0, RecurringDates.dueCount(RecurringFrequency.MONTHLY, d("2026-12-05"), 5, d("2026-11-25")))
        assertEquals(1, RecurringDates.dueCount(RecurringFrequency.MONTHLY, d("2026-11-25"), 25, d("2026-11-25")))
        assertEquals(4, RecurringDates.dueCount(RecurringFrequency.MONTHLY, d("2026-08-15"), 15, d("2026-11-25")))
        assertEquals(4, RecurringDates.dueCount(RecurringFrequency.WEEKLY, d("2026-11-02"), null, d("2026-11-25")))
        assertEquals(366, RecurringDates.dueCount(RecurringFrequency.WEEKLY, d("2000-01-03"), null, d("2026-11-25")))
        assertEquals(10, RecurringDates.dueCount(RecurringFrequency.WEEKLY, d("2000-01-03"), null, d("2026-11-25"), cap = 10))
    }

    @Test
    fun daysUntil() {
        assertEquals(0, RecurringDates.daysUntil(d("2026-11-25"), d("2026-11-25")))
        assertEquals(10, RecurringDates.daysUntil(d("2026-11-25"), d("2026-12-05")))
        assertEquals(-5, RecurringDates.daysUntil(d("2026-11-25"), d("2026-11-20")))
    }

    // --- rules ---------------------------------------------------------------

    @Test
    fun unknownFrequencyIsNullNotACrash() {
        assertNull(RecurringFrequency.fromSlug("daily"))
        assertEquals(RecurringFrequency.YEARLY, RecurringFrequency.fromSlug("yearly"))
    }

    @Test
    fun visibleKeepsOnlyMyLiveRules() {
        val mine = rule(id = "a")
        val gone = rule(id = "b", deleted = true)
        val theirs = rule(id = "c", owner = "other")
        assertEquals(listOf(mine), RecurringRules.visible("me", listOf(mine, gone, theirs)))
    }

    @Test
    fun sortedPutsActiveRulesFirstBySoonestDateThenPausedByName() {
        val later = rule(id = "1", merchant = "Gym", next = "2026-12-20")
        val soon = rule(id = "2", merchant = "Rent", next = "2026-12-01")
        val pausedB = rule(id = "3", merchant = "Zumba", next = "2026-11-01", active = false)
        val pausedA = rule(id = "4", merchant = "Aircon", next = "2026-11-30", active = false)
        assertEquals(listOf("2", "1", "4", "3"), RecurringRules.sorted(listOf(pausedB, later, pausedA, soon)).map { it.id })
    }

    @Test
    fun anchorForIsTheDayOfTheMonthExceptForWeekly() {
        assertEquals(31, RecurringRules.anchorFor(RecurringFrequency.MONTHLY, d("2026-01-31")))
        assertEquals(29, RecurringRules.anchorFor(RecurringFrequency.YEARLY, d("2024-02-29")))
        assertNull(RecurringRules.anchorFor(RecurringFrequency.WEEKLY, d("2026-01-31")))
    }

    @Test
    fun resumeSkipsWhatWasMissedWhilePausedAndKeepsTheAnchor() {
        val paused = rule(frequency = RecurringFrequency.MONTHLY, next = "2026-02-28", anchor = 31, active = false)
        val resumed = RecurringRules.resume(paused, d("2026-04-10"))
        assertTrue(resumed.active)
        assertEquals("2026-04-30", resumed.nextDate)
        assertEquals(31, resumed.anchorDay)
    }

    @Test
    fun resumeLeavesAFutureDateAlone() {
        val paused = rule(next = "2026-12-05", anchor = 5, active = false)
        assertEquals("2026-12-05", RecurringRules.resume(paused, d("2026-11-25")).nextDate)
    }

    @Test
    fun resumeGivesARuleWithNoAnchorTheDayOfItsDate() {
        val paused = rule(next = "2026-02-17", anchor = null, active = false)
        val resumed = RecurringRules.resume(paused, d("2026-04-01"))
        assertEquals("2026-04-17", resumed.nextDate)
        assertEquals(17, resumed.anchorDay)
    }

    @Test
    fun resumeOfAWeeklyRuleHasNoAnchor() {
        val paused = rule(frequency = RecurringFrequency.WEEKLY, next = "2026-11-02", anchor = null, active = false)
        val resumed = RecurringRules.resume(paused, d("2026-11-25"))
        assertEquals("2026-11-30", resumed.nextDate)
        assertNull(resumed.anchorDay)
    }

    // --- words ---------------------------------------------------------------

    @Test
    fun titleFallsBackFromNameToCategoryToKind() {
        assertEquals("Rent", RecurringText.title(rule(merchant = "  Rent ")))
        assertEquals("Bills & Utilities", RecurringText.title(rule(merchant = null, category = "bills")))
        assertEquals("Bills & Utilities", RecurringText.title(rule(merchant = "  ", category = "bills")))
        assertEquals("Recurring expense", RecurringText.title(rule(merchant = null, category = null)))
        assertEquals("Recurring income", RecurringText.title(rule(merchant = null, category = null, type = TxnType.INCOME)))
    }

    @Test
    fun statusIsInWords() {
        val today = d("2026-11-25")
        assertEquals("Paused", RecurringText.status(rule(active = false), today))
        assertEquals("Due today", RecurringText.status(rule(next = "2026-11-25"), today))
        assertEquals("Due tomorrow", RecurringText.status(rule(next = "2026-11-26"), today))
        assertEquals("Due in 10 days", RecurringText.status(rule(next = "2026-12-05"), today))
        assertEquals("Waiting to be logged (was due Nov 20, 2026)", RecurringText.status(rule(next = "2026-11-20"), today))
    }

    @Test
    fun summaryAndNextLineShowTheNextDueDate() {
        val r = rule()
        assertEquals("Expense ₱15,000.00 · Every month", RecurringText.summary(r))
        assertEquals("Next: Dec 5, 2026", RecurringText.nextLine(r))
        assertTrue(RecurringText.nextLine(rule(active = false)).contains("resumed"))
        assertEquals("Income ₱500.00 · Every week", RecurringText.summary(rule(type = TxnType.INCOME, amount = 50_000, frequency = RecurringFrequency.WEEKLY)))
    }

    @Test
    fun remindLabels() {
        assertEquals("On the due day", RecurringText.remindLabel(0))
        assertEquals("1 day before", RecurringText.remindLabel(1))
        assertEquals("7 days before", RecurringText.remindLabel(7))
    }

    @Test
    fun describeReadsAsOneSentenceWithEverythingInWords() {
        val text = RecurringText.describe(rule(), "Cash", d("2026-11-25"))
        assertTrue(text, text.startsWith("Rent. Expense of ₱15,000.00, every month, in wallet Cash."))
        assertTrue(text, text.contains("Due in 10 days."))
        assertTrue(text, text.contains("Next: Dec 5, 2026."))
        assertTrue(text, text.endsWith("Reminder: 1 day before."))
    }

    @Test
    fun nothingClaimsTaxOrAdvice() {
        val all = listOf(RecurringText.EXPLAIN, RecurringText.EMPTY).joinToString(" ").lowercase()
        assertFalse(all.contains("deductible"))
    }

    // --- the SQL strings -----------------------------------------------------

    @Test
    fun sqlStringsAreScopedToTheOwnerAndNeverDeleteForReal() {
        assertTrue(RecurringSql.LIVE_WHERE.contains("owner_id = ?"))
        assertTrue(RecurringSql.TOMBSTONE.startsWith("UPDATE recurring SET deleted = 1"))
        assertTrue(RecurringSql.TOMBSTONE.contains("dirty = 1"))
        assertTrue(RecurringSql.TOMBSTONE.contains("owner_id = ?"))
        assertFalse(RecurringSql.TOMBSTONE.contains("DELETE FROM"))
        assertTrue(RecurringSql.OWN_ROW_WHERE.contains("owner_id = ?"))
        assertTrue(RecurringSql.LOGGED_ON.contains("source = 'recurring'"))
    }
}
