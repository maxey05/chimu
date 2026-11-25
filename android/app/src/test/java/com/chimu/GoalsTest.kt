package com.chimu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The savings-goal math and rules behind More -> Goals (RFC-011).
 *
 * The first tests run the PARITY VECTOR: the same wallets, transactions, goals and expected numbers and words as the web
 * dashboard's test, so the phone and the web are held to showing the same progress. Keep in sync with web/test-goals.js.
 */
class GoalsTest {
    private val me = "me"
    private val today = "2026-11-25"

    private fun wallet(id: String, owner: String, type: WalletType, opening: Long, deleted: Boolean = false) =
        Wallet(id = id, ownerId = owner, name = "w-$id", type = type, openingBalance = opening, deleted = deleted)

    private fun transfer(id: String, owner: String, from: String, to: String, amount: Long, date: String, deleted: Boolean = false) =
        Txn(
            id = id, ownerId = owner, type = TxnType.TRANSFER, walletId = from, toWalletId = to, amount = amount, date = date,
            category = null, merchant = null, note = null, isBusiness = false, deleted = deleted,
        )

    private fun other(id: String, owner: String, type: TxnType, wallet: String, amount: Long, date: String) = Txn(
        id = id, ownerId = owner, type = type, walletId = wallet, toWalletId = null, amount = amount, date = date,
        category = null, merchant = null, note = null, isBusiness = false,
    )

    private fun goal(id: String, owner: String, name: String, target: Long, deadline: String?, wallet: String, deleted: Boolean = false) =
        Goal(id = id, ownerId = owner, name = name, target = target, deadline = deadline, walletId = wallet, deleted = deleted)

    // --- PARITY VECTOR (keep in sync with web/test-goals.js) ---------------------------------------------------------
    // today = "2026-11-25". Owners: me, kim. Amounts are centavos.
    // Wallets: wA me savings 0 | wB me savings 100000 | wC me savings 0 | wD me cash 0 | wE kim savings 0 | wF kim cash 0 | wZ me savings 0 ARCHIVED
    // Txns (not deleted unless noted):
    //  t1 me transfer wD->wA 200000 | t2 me transfer wD->wA 150000 | t3 me transfer wA->wD 50000 | t4 me expense wA 20000 | t5 me income wA 10000
    //  t6 me transfer wD->wA 99900 DELETED | t7 me transfer wD->wB 400000 | t8 me transfer wD->wC 5000 | t9 kim transfer wF->wE 300000
    //  => balances: wA 290000, wB 500000, wC 5000, wE 300000
    // Goals, as id | owner | name | target | deadline | wallet -> saved, left, percent, reached, plan
    //  g1  me  Baguio trip      1200000 2027-02-25 wA -> 290000  910000   24 false MONTHLY:303334:3   (exactly 3 months out)
    //  g2  me  Laptop            500000 2027-01-10 wB -> 500000       0  100 true  REACHED
    //  g3  me  Emergency fund    800000 none       wC ->   5000  795000    0 false NODEADLINE
    //  g4  me  Tuition           600000 2026-11-30 wA -> 290000  310000   48 false MONTHLY:310000:1   (deadline this month)
    //  g5  me  Old debt         1000000 2026-10-31 wC ->   5000  995000    0 false OVERDUE
    //  g6  me  Phone             100000 2027-03-01 wB -> 500000       0  500 true  REACHED            (saved past the target)
    //  g7  me  Gift              700000 2026-11-25 wC ->   5000  695000    0 false MONTHLY:695000:1   (deadline today)
    //  g8  me  No target              0 none       wA -> 290000       0    0 false NOTARGET
    //  g9  me  Lost wallet goal 1000000 2027-02-25 wZ ->      0 1000000    0 false WALLET           (wallet archived)
    //  g10 me  Camera           1000000 2027-02-24 wC ->   5000  995000    0 false MONTHLY:497500:2   (3 calendar months but the last is not complete)
    //  g11 kim Kim's bike       1000000 2027-05-25 wE -> 300000  700000   30 false MONTHLY:116667:6
    //  g12 me  House deposit    1200000 2027-11-25 wC ->   5000 1195000    0 false MONTHLY:99584:12
    //  g13 me  Concert           300000 2026-12-01 wC ->   5000  295000    1 false MONTHLY:295000:1   (next month, earlier day)
    // Phone list for me, in order: g5 g7 g4 g13 g2 g10 g1 g9 g6 g12 g3 g8 (soonest deadline, none last, then name).
    private val wallets = listOf(
        wallet("wA", "me", WalletType.SAVINGS, 0),
        wallet("wB", "me", WalletType.SAVINGS, 100000),
        wallet("wC", "me", WalletType.SAVINGS, 0),
        wallet("wD", "me", WalletType.CASH, 0),
        wallet("wE", "kim", WalletType.SAVINGS, 0),
        wallet("wF", "kim", WalletType.CASH, 0),
        wallet("wZ", "me", WalletType.SAVINGS, 777, deleted = true),
    )
    private val txns = listOf(
        transfer("t1", "me", "wD", "wA", 200000, "2026-11-01"),
        transfer("t2", "me", "wD", "wA", 150000, "2026-11-10"),
        transfer("t3", "me", "wA", "wD", 50000, "2026-11-12"),
        other("t4", "me", TxnType.EXPENSE, "wA", 20000, "2026-11-13"),
        other("t5", "me", TxnType.INCOME, "wA", 10000, "2026-11-14"),
        transfer("t6", "me", "wD", "wA", 99900, "2026-11-15", deleted = true),
        transfer("t7", "me", "wD", "wB", 400000, "2026-11-15"),
        transfer("t8", "me", "wD", "wC", 5000, "2026-11-16"),
        transfer("t9", "kim", "wF", "wE", 300000, "2026-11-17"),
    )
    private val goals = listOf(
        goal("g1", "me", "Baguio trip", 1200000, "2027-02-25", "wA"),
        goal("g2", "me", "Laptop", 500000, "2027-01-10", "wB"),
        goal("g3", "me", "Emergency fund", 800000, null, "wC"),
        goal("g4", "me", "Tuition", 600000, "2026-11-30", "wA"),
        goal("g5", "me", "Old debt", 1000000, "2026-10-31", "wC"),
        goal("g6", "me", "Phone", 100000, "2027-03-01", "wB"),
        goal("g7", "me", "Gift", 700000, "2026-11-25", "wC"),
        goal("g8", "me", "No target", 0, null, "wA"),
        goal("g9", "me", "Lost wallet goal", 1000000, "2027-02-25", "wZ"),
        goal("g10", "me", "Camera", 1000000, "2027-02-24", "wC"),
        goal("g11", "kim", "Kim's bike", 1000000, "2027-05-25", "wE"),
        goal("g12", "me", "House deposit", 1200000, "2027-11-25", "wC"),
        goal("g13", "me", "Concert", 300000, "2026-12-01", "wC"),
    )

    /** id -> saved, left, percent, reached, plan code. */
    private val expected = mapOf(
        "g1" to Expect(290000, 910000, 24, false, "MONTHLY:303334:3"),
        "g2" to Expect(500000, 0, 100, true, "REACHED"),
        "g3" to Expect(5000, 795000, 0, false, "NODEADLINE"),
        "g4" to Expect(290000, 310000, 48, false, "MONTHLY:310000:1"),
        "g5" to Expect(5000, 995000, 0, false, "OVERDUE"),
        "g6" to Expect(500000, 0, 500, true, "REACHED"),
        "g7" to Expect(5000, 695000, 0, false, "MONTHLY:695000:1"),
        "g8" to Expect(290000, 0, 0, false, "NOTARGET"),
        "g9" to Expect(0, 1000000, 0, false, "WALLET"),
        "g10" to Expect(5000, 995000, 0, false, "MONTHLY:497500:2"),
        "g11" to Expect(300000, 700000, 30, false, "MONTHLY:116667:6"),
        "g12" to Expect(5000, 1195000, 0, false, "MONTHLY:99584:12"),
        "g13" to Expect(5000, 295000, 1, false, "MONTHLY:295000:1"),
    )

    private data class Expect(val saved: Long, val left: Long, val percent: Int, val reached: Boolean, val plan: String)

    private fun planCode(plan: GoalPlan): String = when (plan) {
        GoalPlan.WalletUnavailable -> "WALLET"
        GoalPlan.NoTarget -> "NOTARGET"
        GoalPlan.Reached -> "REACHED"
        GoalPlan.NoDeadline -> "NODEADLINE"
        GoalPlan.Overdue -> "OVERDUE"
        is GoalPlan.Monthly -> "MONTHLY:${plan.perMonth}:${plan.months}"
    }

    private fun progress(id: String) = GoalRules.progressList(goals, wallets, txns, today).first { it.goal.id == id }

    @Test
    fun parityVector_everyGoalGivesTheSameNumbersAsTheWeb() {
        val all = GoalRules.progressList(goals, wallets, txns, today)
        assertEquals(goals.map { it.id }, all.map { it.goal.id })
        for (p in all) {
            val e = expected.getValue(p.goal.id)
            val actual = listOf(p.saved, p.left, p.percent, p.reached, planCode(p.plan))
            assertEquals(p.goal.id, listOf(e.saved, e.left, e.percent, e.reached, e.plan), actual)
        }
    }

    @Test
    fun parityVector_phoneListIsMineOnlyInDeadlineOrder() {
        val order = GoalRules.visible(me, goals).map { it.id }
        assertEquals(listOf("g5", "g7", "g4", "g13", "g2", "g10", "g1", "g9", "g6", "g12", "g3", "g8"), order)
        assertFalse("kim's goal is not on my phone's list", "g11" in order)
    }

    @Test
    fun parityVector_words() {
        fun words(id: String) = progress(id).let { listOf(GoalText.summary(it), GoalText.status(it), GoalText.plan(it), GoalText.deadline(it.goal)) }
        assertEquals(
            listOf("₱2,900.00 of ₱12,000.00 · 24% saved", "₱9,100.00 left", "Needed per month: ₱3,033.34 (3 months left)", "Deadline: Feb 25, 2027"),
            words("g1"),
        )
        assertEquals(listOf("₱5,000.00 of ₱5,000.00 · 100% saved", "Goal reached", null, "Deadline: Jan 10, 2027"), words("g2"))
        assertEquals(listOf("₱50.00 of ₱8,000.00 · 0% saved", "₱7,950.00 left", "No deadline", null), words("g3"))
        assertEquals(
            listOf("₱2,900.00 of ₱6,000.00 · 48% saved", "₱3,100.00 left", "Needed per month: ₱3,100.00 (1 month left)", "Deadline: Nov 30, 2026"),
            words("g4"),
        )
        assertEquals(
            listOf("₱50.00 of ₱10,000.00 · 0% saved", "₱9,950.00 left", "Deadline passed (Oct 31, 2026)", "Deadline: Oct 31, 2026"),
            words("g5"),
        )
        assertEquals(listOf("₱5,000.00 of ₱1,000.00 · 500% saved", "Goal reached", null, "Deadline: Mar 1, 2027"), words("g6"))
        assertEquals(
            listOf("₱50.00 of ₱7,000.00 · 0% saved", "₱6,950.00 left", "Needed per month: ₱6,950.00 (1 month left)", "Deadline: Nov 25, 2026"),
            words("g7"),
        )
        assertEquals(listOf("₱2,900.00 saved · no target set", "No target set", null, null), words("g8"))
        assertEquals(
            listOf("Target ₱10,000.00", "Savings wallet unavailable", "Edit this goal to choose another savings wallet.", "Deadline: Feb 25, 2027"),
            words("g9"),
        )
        assertEquals("Needed per month: ₱4,975.00 (2 months left)", GoalText.plan(progress("g10")))
    }

    // --- the acceptance cases -----------------------------------------------------------------------------------------

    @Test
    fun acceptance_neededPerMonthForAGoalThreeMonthsOut() {
        // 25 Nov to 25 Feb is 3 months. 910000 left / 3 = 303333.33, rounded up to 303334 so three payments cover it.
        val p = progress("g1")
        assertEquals(GoalPlan.Monthly(303334, 3), p.plan)
        assertTrue(303334 * 3 >= p.left)
        assertTrue(303333 * 3 < p.left)
        // The deadline's day decides whether the last month is whole: one day earlier makes it 2 months.
        assertEquals(GoalPlan.Monthly(497500, 2), progress("g10").plan)
    }

    @Test
    fun acceptance_aTransferIntoTheSavingsWalletMovesTheProgress() {
        val before = progress("g1")
        assertEquals(24, before.percent)
        // Exactly the transfer "Add to goal" saves: from the cash wallet into the goal's wallet.
        val g = goals.first { it.id == "g1" }
        val draft = com.chimu.ui.GoalFormRules.contribution(g, "wD", "1,500", "2026-11-25", wallets) as com.chimu.ui.ContributionDraft.Ok
        val moved = com.chimu.ui.GoalFormRules.contributionTxn(g, draft, "t-new")
        assertEquals(TxnType.TRANSFER, moved.type)
        assertEquals("wA", moved.toWalletId)
        val after = GoalRules.progressList(goals, wallets, txns + moved, today).first { it.goal.id == "g1" }
        assertEquals(before.saved + 150000, after.saved)
        assertEquals(before.left - 150000, after.left)
        assertEquals(36, after.percent) // 440000 / 1200000 = 36.67%
        assertTrue(after.barFraction > before.barFraction)
        // The same transfer lifts every other goal on that wallet, and nothing else.
        assertEquals(progress("g4").saved + 150000, GoalRules.progressList(goals, wallets, txns + moved, today).first { it.goal.id == "g4" }.saved)
        assertEquals(progress("g3").saved, GoalRules.progressList(goals, wallets, txns + moved, today).first { it.goal.id == "g3" }.saved)
    }

    // --- percent, left, reached --------------------------------------------------------------------------------------

    @Test
    fun percent_isFlooredNotRounded() {
        assertEquals(79, GoalRules.percent(799, 1000))
        assertEquals(99, GoalRules.percent(9999, 10000))
        assertEquals(100, GoalRules.percent(10000, 10000))
        assertEquals(0, GoalRules.percent(1, 1000))
    }

    @Test
    fun percent_zeroWhenNothingOrLessIsSavedOrThereIsNoTarget() {
        assertEquals(0, GoalRules.percent(0, 1000))
        assertEquals(0, GoalRules.percent(-500, 1000))
        assertEquals(0, GoalRules.percent(500, 0))
        assertEquals(0, GoalRules.percent(500, -1))
    }

    @Test
    fun percent_canPassOneHundredButTheBarStopsAtFull() {
        assertEquals(500, progress("g6").percent)
        assertEquals(1f, progress("g6").barFraction, 0f)
        assertEquals(0f, progress("g8").barFraction, 0f) // no target
        assertEquals(0f, progress("g9").barFraction, 0f) // wallet unavailable
        assertEquals(0.5f, GoalRules.progress(goal("x", me, "x", 1000, null, "wA"), 500, today).barFraction, 0.0001f)
    }

    @Test
    fun left_neverNegative() {
        assertEquals(0, GoalRules.left(2000, 1000))
        assertEquals(1000, GoalRules.left(0, 1000))
        assertEquals(1500, GoalRules.left(-500, 1000)) // a wallet below zero is further from the goal
        assertEquals(0, GoalRules.left(500, 0))
    }

    @Test
    fun reached_atExactlyTheTargetAndNeverWithoutOne() {
        assertTrue(GoalRules.reached(1000, 1000))
        assertFalse(GoalRules.reached(999, 1000))
        assertFalse(GoalRules.reached(0, 0))
        assertFalse(GoalRules.reached(10, -5))
    }

    @Test
    fun negativeBalanceGivesZeroPercentAndAFullAmountLeftOnTop() {
        val g = goal("neg", me, "Neg", 100000, "2027-02-25", "wN")
        val p = GoalRules.progress(g, -2500, today)
        assertEquals(0, p.percent)
        assertEquals(102500, p.left)
        assertFalse(p.reached)
        assertEquals(GoalPlan.Monthly(34167, 3), p.plan) // ceil(102500 / 3)
    }

    // --- whole months and the monthly amount ---------------------------------------------------------------------------

    @Test
    fun monthsLeft_countsWholeMonthsAndNeverLessThanOne() {
        val cases = listOf(
            Triple("2026-11-25", "2027-02-25", 3),
            Triple("2026-11-25", "2027-02-24", 2), // the last month is one day short
            Triple("2026-11-25", "2027-02-28", 3),
            Triple("2026-11-25", "2026-11-30", 1), // this month
            Triple("2026-11-25", "2026-11-25", 1), // today
            Triple("2026-11-25", "2026-12-01", 1), // next month, earlier day: 0 whole months, raised to 1
            Triple("2026-11-25", "2026-12-24", 1),
            Triple("2026-11-25", "2026-12-25", 1),
            Triple("2026-11-25", "2027-01-25", 2),
            Triple("2026-11-25", "2027-11-25", 12), // across a year end
            Triple("2026-12-31", "2027-01-01", 1),
            Triple("2026-01-31", "2026-02-28", 1),
            Triple("2026-11-25", "2026-10-31", 1), // before today: plan() calls it overdue first
        )
        for ((from, to, months) in cases) assertEquals("$from to $to", months, GoalRules.monthsLeft(from, to))
    }

    @Test
    fun monthsLeft_nullForAnythingThatIsNotARealDate() {
        assertNull(GoalRules.monthsLeft("2026-11-25", "2027-02-30"))
        assertNull(GoalRules.monthsLeft("2026-11-25", "soon"))
        assertNull(GoalRules.monthsLeft("2026-11-25", ""))
        assertNull(GoalRules.monthsLeft("2026-13-01", "2027-01-01"))
        assertNull(GoalRules.monthsLeft("2026-11-25", "2027-2-5"))
    }

    @Test
    fun neededPerMonth_roundsUpSoThePaymentsAlwaysReachTheGoal() {
        assertEquals(303334, GoalRules.neededPerMonth(910000, 3))
        assertEquals(300000, GoalRules.neededPerMonth(900000, 3)) // exact: no extra centavo
        assertEquals(1, GoalRules.neededPerMonth(1, 12))
        assertEquals(500, GoalRules.neededPerMonth(500, 1))
        assertEquals(0, GoalRules.neededPerMonth(0, 3))
        assertEquals(0, GoalRules.neededPerMonth(1000, 0))
    }

    // --- plan: which case wins ---------------------------------------------------------------------------------------

    @Test
    fun plan_noDeadlineMeansNothingToPaceAgainst() {
        assertEquals(GoalPlan.NoDeadline, GoalRules.plan(100000, 0, null, today))
        assertEquals(GoalPlan.NoDeadline, GoalRules.plan(100000, 0, "not a date", today))
    }

    @Test
    fun plan_deadlinePassedIsOverdueAndNotAnAmount() {
        assertEquals(GoalPlan.Overdue, GoalRules.plan(100000, 10, "2026-11-24", today))
        assertEquals(GoalPlan.Overdue, GoalRules.plan(100000, 10, "2025-01-01", today))
    }

    @Test
    fun plan_deadlineTodayOrThisMonthIsOneMonthOfEverythingLeft() {
        assertEquals(GoalPlan.Monthly(99990, 1), GoalRules.plan(100000, 10, "2026-11-25", today))
        assertEquals(GoalPlan.Monthly(99990, 1), GoalRules.plan(100000, 10, "2026-11-30", today))
    }

    @Test
    fun plan_aReachedGoalNeedsNothingWhateverItsDeadline() {
        assertEquals(GoalPlan.Reached, GoalRules.plan(100000, 100000, "2026-01-01", today)) // passed, but done
        assertEquals(GoalPlan.Reached, GoalRules.plan(100000, 250000, null, today))
        assertEquals(GoalPlan.Reached, GoalRules.plan(100000, 100000, "2027-02-25", today))
    }

    @Test
    fun plan_walletUnavailableBeatsEverything_thenNoTarget() {
        assertEquals(GoalPlan.WalletUnavailable, GoalRules.plan(100000, null, "2027-02-25", today))
        assertEquals(GoalPlan.WalletUnavailable, GoalRules.plan(0, null, null, today))
        assertEquals(GoalPlan.NoTarget, GoalRules.plan(0, 5, "2027-02-25", today))
        assertEquals(GoalPlan.NoTarget, GoalRules.plan(-1, 5, null, today))
    }

    @Test
    fun plan_oneCentavoShortIsNotReached() {
        assertEquals(GoalPlan.Monthly(1, 3), GoalRules.plan(100000, 99999, "2027-02-25", today))
    }

    // --- which goals the phone lists ---------------------------------------------------------------------------------

    @Test
    fun visible_dropsDeletedGoalsAndOtherPeoples() {
        val list = listOf(
            goal("a", me, "A", 1, null, "w"),
            goal("b", me, "B", 1, null, "w", deleted = true),
            goal("c", "kim", "C", 1, null, "w"),
        )
        assertEquals(listOf("a"), GoalRules.visible(me, list).map { it.id })
        assertEquals(emptyList<String>(), GoalRules.visible("nobody", list).map { it.id })
    }

    @Test
    fun visible_sortsByDeadlineThenNameIgnoringCaseThenId() {
        val list = listOf(
            goal("1", me, "beta", 1, "2027-01-01", "w"),
            goal("2", me, "Alpha", 1, "2027-01-01", "w"),
            goal("3", me, "zeta", 1, "2026-12-31", "w"),
            goal("4", me, "none b", 1, null, "w"),
            goal("5", me, "None A", 1, null, "w"),
            goal("7", me, "same", 1, null, "w"),
            goal("6", me, "same", 1, null, "w"),
        )
        assertEquals(listOf("3", "2", "1", "5", "4", "6", "7"), GoalRules.visible(me, list).map { it.id })
    }

    // --- wallet rule -------------------------------------------------------------------------------------------------

    @Test
    fun checkWallet_existingMustBeALiveSavingsWallet() {
        assertNull(GoalRules.checkWallet("wA", null, wallets))
        assertNotNull(GoalRules.checkWallet("wD", null, wallets)) // cash
        assertNotNull(GoalRules.checkWallet("wZ", null, wallets)) // archived
        assertNotNull(GoalRules.checkWallet("nope", null, wallets)) // unknown
    }

    @Test
    fun checkWallet_aNewWalletMustBeSavingsWithTheGoalsWalletId() {
        val fresh = wallet("wNew", me, WalletType.SAVINGS, 0)
        assertNull(GoalRules.checkWallet("wNew", fresh, wallets))
        assertNotNull(GoalRules.checkWallet("other", fresh, wallets))
        assertNotNull(GoalRules.checkWallet("wNew", fresh.copy(type = WalletType.CASH), wallets))
    }

    // --- words -------------------------------------------------------------------------------------------------------

    @Test
    fun text_oneMonthIsSingularAndNothingClaimsTaxAdvice() {
        assertTrue(GoalText.plan(progress("g4"))!!.endsWith("(1 month left)"))
        assertTrue(GoalText.plan(progress("g1"))!!.endsWith("(3 months left)"))
        val every = goals.map { progress(it.id) }.flatMap {
            listOf(GoalText.summary(it), GoalText.status(it), GoalText.plan(it) ?: "", GoalText.describe(it, "Wallet"))
        }
        assertTrue(every.none { it.contains("deductible", ignoreCase = true) })
    }

    @Test
    fun text_describeReadsAsOneSentenceForAScreenReader() {
        assertEquals(
            "Baguio trip goal. ₱2,900.00 of ₱12,000.00, 24% saved. ₱9,100.00 left. " +
                "Needed per month: ₱3,033.34 (3 months left). Deadline: Feb 25, 2027. Savings wallet: Bakasyon.",
            GoalText.describe(progress("g1"), "Bakasyon"),
        )
        assertEquals("Laptop goal. ₱5,000.00 of ₱5,000.00, 100% saved. Goal reached. Deadline: Jan 10, 2027.", GoalText.describe(progress("g2"), null))
    }

    @Test
    fun isDate_acceptsOnlyRealIsoDates() {
        assertTrue(GoalRules.isDate("2028-02-29"))
        assertFalse(GoalRules.isDate("2027-02-29"))
        assertFalse(GoalRules.isDate("2027-2-9"))
        assertFalse(GoalRules.isDate(null))
        assertFalse(GoalRules.isDate("2027-02-25T00:00"))
    }

    // --- sql text ----------------------------------------------------------------------------------------------------

    @Test
    fun sql_tombstoneAndOwnRowGuards() {
        assertTrue(GoalSql.TOMBSTONE.contains("deleted = 1") && GoalSql.TOMBSTONE.contains("dirty = 1") && GoalSql.TOMBSTONE.contains("owner_id = ?"))
        assertFalse(GoalSql.TOMBSTONE.contains("DELETE FROM", ignoreCase = true))
        assertEquals("id = ? AND owner_id = ?", GoalSql.OWN_ROW_WHERE)
        assertTrue(GoalSql.OWN_LIVE_WHERE.contains("owner_id = ?") && GoalSql.OWN_LIVE_WHERE.contains("deleted = 0"))
    }
}
