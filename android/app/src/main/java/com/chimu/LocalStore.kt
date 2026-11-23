package com.chimu

import android.content.SharedPreferences
import java.io.File

/**
 * The sync engine's view of the phone: rows through [Db], and the pull cursors (`last_rev` per
 * table) and cache owner in preferences, as DESIGN.md -> Sync protocol asks.
 *
 * The cursors are not per user. They describe the cache as it is now, and [SyncEngine] starts them
 * over whenever a different user signs in ([cacheOwner]).
 *
 * Blocks on disk: call it off the main thread.
 */
class LocalStore(private val db: Db, private val prefs: SharedPreferences) : SyncLocal {
    override fun dirtyRows(table: SyncTable, owner: String) = db.dirtyRows(table, owner)

    override fun applyRemote(table: SyncTable, rows: List<Row>) = db.applyRemote(table, rows)

    override fun photosToUpload(owner: String) = db.photosToUpload(owner)

    override fun setReceiptPath(owner: String, txnId: String, path: String) = db.setReceiptPath(owner, txnId, path)

    override fun readPhoto(localPhoto: String): ByteArray? =
        File(localPhoto).takeIf { it.isFile }?.readBytes()

    override fun cachedMembers() = db.cachedMembers()

    override fun replaceMembers(rows: List<Row>) = db.replaceMembers(rows)

    override fun wipeOthers(owner: String) = db.wipeOthers(owner)

    override fun cursor(table: SyncTable): Long = prefs.getLong(cursorKey(table), 0L)

    override fun setCursor(table: SyncTable, rev: Long) {
        prefs.edit().putLong(cursorKey(table), rev).apply()
    }

    override fun resetCursors() {
        val editor = prefs.edit()
        SyncTables.inOrder.forEach { editor.remove(cursorKey(it)) }
        editor.apply()
    }

    override fun cacheOwner(): String? = prefs.getString(KEY_CACHE_OWNER, null)

    override fun setCacheOwner(owner: String) {
        prefs.edit().putString(KEY_CACHE_OWNER, owner).apply()
    }

    /** How many of [owner]'s rows still wait to be pushed. For the Home screen. */
    fun pendingCount(owner: String): Int = db.pendingCount(owner)

    private fun cursorKey(table: SyncTable) = "last_rev_${table.name}"

    private companion object {
        const val KEY_CACHE_OWNER = "sync_cache_owner"
    }
}
