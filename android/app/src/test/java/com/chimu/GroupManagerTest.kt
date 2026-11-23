package com.chimu

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Create, join, leave and remove (RFC-006): input checks, error words, and the sync after a success. */
class GroupManagerTest {
    private val family = Group("g1", "Family", "K7M4QX", "user-me")

    private class FakeApi : GroupApi {
        val calls = mutableListOf<String>()
        var failure: Exception? = null
        var result = Group("g1", "Family", "K7M4QX", "user-me")

        private fun go(call: String) {
            calls += call
            failure?.let { throw it }
        }

        override suspend fun createGroup(name: String): Group { go("create:$name"); return result }

        override suspend fun joinGroup(code: String): Group { go("join:$code"); return result }

        override suspend fun leaveGroup(groupId: String) = go("leave:$groupId")

        override suspend fun removeMember(groupId: String, userId: String) = go("remove:$groupId:$userId")
    }

    private class Harness(var syncResult: Boolean = true) {
        val api = FakeApi()
        var syncs = 0
        val manager = GroupManager(api, afterChange = { syncs++; syncResult })
    }

    @Test
    fun creating_sendsTheTrimmedName_selectsTheGroup_andSyncsOnce() {
        val h = Harness()

        val result = runBlocking { h.manager.create("  Family  ") }

        assertEquals(GroupResult.Done(family, synced = true), result)
        assertEquals(listOf("create:Family"), h.api.calls)
        assertEquals("g1", h.manager.selected.value)
        assertEquals(1, h.syncs)
    }

    @Test
    fun aBadName_isRefusedWithoutCallingTheServerOrSyncing() {
        val h = Harness()

        assertEquals(GroupResult.Failed(GroupText.NAME_HELP), runBlocking { h.manager.create("   ") })
        assertEquals(GroupResult.Failed(GroupText.NAME_HELP), runBlocking { h.manager.create("x".repeat(61)) })

        assertTrue(h.api.calls.isEmpty())
        assertEquals(0, h.syncs)
    }

    @Test
    fun joining_sendsTheCleanedCode_andSelectsTheGroup() {
        val h = Harness()

        val result = runBlocking { h.manager.join(" k7m 4qx ") }

        assertTrue(result is GroupResult.Done)
        assertEquals(listOf("join:K7M4QX"), h.api.calls)
        assertEquals("g1", h.manager.selected.value)
        assertEquals(1, h.syncs)
    }

    @Test
    fun aBadCode_isRefusedWithoutCallingTheServer() {
        val h = Harness()

        assertEquals(GroupResult.Failed(GroupText.CODE_HELP), runBlocking { h.manager.join("HELLO0") })

        assertTrue(h.api.calls.isEmpty())
        assertEquals(0, h.syncs)
    }

    @Test
    fun whenTheServerCantBeReached_nothingChanges_andNothingSyncs() {
        val h = Harness()
        h.api.failure = SyncUnavailable("offline")
        h.manager.select("g9")

        val result = runBlocking { h.manager.create("Family") }

        assertEquals(GroupResult.Failed(GroupText.UNREACHABLE), result)
        assertEquals("the user's pick is untouched", "g9", h.manager.selected.value)
        assertEquals(0, h.syncs)
    }

    @Test
    fun whenTheServerSaysNo_itsWordsReachTheUser() {
        val h = Harness()
        h.api.failure = SyncRejected(400, "That invite code isn't right. Check it with the person who gave it to you.")

        val result = runBlocking { h.manager.join("K7M4QX") }

        assertEquals(GroupResult.Failed("That invite code isn't right. Check it with the person who gave it to you."), result)
        assertEquals(0, h.syncs)
        assertNull(h.manager.selected.value)
    }

    @Test
    fun aRefusalWithNoWords_getsAPlainSentence() {
        val h = Harness()
        h.api.failure = SyncRejected(400, "")

        assertEquals(GroupResult.Failed(GroupText.SERVER_NO), runBlocking { h.manager.leave("g1") })
    }

    @Test
    fun leaving_forgetsTheSelection_onlyIfItWasThatGroup_andSyncs() {
        val h = Harness()
        h.manager.select("g1")
        assertEquals(GroupResult.Done(null, true), runBlocking { h.manager.leave("g1") })
        assertNull(h.manager.selected.value)
        assertEquals(listOf("leave:g1"), h.api.calls)
        assertEquals(1, h.syncs)

        h.manager.select("g2")
        runBlocking { h.manager.leave("g1") }
        assertEquals("another group stays selected", "g2", h.manager.selected.value)
    }

    @Test
    fun removing_callsTheServerWithBothIds_andSyncs() {
        val h = Harness()

        val result = runBlocking { h.manager.remove("g1", "user-ana") }

        assertEquals(GroupResult.Done(null, true), result)
        assertEquals(listOf("remove:g1:user-ana"), h.api.calls)
        assertEquals(1, h.syncs)
    }

    @Test
    fun ifTheFollowUpSyncDoesntFinish_theChangeStillCountsAsDone_withAHonestFlag() {
        val h = Harness(syncResult = false)

        val result = runBlocking { h.manager.create("Family") }

        assertEquals(GroupResult.Done(family, synced = false), result)
    }

    @Test
    fun ifTheFollowUpSyncCrashes_theChangeStillCountsAsDone() {
        val api = FakeApi()
        val manager = GroupManager(api, afterChange = { throw IllegalStateException("boom") })

        val result = runBlocking { manager.join("K7M4QX") }

        assertEquals(GroupResult.Done(family, synced = false), result)
    }

    @Test
    fun anUnexpectedFailure_syncsAnyway_andAdmitsItDidntGoAsPlanned() {
        val h = Harness()
        h.api.failure = IllegalArgumentException("unreadable answer")

        val result = runBlocking { h.manager.create("Family") }

        assertTrue(result is GroupResult.Failed)
        assertTrue("unreadable answer" in (result as GroupResult.Failed).words)
        assertEquals("the server may have made the group, so the lists are refreshed", 1, h.syncs)
    }

    @Test
    fun cancelling_isNeverTurnedIntoAnErrorMessage() {
        val h = Harness()
        h.api.failure = CancellationException("screen closed")

        try {
            runBlocking { h.manager.create("Family") }
            fail("expected the cancellation to propagate")
        } catch (e: CancellationException) {
            // expected
        }
        assertEquals(0, h.syncs)
    }

    @Test
    fun theUsersPick_canBeChangedAndCleared() {
        val h = Harness()
        h.manager.select("g2")
        assertEquals("g2", h.manager.selected.value)
        h.manager.select(null)
        assertFalse(h.manager.selected.value != null)
    }
}
