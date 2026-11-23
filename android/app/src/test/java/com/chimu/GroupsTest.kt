package com.chimu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules behind the Group tab (RFC-006): input checks, what a user may see, feed, per-member totals and words. */
class GroupsTest {
    private val me = "user-me"
    private val ana = "user-ana"
    private val ben = "user-ben"
    private val stranger = "user-stranger"

    private fun txn(
        id: String,
        owner: String,
        amount: Long,
        type: TxnType = TxnType.EXPENSE,
        date: String = "2026-11-10",
        category: String? = "food",
        status: String = "confirmed",
        deleted: Boolean = false,
        updatedAt: Long = 1_000,
    ) = Txn(
        id = id, ownerId = owner, type = type, walletId = "w-$owner", toWalletId = if (type == TxnType.TRANSFER) "w2-$owner" else null,
        amount = amount, date = date, category = category, merchant = null, note = null, isBusiness = false,
        status = status, deleted = deleted, updatedAt = updatedAt,
    )

    private fun wallet(id: String, owner: String, name: String) = Wallet(id, owner, name, WalletType.CASH, 0)

    private val family = Group("g1", "Family", "K7M4QX", me)
    private val office = Group("g2", "Office", "ABCDEF", ana)
    private val others = Group("g3", "Strangers", "ZZZ222", stranger)

    private val memberships = listOf(
        Membership("g1", me, "owner"), Membership("g1", ana, "member"),
        Membership("g2", ana, "owner"), Membership("g2", ben, "member"), Membership("g2", me, "member"),
        Membership("g3", stranger, "owner"),
    )

    // --- input ----------------------------------------------------------------------------

    @Test
    fun aGroupName_isTrimmed_andMustBeOneToSixtyCharacters() {
        assertEquals("Family", GroupRules.cleanName("  Family \n"))
        assertNull(GroupRules.cleanName("   "))
        assertNull(GroupRules.cleanName(""))
        assertEquals(60, GroupRules.cleanName("a".repeat(60))!!.length)
        assertNull(GroupRules.cleanName("a".repeat(61)))
    }

    @Test
    fun aGroupName_isCountedInCharacters_likePostgresDoes_notInUtf16Units() {
        val sixtyEmoji = "😀".repeat(60) // 120 UTF-16 units, 60 characters: the server accepts it
        assertEquals(sixtyEmoji, GroupRules.cleanName(sixtyEmoji))
        assertNull(GroupRules.cleanName("😀".repeat(61)))
    }

    @Test
    fun anInviteCode_isUpperCased_andLosesSpacesAndDashes() {
        assertEquals("K7M4QX", GroupRules.cleanCode("k7m4qx"))
        assertEquals("K7M4QX", GroupRules.cleanCode(" K7M 4QX \n"))
        assertEquals("K7M4QX", GroupRules.cleanCode("k7m-4qx"))
    }

    @Test
    fun anInviteCode_thatCantExist_isRefusedBeforeAskingTheServer() {
        assertNull(GroupRules.cleanCode(""))
        assertNull(GroupRules.cleanCode("K7M4Q")) // 5
        assertNull(GroupRules.cleanCode("K7M4QXX")) // 7
        assertNull(GroupRules.cleanCode("K7M4Q0")) // 0
        assertNull(GroupRules.cleanCode("K7M4Q1")) // 1
        assertNull(GroupRules.cleanCode("K7M4QI")) // I
        assertNull(GroupRules.cleanCode("K7M4QO")) // O
        assertNull(GroupRules.cleanCode("K7M4Q!"))
    }

    @Test
    fun everyCharacterTheServerCanMakeIsAccepted() {
        // 32 characters in the alphabet; any 6 of them make a code
        GroupRules.CODE_ALPHABET.chunked(6).filter { it.length == 6 }.forEach { assertEquals(it, GroupRules.cleanCode(it)) }
        assertEquals(32, GroupRules.CODE_ALPHABET.length)
        assertEquals(32, GroupRules.CODE_ALPHABET.toSet().size)
    }

    // --- words ----------------------------------------------------------------------------

    @Test
    fun theJoinScreenNotice_isTheExactSentenceFromTheRfc() {
        assertEquals("Everyone in this group will see everything you log, including income.", GroupText.SHARING_NOTICE)
    }

    @Test
    fun theShareMessage_carriesTheCode_theName_andTheSharingWarning() {
        val text = GroupText.shareMessage(family)
        assertTrue("Family" in text)
        assertTrue("K7M4QX" in text)
        assertTrue("including income" in text)
    }

    @Test
    fun theCodeIsShownInTwoHalves() {
        assertEquals("K7M 4QX", GroupText.codeForDisplay("K7M4QX"))
    }

    @Test
    fun personLabels_sayYouForTheUser_andNeverShowABlank() {
        assertEquals("Matt (you)", GroupText.personLabel("Matt", isMe = true))
        assertEquals("You", GroupText.personLabel("", isMe = true))
        assertEquals("Ana", GroupText.personLabel(" Ana ", isMe = false))
        assertEquals("A group member", GroupText.personLabel(null, isMe = false))
        assertEquals("A group member", GroupText.personLabel("   ", isMe = false))
    }

    @Test
    fun serverMessages_becomeWordsForTheUser_whetherPlainOrInsideJson() {
        assertTrue("isn't right" in GroupText.serverWords("invalid invite code"))
        assertTrue("isn't right" in GroupText.serverWords("""{"code":"22023","details":null,"hint":null,"message":"invalid invite code"}"""))
        assertTrue("leave only after everyone else" in GroupText.serverWords("the owner cannot leave while other members remain"))
        assertTrue("Only the person who started" in GroupText.serverWords("only the group owner can remove members"))
        assertTrue("Leave group" in GroupText.serverWords("the owner cannot remove themself"))
        assertEquals(GroupText.NAME_HELP, GroupText.serverWords("group name must be 1 to 60 characters"))
        assertTrue("Pull down to refresh" in GroupText.serverWords("that user is not in this group"))
        assertTrue("Pull down to refresh" in GroupText.serverWords("you are not in this group"))
        assertTrue("not signed in" in GroupText.serverWords("not signed in").lowercase())
    }

    @Test
    fun anUnknownServerMessage_isNeverShownRaw() {
        assertEquals(GroupText.SERVER_NO, GroupText.serverWords("duplicate key value violates unique constraint \"groups_pkey\""))
        assertEquals(GroupText.SERVER_NO, GroupText.serverWords(null))
        assertEquals(GroupText.SERVER_NO, GroupText.serverWords(""))
    }

    // --- what a user may see --------------------------------------------------------------

    @Test
    fun aUserSeesOnlyTheirOwnGroups_theirMembers_andOnlyThosePeoplesRows() {
        val snap = GroupRules.snapshot(
            me = me,
            groups = listOf(family, office, others),
            memberships = memberships,
            names = mapOf(me to "Matt", ana to "Ana", ben to "Ben", stranger to "Sam"),
            txns = listOf(txn("t1", me, 100), txn("t2", ana, 200), txn("t3", ben, 300), txn("t4", stranger, 400)),
            wallets = listOf(wallet("w-me", me, "Cash"), wallet("w-ana", ana, "Ana's GCash"), wallet("w-sam", stranger, "Sam's")),
        )

        assertEquals(listOf("g1", "g2"), snap.groups.map { it.id })
        assertEquals(setOf(me, ana, ben), snap.txns.map { it.ownerId }.toSet())
        assertFalse("Sam is in nobody's group with me", "t4" in snap.txns.map { it.id })
        assertFalse("Sam's name is not kept", stranger in snap.names)
        assertFalse("Sam's wallet name is not kept", "w-sam" in snap.walletNames)
        assertEquals("Ana's GCash", snap.walletNames["w-ana"])
        assertFalse("the other group's membership rows are dropped", snap.memberships.any { it.groupId == "g3" })
    }

    @Test
    fun anotherAccountsLeftoversOnTheSamePhone_showNothing() {
        // Ana was signed in and shared a group with Ben. Now I sign in, in no group: her cache is still on the phone.
        val snap = GroupRules.snapshot(
            me = me,
            groups = listOf(office),
            memberships = listOf(Membership("g2", ana, "owner"), Membership("g2", ben, "member")),
            names = mapOf(ana to "Ana", ben to "Ben"),
            txns = listOf(txn("t2", ana, 200), txn("t3", ben, 300)),
            wallets = listOf(wallet("w-ana", ana, "Ana's GCash")),
        )

        assertTrue(snap.groups.isEmpty())
        assertTrue(snap.memberships.isEmpty())
        assertTrue(snap.txns.isEmpty())
        assertTrue(snap.names.isEmpty())
        assertTrue(snap.walletNames.isEmpty())
    }

    @Test
    fun theCacheNeverShowsADeletedRow_orAnInboxItem() {
        val snap = GroupRules.snapshot(
            me, listOf(family), memberships,
            names = emptyMap(),
            txns = listOf(
                txn("live", ana, 100), txn("gone", ana, 100, deleted = true), txn("inbox", me, 100, status = "inbox"),
            ),
            wallets = emptyList(),
        )
        assertEquals(listOf("live"), snap.txns.map { it.id })
    }

    @Test
    fun groupsAreListedByName_ignoringCase() {
        val snap = GroupRules.snapshot(
            me, listOf(Group("g2", "office", "ABCDEF", ana), Group("g1", "Family", "K7M4QX", me)), memberships,
            emptyMap(), emptyList(), emptyList(),
        )
        assertEquals(listOf("g1", "g2"), snap.groups.map { it.id })
    }

    @Test
    fun pick_keepsTheChosenGroup_elseTheFirst_elseNothing() {
        assertEquals("g2", GroupRules.pick("g2", listOf(family, office))!!.id)
        assertEquals("g1", GroupRules.pick("gone", listOf(family, office))!!.id)
        assertEquals("g1", GroupRules.pick(null, listOf(family, office))!!.id)
        assertNull(GroupRules.pick("g1", emptyList()))
    }

    // --- members --------------------------------------------------------------------------

    private fun snapshot() = GroupRules.snapshot(
        me, listOf(family, office), memberships,
        names = mapOf(me to "Matt", ana to "Ana", ben to "Ben"), txns = emptyList(), wallets = emptyList(),
    )

    @Test
    fun members_listTheOwnerFirst_thenTheOthersByName() {
        val members = snapshot().membersOf("g2") // owner Ana; Ben and me (Matt) are members
        assertEquals(listOf("Ana", "Ben", "Matt (you)"), members.map { it.label })
        assertTrue(members.first().isOwner)
        assertEquals(listOf(true), members.filter { it.isMe }.map { true })
    }

    @Test
    fun anOwner_canLeaveOnlyWhenAlone_andAMemberAlways() {
        val snap = snapshot()
        assertFalse("I own g1 and Ana is in it", GroupRules.canLeave(snap.membersOf("g1")))
        assertTrue("I am only a member of g2", GroupRules.canLeave(snap.membersOf("g2")))

        val alone = GroupRules.snapshot(me, listOf(family), listOf(Membership("g1", me, "owner")), emptyMap(), emptyList(), emptyList())
        assertTrue(GroupRules.canLeave(alone.membersOf("g1")))
    }

    @Test
    fun onlyTheOwnerCanRemove_andNotThemself() {
        val snap = snapshot()
        val g1 = snap.membersOf("g1")
        val ana1 = g1.first { it.userId == ana }
        val me1 = g1.first { it.isMe }
        assertTrue("I own g1", GroupRules.canRemove(g1, ana1))
        assertFalse("not myself", GroupRules.canRemove(g1, me1))

        val g2 = snap.membersOf("g2")
        assertFalse("I am only a member of g2", GroupRules.canRemove(g2, g2.first { it.userId == ben }))
    }

    // --- feed and totals ------------------------------------------------------------------

    @Test
    fun theFeed_isNewestFirst_andOnlyHoldsTheGroupsPeople() {
        val all = listOf(
            txn("old", ana, 100, date = "2026-11-01"),
            txn("new", me, 100, date = "2026-11-20"),
            txn("mid", ana, 100, date = "2026-11-10"),
            txn("outsider", stranger, 100, date = "2026-11-25"),
        )
        assertEquals(listOf("new", "mid", "old"), GroupLedger.feed(all, setOf(me, ana)).map { it.id })
    }

    @Test
    fun theFeed_hidesDeletedRowsAndInboxItems() {
        val all = listOf(txn("a", ana, 1), txn("b", ana, 1, deleted = true), txn("c", me, 1, status = "inbox"))
        assertEquals(listOf("a"), GroupLedger.feed(all, setOf(me, ana)).map { it.id })
    }

    @Test
    fun theFeed_canBeNarrowedToOneMember_andOneCategory() {
        val all = listOf(
            txn("a", ana, 1, category = "food"), txn("b", ana, 1, category = "transport"),
            txn("c", me, 1, category = "food"), txn("d", me, 1, category = "transport"),
        )
        val ids = setOf(me, ana)
        assertEquals(setOf("a", "b"), GroupLedger.feed(all, ids, GroupFilter(memberId = ana)).map { it.id }.toSet())
        assertEquals(setOf("a", "c"), GroupLedger.feed(all, ids, GroupFilter(category = "food")).map { it.id }.toSet())
        assertEquals(listOf("a"), GroupLedger.feed(all, ids, GroupFilter(memberId = ana, category = "food")).map { it.id })
    }

    @Test
    fun aFilterForSomeoneOutsideTheGroup_showsNothing() {
        val all = listOf(txn("a", ana, 1), txn("s", stranger, 1))
        assertTrue(GroupLedger.feed(all, setOf(me, ana), GroupFilter(memberId = stranger)).isEmpty())
    }

    @Test
    fun memberTotals_matchAManualSum() {
        // Ana, November: spent 123.45 + 10.00 + 0.55 = 134.00; income 5,000.00. October and a transfer don't count.
        // Matt, November: spent 1,000.00. Ben has logged nothing.
        val all = listOf(
            txn("a1", ana, 12_345, date = "2026-11-02"),
            txn("a2", ana, 1_000, date = "2026-11-30"),
            txn("a3", ana, 55, date = "2026-11-15"),
            txn("a4", ana, 500_000, type = TxnType.INCOME, date = "2026-11-01", category = "salary"),
            txn("a5", ana, 99_999, date = "2026-10-31"),
            txn("a6", ana, 70_000, type = TxnType.TRANSFER, date = "2026-11-05", category = null),
            txn("m1", me, 100_000, date = "2026-11-09"),
        )
        val members = snapshot().membersOf("g2") // Ana, Ben, Matt

        val totals = GroupLedger.memberTotals(all, members, "2026-11").associateBy { it.member.userId }

        assertEquals(MonthTotals(spent = 13_400, income = 500_000), totals.getValue(ana).totals)
        assertEquals(MonthTotals(spent = 0, income = 0), totals.getValue(ben).totals)
        assertEquals(MonthTotals(spent = 100_000, income = 0), totals.getValue(me).totals)
        assertEquals(listOf(ana, ben, me), GroupLedger.memberTotals(all, members, "2026-11").map { it.member.userId })
    }

    @Test
    fun memberTotals_ignoreDeletedRowsAndInboxItems() {
        val all = listOf(
            txn("a", ana, 1_000), txn("b", ana, 5_000, deleted = true), txn("c", ana, 7_000, status = "inbox"),
        )
        val members = snapshot().membersOf("g1")
        val ana1 = GroupLedger.memberTotals(all, members, "2026-11").first { it.member.userId == ana }
        assertEquals(1_000, ana1.totals.spent)
    }

    @Test
    fun theSqlForTheCacheHidesDeletedRowsAndInboxItems() {
        assertEquals("deleted = 0 AND status <> 'inbox'", GroupSql.CACHED_TXNS_WHERE)
    }
}
