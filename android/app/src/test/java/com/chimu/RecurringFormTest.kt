package com.chimu

import com.chimu.ui.RecurringDraft
import com.chimu.ui.RecurringFormRules
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the Recurring form turns typed text into a rule (RFC-010): what it accepts, what it refuses, and the anchor day. */
class RecurringFormTest {
    private val wallets = setOf("w1", "w2")
    private val today = LocalDate.parse("2026-11-25")

    private fun draft(
        type: String = "expense",
        name: String = "Rent",
        amount: String = "15,000",
        category: String? = "bills",
        wallet: String? = "w1",
        frequency: String = "monthly",
        date: String? = "2026-12-05",
        remind: Int? = 1,
        note: String = "",
    ) = RecurringFormRules.draft(type, name, amount, category, wallet, wallets, frequency, date, remind, note)

    private fun ok(d: RecurringDraft) = d as RecurringDraft.Ok
    private fun message(d: RecurringDraft) = (d as RecurringDraft.Invalid).message

    @Test
    fun aGoodDraftBecomesCentavosAndTrimmedText() {
        val d = ok(draft(name = "  Rent  ", amount = "15,000.50", note = "  due on the 5th "))
        assertEquals(TxnType.EXPENSE, d.type)
        assertEquals("Rent", d.name)
        assertEquals(1_500_050L, d.amount)
        assertEquals("bills", d.category)
        assertEquals("w1", d.walletId)
        assertEquals(RecurringFrequency.MONTHLY, d.frequency)
        assertEquals(LocalDate.parse("2026-12-05"), d.nextDate)
        assertEquals(1, d.remindDaysBefore)
        assertEquals("due on the 5th", d.note)
    }

    @Test
    fun aBlankNoteIsNull() {
        assertNull(ok(draft(note = "   ")).note)
    }

    @Test
    fun theNameIsRequiredAndAtMostSixtyCharacters() {
        assertEquals(RecurringFormRules.NAME_INVALID, message(draft(name = "   ")))
        assertEquals(RecurringFormRules.NAME_INVALID, message(draft(name = "x".repeat(61))))
        ok(draft(name = "x".repeat(60)))
    }

    @Test
    fun theAmountMustBeAboveZeroAndStrict() {
        for (bad in listOf("", "0", "0.00", "-5", "abc", "1.234", "1,00", "5,000,")) {
            assertEquals(bad, RecurringFormRules.AMOUNT_INVALID, message(draft(amount = bad)))
        }
        assertEquals(1L, ok(draft(amount = "0.01")).amount)
    }

    @Test
    fun theWalletMustBeOneOfTheActiveWallets() {
        assertEquals(RecurringFormRules.WALLET_INVALID, message(draft(wallet = null)))
        assertEquals(RecurringFormRules.WALLET_INVALID, message(draft(wallet = "archived")))
        assertEquals("w2", ok(draft(wallet = "w2")).walletId)
    }

    @Test
    fun aTransferCannotBeRecurring() {
        assertEquals("Choose expense or income.", message(draft(type = "transfer")))
    }

    @Test
    fun theCategoryMustMatchTheType() {
        assertTrue(message(draft(type = "income", category = "bills")).contains("income categories"))
        assertTrue(message(draft(type = "expense", category = "salary")).contains("expense categories"))
        assertNull(ok(draft(category = null)).category)
        assertEquals("salary", ok(draft(type = "income", category = "salary")).category)
    }

    @Test
    fun theDateAndFrequencyAndReminderMustBeValid() {
        assertEquals(RecurringFormRules.DATE_INVALID, message(draft(date = null)))
        assertEquals(RecurringFormRules.DATE_INVALID, message(draft(date = "2026-02-30")))
        assertEquals("Choose how often it repeats.", message(draft(frequency = "daily")))
        assertEquals(RecurringFormRules.REMIND_INVALID, message(draft(remind = null)))
        assertEquals(RecurringFormRules.REMIND_INVALID, message(draft(remind = 31)))
        assertEquals(RecurringFormRules.REMIND_INVALID, message(draft(remind = -1)))
        assertEquals(0, ok(draft(remind = 0)).remindDaysBefore)
        assertEquals(30, ok(draft(remind = 30)).remindDaysBefore)
    }

    // --- the rule that is saved ----------------------------------------------------

    @Test
    fun aNewMonthlyRuleIsActiveAndAnchoredOnTheDayPicked() {
        val rule = RecurringFormRules.toRule(ok(draft(date = "2026-12-31")), existing = null, id = "new-id", owner = "me")
        assertEquals("new-id", rule.id)
        assertEquals("me", rule.ownerId)
        assertTrue(rule.active)
        assertEquals(31, rule.anchorDay)
        assertEquals("2026-12-31", rule.nextDate)
        assertEquals("Rent", rule.merchant)
        assertEquals(1_500_000L, rule.amount)
        assertEquals(false, rule.deleted)
    }

    @Test
    fun aWeeklyRuleHasNoAnchor() {
        assertNull(RecurringFormRules.toRule(ok(draft(frequency = "weekly")), null, "id", "me").anchorDay)
    }

    private fun existing(next: String = "2026-02-28", anchor: Int? = 31, active: Boolean = true) = RecurringRule(
        "old-id", "me", TxnType.EXPENSE, "w1", 1_500_000, "bills", "Rent", null, RecurringFrequency.MONTHLY, next, 1, active, anchor, 777,
    )

    @Test
    fun editingTheAmountKeepsTheAnchorOfARuleSittingOnAShortMonth() {
        val old = existing(next = "2026-02-28", anchor = 31)
        val rule = RecurringFormRules.toRule(ok(draft(amount = "16,000", date = "2026-02-28")), old, "ignored", "someone")
        assertEquals("old-id", rule.id)
        assertEquals("me", rule.ownerId)
        assertEquals(31, rule.anchorDay)
        assertEquals(1_600_000L, rule.amount)
    }

    @Test
    fun pickingANewDateMovesTheAnchor() {
        val old = existing(next = "2026-02-28", anchor = 31)
        val rule = RecurringFormRules.toRule(ok(draft(date = "2026-03-15")), old, "x", "me")
        assertEquals(15, rule.anchorDay)
    }

    @Test
    fun changingTheFrequencyMovesTheAnchorToo() {
        val old = existing(next = "2026-02-28", anchor = 31)
        assertNull(RecurringFormRules.toRule(ok(draft(frequency = "weekly", date = "2026-02-28")), old, "x", "me").anchorDay)
        assertEquals(28, RecurringFormRules.toRule(ok(draft(frequency = "yearly", date = "2026-02-28")), old, "x", "me").anchorDay)
    }

    @Test
    fun editingAKeepsAPausedRulePausedAndAnUnanchoredRuleGetsTheDateDay() {
        val paused = existing(active = false, anchor = null)
        val rule = RecurringFormRules.toRule(ok(draft(date = "2026-02-28")), paused, "x", "me")
        assertEquals(false, rule.active)
        assertEquals(28, rule.anchorDay)
    }

    // --- the options and the heads-up -----------------------------------------------

    @Test
    fun remindOptionsIncludeAStoredValueThatIsNotInTheUsualList() {
        assertEquals(listOf("0", "1", "2", "3", "7"), RecurringFormRules.remindOptions(1).map { it.key })
        assertEquals(listOf("0", "1", "2", "3", "5", "7"), RecurringFormRules.remindOptions(5).map { it.key })
        assertEquals("5 days before", RecurringFormRules.remindOptions(5).first { it.key == "5" }.label)
    }

    @Test
    fun categoryOptionsStartWithNoCategory() {
        val expense = RecurringFormRules.categoryOptions(TxnType.EXPENSE)
        assertNull(expense.first().key)
        assertEquals("No category", expense.first().label)
        assertTrue(expense.any { it.key == "bills" })
        assertTrue(RecurringFormRules.categoryOptions(TxnType.INCOME).any { it.key == "salary" })
        assertEquals("No category", RecurringFormRules.categoryLabel(null))
        assertEquals("Bills & Utilities", RecurringFormRules.categoryLabel("bills"))
    }

    @Test
    fun typeAndFrequencyOptionsAreTheAppsOwn() {
        assertEquals(listOf("expense", "income"), RecurringFormRules.typeOptions.map { it.key })
        assertEquals(listOf("weekly", "monthly", "yearly"), RecurringFormRules.frequencyOptions.map { it.key })
    }

    @Test
    fun aPastDateSaysHowManyEntriesWillBeLogged() {
        assertNull(RecurringFormRules.pastNote(RecurringFrequency.MONTHLY, LocalDate.parse("2026-11-25"), today))
        assertNull(RecurringFormRules.pastNote(RecurringFrequency.MONTHLY, LocalDate.parse("2026-12-01"), today))
        assertTrue(RecurringFormRules.pastNote(RecurringFrequency.MONTHLY, LocalDate.parse("2026-08-15"), today)!!.endsWith("until today: 4 entries."))
        assertTrue(RecurringFormRules.pastNote(RecurringFrequency.MONTHLY, LocalDate.parse("2026-11-24"), today)!!.endsWith("until today: 1 entry."))
        assertTrue(RecurringFormRules.pastNote(RecurringFrequency.WEEKLY, LocalDate.parse("2000-01-03"), today)!!.endsWith("at least 366 entries."))
    }
}
