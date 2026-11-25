package com.chimu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The budget math and rules behind More -> Budgets, Home's budget bars and the alerts (RFC-009).
 *
 * The first test runs the PARITY VECTOR, the same data and expected numbers as the web dashboard's test, so the phone and the
 * web are held to showing the same usage (RFC-009 acceptance). Keep in sync with web/test-budgets.js.
 */
class BudgetsTest {
    private val me = "me"
    private val month = "2026-11"

    private fun txn(
        id: String,
        owner: String,
        category: String?,
        date: String,
        amount: Long,
        type: TxnType = TxnType.EXPENSE,
        deleted: Boolean = false,
        status: String = "confirmed",
    ) = Txn(
        id = id, ownerId = owner, type = type, walletId = "w-$owner",
        toWalletId = if (type == TxnType.TRANSFER) "w2-$owner" else null,
        amount = amount, date = date, category = category, merchant = null, note = null, isBusiness = false,
        status = status, deleted = deleted,
    )

    private fun budget(id: String, group: String?, category: String?, limit: Long, owner: String = me, deleted: Boolean = false) =
        Budget(id = id, ownerId = owner, groupId = group, category = category, monthlyLimit = limit, deleted = deleted)

    private fun group(id: String, name: String, vararg members: String) =
        Group(id, name, "CODE12", members.first()) to members.mapIndexed { i, m -> Membership(id, m, if (i == 0) "owner" else "member") }

    private fun snapshot(vararg groups: Pair<Group, List<Membership>>, txns: List<Txn> = emptyList()) = GroupSnapshot(
        me = me,
        groups = groups.map { it.first },
        memberships = groups.flatMap { it.second },
        txns = txns,
    )

    private fun usage(list: List<BudgetUsage>, id: String) = list.first { it.budget.id == id }

    // --- PARITY VECTOR (keep in sync with web/test-budgets.js) -------------------------------------------------------
    // month = "2026-11". me = "me". Group "g1" members: me, kim. "zed" is not in g1. Txns (type expense unless noted, amounts centavos):
    //  t1 me food 2026-11-03 30000 | t2 me food 2026-11-20 20000 | t3 me groceries 2026-11-05 50000 | t4 me category=null 2026-11-06 10000 | t5 me category="xyz" 2026-11-07 5000
    //  t6 me food 2026-10-31 99900 (other month) | t7 me INCOME salary 2026-11-04 1000000 | t8 me TRANSFER 2026-11-08 70000 | t9 me food 2026-11-09 40000 deleted=true
    //  t10 kim food 2026-11-10 25000 | t11 kim groceries 2026-11-11 15000 | t12 zed food 2026-11-12 99000
    // Budgets (all live) and expected (spent, percent, level):
    //  b1 me personal overall limit 200000        -> 115000, 57, OK
    //  b2 me personal food limit 60000            -> 50000, 83, WARN
    //  b3 me personal other limit 15000           -> 15000, 100, OVER
    //  b4 group g1 food limit 100000 (owner me)   -> 75000, 75, OK      (me 50000 + kim 25000)
    //  b5 group g1 overall limit 150000 (owner me)-> 155000, 103, OVER  (me 115000 + kim 40000)
    //  b6 me personal groceries limit 40000       -> 50000, 125, OVER
    private val vectorTxns = listOf(
        txn("t1", "me", "food", "2026-11-03", 30000),
        txn("t2", "me", "food", "2026-11-20", 20000),
        txn("t3", "me", "groceries", "2026-11-05", 50000),
        txn("t4", "me", null, "2026-11-06", 10000),
        txn("t5", "me", "xyz", "2026-11-07", 5000),
        txn("t6", "me", "food", "2026-10-31", 99900),
        txn("t7", "me", "salary", "2026-11-04", 1000000, type = TxnType.INCOME),
        txn("t8", "me", null, "2026-11-08", 70000, type = TxnType.TRANSFER),
        txn("t9", "me", "food", "2026-11-09", 40000, deleted = true),
        txn("t10", "kim", "food", "2026-11-10", 25000),
        txn("t11", "kim", "groceries", "2026-11-11", 15000),
        txn("t12", "zed", "food", "2026-11-12", 99000),
    )
    private val vectorBudgets = listOf(
        budget("b1", null, null, 200000),
        budget("b2", null, "food", 60000),
        budget("b3", null, "other", 15000),
        budget("b4", "g1", "food", 100000),
        budget("b5", "g1", null, 150000),
        budget("b6", null, "groceries", 40000),
    )
    private val g1 = group("g1", "Family", "me", "kim")

    // Everything is handed over, zed's rows and the deleted one included: the rules, not the caller, must leave them out.
    private fun vectorUsages(extra: List<Txn> = emptyList()): List<BudgetUsage> {
        val all = vectorTxns + extra
        return BudgetRules.usageList(me, vectorBudgets, all.filter { it.ownerId == me }, snapshot(g1, txns = all), month)
    }

    @Test
    fun parityVector_spentPercentAndLevel() {
        val usages = vectorUsages()
        val expected = mapOf(
            "b1" to Triple(115000L, 57, BudgetLevel.OK),
            "b2" to Triple(50000L, 83, BudgetLevel.WARN),
            "b3" to Triple(15000L, 100, BudgetLevel.OVER),
            "b4" to Triple(75000L, 75, BudgetLevel.OK),
            "b5" to Triple(155000L, 103, BudgetLevel.OVER),
            "b6" to Triple(50000L, 125, BudgetLevel.OVER),
        )
        assertEquals(expected.keys, usages.map { it.budget.id }.toSet())
        for ((id, want) in expected) {
            val u = usage(usages, id)
            assertEquals("$id spent", want.first, u.spent)
            assertEquals("$id percent", want.second, u.percent)
            assertEquals("$id level", want.third, u.level)
        }
    }

    @Test
    fun parityVector_alsoHoldsWhenTheSnapshotIsBuiltTheWayTheRepoBuildsIt() {
        // zed is in some other group, and the cache holds rows of people this user shares nothing with.
        val groups = listOf(Group("g1", "Family", "X", "me"), Group("g2", "Others", "Y", "zed"))
        val memberships = listOf(
            Membership("g1", "me", "owner"), Membership("g1", "kim", "member"), Membership("g2", "zed", "owner"),
        )
        val snap = GroupRules.snapshot(me, groups, memberships, emptyMap(), vectorTxns, emptyList())
        val usages = BudgetRules.usageList(me, vectorBudgets, vectorTxns.filter { it.ownerId == me && !it.deleted }, snap, month)
        assertEquals(75000L, usage(usages, "b4").spent)
        assertEquals(155000L, usage(usages, "b5").spent)
        assertEquals(115000L, usage(usages, "b1").spent)
    }

    @Test
    fun anInboxRow_neverCounts_personalOrGroup() {
        val inbox = txn("inbox1", "me", "food", "2026-11-09", 40000, status = "inbox")
        val withInbox = vectorUsages(listOf(inbox))
        val without = vectorUsages()
        assertEquals(without.map { it.spent }, withInbox.map { it.spent })
        val kimInbox = txn("inbox2", "kim", "food", "2026-11-09", 40000, status = "inbox")
        assertEquals(75000L, usage(vectorUsages(listOf(kimInbox)), "b4").spent)
    }

    @Test
    fun aScanNotReadYet_countsAsZero() {
        val pending = txn("scan", "me", null, "2026-11-20", 0, status = "pending")
        assertEquals(115000L, usage(vectorUsages(listOf(pending)), "b1").spent)
    }

    @Test
    fun otherMonths_areTheirOwnNumbers() {
        val october = BudgetRules.usageList(me, vectorBudgets, vectorTxns.filter { it.ownerId == me }, snapshot(g1, txns = vectorTxns), "2026-10")
        assertEquals(99900L, usage(october, "b1").spent)
        assertEquals(99900L, usage(october, "b2").spent)
        assertEquals(0L, usage(october, "b6").spent)
        assertEquals(99900L, usage(october, "b4").spent) // my food, in the group budget too
    }

    @Test
    fun anExplicitOtherCategoryCounts_withNullAndUnknownOnes() {
        val txns = listOf(
            txn("a", me, "other", "2026-11-01", 100), txn("b", me, null, "2026-11-01", 200), txn("c", me, "xyz", "2026-11-01", 400),
            txn("d", me, "salary", "2026-11-01", 800, type = TxnType.EXPENSE), // an income slug on an expense is not an expense category
        )
        val u = BudgetRules.usageList(me, listOf(budget("o", null, "other", 10000)), txns, GroupSnapshot(me = me), month).single()
        assertEquals(1500L, u.spent)
    }

    @Test
    fun aPersonalBudget_ignoresOtherPeoplesRows_evenIfTheyAreInTheList() {
        val txns = listOf(txn("a", me, "food", "2026-11-01", 100), txn("b", "kim", "food", "2026-11-01", 900))
        val u = BudgetRules.usageList(me, listOf(budget("p", null, "food", 1000)), txns, snapshot(g1, txns = txns), month).single()
        assertEquals(100L, u.spent)
    }

    @Test
    fun aGroupBudget_sumsEveryMember_notJustTheOwner() {
        val txns = listOf(txn("a", me, "food", "2026-11-01", 100), txn("b", "kim", "food", "2026-11-02", 900))
        val kimsBudget = budget("gb", "g1", "food", 2000, owner = "kim")
        val u = BudgetRules.usageList(me, listOf(kimsBudget), txns.filter { it.ownerId == me }, snapshot(g1, txns = txns), month).single()
        assertEquals(1000L, u.spent)
    }

    // --- percent and level ---------------------------------------------------------------------------------------------

    @Test
    fun percentFloors_79point9StaysBelowWarn() {
        assertEquals(79, BudgetRules.percent(79_900, 100_000))
        assertEquals(BudgetLevel.OK, BudgetRules.level(79_900, 100_000))
        assertEquals(79, BudgetRules.percent(7_999, 10_000))
        assertEquals(BudgetLevel.OK, BudgetRules.level(7_999, 10_000))
    }

    @Test
    fun exactly80Percent_isWarn() {
        assertEquals(80, BudgetRules.percent(8_000, 10_000))
        assertEquals(BudgetLevel.WARN, BudgetRules.level(8_000, 10_000))
        assertEquals(BudgetLevel.WARN, BudgetRules.level(80, 100))
        assertEquals(BudgetLevel.WARN, BudgetRules.level(8, 10)) // whole numbers, no rounding
    }

    @Test
    fun just_belowFullIsWarn_andExactly100PercentIsOver() {
        assertEquals(99, BudgetRules.percent(9_999, 10_000))
        assertEquals(BudgetLevel.WARN, BudgetRules.level(9_999, 10_000))
        assertEquals(100, BudgetRules.percent(10_000, 10_000))
        assertEquals(BudgetLevel.OVER, BudgetRules.level(10_000, 10_000))
        assertEquals(BudgetLevel.OVER, BudgetRules.level(10_001, 10_000))
    }

    @Test
    fun aLimitOfZeroOrLess_isUnset_neverOver() {
        for (limit in listOf(0L, -5L)) {
            assertEquals(0, BudgetRules.percent(123_456, limit))
            assertEquals(BudgetLevel.OK, BudgetRules.level(123_456, limit))
            val u = BudgetUsage(budget("x", null, null, limit), 123_456, BudgetRules.percent(123_456, limit), BudgetRules.level(123_456, limit))
            assertEquals(0f, u.barFraction, 0f)
        }
    }

    @Test
    fun hugePercentDoesNotOverflow() {
        assertEquals(Int.MAX_VALUE, BudgetRules.percent(100_000_000_000_000L, 1))
    }

    @Test
    fun remainingMayGoNegative_andTheBarStopsAtFull() {
        val over = usage(vectorUsages(), "b6") // 50000 of 40000
        assertEquals(-10_000L, over.remaining)
        assertEquals(1f, over.barFraction, 0f)
        val half = usage(vectorUsages(), "b1") // 115000 of 200000
        assertEquals(85_000L, half.remaining)
        assertEquals(0.575f, half.barFraction, 0.0001f)
        val none = BudgetUsage(budget("z", null, null, 1000), 0, 0, BudgetLevel.OK)
        assertEquals(0f, none.barFraction, 0f)
    }

    // --- visibility ----------------------------------------------------------------------------------------------------

    @Test
    fun visible_mineAndGroupMatesInMyGroups_butNeverOthersPersonalOrOthersGroups() {
        val snap = snapshot(g1)
        val all = listOf(
            budget("mine", null, "food", 1),
            budget("myGroup", "g1", "food", 1),
            budget("kimPersonal", null, "food", 1, owner = "kim"),
            budget("kimGroup", "g1", "groceries", 1, owner = "kim"),
            budget("zedGroup", "g9", "food", 1, owner = "zed"),
            budget("meOtherGroup", "g9", "food", 1),
            budget("gone", null, "health", 1, deleted = true),
            budget("goneGroup", "g1", "health", 1, owner = "kim", deleted = true),
        )
        val ids = BudgetRules.visible(me, all, snap).map { it.id }.toSet()
        assertEquals(setOf("mine", "myGroup", "kimGroup"), ids)
    }

    @Test
    fun visible_withNoGroups_isJustMyPersonalBudgets() {
        val all = listOf(budget("a", null, null, 1), budget("b", "g1", null, 1), budget("c", null, null, 1, owner = "kim"))
        assertEquals(listOf("a"), BudgetRules.visible(me, all, GroupSnapshot(me = me)).map { it.id })
    }

    @Test
    fun usageList_hasNoEntryForWhatIsNotVisible() {
        val all = listOf(budget("kimPersonal", null, null, 100, owner = "kim"), budget("mine", null, null, 100))
        val txns = listOf(txn("a", "kim", "food", "2026-11-01", 500))
        assertEquals(listOf("mine"), BudgetRules.usageList(me, all, emptyList(), snapshot(g1, txns = txns), month).map { it.budget.id })
    }

    // --- ordering ------------------------------------------------------------------------------------------------------

    @Test
    fun order_personalFirst_thenGroupsByName_overallFirst_thenCategoryLabel() {
        val g2 = group("g2", "alpha club", "me")
        val snap = snapshot(g1, g2) // "alpha club" sorts before "Family"
        val all = listOf(
            budget("gFamilyFood", "g1", "food", 1),
            budget("gFamilyAll", "g1", null, 1),
            budget("gAlphaHealth", "g2", "health", 1),
            budget("pTransport", null, "transport", 1),
            budget("pAll", null, null, 1),
            budget("pFood", null, "food", 1), // "Food & Dining"
            budget("pBills", null, "bills", 1), // "Bills & Utilities"
            budget("pOther", null, "other", 1), // "Other"
        )
        assertEquals(
            listOf("pAll", "pBills", "pFood", "pOther", "pTransport", "gAlphaHealth", "gFamilyAll", "gFamilyFood"),
            BudgetRules.visible(me, all.shuffled(java.util.Random(7)), snap).map { it.id },
        )
    }

    @Test
    fun order_inOneScope_myBudgetComesBeforeAMatesForTheSameCategory_andIsStable() {
        val snap = snapshot(g1)
        val all = listOf(budget("k", "g1", "food", 1, owner = "kim"), budget("m", "g1", "food", 1))
        assertEquals(listOf("m", "k"), BudgetRules.visible(me, all, snap).map { it.id })
        assertEquals(listOf("m", "k"), BudgetRules.visible(me, all.reversed(), snap).map { it.id })
    }

    @Test
    fun top_isByPercentDescending_limitedToN_equalPercentsKeepTheirOrder() {
        val usages = vectorUsages()
        // b6 125, b5 103, b3 100, b2 83, b4 75, b1 57
        assertEquals(listOf("b6", "b5", "b3"), BudgetRules.top(usages).map { it.budget.id })
        assertEquals(listOf("b6"), BudgetRules.top(usages, 1).map { it.budget.id })
        assertEquals(6, BudgetRules.top(usages, 99).size)
        assertTrue(BudgetRules.top(usages, 0).isEmpty())
        assertTrue(BudgetRules.top(usages, -2).isEmpty())
        val tied = listOf(
            BudgetUsage(budget("x", null, null, 100), 50, 50, BudgetLevel.OK),
            BudgetUsage(budget("y", null, null, 100), 50, 50, BudgetLevel.OK),
        )
        assertEquals(listOf("x", "y"), BudgetRules.top(tied).map { it.budget.id })
    }

    // --- check() -------------------------------------------------------------------------------------------------------

    private fun check(
        group: String? = null, category: String? = "food", limit: Long? = 5000, editing: String? = null,
        existing: List<Budget> = emptyList(), snap: GroupSnapshot = snapshot(g1),
    ) = BudgetRules.check(me, group, category, limit, editing, existing, snap)

    @Test
    fun check_acceptsAGoodDraft_personalOrGroup_categoryOrOverall() {
        assertNull(check())
        assertNull(check(category = null))
        assertNull(check(group = "g1"))
        assertNull(check(group = "g1", category = null, limit = 1))
        assertNull(check(category = "other"))
    }

    @Test
    fun check_limitMustBeAboveZero() {
        assertEquals(BudgetRules.LIMIT_HELP, check(limit = null))
        assertEquals(BudgetRules.LIMIT_HELP, check(limit = 0))
        assertEquals(BudgetRules.LIMIT_HELP, check(limit = -100))
    }

    @Test
    fun check_categoryMustBeAnExpenseSlug() {
        assertEquals(BudgetRules.CATEGORY_HELP, check(category = "salary")) // an income category
        assertEquals(BudgetRules.CATEGORY_HELP, check(category = "xyz"))
        assertEquals(BudgetRules.CATEGORY_HELP, check(category = ""))
    }

    @Test
    fun check_groupMustBeOneOfMine() {
        assertEquals(BudgetRules.GROUP_HELP, check(group = "g9"))
        assertEquals(BudgetRules.GROUP_HELP, check(group = "g1", snap = GroupSnapshot(me = me)))
    }

    @Test
    fun check_refusesASecondLiveBudgetForTheSameScope_withAClearMessage() {
        val existing = listOf(budget("a", null, "food", 1000), budget("b", "g1", null, 1000), budget("c", null, null, 1000))
        assertEquals("You already have a budget for Food & Dining (Personal). Edit that one instead.", check(existing = existing))
        assertEquals("You already have a budget for Overall (group Family). Edit that one instead.", check(group = "g1", category = null, existing = existing))
        assertEquals("You already have a budget for Overall (Personal). Edit that one instead.", check(category = null, existing = existing))
    }

    @Test
    fun check_editingAnExistingBudgetDoesNotClashWithItself() {
        val existing = listOf(budget("a", null, "food", 1000))
        assertNull(check(editing = "a", existing = existing))
        // ...but moving it onto another budget's scope does.
        val two = existing + budget("b", null, "groceries", 1000)
        assertNotNull(check(category = "groceries", editing = "a", existing = two))
    }

    @Test
    fun check_otherScopesOtherOwnersAndDeletedBudgetsDoNotClash() {
        val existing = listOf(
            budget("a", null, "food", 1000, owner = "kim"), // someone else's
            budget("b", "g1", "food", 1000), // same category, another scope
            budget("c", null, "groceries", 1000), // same scope, another category
            budget("d", null, "food", 1000, deleted = true), // deleted
        )
        assertNull(check(existing = existing))
    }

    // --- words ---------------------------------------------------------------------------------------------------------

    @Test
    fun text_titlesScopesSummaryAndStatus() {
        val snap = snapshot(g1, txns = vectorTxns)
        val usages = BudgetRules.usageList(me, vectorBudgets, vectorTxns.filter { it.ownerId == me }, snap, month)
        val b1 = usage(usages, "b1")
        val b2 = usage(usages, "b2")
        val b4 = usage(usages, "b4")
        val b6 = usage(usages, "b6")
        assertEquals("Overall", BudgetText.title(b1, snap))
        assertEquals("Food & Dining", BudgetText.title(b2, snap))
        assertEquals("Personal", BudgetText.scope(b2.budget, snap))
        assertEquals("Family", BudgetText.scope(b4.budget, snap))
        assertEquals("Group", BudgetText.scope(b4.budget, GroupSnapshot(me = me))) // name unknown
        assertEquals("₱500.00 of ₱600.00 · 83% used", BudgetText.summary(b2))
        assertEquals("On track", BudgetText.status(b1))
        assertEquals("Close to the limit", BudgetText.status(b2))
        assertEquals("Over the limit by ₱100.00", BudgetText.status(b6))
    }

    @Test
    fun text_exactlyAtTheLimit_isOverTheLevelButNotOverByAnything() {
        val u = BudgetUsage(budget("x", null, null, 1000), 1000, 100, BudgetLevel.OVER)
        assertEquals("At the limit", BudgetText.status(u))
    }

    @Test
    fun text_describeIsOneFullSentenceForTalkBack() {
        val snap = snapshot(g1, txns = vectorTxns)
        val usages = BudgetRules.usageList(me, vectorBudgets, vectorTxns.filter { it.ownerId == me }, snap, month)
        assertEquals(
            "Food & Dining budget, personal. ₱500.00 spent of ₱600.00, 83 percent used. Close to the limit.",
            BudgetText.describe(usage(usages, "b2"), snap),
        )
        assertEquals(
            "Overall budget, group Family. ₱1,550.00 spent of ₱1,500.00, 103 percent used. Over the limit by ₱50.00.",
            BudgetText.describe(usage(usages, "b5"), snap),
        )
    }

    @Test
    fun text_anUnsetLimitSaysSo() {
        val u = BudgetUsage(budget("x", null, null, 0), 2500, 0, BudgetLevel.OK)
        assertEquals("₱25.00 spent · no limit set", BudgetText.summary(u))
        assertEquals("No limit set", BudgetText.status(u))
    }

    @Test
    fun text_alertWording_isInformationNotAdvice() {
        val snap = snapshot(g1, txns = vectorTxns)
        val usages = BudgetRules.usageList(me, vectorBudgets, vectorTxns.filter { it.ownerId == me }, snap, month)
        val b2 = usage(usages, "b2")
        assertEquals("Food & Dining budget: 83% used", BudgetText.alertTitle(b2, snap))
        assertEquals("₱500.00 of ₱600.00 spent in November 2026. Close to the limit.", BudgetText.alertBody(b2, month))
        assertEquals("Group Family · Overall budget: 103% used", BudgetText.alertTitle(usage(usages, "b5"), snap))
    }

    @Test
    fun theSqlConstants_areTheTombstoneAndLiveFilterTheDbRelyOn() {
        // Run against a real SQLite by /tmp/wA/chk/check_budget_sql.py; here just pin what it checks.
        assertEquals("deleted = 0", BudgetSql.LIVE_WHERE)
        assertTrue(BudgetSql.TOMBSTONE.contains("deleted = 1") && BudgetSql.TOMBSTONE.contains("dirty = 1"))
        assertFalse(BudgetSql.TOMBSTONE.contains("DELETE FROM"))
    }
}
