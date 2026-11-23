package com.chimu

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RFC-006 at the sync level: groups and display names are cached with the memberships, a change of
 * membership (create, join, leave, remove) starts the full re-pull, and other people's rows are never sent back.
 * Run against the in-memory phone and server of [FakeLocal] and [FakeRemote].
 */
class GroupSyncTest {
    private val me = "user-me"
    private val ana = "user-ana"
    private val wallets = SyncTables.wallets
    private val txns = SyncTables.transactions

    private fun wallet(id: String, owner: String, name: String = "Cash", updatedAt: Long = 1_000): Row = mapOf(
        "id" to id, "owner_id" to owner, "updated_at" to updatedAt, "deleted" to 0L, "rev" to 0L,
        "name" to name, "type" to "cash", "opening_balance" to 0L,
    )

    private fun txn(id: String, owner: String, wallet: String, amount: Long = 10_000L, type: String = "expense", date: String = "2026-11-10"): Row = mapOf(
        "id" to id, "owner_id" to owner, "updated_at" to 1_000L, "deleted" to 0L, "rev" to 0L,
        "type" to type, "wallet_id" to wallet, "to_wallet_id" to null, "amount" to amount, "date" to date,
        "category" to "food", "merchant" to null, "note" to null, "is_business" to 0L, "source" to "manual",
        "ref_no" to null, "recurring_id" to null, "receipt_path" to null, "seller_tin" to null,
        "invoice_no" to null, "vatable_sales" to null, "vat" to null, "vat_exempt" to null,
    )

    private fun member(group: String, user: String, role: String = "member"): Row =
        mapOf("group_id" to group, "user_id" to user, "role" to role, "joined_at" to "2026-11-01T00:00:00+00:00")

    private fun group(id: String = "g1", owner: String = me): Row =
        mapOf("id" to id, "name" to "Family", "invite_code" to "K7M4QX", "owner_id" to owner, "created_at" to "2026-11-01T00:00:00+00:00")

    private fun profile(id: String, name: String): Row = mapOf("id" to id, "display_name" to name)

    private fun sync(local: FakeLocal, remote: FakeRemote): SyncReport = runBlocking { SyncEngine(local, remote).sync(me) }

    private fun syncFails(local: FakeLocal, remote: FakeRemote) {
        try {
            runBlocking { SyncEngine(local, remote).sync(me) }
            throw AssertionError("expected the sync to fail")
        } catch (e: SyncUnavailable) {
            // expected
        }
    }

    /** The server after I created group g1 and Ana joined it, each with a wallet and an expense. */
    private fun serverWithBoth(): FakeRemote = FakeRemote(me).apply {
        memberRows = listOf(member("g1", me, "owner"), member("g1", ana))
        groupRows = listOf(group())
        profileRows = listOf(profile(me, "Matt"), profile(ana, "Ana"))
        visibleOwners += ana
        serverWrite(wallets, wallet("w-me", me))
        serverWrite(wallets, wallet("w-ana", ana, name = "Ana's cash"))
        serverWrite(txns, txn("t-me", me, "w-me", 12_300L))
        serverWrite(txns, txn("t-ana", ana, "w-ana", 45_600L))
    }

    @Test
    fun theGroup_itsMembers_andTheirNames_areCachedWithTheirRows() {
        val local = FakeLocal()
        sync(local, serverWithBoth())

        assertEquals(listOf("g1"), local.groups.map { it["id"] })
        assertEquals("K7M4QX", local.groups.single()["invite_code"])
        assertEquals(mapOf(me to "Matt", ana to "Ana"), local.profiles.associate { it["id"] as String to it["display_name"] })
        assertEquals(setOf("g1:$me", "g1:$ana"), local.cachedMembers())
        assertEquals(setOf("t-me", "t-ana"), local.ids(txns))
    }

    @Test
    fun joiningAGroup_bringsInTheOtherMembersRows_andTheyAreNeverSentBack() {
        val remote = FakeRemote(me)
        val local = FakeLocal()
        remote.serverWrite(wallets, wallet("w-ana", ana))
        remote.serverWrite(txns, txn("t-ana", ana, "w-ana"))
        repeat(3) { remote.serverWrite(wallets, wallet("pad$it", me)) } // moves the cursor past Ana's rows
        sync(local, remote)
        assertTrue("nothing of Ana's before the join", local.ids(txns).isEmpty())

        // the join RPC ran on the server
        remote.memberRows = listOf(member("g1", ana, "owner"), member("g1", me))
        remote.groupRows = listOf(group(owner = ana))
        remote.profileRows = listOf(profile(me, "Matt"), profile(ana, "Ana"))
        remote.visibleOwners += ana
        remote.upsertCalls.clear()
        sync(local, remote)

        assertEquals(setOf("t-ana"), local.ids(txns))
        assertEquals("Ana", local.profiles.first { it["id"] == ana }["display_name"])
        assertFalse("Ana's rows are read-only here: nothing of hers is pushed", remote.upsertCalls.any { it.first == "transactions" })
        assertFalse(local.isDirty(txns, "t-ana"))
    }

    @Test
    fun leavingAGroup_dropsTheOtherMembersRows_theirNames_andTheGroup_butKeepsMine() {
        val remote = serverWithBoth()
        val local = FakeLocal()
        sync(local, remote)
        assertEquals(setOf("t-me", "t-ana"), local.ids(txns))

        // leave_group ran: no memberships, no groups, no names but my own, no more visible owners
        remote.memberRows = emptyList()
        remote.groupRows = emptyList()
        remote.profileRows = listOf(profile(me, "Matt"))
        remote.visibleOwners.clear()
        sync(local, remote)

        assertEquals(setOf("t-me"), local.ids(txns))
        assertEquals(setOf("w-me"), local.ids(wallets))
        assertTrue(local.groups.isEmpty())
        assertTrue(local.cachedMembers().isEmpty())
        assertEquals(listOf(me), local.profiles.map { it["id"] })
    }

    @Test
    fun beingRemovedByTheOwner_looksTheSameAsLeaving() {
        val remote = serverWithBoth()
        val local = FakeLocal()
        sync(local, remote)

        // The owner ran remove_member on me; I only find out on my next sync.
        remote.memberRows = emptyList()
        remote.groupRows = emptyList()
        remote.profileRows = listOf(profile(me, "Matt"))
        remote.visibleOwners.clear()
        sync(local, remote)

        assertFalse("Ana's expense is no longer on this phone", "t-ana" in local.ids(txns))
        assertTrue(local.groups.isEmpty())
    }

    @Test
    fun aNewMemberOfMyGroup_causesAFullRepull_soTheirOlderRowsArrive() {
        val remote = FakeRemote(me)
        val local = FakeLocal()
        remote.memberRows = listOf(member("g1", me, "owner"))
        remote.groupRows = listOf(group())
        remote.profileRows = listOf(profile(me, "Matt"))
        remote.serverWrite(wallets, wallet("w-ana", ana))
        remote.serverWrite(txns, txn("t-ana", ana, "w-ana")) // written long before Ana joined
        repeat(3) { remote.serverWrite(wallets, wallet("pad$it", me)) }
        sync(local, remote)
        assertTrue(local.ids(txns).isEmpty())

        remote.memberRows = listOf(member("g1", me, "owner"), member("g1", ana)) // Ana joins my group
        remote.profileRows = listOf(profile(me, "Matt"), profile(ana, "Ana"))
        remote.visibleOwners += ana
        sync(local, remote)

        assertEquals(setOf("t-ana"), local.ids(txns))
    }

    @Test
    fun aRenamedMember_showsUpAtTheNextSync_withoutAMembershipChange() {
        val remote = serverWithBoth()
        val local = FakeLocal()
        sync(local, remote)

        remote.profileRows = listOf(profile(me, "Matt"), profile(ana, "Ana Marie"))
        sync(local, remote)

        assertEquals("Ana Marie", local.profiles.first { it["id"] == ana }["display_name"])
    }

    @Test
    fun ifTheNamesCantBeFetched_theWholeCacheStaysAsItWas() {
        val remote = serverWithBoth()
        val local = FakeLocal()
        sync(local, remote)

        remote.memberRows = emptyList() // I left...
        remote.groupRows = emptyList()
        remote.visibleOwners.clear() // (and the server no longer shows me Ana's rows)
        remote.callsUntilOffline = 1 // ...but the connection drops after members(), before groups()
        syncFails(local, remote)

        assertEquals("the old group is still shown, not a half-updated mix", listOf("g1"), local.groups.map { it["id"] })
        assertEquals(setOf("g1:$me", "g1:$ana"), local.cachedMembers())
        assertEquals(setOf("t-me", "t-ana"), local.ids(txns))

        remote.online = true
        remote.callsUntilOffline = null
        sync(local, remote)
        assertEquals(setOf("t-me"), local.ids(txns))
        assertTrue(local.groups.isEmpty())
    }
}
