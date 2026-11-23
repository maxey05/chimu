package com.chimu

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Inbox lifecycle against the sync engine (RFC-005 acceptance: "Confirm -> syncs; dismiss -> gone;
 * Inbox items absent from Supabase"), using the in-memory phone and server from SyncFakes.kt, which
 * follow the real rules. The same rules as SQL (the WHERE clauses and the 2 -> 3 migration) were checked against a
 * real SQLite once, outside Gradle: see rfcs/RFC-005-auto-logging.md -> Notes.
 */
class InboxSyncTest {
    private val me = "user-me"
    private val wallets = SyncTables.wallets
    private val txns = SyncTables.transactions

    private fun wallet(id: String): Row = mapOf(
        "id" to id, "owner_id" to me, "updated_at" to 1_000L, "deleted" to 0L, "rev" to 0L,
        "name" to "GCash", "type" to "gcash", "opening_balance" to 0L,
    )

    private fun autoLogged(id: String, refNo: String? = "9876543210123", source: String = "notification"): Row = mapOf(
        "id" to id, "owner_id" to me, "updated_at" to 2_000L, "deleted" to 0L, "rev" to 0L,
        "type" to "expense", "wallet_id" to "w1", "to_wallet_id" to null, "amount" to 25_000L, "date" to "2026-11-23",
        "category" to null, "merchant" to "SM Supermarket", "note" to null, "is_business" to 0L, "source" to source,
        "ref_no" to refNo, "recurring_id" to null, "receipt_path" to null, "seller_tin" to null,
        "invoice_no" to null, "vatable_sales" to null, "vat" to null, "vat_exempt" to null,
    )

    private fun sync(local: FakeLocal, remote: FakeRemote) = runBlocking { SyncEngine(local, remote).sync(me) }

    @Test
    fun anInboxItem_isNeverPushed_andDoesNotCountAsWaiting() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        local.put(wallets, wallet("w1"))
        local.put(txns, autoLogged("a1"), status = "inbox")

        val report = sync(local, remote)

        assertEquals("only the wallet went", setOf("w1"), remote.ids(wallets))
        assertTrue("the Inbox item is absent from Supabase", remote.ids(txns).isEmpty())
        assertEquals(1, report.pushed)
        assertTrue(report.clean)
        assertEquals("inbox", local.get(txns, "a1")!!["status"])
    }

    @Test
    fun anInboxItem_isNotPushedEvenIfSyncRunsManyTimes() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        local.put(wallets, wallet("w1"))
        local.put(txns, autoLogged("a1"), status = "inbox")
        repeat(3) { sync(local, remote) }
        assertTrue(remote.ids(txns).isEmpty())
        assertTrue(remote.upsertCalls.none { (table, _) -> table == "transactions" })
    }

    @Test
    fun confirming_makesItAnOrdinaryDirtyRow_thatSyncs() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        local.put(wallets, wallet("w1"))
        local.put(txns, autoLogged("a1"), status = "inbox")
        sync(local, remote)
        assertTrue(remote.ids(txns).isEmpty())

        // What Db.confirmTxn does: the edit fields, status = confirmed, updated_at now, dirty = 1.
        local.edit(txns, "a1", updatedAt = 9_000L, "category" to "groceries", "status" to "confirmed")
        assertTrue(local.isDirty(txns, "a1"))

        val report = sync(local, remote)

        val onServer = remote.server(txns, "a1")!!
        assertEquals("groceries", onServer["category"])
        assertEquals("notification", onServer["source"])
        assertEquals("9876543210123", onServer["ref_no"])
        assertEquals(25_000L, onServer["amount"])
        assertFalse(local.isDirty(txns, "a1"))
        assertEquals(1, report.pushed)
    }

    @Test
    fun theRowTheServerGetsHasNoPhoneOnlyColumns() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        local.put(wallets, wallet("w1"))
        local.put(txns, autoLogged("a1"), status = "confirmed") // FakeRemote refuses status, raw_text and captured_at in a push
        sync(local, remote)
        val stored = remote.server(txns, "a1")!!
        assertFalse(stored.containsKey("raw_text"))
        assertFalse(stored.containsKey("captured_at"))
        assertFalse(stored.containsKey("status"))
    }

    @Test
    fun dismissing_removesTheItemForGood_andTheServerNeverHeardOfIt() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        local.put(wallets, wallet("w1"))
        local.put(txns, autoLogged("a1"), status = "inbox")
        local.put(txns, autoLogged("a2", refNo = "5551112223334", source = "sms"), status = "inbox")

        // Db.dismissInbox: a hard delete, allowed only for status = inbox.
        local.table(txns).remove("a1")
        sync(local, remote)

        assertNull(local.get(txns, "a1"))
        assertEquals(setOf("a2"), local.ids(txns))
        assertTrue(remote.ids(txns).isEmpty())
        assertTrue("no tombstone was ever sent either", remote.upsertCalls.none { (table, _) -> table == "transactions" })
    }

    @Test
    fun aReceiptCanBeAttachedToAnInboxItem_andThePhotoWaitsForConfirmation() {
        val local = FakeLocal()
        val remote = FakeRemote(me)
        local.put(wallets, wallet("w1"))
        local.put(txns, autoLogged("a1"), status = "inbox", localPhoto = "/photos/a1.jpg")
        local.photoFiles["/photos/a1.jpg"] = byteArrayOf(1, 2, 3)

        sync(local, remote)
        assertTrue("the photo of an unconfirmed item is not uploaded", remote.uploads.isEmpty())

        local.edit(txns, "a1", updatedAt = 9_000L, "status" to "confirmed")
        sync(local, remote)
        assertEquals(setOf("$me/a1.jpg"), remote.uploads.keys)
        assertEquals("$me/a1.jpg", remote.server(txns, "a1")!!["receipt_path"])
    }

    // ---- receipt matching sees Inbox items ----

    private fun inboxTxn(id: String, amount: Long, date: String) = Txn(
        id, me, TxnType.EXPENSE, "w1", null, amount, date, null, "SM", null, false, source = "notification", status = "inbox",
    )

    @Test
    fun aScansMatchCanBeAnInboxItem() {
        val inbox = listOf(inboxTxn("a1", 25_000, "2026-11-23"))
        val match = ReceiptMatch.find(emptyList<Txn>() + inbox, "scan", 25_000, "2026-11-24")
        assertEquals("a1", match!!.id)
        assertNull(ReceiptMatch.find(inbox, "scan", 25_001, "2026-11-24"))
        assertNull("more than 2 days apart", ReceiptMatch.find(inbox, "scan", 25_000, "2026-11-26"))
    }
}
