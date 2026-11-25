package com.chimu

import com.chimu.ui.BudgetDraft
import com.chimu.ui.BudgetFormRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules behind the Budgets form and list (RFC-009, phone UI): see ui/BudgetForm.kt. The usage maths is in BudgetsTest. */
class BudgetsFormTest {
    private val me = "me"
    private val kim = "kim"
    private val family = Group("g1", "Family", "ABC234", me)
    private val trip = Group("g2", "Barkada", "XYZ789", kim)

    private fun snapshot(vararg groups: Group) = GroupSnapshot(
        me = me,
        groups = groups.toList(),
        memberships = groups.flatMap { listOf(Membership(it.id, me, "member"), Membership(it.id, kim, "member")) },
        names = mapOf(kim to "Kim", me to "Matt"),
    )

    private fun budget(id: String, groupId: String? = null, category: String? = null, limit: Long = 100_000, owner: String = me) =
        Budget(id, owner, groupId, category, limit)

    private fun usage(b: Budget, spent: Long) =
        BudgetUsage(b, spent, BudgetRules.percent(spent, b.monthlyLimit), BudgetRules.level(spent, b.monthlyLimit))

    private fun draft(
        limit: String,
        groupId: String? = null,
        category: String? = null,
        editing: String? = null,
        existing: List<Budget> = emptyList(),
        snap: GroupSnapshot = snapshot(family),
    ) = BudgetFormRules.draft(me, groupId, category, limit, editing, existing, snap)

    @Test
    fun typedLimitBecomesCentavos() {
        assertEquals(BudgetDraft.Ok(null, "food", 600_000L), draft("6,000", category = "food"))
        assertEquals(BudgetDraft.Ok("g1", null, 600_050L), draft("₱6,000.50", groupId = "g1"))
    }

    @Test
    fun unreadableLimitGetsTheFormatHelp() {
        for (text in listOf("", "  ", "abc", "6,0", "1.234", "-5", "6k")) {
            assertEquals("for '$text'", BudgetDraft.Invalid(BudgetFormRules.LIMIT_INVALID), draft(text))
        }
    }

    @Test
    fun zeroLimitShowsTheRulesMessage() {
        assertEquals(BudgetDraft.Invalid(BudgetRules.LIMIT_HELP), draft("0"))
        assertEquals(BudgetDraft.Invalid(BudgetRules.LIMIT_HELP), draft("0.00"))
    }

    @Test
    fun rulesMessagesAreShownAsTheyAre() {
        assertEquals(BudgetDraft.Invalid(BudgetRules.CATEGORY_HELP), draft("100", category = "salary"))
        assertEquals(BudgetDraft.Invalid(BudgetRules.GROUP_HELP), draft("100", groupId = "g-not-mine"))
    }

    @Test
    fun duplicateIsRefusedButEditingItselfIsNot() {
        val food = budget("b1", category = "food")
        val clash = draft("100", category = "food", existing = listOf(food))
        assertTrue(clash is BudgetDraft.Invalid && "already have" in clash.message)
        assertEquals(BudgetDraft.Ok(null, "food", 10_000L), draft("100", category = "food", editing = "b1", existing = listOf(food)))
        // another scope or another category is not a clash
        assertTrue(draft("100", category = "groceries", existing = listOf(food)) is BudgetDraft.Ok)
        assertTrue(draft("100", groupId = "g1", category = "food", existing = listOf(food)) is BudgetDraft.Ok)
    }

    @Test
    fun otherMembersBudgetIsNotMyDuplicate() {
        val theirs = budget("b9", groupId = "g1", category = "food", owner = kim)
        assertTrue(draft("100", groupId = "g1", category = "food", existing = listOf(theirs)) is BudgetDraft.Ok)
    }

    @Test
    fun scopeChoicesOnlyOfferGroupsTheUserIsIn() {
        assertEquals(listOf(null to "Personal"), BudgetFormRules.scopeOptions(snapshot()).map { it.key to it.label })
        assertEquals(
            listOf(null to "Personal", "g1" to "Group: Family", "g2" to "Group: Barkada"),
            BudgetFormRules.scopeOptions(snapshot(family, trip)).map { it.key to it.label },
        )
    }

    @Test
    fun categoryChoicesAreOverallThenEveryExpenseCategory() {
        val options = BudgetFormRules.categoryOptions()
        assertNull(options.first().key)
        assertEquals(Categories.expense.map { it.slug }, options.drop(1).map { it.key })
        assertEquals(Categories.expense.map { it.label }, options.drop(1).map { it.label })
    }

    @Test
    fun labelsAndNotes() {
        val snap = snapshot(family)
        assertEquals("Personal", BudgetFormRules.scopeLabel(null, snap))
        assertEquals("Group: Family", BudgetFormRules.scopeLabel("g1", snap))
        assertEquals("Overall (all categories)", BudgetFormRules.categoryLabel(null))
        assertEquals("Food & Dining", BudgetFormRules.categoryLabel("food"))
        assertEquals("Counts what every member of the group spends.", BudgetFormRules.scopeNote("g1"))
        assertEquals("Counts only what you spend.", BudgetFormRules.scopeNote(null))
    }

    @Test
    fun onlyTheOwnerMayEditAndOthersSeeWhoSetIt() {
        val snap = snapshot(family)
        val mine = budget("b1", groupId = "g1")
        val theirs = budget("b2", groupId = "g1", owner = kim)
        assertTrue(BudgetFormRules.isMine(mine, me))
        assertTrue(!BudgetFormRules.isMine(theirs, me))
        assertEquals("Set by Kim", BudgetFormRules.setBy(theirs, snap))
        assertEquals("Set by A group member", BudgetFormRules.setBy(budget("b3", groupId = "g1", owner = "zed"), snap))
    }

    @Test
    fun rowDescriptionAddsWhoSetItOnlyForOthers() {
        val snap = snapshot(family)
        val mine = usage(budget("b1", category = "food", limit = 600_000), 480_000)
        val theirs = usage(budget("b2", groupId = "g1", category = "food", owner = kim, limit = 600_000), 480_000)
        assertEquals(BudgetText.describe(mine, snap), BudgetFormRules.rowDescription(mine, snap, me))
        val read = BudgetFormRules.rowDescription(theirs, snap, me)
        assertTrue(read.startsWith(BudgetText.describe(theirs, snap).trimEnd('.')))
        assertTrue("Set by Kim" in read && "can't change" in read)
        assertTrue("Close to the limit" in read && "80 percent" in read)
    }

    @Test
    fun sectionsAreOnePerScopeInTheOrderGiven() {
        val sameName = Group("g3", "Family", "QQQ222", kim)
        val snap = snapshot(family, sameName)
        val usages = listOf(
            usage(budget("b1"), 0),
            usage(budget("b2", category = "food"), 0),
            usage(budget("b3", groupId = "g1"), 0),
            usage(budget("b4", groupId = "g3"), 0),
            usage(budget("b5", groupId = "g1", category = "food", owner = kim), 0),
        )
        val sections = BudgetFormRules.sections(usages, snap)
        assertEquals(listOf<String?>(null, "g1", "g3"), sections.map { it.groupId })
        assertEquals(listOf("Personal", "Family", "Family"), sections.map { it.title })
        assertEquals(listOf(listOf("b1", "b2"), listOf("b3", "b5"), listOf("b4")), sections.map { s -> s.usages.map { it.budget.id } })
        assertTrue(BudgetFormRules.sections(emptyList(), snap).isEmpty())
    }

    @Test
    fun sectionsFollowTheRealUsageOrder() {
        val snap = snapshot(trip, family) // Repo sorts groups by name; usageList sorts them itself too
        val budgets = listOf(
            budget("g-trip", groupId = "g2"),
            budget("g-fam", groupId = "g1", category = "food"),
            budget("p-food", category = "food"),
            budget("p-all"),
        )
        val sections = BudgetFormRules.sections(BudgetRules.usageList(me, budgets, emptyList(), snap, "2026-11"), snap)
        assertEquals(listOf("Personal", "Barkada", "Family"), sections.map { it.title })
        assertEquals(listOf("p-all", "p-food"), sections.first().usages.map { it.budget.id })
    }
}
