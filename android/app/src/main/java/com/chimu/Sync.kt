package com.chimu

/** The server can't be reached right now (offline, timeout, 5xx, expired sign-in). Nothing is lost; try again later. */
class SyncUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The server refused a write (a 4xx such as a failed check or an RLS rejection). Retrying the same rows won't help. */
class SyncRejected(val status: Int, message: String, cause: Throwable? = null) : Exception(message, cause)

/** A receipt photo waiting to go up: the transaction it belongs to and the file on the phone. */
data class PhotoJob(val txnId: String, val localPhoto: String)

/** A scanned receipt whose photo is up and that is waiting to be read: the transaction and the photo's path in the `receipts` bucket. */
data class ExtractJob(val txnId: String, val receiptPath: String)

/** What the engine needs from the phone's database and preferences. [LocalStore] is the real one. */
interface SyncLocal {
    /** [owner]'s rows with `dirty = 1`, skipping `inbox` transactions. Tombstones included. */
    fun dirtyRows(table: SyncTable, owner: String): List<Row>

    /** Stores rows that came from the server, each only if [Lww.shouldApply] says so, and clears their `dirty`. Returns how many were stored. */
    fun applyRemote(table: SyncTable, rows: List<Row>): Int

    /** [owner]'s live, non-inbox transactions that have a local photo but no `receipt_path` yet. */
    fun photosToUpload(owner: String): List<PhotoJob>

    /** Records the uploaded photo's path as an edit (`updated_at` now, `dirty = 1`). False if the row is gone or already has a path. */
    fun setReceiptPath(owner: String, txnId: String, path: String): Boolean

    /** [owner]'s scans that are `pending`, have an uploaded photo and are not deleted. Failed ones (`extract_error` set) only when [includeFailed]. */
    fun pendingExtractions(owner: String, includeFailed: Boolean): List<ExtractJob>

    /** Fills a still-`pending` scan from [receipt], marks it `extracted` as an edit (`dirty = 1`). False if it is no longer pending. */
    fun applyExtraction(owner: String, txnId: String, receipt: ExtractedReceipt): Boolean

    /** Remembers why reading [txnId] failed (phone only, never pushed). */
    fun markExtractionFailed(owner: String, txnId: String, message: String)

    /** The photo's bytes, or null if the file is gone. */
    fun readPhoto(localPhoto: String): ByteArray?

    /** `group_id:user_id` of every cached membership row. */
    fun cachedMembers(): Set<String>

    fun replaceMembers(rows: List<Row>)

    /** Replaces the cached groups with [rows] (`id`, `name`, `invite_code`, `owner_id`, `created_at`). RFC-006. */
    fun replaceGroups(rows: List<Row>)

    /** Replaces the cached display names with [rows] (`id`, `display_name`). RFC-006. */
    fun replaceProfiles(rows: List<Row>)

    /** Forgets cached rows that belong to anyone but [owner], except rows with unsynced edits. */
    fun wipeOthers(owner: String)

    /** `last_rev` for [table]: the highest server `rev` pulled so far, 0 if none. */
    fun cursor(table: SyncTable): Long

    fun setCursor(table: SyncTable, rev: Long)

    fun resetCursors()

    /** Whose pull cursors and group cache the phone is holding, or null on a fresh install. */
    fun cacheOwner(): String?

    fun setCacheOwner(owner: String)
}

/** What the engine needs from the server. [SupaRemote] is the real one. Network and server errors surface as [SyncUnavailable] / [SyncRejected]. */
interface SyncRemote {
    /** Upserts [rows] and returns the rows as the server stored them (a write the server discarded as too old comes back as the stored row). */
    suspend fun upsert(table: SyncTable, rows: List<Row>): List<Row>

    /** Up to [limit] rows with `rev > afterRev`, lowest `rev` first. */
    suspend fun pull(table: SyncTable, afterRev: Long, limit: Int): List<Row>

    /** Every `group_members` row the caller can see (`group_id`, `user_id`, `role`, `joined_at`). */
    suspend fun members(): List<Row>

    /** Every group the caller is in (`id`, `name`, `invite_code`, `owner_id`, `created_at`). RFC-006. */
    suspend fun groups(): List<Row>

    /** The caller's profile and those of everyone they share a group with (`id`, `display_name`). RFC-006. */
    suspend fun profiles(): List<Row>

    suspend fun uploadPhoto(path: String, bytes: ByteArray)

    suspend fun photoUrl(path: String): String

    /**
     * Asks the `extract` Edge Function to read the receipt photo at [path]. A [SyncUnavailable] means
     * try again later (offline, reader busy, expired sign-in); a [SyncRejected] means this photo was
     * refused or couldn't be read, and its message says why in words fit for the user.
     */
    suspend fun extract(path: String): ExtractedReceipt
}

/**
 * The outcome of one sync that ran to the end. Rejected rows and failed photos stay on the phone for
 * the next try. [extracted] counts scans read this time; [extractFailures] counts scans that could not
 * be read now (refused, unreadable, or the reader was unreachable).
 */
data class SyncReport(
    val pushed: Int = 0,
    val pulled: Int = 0,
    val rejected: Int = 0,
    val photoFailures: Int = 0,
    val extracted: Int = 0,
    val extractFailures: Int = 0,
) {
    val clean: Boolean get() = rejected == 0 && photoFailures == 0 && extractFailures == 0
}

/**
 * The phone <-> Supabase sync, following DESIGN.md -> Sync protocol:
 *
 * 1. **Push** each table's dirty rows in dependency order. The server keeps last-writer-wins (its
 *    `lww_guard` trigger) and sends back the rows as stored, which are applied here with the same
 *    rule ([Lww]). That clears `dirty` for accepted rows and takes the server's copy for a write it
 *    discarded as too old.
 * 2. **Photos**: upload `local_photo` where `receipt_path` is empty, then push those rows again.
 * 3. **Reading** (RFC-004): ask `extract` to read each scan whose photo is up, fill the row, then push
 *    those rows again. Needs the photo to be on the server first, so it comes after step 2.
 * 4. **Membership**: if the visible group members changed, forget other people's cached rows and
 *    start the pull cursors over. Groups and display names are refreshed here too (RFC-006).
 * 5. **Pull** each table's rows with `rev > last_rev` and apply them with [Lww].
 *
 * Nothing here deletes or un-dirties a row on a failure (RULES 20): `dirty` is cleared only by
 * [SyncLocal.applyRemote] of a row the server acknowledged. A [SyncUnavailable] stops the sync and
 * propagates; a [SyncRejected] write is isolated to its own row and counted, the rest carries on.
 */
class SyncEngine(
    private val local: SyncLocal,
    private val remote: SyncRemote,
) {
    /**
     * @param retryFailed also try again the scans that failed to be read earlier. Off for the quiet
     *   background syncs, so a photo the reader can't read isn't sent (and paid for) again and again;
     *   on when the user asks (app start, pull-to-refresh, "Sync now").
     */
    suspend fun sync(owner: String, retryFailed: Boolean = false): SyncReport {
        claimCache(owner)
        val run = Run()

        SyncTables.inOrder.forEach { push(it, owner, run) }
        if (uploadPhotos(owner, run) > 0) push(SyncTables.transactions, owner, run)
        if (extractReceipts(owner, retryFailed, run) > 0) push(SyncTables.transactions, owner, run)

        refreshMembership(owner)
        SyncTables.inOrder.forEach { pull(it, run) }

        return SyncReport(run.pushed, run.pulled, run.rejected, run.photoFailures, run.extracted, run.extractFailures)
    }

    private class Run {
        var pushed = 0
        var pulled = 0
        var rejected = 0
        var photoFailures = 0
        var extracted = 0
        var extractFailures = 0
    }

    /**
     * The pull cursors mean "everything this user can see, up to this rev". If someone else signed
     * in last they mean nothing for [owner], so start them over (a full pull restores everything).
     * The cached group memberships need no reset: a difference between them and the server's is
     * what [refreshMembership] looks for.
     */
    private fun claimCache(owner: String) {
        if (local.cacheOwner() == owner) return
        local.resetCursors()
        local.setCacheOwner(owner)
    }

    private suspend fun push(table: SyncTable, owner: String, run: Run) {
        for (batch in local.dirtyRows(table, owner).chunked(PUSH_BATCH)) {
            try {
                acknowledge(table, batch, remote.upsert(table, batch), run)
            } catch (e: SyncRejected) {
                // One bad row would block the whole batch forever. Find it by sending the rows alone.
                if (batch.size == 1) run.rejected++ else batch.forEach { pushOne(table, it, run) }
            }
        }
    }

    private suspend fun pushOne(table: SyncTable, row: Row, run: Run) {
        try {
            acknowledge(table, listOf(row), remote.upsert(table, listOf(row)), run)
        } catch (e: SyncRejected) {
            run.rejected++
        }
    }

    private fun acknowledge(table: SyncTable, sent: List<Row>, stored: List<Row>, run: Run) {
        local.applyRemote(table, stored)
        run.pushed += sent.size
    }

    /** Returns how many photos went up. A missing file or a refused upload is counted and skipped. */
    private suspend fun uploadPhotos(owner: String, run: Run): Int {
        var uploaded = 0
        for (job in local.photosToUpload(owner)) {
            val bytes = local.readPhoto(job.localPhoto)
            if (bytes == null) {
                run.photoFailures++
                continue
            }
            val path = photoPath(owner, job.txnId)
            try {
                remote.uploadPhoto(path, bytes)
            } catch (e: SyncRejected) {
                run.photoFailures++
                continue
            }
            if (local.setReceiptPath(owner, job.txnId, path)) uploaded++
        }
        return uploaded
    }

    /**
     * Reads each waiting scan. Returns how many were filled in. A refused or unreadable photo is
     * remembered on its row and skipped. If the reader can't be reached the loop stops, since the next
     * scan would meet the same trouble, and the rest of the sync (pull) carries on: the scans stay
     * `pending` and nothing is stored as an error.
     */
    private suspend fun extractReceipts(owner: String, retryFailed: Boolean, run: Run): Int {
        var filled = 0
        for (job in local.pendingExtractions(owner, retryFailed)) {
            val receipt = try {
                remote.extract(job.receiptPath)
            } catch (e: SyncRejected) {
                local.markExtractionFailed(owner, job.txnId, e.message ?: "The receipt couldn't be read.")
                run.extractFailures++
                continue
            } catch (e: SyncUnavailable) {
                run.extractFailures++
                break
            }
            if (local.applyExtraction(owner, job.txnId, receipt)) {
                filled++
                run.extracted++
            }
        }
        return filled
    }

    /**
     * Step 4 of the protocol, and the full re-pull of RFC-006: after a group is created, joined or left,
     * or a member is removed, the server's memberships differ from the cached ones, so the cursors start
     * over and other people's cached rows are dropped; the pull that follows brings back exactly what
     * the user may see now. Groups and names are fetched with the memberships, before anything on the phone
     * changes, so a failure leaves the cache as it was.
     */
    private suspend fun refreshMembership(owner: String) {
        val members = remote.members()
        val groups = remote.groups()
        val profiles = remote.profiles()
        val remoteKeys = members.map(::memberKey).toSet()
        if (remoteKeys != local.cachedMembers()) {
            // Order matters if the app dies halfway: the cache is replaced last, so the next sync
            // sees the difference again and finishes the job.
            local.resetCursors()
            local.wipeOthers(owner)
        }
        local.replaceGroups(groups)
        local.replaceProfiles(profiles)
        local.replaceMembers(members)
    }

    private suspend fun pull(table: SyncTable, run: Run) {
        var after = local.cursor(table)
        while (true) {
            val page = remote.pull(table, after, PULL_PAGE)
            if (page.isEmpty()) return
            val newest = page.maxOf { it["rev"] as Long }
            check(newest > after) { "the server returned ${table.name} rows that are not newer than rev $after" }
            local.applyRemote(table, page)
            after = newest
            local.setCursor(table, after)
            run.pulled += page.size
            if (page.size < PULL_PAGE) return
        }
    }

    companion object {
        const val PUSH_BATCH = 100
        const val PULL_PAGE = 500

        /** Where a transaction's photo lives in the `receipts` bucket: `{owner_id}/{transaction_id}.jpg` (DESIGN.md -> Visibility). */
        fun photoPath(owner: String, txnId: String) = "$owner/$txnId.jpg"

        private fun memberKey(row: Row) = "${row["group_id"]}:${row["user_id"]}"
    }
}
