package com.chimu

/**
 * An in-memory phone for [SyncEngine] tests. It follows the same rules as [Db]: only the owner's
 * dirty rows are pushed, `inbox` rows never are, pulled rows go through [Lww], `status` and
 * `local_photo` are phone-only, and the cache wipe keeps rows with unsynced edits.
 */
class FakeLocal : SyncLocal {
    /** table name -> id -> the row's columns, plus `dirty` (Boolean) and the phone-only `status` and `local_photo`. */
    val tables: Map<String, MutableMap<String, MutableMap<String, Any?>>> =
        SyncTables.inOrder.associate { it.name to LinkedHashMap<String, MutableMap<String, Any?>>() }

    val photoFiles = mutableMapOf<String, ByteArray>()
    var members: List<Row> = emptyList()
    val cursors = mutableMapOf<String, Long>()
    var cacheOwnerValue: String? = null
    var clock = 5_000_000L

    fun table(table: SyncTable) = tables.getValue(table.name)

    /** Adds a row as the phone would have it after a local edit. */
    fun put(
        table: SyncTable,
        row: Row,
        dirty: Boolean = true,
        status: String = "confirmed",
        localPhoto: String? = null,
    ) {
        val stored = LinkedHashMap<String, Any?>(row)
        stored["dirty"] = dirty
        stored["status"] = status
        stored["local_photo"] = localPhoto
        table(table)[row["id"] as String] = stored
    }

    fun get(table: SyncTable, id: String): Map<String, Any?>? = table(table)[id]

    /** A user edit: change columns, set `updated_at`, set `dirty`. */
    fun edit(table: SyncTable, id: String, updatedAt: Long, vararg changes: Pair<String, Any?>) {
        val row = checkNotNull(table(table)[id]) { "no $id in ${table.name}" }
        changes.forEach { (k, v) -> row[k] = v }
        row["updated_at"] = updatedAt
        row["dirty"] = true
    }

    fun isDirty(table: SyncTable, id: String) = table(table)[id]?.get("dirty") == true

    fun ids(table: SyncTable): Set<String> = table(table).keys

    // --- SyncLocal -------------------------------------------------------------

    override fun dirtyRows(table: SyncTable, owner: String): List<Row> =
        table(table).values
            .filter { it["owner_id"] == owner && it["dirty"] == true && !(table.holdsInbox && it["status"] == "inbox") }
            .sortedWith(compareBy({ it["updated_at"] as Long }, { it["id"] as String }))
            .map { row -> table.pushColumns.associate { it.name to row[it.name] } }

    override fun applyRemote(table: SyncTable, rows: List<Row>): Int {
        var applied = 0
        for (row in rows) {
            val id = row["id"] as String
            val existing = table(table)[id]
            if (!Lww.shouldApply(existing?.get("updated_at") as Long?, row["updated_at"] as Long)) continue
            val stored = LinkedHashMap<String, Any?>()
            table.columns.forEach { stored[it.name] = row[it.name] }
            stored["dirty"] = false
            stored["status"] = existing?.get("status") ?: "confirmed"
            stored["local_photo"] = existing?.get("local_photo")
            table(table)[id] = stored
            applied++
        }
        return applied
    }

    override fun photosToUpload(owner: String): List<PhotoJob> =
        table(SyncTables.transactions).values
            .filter {
                it["owner_id"] == owner && it["deleted"] == 0L && it["status"] != "inbox" &&
                    !(it["local_photo"] as String?).isNullOrEmpty() && it["receipt_path"] == null
            }
            .map { PhotoJob(it["id"] as String, it["local_photo"] as String) }

    override fun setReceiptPath(owner: String, txnId: String, path: String): Boolean {
        val row = table(SyncTables.transactions)[txnId] ?: return false
        if (row["owner_id"] != owner || row["receipt_path"] != null) return false
        row["receipt_path"] = path
        row["updated_at"] = ++clock
        row["dirty"] = true
        return true
    }

    override fun readPhoto(localPhoto: String): ByteArray? = photoFiles[localPhoto]

    override fun cachedMembers(): Set<String> = members.map { "${it["group_id"]}:${it["user_id"]}" }.toSet()

    override fun replaceMembers(rows: List<Row>) {
        members = rows.map { it.toMap() }
    }

    override fun wipeOthers(owner: String) {
        tables.values.forEach { rows -> rows.values.removeAll { it["owner_id"] != owner && it["dirty"] != true } }
    }

    override fun cursor(table: SyncTable): Long = cursors[table.name] ?: 0L

    override fun setCursor(table: SyncTable, rev: Long) {
        cursors[table.name] = rev
    }

    override fun resetCursors() = cursors.clear()

    override fun cacheOwner(): String? = cacheOwnerValue

    override fun setCacheOwner(owner: String) {
        cacheOwnerValue = owner
    }
}

/**
 * An in-memory Supabase for [SyncEngine] tests. It reproduces what the real backend does for sync
 * (supabase/migrations/0001_init.sql, checked by supabase/check_rls.py):
 * `lww_guard` (a write with an older `updated_at` is discarded and the stored row comes back),
 * `set_rev` (a changed row gets the next `rev`), and RLS (the caller reads its own rows plus
 * those of [visibleOwners], and writes only its own).
 */
class FakeRemote(private val caller: String) : SyncRemote {
    private val tables: Map<String, MutableMap<String, MutableMap<String, Any?>>> =
        SyncTables.inOrder.associate { it.name to LinkedHashMap<String, MutableMap<String, Any?>>() }

    private var revSeq = 0L

    /** Other owners whose rows the caller can read (they share a group with the caller). */
    val visibleOwners = mutableSetOf<String>()
    var memberRows: List<Row> = emptyList()
    val uploads = mutableMapOf<String, ByteArray>()

    /** Every `upsert`: table name and how many rows were sent. */
    val upsertCalls = mutableListOf<Pair<String, Int>>()
    var pullCalls = 0

    var online = true

    /** Go offline after this many more successful calls (the airplane-mode-mid-sync switch). */
    var callsUntilOffline: Int? = null

    /** An upsert containing one of these ids is refused with a 400, as a failed CHECK would be. */
    val rejectIds = mutableSetOf<String>()
    var rejectUploads = false

    /** Runs after the server stored an upsert and before the answer reaches the phone. */
    var afterStore: (() -> Unit)? = null

    fun server(table: SyncTable, id: String): Map<String, Any?>? = tables.getValue(table.name)[id]

    fun ids(table: SyncTable): Set<String> = tables.getValue(table.name).keys

    /** A change made directly on the server (the Supabase table editor, the web dashboard). */
    fun serverWrite(table: SyncTable, row: Row) {
        store(table, row)
    }

    private fun store(table: SyncTable, row: Row): Map<String, Any?> {
        val rows = tables.getValue(table.name)
        val id = row["id"] as String
        val old = rows[id]
        if (old != null && (row["updated_at"] as Long) < (old["updated_at"] as Long)) return old // lww_guard
        val next = LinkedHashMap<String, Any?>()
        table.columns.forEach { next[it.name] = row[it.name] }
        val unchanged = old != null && table.columns.filter { it.name != "rev" }.all { old[it.name] == next[it.name] }
        next["rev"] = if (unchanged) old!!["rev"] else ++revSeq // set_rev
        rows[id] = next
        return next
    }

    private fun gate() {
        callsUntilOffline?.let { left -> if (left <= 0) online = false else callsUntilOffline = left - 1 }
        if (!online) throw SyncUnavailable("offline")
    }

    // --- SyncRemote ------------------------------------------------------------

    override suspend fun upsert(table: SyncTable, rows: List<Row>): List<Row> {
        gate()
        upsertCalls += table.name to rows.size
        if (rows.any { it["id"] in rejectIds }) throw SyncRejected(400, "check constraint failed")
        if (rows.any { it["owner_id"] != caller }) throw SyncRejected(403, "row-level security")
        // The phone must never send the pull cursor or its own bookkeeping columns.
        rows.forEach { row -> check("rev" !in row && "dirty" !in row && "status" !in row && "local_photo" !in row) }
        val stored = rows.map { store(table, it) }.map { it.toMap() }
        afterStore?.invoke()
        return stored
    }

    override suspend fun pull(table: SyncTable, afterRev: Long, limit: Int): List<Row> {
        gate()
        pullCalls++
        return tables.getValue(table.name).values
            .filter { (it["rev"] as Long) > afterRev && (it["owner_id"] == caller || it["owner_id"] in visibleOwners) }
            .sortedBy { it["rev"] as Long }
            .take(limit)
            .map { it.toMap() }
    }

    override suspend fun members(): List<Row> {
        gate()
        return memberRows.map { it.toMap() }
    }

    override suspend fun uploadPhoto(path: String, bytes: ByteArray) {
        gate()
        if (rejectUploads) throw SyncRejected(413, "too large")
        uploads[path] = bytes
    }

    override suspend fun photoUrl(path: String): String = "https://example.test/signed/$path"
}
