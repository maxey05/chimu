package com.chimu

import com.chimu.ui.ContributionDraft
import com.chimu.ui.GoalDraft
import com.chimu.ui.GoalFormRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure side of the Goals forms (RFC-011): the goal form's draft, its wallet choices, and the "Add to goal" transfer. */
class GoalsFormTest {
    private val me = "me"
    private val today = "2026-11-25"

    private fun wallet(id: String, name: String, type: WalletType, deleted: Boolean = false) =
        Wallet(id = id, ownerId = me, name = name, type = type, openingBalance = 0, deleted = deleted)

    private val cash = wallet("cash", "Cash", WalletType.CASH)
    private val gcash = wallet("gcash", "GCash", WalletType.GCASH)
    private val bank = wallet("bank", "bank", WalletType.BANK)
    private val trip = wallet("trip", "Trip fund", WalletType.SAVINGS)
    private val rainy = wallet("rainy", "rainy day", WalletType.SAVINGS)
    private val oldSavings = wallet("old", "Old savings", WalletType.SAVINGS, deleted = true)
    private val oldCash = wallet("oldcash", "Old cash", WalletType.CASH, deleted = true)
    private val all = listOf(cash, gcash, bank, trip, rainy, oldSavings, oldCash)

    private fun goal(deadline: String? = "2027-02-25", walletId: String = "trip") =
        Goal(id = "g", ownerId = me, name = "Baguio trip", target = 2000000, deadline = deadline, walletId = walletId)

    private fun draft(
        name: String = "Baguio trip",
        target: String = "20,000",
        deadline: String? = "2027-04-30",
        key: String? = "trip",
        newName: String = "",
        wallets: List<Wallet> = all,
        existing: Goal? = null,
    ) = GoalFormRules.draft(name, target, deadline, key, newName, wallets, today, existing)

    private fun invalid(d: GoalDraft): String = (d as GoalDraft.Invalid).message

    // --- the wallet dropdown ------------------------------------------------------------------------------------------

    @Test
    fun savingsWallets_areLiveSavingsOnesByName() {
        assertEquals(listOf("rainy", "trip"), GoalFormRules.savingsWallets(all).map { it.id }) // "rainy day" < "Trip fund" ignoring case
        assertTrue(GoalFormRules.savingsWallets(listOf(cash, gcash, oldSavings)).isEmpty())
    }

    @Test
    fun walletChoices_endWithNewSavingsWallet_andAreOnlyThatWithNoSavingsWallet() {
        assertEquals(listOf("rainy", "trip", GoalFormRules.NEW_WALLET), GoalFormRules.walletChoices(all).map { it.key })
        assertEquals(listOf(GoalFormRules.NEW_WALLET), GoalFormRules.walletChoices(listOf(cash)).map { it.key })
    }

    @Test
    fun initialWalletKey_prefersTheGoalsOwnLiveWallet_thenTheFirstSavingsWallet_thenNew() {
        assertEquals("trip", GoalFormRules.initialWalletKey(goal(walletId = "trip"), all))
        assertEquals("rainy", GoalFormRules.initialWalletKey(null, all)) // first by name
        assertEquals("rainy", GoalFormRules.initialWalletKey(goal(walletId = "old"), all)) // its wallet was archived
        assertEquals(GoalFormRules.NEW_WALLET, GoalFormRules.initialWalletKey(null, listOf(cash, gcash)))
        assertEquals(GoalFormRules.NEW_WALLET, GoalFormRules.initialWalletKey(goal(walletId = "old"), listOf(cash, oldSavings)))
    }

    // --- the goal draft ----------------------------------------------------------------------------------------------

    @Test
    fun draft_aGoodFormBecomesAGoalOnAnExistingSavingsWallet() {
        val ok = draft() as GoalDraft.Ok
        assertEquals(GoalDraft.Ok("Baguio trip", 2000000, "2027-04-30", "trip", null), ok)
    }

    @Test
    fun draft_trimsTheNameAndAcceptsNoDeadline() {
        val ok = draft(name = "  Baguio trip  ", deadline = null) as GoalDraft.Ok
        assertEquals("Baguio trip", ok.name)
        assertNull(ok.deadline)
    }

    @Test
    fun draft_nameMustBeOneToSixtyCharacters() {
        assertEquals(GoalFormRules.NAME_INVALID, invalid(draft(name = "")))
        assertEquals(GoalFormRules.NAME_INVALID, invalid(draft(name = "   ")))
        assertEquals(GoalFormRules.NAME_INVALID, invalid(draft(name = "x".repeat(61))))
        assertTrue(draft(name = "x".repeat(60)) is GoalDraft.Ok)
    }

    @Test
    fun draft_targetMustBeAValidAmountAboveZero() {
        for (bad in listOf("", "0", "0.00", "-5", "abc", "1,2,3", "5.555", "20000pesos")) {
            assertEquals("target '$bad'", GoalFormRules.TARGET_INVALID, invalid(draft(target = bad)))
        }
        assertEquals(1, (draft(target = "0.01") as GoalDraft.Ok).target)
        assertEquals(2000050, (draft(target = "₱20,000.50") as GoalDraft.Ok).target)
    }

    @Test
    fun draft_deadlineMustBeARealDateTodayOrLater() {
        assertEquals(GoalFormRules.DEADLINE_INVALID, invalid(draft(deadline = "2026-11-24")))
        assertEquals(GoalFormRules.DEADLINE_INVALID, invalid(draft(deadline = "2027-02-30")))
        assertEquals(GoalFormRules.DEADLINE_INVALID, invalid(draft(deadline = "tomorrow")))
        assertTrue(draft(deadline = "2026-11-25") is GoalDraft.Ok) // today counts
    }

    @Test
    fun draft_anEditMayKeepADeadlineThatHasPassed_butNotPickAnotherPastOne() {
        val overdue = goal(deadline = "2026-10-31")
        assertTrue(draft(deadline = "2026-10-31", existing = overdue) is GoalDraft.Ok)
        assertEquals(GoalFormRules.DEADLINE_INVALID, invalid(draft(deadline = "2026-10-30", existing = overdue)))
        assertEquals(GoalFormRules.DEADLINE_INVALID, invalid(draft(deadline = "2026-10-31", existing = goal(deadline = "2027-01-01"))))
    }

    @Test
    fun draft_walletMustBeOneOfMyLiveSavingsWallets() {
        assertEquals(GoalRules.WALLET_HELP, invalid(draft(key = "cash"))) // not a savings wallet
        assertEquals(GoalRules.WALLET_HELP, invalid(draft(key = "old"))) // archived
        assertEquals(GoalRules.WALLET_HELP, invalid(draft(key = "someone-elses")))
        assertEquals(GoalRules.WALLET_HELP, invalid(draft(key = null)))
        assertEquals("rainy", (draft(key = "rainy") as GoalDraft.Ok).walletId)
    }

    @Test
    fun draft_aNewWalletIsNamedLikeTheGoalUnlessTheNameIsTyped() {
        val like = draft(key = GoalFormRules.NEW_WALLET, wallets = listOf(cash)) as GoalDraft.Ok
        assertNull(like.walletId)
        assertEquals("Baguio trip", like.newWalletName)
        val typed = draft(key = GoalFormRules.NEW_WALLET, newName = "  Bakasyon  ", wallets = listOf(cash)) as GoalDraft.Ok
        assertEquals("Bakasyon", typed.newWalletName)
        assertEquals(
            GoalFormRules.WALLET_NAME_INVALID,
            invalid(draft(key = GoalFormRules.NEW_WALLET, newName = "w".repeat(61), wallets = listOf(cash))),
        )
    }

    @Test
    fun draft_checksComeInOrder_nameThenTargetThenDeadlineThenWallet() {
        assertEquals(GoalFormRules.NAME_INVALID, invalid(draft(name = "", target = "", deadline = "x", key = "cash")))
        assertEquals(GoalFormRules.TARGET_INVALID, invalid(draft(target = "", deadline = "x", key = "cash")))
        assertEquals(GoalFormRules.DEADLINE_INVALID, invalid(draft(deadline = "x", key = "cash")))
    }

    // --- Add to goal -------------------------------------------------------------------------------------------------

    @Test
    fun sourceWallets_areEveryLiveWalletExceptTheGoalsOwn() {
        assertEquals(listOf("bank", "cash", "gcash", "rainy"), GoalFormRules.sourceWallets(goal(), all).map { it.id })
        assertTrue(GoalFormRules.sourceWallets(goal(), listOf(trip)).isEmpty())
    }

    @Test
    fun canAdd_onlyWhileTheGoalsWalletIsLive() {
        assertTrue(GoalFormRules.canAdd(goal(), all))
        assertFalse(GoalFormRules.canAdd(goal(walletId = "old"), all))
        assertFalse(GoalFormRules.canAdd(goal(walletId = "missing"), all))
    }

    @Test
    fun contribution_aGoodFormBecomesATransferIntoTheGoalsWallet() {
        val ok = GoalFormRules.contribution(goal(), "cash", "1,500", "2026-11-25", all) as ContributionDraft.Ok
        assertEquals(ContributionDraft.Ok("cash", 150000, "2026-11-25"), ok)
        val txn = GoalFormRules.contributionTxn(goal(), ok, "new-id")
        assertEquals("new-id", txn.id)
        assertEquals(TxnType.TRANSFER, txn.type)
        assertEquals("cash", txn.walletId)
        assertEquals("trip", txn.toWalletId)
        assertEquals(150000, txn.amount)
        assertEquals("2026-11-25", txn.date)
        assertNull(txn.category)
        assertNull(txn.merchant)
        assertFalse(txn.isBusiness)
        assertEquals("manual", txn.source)
        assertEquals("confirmed", txn.status)
        assertFalse(txn.deleted)
        assertEquals("Toward goal: Baguio trip", txn.note)
    }

    @Test
    fun contribution_theSourceCannotBeTheGoalsWalletOrAnArchivedOrUnknownOne() {
        for (from in listOf("trip", "oldcash", "old", "nope", null)) {
            assertEquals("from $from", GoalFormRules.FROM_INVALID, (GoalFormRules.contribution(goal(), from, "10", "2026-11-25", all) as ContributionDraft.Invalid).message)
        }
    }

    @Test
    fun contribution_amountMustBeAboveZero() {
        for (bad in listOf("", "0", "0.00", "-1", "x", "1.234")) {
            assertEquals(
                "amount '$bad'", GoalFormRules.AMOUNT_INVALID,
                (GoalFormRules.contribution(goal(), "cash", bad, "2026-11-25", all) as ContributionDraft.Invalid).message,
            )
        }
    }

    @Test
    fun contribution_dateMustBeReal() {
        for (bad in listOf(null, "", "2026-02-30", "yesterday")) {
            assertEquals(
                "date $bad", GoalFormRules.DATE_INVALID,
                (GoalFormRules.contribution(goal(), "cash", "10", bad, all) as ContributionDraft.Invalid).message,
            )
        }
    }

    @Test
    fun contribution_refusedWhenTheGoalsWalletWasArchived() {
        val msg = (GoalFormRules.contribution(goal(walletId = "old"), "cash", "10", "2026-11-25", all) as ContributionDraft.Invalid).message
        assertEquals(GoalFormRules.GOAL_WALLET_GONE, msg)
    }
}
