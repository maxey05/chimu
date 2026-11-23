package com.chimu

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * RFC-004's sync scenarios: a scanned receipt goes pending -> photo up -> read -> extracted, and the
 * "airplane mode" and "reader trouble" cases lose nothing. Run against [FakeLocal] / [FakeRemote].
 */
class ReceiptSyncTest {
    private val me = "user-me"
    private val wallets = SyncTables.wallets
    private val txns = SyncTables.transactions
    private val path = "$me/scan1.jpg"

    private fun wallet(): Row = mapOf(
        "id" to "w1", "owner_id" to me, "updated_at" to 1_000L, "deleted" to 0L, "rev" to 0L,
        "name" to "Cash", "type" to "cash", "opening_balance" to 0L,
    )

    /** A scan as the phone creates it: pending, amount 0, today's date, source receipt. */
    private fun scan(id: String = "scan1"): Row = mapOf(
        "id" to id, "owner_id" to me, "updated_at" to 2_000L, "deleted" to 0L, "rev" to 0L,
        "type" to "expense", "wallet_id" to "w1", "to_wallet_id" to null, "amount" to 0L, "date" to "2026-11-23",
        "category" to null, "merchant" to null, "note" to null, "is_business" to 0L, "source" to "receipt",
        "ref_no" to null, "recurring_id" to null, "receipt_path" to null, "seller_tin" to null,
        "invoice_no" to null, "vatable_sales" to null, "vat" to null, "vat_exempt" to null,
    )

    private val jollibee = ExtractedReceipt(
        type = TxnType.EXPENSE, amount = 45_050, date = "2026-11-20", merchant = "Jollibee", category = "food",
        refNo = "R-1", sellerTin = "123-456-789-000", invoiceNo = "SI-77",
        vatableSales = 40_223, vat = 4_827, vatExempt = null,
    )

    private fun phone(id: String = "scan1"): FakeLocal = FakeLocal().apply {
        put(wallets, wallet())
        put(txns, scan(id), status = "pending", localPhoto = "/photos/$id.jpg")
        photoFiles["/photos/$id.jpg"] = byteArrayOf(7, 7, 7)
    }

    private fun server(): FakeRemote = FakeRemote(me).apply { readings[path] = jollibee }

    private fun sync(local: FakeLocal, remote: FakeRemote, retryFailed: Boolean = false): SyncReport =
        runBlocking { SyncEngine(local, remote).sync(me, retryFailed) }

    // --- the happy path ------------------------------------------------------------

    @Test
    fun aPendingScan_isUploadedReadAndSavedAsExtracted() {
        val local = phone()
        val remote = server()

        val report = sync(local, remote)

        assertTrue(report.clean)
        assertEquals(1, report.extracted)
        val row = local.get(txns, "scan1")!!
        assertEquals("extracted", row["status"])
        assertEquals(45_050L, row["amount"])
        assertEquals("2026-11-20", row["date"])
        assertEquals("Jollibee", row["merchant"])
        assertEquals("food", row["category"])
        assertEquals(4_827L, row["vat"])
        assertEquals(path, row["receipt_path"])
        assertFalse("the filled-in row was pushed", local.isDirty(txns, "scan1"))

        val onServer = remote.server(txns, "scan1")!!
        assertEquals(45_050L, onServer["amount"])
        assertEquals("Jollibee", onServer["merchant"])
        assertEquals(path, onServer["receipt_path"])
        assertEquals("receipt", onServer["source"])
        assertEquals(listOf(path), remote.extractCalls)
    }

    @Test
    fun fieldsTheReaderCouldNotFind_keepTheirPlaceholders() {
        val local = phone()
        val remote = server()
        remote.readings[path] = jollibee.copy(amount = null, date = null, merchant = null)

        sync(local, remote)

        val row = local.get(txns, "scan1")!!
        assertEquals("extracted", row["status"])
        assertEquals(0L, row["amount"])
        assertEquals("2026-11-23", row["date"])
        assertNull(row["merchant"])
    }

    @Test
    fun theReaderIsAskedOnlyAfterThePhotoIsOnTheServer() {
        val local = phone()
        local.photoFiles.clear() // the file is gone, so the upload can't happen
        val remote = server()

        val report = sync(local, remote)

        assertEquals(1, report.photoFailures)
        assertTrue(remote.extractCalls.isEmpty())
        assertEquals("pending", local.get(txns, "scan1")!!["status"])
    }

    @Test
    fun anAlreadyExtractedScan_isNotReadAgain() {
        val local = phone()
        val remote = server()
        sync(local, remote)
        remote.extractCalls.clear()

        sync(local, remote, retryFailed = true)

        assertTrue(remote.extractCalls.isEmpty())
    }

    // --- acceptance: airplane mode -------------------------------------------------

    @Test
    fun offline_theScanStaysPending_andIsReadWhenBackOnline() {
        val local = phone()
        val remote = server()
        remote.online = false

        try {
            sync(local, remote)
            fail("expected SyncUnavailable")
        } catch (e: SyncUnavailable) {
            // expected
        }
        assertEquals("pending", local.get(txns, "scan1")!!["status"])
        assertNull(local.get(txns, "scan1")!!["extract_error"])
        assertTrue(local.isDirty(txns, "scan1"))

        remote.online = true
        val report = sync(local, remote)

        assertTrue(report.clean)
        assertEquals("extracted", local.get(txns, "scan1")!!["status"])
        assertEquals(45_050L, remote.server(txns, "scan1")!!["amount"])
    }

    @Test
    fun droppingOfflineBetweenUploadAndReading_losesNothing() {
        val local = phone()
        val remote = server()
        // wallets push, transactions push, photo upload, then the connection drops before the reader is asked.
        remote.callsUntilOffline = 3

        try {
            sync(local, remote)
            fail("expected SyncUnavailable")
        } catch (e: SyncUnavailable) {
            // expected
        }
        // Either the scan was never read, or it was read; what matters is that the next sync finishes the job.
        remote.callsUntilOffline = null
        remote.online = true
        assertTrue(sync(local, remote).clean)
        assertEquals("extracted", local.get(txns, "scan1")!!["status"])
        assertEquals(path, remote.server(txns, "scan1")!!["receipt_path"])
    }

    // --- reader trouble ------------------------------------------------------------

    @Test
    fun aPhotoTheReaderRefuses_isRememberedAndNotSentAgainOnQuietSyncs() {
        val local = phone()
        val remote = server()
        remote.readings.clear() // the reader answers 422 for this photo

        val first = sync(local, remote)

        assertEquals(1, first.extractFailures)
        assertFalse(first.clean)
        assertEquals("The receipt couldn't be read.", local.get(txns, "scan1")!!["extract_error"])
        assertEquals("pending", local.get(txns, "scan1")!!["status"])
        assertEquals("the failure is phone-only, so it did not make the row dirty", false, local.isDirty(txns, "scan1"))

        remote.extractCalls.clear()
        sync(local, remote)
        assertTrue("a quiet sync must not pay for the same photo again", remote.extractCalls.isEmpty())

        remote.readings[path] = jollibee
        val retried = sync(local, remote, retryFailed = true)

        assertEquals(1, retried.extracted)
        assertEquals("extracted", local.get(txns, "scan1")!!["status"])
        assertNull(local.get(txns, "scan1")!!["extract_error"])
    }

    @Test
    fun aBusyReader_leavesTheScanPending_withoutAnErrorOnIt_andTheRestOfTheSyncStillRuns() {
        val local = phone()
        val remote = server()
        remote.extractFailure = SyncUnavailable("The reader is busy.")
        // Something another phone wrote that this sync should still pull.
        remote.serverWrite(
            txns,
            scan("elsewhere").toMutableMap().apply { put("amount", 500L); put("source", "manual") },
        )

        val report = sync(local, remote)

        assertEquals(1, report.extractFailures)
        assertEquals("pending", local.get(txns, "scan1")!!["status"])
        assertNull("an unreachable reader is not the photo's fault", local.get(txns, "scan1")!!["extract_error"])
        assertTrue("the pull still ran", "elsewhere" in local.ids(txns))

        remote.extractFailure = null
        assertTrue(sync(local, remote).clean) // a quiet sync is enough: nothing was remembered as failed
        assertEquals("extracted", local.get(txns, "scan1")!!["status"])
    }

    @Test
    fun aReaderThatStopsAnswering_isAskedOnlyOncePerSync() {
        val local = FakeLocal().apply { put(wallets, wallet()) }
        for (id in listOf("a", "b", "c")) {
            local.put(txns, scan(id), status = "pending", localPhoto = "/photos/$id.jpg")
            local.photoFiles["/photos/$id.jpg"] = byteArrayOf(1)
        }
        val remote = FakeRemote(me).apply { extractFailure = SyncUnavailable("busy") }

        sync(local, remote)

        assertEquals(1, remote.extractCalls.size)
    }

    @Test
    fun aScanTheUserFilledInByHand_isNeverOverwrittenByTheReader() {
        val local = phone()
        val remote = server()
        // While the reader is working, the user types the numbers in and saves (status -> confirmed).
        remote.duringExtract = {
            local.edit(txns, "scan1", 9_000_000, "amount" to 99_900L, "merchant" to "Typed by me", "status" to "confirmed")
        }

        sync(local, remote)

        val row = local.get(txns, "scan1")!!
        assertEquals("confirmed", row["status"])
        assertEquals(99_900L, row["amount"])
        assertEquals("Typed by me", row["merchant"])
        assertTrue("the user's edit is still waiting to go up", local.isDirty(txns, "scan1"))

        sync(local, remote) // the app does this a few seconds after a save
        assertEquals(99_900L, remote.server(txns, "scan1")!!["amount"])
        assertEquals("Typed by me", remote.server(txns, "scan1")!!["merchant"])
        assertEquals(1, remote.extractCalls.size)
    }

    @Test
    fun aConfirmedScan_isNotReadAtAll() {
        val local = FakeLocal().apply { put(wallets, wallet()) }
        local.put(txns, scan(), status = "confirmed", localPhoto = "/photos/scan1.jpg")
        local.photoFiles["/photos/scan1.jpg"] = byteArrayOf(1)
        val remote = server()

        sync(local, remote, retryFailed = true)

        assertTrue(remote.extractCalls.isEmpty())
    }

    @Test
    fun manualEntriesAndDeletedScans_areNeverSentToTheReader() {
        val local = FakeLocal().apply { put(wallets, wallet()) }
        local.put(txns, scan("manual").toMutableMap().apply { put("source", "manual"); put("receipt_path", "$me/manual.jpg") }, status = "pending")
        local.put(txns, scan("gone").toMutableMap().apply { put("deleted", 1L); put("receipt_path", "$me/gone.jpg") }, status = "pending")
        val remote = server()

        sync(local, remote, retryFailed = true)

        assertTrue(remote.extractCalls.isEmpty())
    }

    @Test
    fun theServerNeverReceivesPhoneOnlyColumns() {
        // FakeRemote.upsert fails the test if "status", "local_photo" or "extract_error" are in a pushed row.
        val local = phone()
        val remote = server()
        remote.readings.clear()
        sync(local, remote)
        remote.readings[path] = jollibee
        sync(local, remote, retryFailed = true)
        assertTrue(remote.upsertCalls.isNotEmpty())
    }
}
