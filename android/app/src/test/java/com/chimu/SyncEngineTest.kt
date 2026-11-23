package com.chimu

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The sync scenarios from RFC-003's acceptance list, run against an in-memory phone and server
 * ([FakeLocal], [FakeRemote]) that follow the real rules.
 */
class SyncEngineTest {
    private val me = "user-me"
    private val other = "user-other"

    private val wallets = SyncTables.wallets
    private val txns = SyncTables.transactions

    private fun wallet(id: String, owner: String = me, name: String = "Cash", updatedAt: Long = 1_000, deleted: Boolean = false): Row = mapOf(
        "id" to id, "owner_id" to owner, "updated_at" to updatedAt, "deleted" to if (deleted) 1L else 0L, "rev" to 0L,
        "name" to name, "type" to "cash", "opening_balance" to 0L,
    )

    private fun txn(
        id: String,
        owner: String = me,
        wallet: String = "w1",
        note: String? = "lunch",
        updatedAt: Long = 1_000,
        deleted: Boolean = false,
        receiptPath: String? = null,
    ): Row = mapOf(
        "id" to id, "owner_id" to owner, "updated_at" to updatedAt, "deleted" to if (deleted) 1L else 0L, "rev" to 0L,
        "type" to "expense", "wallet_id" to wallet, "to_wallet_id" to null, "amount" to 12_345L, "date" to "2026-11-10",
        "category" to "food", "merchant" to null, "note" to note, "is_business" to 0L, "source" to "manual",
        "ref_no" to null, "recurring_id" to null, "receipt_path" to receiptPath, "seller_tin" to null,
        "invoice_no" to null, "vatable_sales" to null, "vat" to null, "vat_exempt" to null,
    )

    private fun member(group: String, user: String): Row =
        mapOf("group_id" to group, "user_id" to user, "role" to "member", "joined_at" to "2026-11-01T00:00:00+00:00")

    private fun sync(local: FakeLocal, remote: FakeRemote, owner: String = me): SyncReport =
        runBlocking { SyncEngine(local, remote).sync(owner) }

    private fun syncFails(local: FakeLocal, remote: FakeRemote) {
        try {
            sync(local, remote)
            fail("expected the sync to stop with SyncUnavailable")
        } catch (e: SyncUnavailable) {
            // expected
        }
    }

    // --- acceptance: "Edit offline -> reconnect -> row appears in Supabase" -------

    @Test
    fun offlineEdits_reachTheServerOnceBackOnline() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        local.put(wallets, wallet("w1"))
        local.put(txns, txn("t1"))

        remote.online = false
        syncFails(local, remote)
        assertTrue("still waiting to be sent", local.isDirty(wallets, "w1") && local.isDirty(txns, "t1"))
        assertTrue(remote.ids(wallets).isEmpty())

        remote.online = true
        val report = sync(local, remote)

        assertEquals(setOf("w1"), remote.ids(wallets))
        assertEquals("lunch", remote.server(txns, "t1")!!["note"])
        assertFalse(local.isDirty(wallets, "w1"))
        assertFalse(local.isDirty(txns, "t1"))
        assertEquals("the phone now knows the server's rev", 2L, (local.get(txns, "t1")!!["rev"] as Long))
        assertEquals(2, report.pushed)
        assertTrue(report.clean)
    }

    @Test
    fun tombstones_areSentToo() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        local.put(wallets, wallet("w1"))
        local.put(txns, txn("t1", deleted = true))

        sync(local, remote)

        assertEquals(1L, remote.server(txns, "t1")!!["deleted"])
    }

    // --- acceptance: "Edit in Supabase (newer updated_at) -> phone shows it" ---------

    @Test
    fun aNewerServerEdit_reachesThePhone() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        local.put(wallets, wallet("w1"))
        local.put(txns, txn("t1", updatedAt = 1_000))
        sync(local, remote)

        remote.serverWrite(txns, txn("t1", note = "edited in the table editor", updatedAt = 9_000))
        val report = sync(local, remote)

        assertEquals("edited in the table editor", local.get(txns, "t1")!!["note"])
        assertEquals(9_000L, local.get(txns, "t1")!!["updated_at"])
        assertFalse(local.isDirty(txns, "t1"))
        assertEquals(1, report.pulled)
    }

    @Test
    fun aRowCreatedOnTheServer_appearsOnThePhone() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        remote.serverWrite(wallets, wallet("w9", name = "From the dashboard"))

        sync(local, remote)

        assertEquals("From the dashboard", local.get(wallets, "w9")!!["name"])
        assertFalse(local.isDirty(wallets, "w9"))
    }

    // --- acceptance: "An older edit doesn't overwrite a newer one on either side" ---

    @Test
    fun anOlderPhoneEdit_doesNotOverwriteTheNewerServerRow() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        local.put(wallets, wallet("w1"))
        local.put(txns, txn("t1", updatedAt = 1_000))
        sync(local, remote)

        // Someone edits on the web at t=9000. The phone, with a slow clock, edits at t=5000 without having seen it.
        remote.serverWrite(txns, txn("t1", note = "web edit", updatedAt = 9_000))
        local.edit(txns, "t1", updatedAt = 5_000, "note" to "phone edit")
        sync(local, remote)

        assertEquals("the server keeps the newer row", "web edit", remote.server(txns, "t1")!!["note"])
        assertEquals("the phone takes it back instead of keeping a copy the server threw away", "web edit", local.get(txns, "t1")!!["note"])
        assertFalse(local.isDirty(txns, "t1"))
    }

    @Test
    fun anOlderServerRow_doesNotOverwriteANewerUnsentPhoneEdit() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        local.put(wallets, wallet("w1"))
        local.put(txns, txn("t1", updatedAt = 1_000))
        sync(local, remote)

        // The phone edits at t=8000, but the server refuses this row (so it stays unsent) while an older
        // server version (t=3000, newer rev than the cursor) arrives in the pull.
        local.edit(txns, "t1", updatedAt = 8_000, "note" to "phone edit")
        remote.serverWrite(txns, txn("t1", note = "older web edit", updatedAt = 3_000))
        remote.rejectIds += "t1"
        val report = sync(local, remote)

        assertEquals("phone edit", local.get(txns, "t1")!!["note"])
        assertTrue("and it is still waiting to be sent", local.isDirty(txns, "t1"))
        assertEquals(1, report.rejected)
    }

    // --- acceptance: "Clear app data -> sign in -> everything restored" -----------

    @Test
    fun aFreshInstall_getsEverythingBack() {
        val remote = FakeRemote(me)
        val before = FakeLocal()
        before.put(wallets, wallet("w1"))
        before.put(wallets, wallet("w2", name = "GCash", deleted = true))
        before.put(txns, txn("t1"))
        before.put(txns, txn("t2", note = null, deleted = true))
        sync(before, remote)

        val after = FakeLocal() // app data cleared
        val report = sync(after, remote)

        assertEquals(setOf("w1", "w2"), after.ids(wallets))
        assertEquals(setOf("t1", "t2"), after.ids(txns))
        assertEquals("tombstones come back as tombstones", 1L, after.get(wallets, "w2")!!["deleted"])
        assertNull(after.get(txns, "t2")!!["note"])
        assertEquals(before.get(txns, "t1")!!.filterKeys { it !in PHONE_ONLY }, after.get(txns, "t1")!!.filterKeys { it !in PHONE_ONLY })
        assertTrue(SyncTables.inOrder.all { t -> after.table(t).values.none { it["dirty"] == true } })
        assertEquals(4, report.pulled)
        assertEquals(0, report.pushed)
    }

    // --- acceptance: "Second sync with no changes sends nothing" --------------------

    @Test
    fun aSecondSyncWithNoChanges_sendsNothing() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        local.put(wallets, wallet("w1"))
        local.put(txns, txn("t1"))
        sync(local, remote)

        remote.upsertCalls.clear()
        val report = sync(local, remote)

        assertTrue("no upsert at all: ${remote.upsertCalls}", remote.upsertCalls.isEmpty())
        assertEquals(SyncReport(), report)
    }

    @Test
    fun pushedRows_areNotDownloadedAgainOnTheNextSyncs() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        local.put(wallets, wallet("w1"))
        sync(local, remote)
        assertEquals("the first pull echoes the phone's own row once and moves the cursor past it", 1L, local.cursor(wallets))

        assertEquals(0, sync(local, remote).pulled)
        assertEquals(0, sync(local, remote).pulled)
    }

    // --- acceptance: "Airplane mode mid-sync -> no data loss, retried later" -------

    @Test
    fun airplaneModeBetweenTables_losesNothingAndFinishesLater() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        local.put(wallets, wallet("w1"))
        local.put(txns, txn("t1"))
        remote.callsUntilOffline = 1 // the wallets upsert goes through, then the connection drops

        syncFails(local, remote)

        assertFalse("sent and acknowledged", local.isDirty(wallets, "w1"))
        assertTrue("not sent, still waiting", local.isDirty(txns, "t1"))
        assertEquals("lunch", local.get(txns, "t1")!!["note"])
        assertTrue(remote.ids(txns).isEmpty())

        remote.online = true
        remote.callsUntilOffline = null
        sync(local, remote)

        assertEquals(setOf("t1"), remote.ids(txns))
        assertFalse(local.isDirty(txns, "t1"))
    }

    @Test
    fun airplaneModeMidPull_resumesFromTheLastSavedCursor() {
        val remote = FakeRemote(me)
        remote.serverWrite(wallets, wallet("w1"))
        remote.serverWrite(txns, txn("t1"))
        val local = FakeLocal()
        // members, groups, profiles (3) + wallets pull (1) succeed, then the transactions pull fails
        remote.callsUntilOffline = 4

        syncFails(local, remote)

        assertEquals(setOf("w1"), local.ids(wallets))
        assertTrue(local.ids(txns).isEmpty())
        assertEquals(1L, local.cursor(wallets))
        assertEquals(0L, local.cursor(txns))

        remote.online = true
        remote.callsUntilOffline = null
        sync(local, remote)
        assertEquals(setOf("t1"), local.ids(txns))
    }

    @Test
    fun anEditMadeWhileAPushIsInFlight_staysWaitingForTheNextSync() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        local.put(wallets, wallet("w1", updatedAt = 1_000))
        remote.afterStore = {
            // the user saves again after the phone read the row but before the server's answer arrives
            local.edit(wallets, "w1", updatedAt = 2_000, "name" to "Renamed")
            remote.afterStore = null
        }

        sync(local, remote)

        assertEquals("Renamed", local.get(wallets, "w1")!!["name"])
        assertTrue("the newer edit has not been sent, so it must still be dirty", local.isDirty(wallets, "w1"))

        sync(local, remote)
        assertEquals("Renamed", remote.server(wallets, "w1")!!["name"])
        assertFalse(local.isDirty(wallets, "w1"))
    }

    // --- push rules ------------------------------------------------------------------

    @Test
    fun inboxItems_neverLeaveThePhone() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        local.put(wallets, wallet("w1"))
        local.put(txns, txn("t1"))
        local.put(txns, txn("inbox1"), status = "inbox")

        sync(local, remote)

        assertEquals(setOf("t1"), remote.ids(txns))
        assertTrue("it stays on the phone, untouched", local.isDirty(txns, "inbox1"))
        assertEquals("inbox", local.get(txns, "inbox1")!!["status"])
    }

    @Test
    fun anotherAccountsUnsentRows_areNotPushedUnderThisSession() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        local.put(wallets, wallet("w1"))
        local.put(wallets, wallet("theirs", owner = other))

        sync(local, remote)

        assertEquals(setOf("w1"), remote.ids(wallets))
        assertTrue(local.isDirty(wallets, "theirs"))
    }

    @Test
    fun oneRefusedRow_doesNotBlockTheRest() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        local.put(wallets, wallet("w1"))
        listOf("t1", "bad", "t3").forEach { local.put(txns, txn(it)) }
        remote.rejectIds += "bad"

        val report = sync(local, remote)

        assertEquals(setOf("t1", "t3"), remote.ids(txns))
        assertTrue("the refused row is kept for another try", local.isDirty(txns, "bad"))
        assertEquals("lunch", local.get(txns, "bad")!!["note"])
        assertFalse(local.isDirty(txns, "t1"))
        assertFalse(local.isDirty(txns, "t3"))
        assertEquals(1, report.rejected)
        assertFalse(report.clean)
    }

    @Test
    fun aRefusedRowIsSentAgainOnTheNextSync() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        local.put(wallets, wallet("w1"))
        local.put(txns, txn("bad"))
        remote.rejectIds += "bad"
        sync(local, remote)

        remote.rejectIds.clear()
        val report = sync(local, remote)

        assertEquals(setOf("bad"), remote.ids(txns))
        assertTrue(report.clean)
    }

    @Test
    fun manyDirtyRows_goInBatches() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        local.put(wallets, wallet("w1"))
        repeat(250) { local.put(txns, txn("t$it")) }

        sync(local, remote)

        val sizes = remote.upsertCalls.filter { it.first == "transactions" }.map { it.second }
        assertEquals(listOf(100, 100, 50), sizes)
        assertEquals(250, remote.ids(txns).size)
    }

    // --- pull rules ------------------------------------------------------------------

    @Test
    fun aLongPull_isReadInPagesAndTheCursorEndsAtTheNewestRev() {
        val remote = FakeRemote(me)
        repeat(1_203) { remote.serverWrite(wallets, wallet("w$it", updatedAt = 1_000L + it)) }
        val local = FakeLocal()

        val report = sync(local, remote)

        assertEquals(1_203, local.ids(wallets).size)
        assertEquals(1_203L, local.cursor(wallets))
        assertEquals(1_203, report.pulled)
    }

    @Test
    fun aServerThatDoesNotMoveTheCursorForward_isReportedNotLoopedOn() {
        val stuck = object : SyncRemote by FakeRemote(me) {
            override suspend fun pull(table: SyncTable, afterRev: Long, limit: Int): List<Row> =
                listOf(wallet("w1") + ("rev" to afterRev))
        }
        try {
            runBlocking { SyncEngine(FakeLocal(), stuck).sync(me) }
            fail("expected the engine to refuse a pull that goes nowhere")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("not newer"))
        }
    }

    // --- groups and other people's rows ---------------------------------------------------

    @Test
    fun otherMembersRows_areCachedWhileTheyShareAGroup_andForgottenWhenTheyLeave() {
        val remote = FakeRemote(me)
        val local = FakeLocal()
        remote.memberRows = listOf(member("g1", me), member("g1", other))
        remote.visibleOwners += other
        remote.serverWrite(wallets, wallet("theirs", owner = other, name = "Their wallet"))
        local.put(wallets, wallet("mine"))
        sync(local, remote)
        assertEquals(setOf("mine", "theirs"), local.ids(wallets))

        remote.memberRows = listOf(member("g1", me)) // they left
        remote.visibleOwners.clear()
        sync(local, remote)

        assertEquals("their rows are gone from the cache", setOf("mine"), local.ids(wallets))
        assertEquals("and mine are still here", "Cash", local.get(wallets, "mine")!!["name"])
    }

    @Test
    fun aMemberWhoJoinsLater_hasTheirOlderRowsPulled() {
        val remote = FakeRemote(me)
        val local = FakeLocal()
        remote.memberRows = listOf(member("g1", me))
        remote.serverWrite(wallets, wallet("theirs", owner = other, name = "Written long ago"))
        repeat(5) { remote.serverWrite(wallets, wallet("mine$it")) } // pushes the cursor past the other person's row
        sync(local, remote)
        assertFalse("not visible yet: they are in no common group", "theirs" in local.ids(wallets))

        remote.memberRows = listOf(member("g1", me), member("g1", other)) // they join
        remote.visibleOwners += other
        sync(local, remote)

        assertEquals("their old row has a rev below the cursor, so only a fresh pull finds it", "Written long ago", local.get(wallets, "theirs")!!["name"])
    }

    @Test
    fun forgettingOtherPeoplesRows_keepsAnotherAccountsUnsentEdits() {
        val remote = FakeRemote(me)
        val local = FakeLocal()
        local.cacheOwnerValue = me
        local.put(wallets, wallet("theirs-clean", owner = other), dirty = false)
        local.put(wallets, wallet("theirs-unsent", owner = other), dirty = true)
        remote.memberRows = listOf(member("g1", me)) // the cache has no members, so this counts as a change

        sync(local, remote)

        assertEquals(setOf("theirs-unsent"), local.ids(wallets))
        assertTrue(local.isDirty(wallets, "theirs-unsent"))
    }

    @Test
    fun whenADifferentUserSignsIn_thePullStartsOverFromZero() {
        val remote = FakeRemote(other)
        remote.serverWrite(wallets, wallet("o1", owner = other))
        val local = FakeLocal()
        local.cacheOwnerValue = me
        local.cursors[wallets.name] = 500 // the first account's cursor, far ahead of anything `other` has pulled
        local.put(wallets, wallet("mine-unsent"), dirty = true)

        sync(local, remote, owner = other)

        assertEquals(other, local.cacheOwnerValue)
        assertEquals("the pull started from zero, so nothing was skipped", "o1", local.get(wallets, "o1")!!["id"])
        assertTrue("the first account's unsent row survives, untouched", local.isDirty(wallets, "mine-unsent"))
        assertFalse("and was not sent as the second account", "mine-unsent" in remote.ids(wallets))
    }

    @Test
    fun theSameUserSigningInAgain_keepsTheirCursors() {
        val remote = FakeRemote(me)
        remote.serverWrite(wallets, wallet("w1"))
        val local = FakeLocal()
        sync(local, remote)
        remote.pullCalls = 0
        local.cursors[wallets.name] = 1

        val report = sync(local, remote)

        assertEquals("nothing is downloaded twice", 0, report.pulled)
    }

    @Test
    fun aGroupMemberWhoSignsInOnTheSamePhone_getsRowsThePreviousUserHadCached() {
        // Both people are in the same group, so the cached membership is identical: only the cursors differ.
        val remote = FakeRemote(other)
        remote.memberRows = listOf(member("g1", me), member("g1", other))
        remote.visibleOwners += me
        remote.serverWrite(wallets, wallet("mine", owner = me))
        remote.serverWrite(wallets, wallet("theirs", owner = other))
        val local = FakeLocal()
        local.cacheOwnerValue = me
        local.members = remote.memberRows
        local.cursors[wallets.name] = 2
        local.put(wallets, wallet("mine", owner = me), dirty = false)

        sync(local, remote, owner = other)

        assertEquals("the second user's own row was not skipped by the first user's cursor", "theirs", local.get(wallets, "theirs")!!["id"])
    }

    @Test
    fun aWriteTheServerDiscards_isReplacedByTheServersRowEvenWhenThePullWouldNotReturnIt() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        remote.serverWrite(wallets, wallet("w1", name = "Newer on the server", updatedAt = 9_000))
        sync(local, remote) // the phone now has it, and its cursor is past it
        assertEquals("Newer on the server", local.get(wallets, "w1")!!["name"])

        // The phone's clock is behind: its edit carries an older updated_at than the server row it already has.
        local.edit(wallets, "w1", updatedAt = 5_000, "name" to "Phone edit")
        sync(local, remote)

        assertEquals("the server discarded it", "Newer on the server", remote.server(wallets, "w1")!!["name"])
        assertEquals("the phone must not keep a copy the server threw away", "Newer on the server", local.get(wallets, "w1")!!["name"])
        assertFalse("and must not try again forever", local.isDirty(wallets, "w1"))
        remote.upsertCalls.clear()
        sync(local, remote)
        assertTrue(remote.upsertCalls.isEmpty())
    }

    // --- photos --------------------------------------------------------------------

    @Test
    fun aLocalPhoto_isUploadedThenItsRowIsPushedAgainWithThePath() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        local.put(wallets, wallet("w1"))
        local.put(txns, txn("t1"), localPhoto = "/photos/t1.jpg")
        local.photoFiles["/photos/t1.jpg"] = byteArrayOf(1, 2, 3)

        val report = sync(local, remote)

        val path = "$me/t1.jpg"
        assertEquals(path, SyncEngine.photoPath(me, "t1"))
        assertTrue(remote.uploads[path]!!.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(path, remote.server(txns, "t1")!!["receipt_path"])
        assertEquals(path, local.get(txns, "t1")!!["receipt_path"])
        assertFalse(local.isDirty(txns, "t1"))
        assertEquals("transactions were pushed, then pushed again with the path", 2, remote.upsertCalls.count { it.first == "transactions" })
        assertTrue(report.clean)
    }

    @Test
    fun aPhotoThatIsAlreadyUploaded_isNotUploadedAgain() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        local.put(wallets, wallet("w1"))
        local.put(txns, txn("t1", receiptPath = "$me/t1.jpg"), localPhoto = "/photos/t1.jpg")
        local.photoFiles["/photos/t1.jpg"] = byteArrayOf(1)

        sync(local, remote)

        assertTrue(remote.uploads.isEmpty())
    }

    @Test
    fun inboxAndDeletedRows_neverUploadTheirPhotos() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        local.put(wallets, wallet("w1"))
        local.put(txns, txn("inbox1"), status = "inbox", localPhoto = "/photos/a.jpg")
        local.put(txns, txn("gone", deleted = true), localPhoto = "/photos/b.jpg")
        local.photoFiles["/photos/a.jpg"] = byteArrayOf(1)
        local.photoFiles["/photos/b.jpg"] = byteArrayOf(1)

        sync(local, remote)

        assertTrue(remote.uploads.isEmpty())
    }

    @Test
    fun aMissingPhotoFile_isCountedAndTheRestOfTheSyncStillRuns() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        local.put(wallets, wallet("w1"))
        local.put(txns, txn("t1"), localPhoto = "/photos/lost.jpg") // file was deleted from the phone

        val report = sync(local, remote)

        assertEquals(1, report.photoFailures)
        assertFalse(report.clean)
        assertEquals("the transaction itself still synced", setOf("t1"), remote.ids(txns))
        assertNull(local.get(txns, "t1")!!["receipt_path"])
    }

    @Test
    fun aRefusedUpload_isCountedAndRetriedNextTime() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        local.put(wallets, wallet("w1"))
        local.put(txns, txn("t1"), localPhoto = "/photos/t1.jpg")
        local.photoFiles["/photos/t1.jpg"] = byteArrayOf(9)
        remote.rejectUploads = true

        assertEquals(1, sync(local, remote).photoFailures)
        assertNull(local.get(txns, "t1")!!["receipt_path"])

        remote.rejectUploads = false
        assertTrue(sync(local, remote).clean)
        assertEquals("$me/t1.jpg", remote.server(txns, "t1")!!["receipt_path"])
    }

    @Test
    fun aDroppedConnectionDuringAnUpload_stopsTheSyncAndKeepsThePhotoQueued() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        local.put(wallets, wallet("w1"))
        local.put(txns, txn("t1"), localPhoto = "/photos/t1.jpg")
        local.photoFiles["/photos/t1.jpg"] = byteArrayOf(9)
        remote.callsUntilOffline = 2 // wallets upsert, transactions upsert, then the upload drops

        syncFails(local, remote)

        assertEquals(listOf(PhotoJob("t1", "/photos/t1.jpg")), local.photosToUpload(me))
    }

    private companion object {
        val PHONE_ONLY = setOf("dirty", "status", "local_photo")
    }
}
