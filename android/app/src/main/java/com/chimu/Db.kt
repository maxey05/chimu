package com.chimu

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The phone's local SQLite database, the offline source of truth (DESIGN.md -> Architecture).
 *
 * Every write helper sets `updated_at` to now and `dirty = 1` so the sync engine ([SyncEngine]) knows
 * what to push (RULES 8, 10). Nothing is ever hard-deleted: deleting a row sets `deleted = 1`
 * (RULES 9). All SQL is parameterised (RULES 18).
 *
 * Calls block on disk, so callers run them off the main thread (see [Repo]).
 */
class Db(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NAME, null, Schema.VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        Schema.CREATE_STATEMENTS.forEach { db.execSQL(it) }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first schema, so there is nothing to upgrade from yet.
        // Later versions add `if (oldVersion < N) { ... }` steps here, in the same change as
        // the matching Supabase migration and the DESIGN.md update (RULES 11).
    }

    // --- wallets -------------------------------------------------------------

    /** All of [ownerId]'s wallets, archived ones included (they are `deleted = true`). */
    fun wallets(ownerId: String): List<Wallet> =
        readableDatabase.query(
            "wallets", null, "owner_id = ?", arrayOf(ownerId), null, null, "name COLLATE NOCASE",
        ).use { c -> buildList { while (c.moveToNext()) add(c.toWallet()) } }

    fun insertWallet(wallet: Wallet) {
        writableDatabase.insertOrThrow("wallets", null, walletValues(wallet, System.currentTimeMillis()))
    }

    /** Updates name, type, opening balance and tombstone; id and owner never change. */
    fun updateWallet(wallet: Wallet) {
        val values = walletValues(wallet, System.currentTimeMillis()).apply {
            remove("id")
            remove("owner_id")
        }
        writableDatabase.update("wallets", values, "id = ? AND owner_id = ?", arrayOf(wallet.id, wallet.ownerId))
    }

    /** "Archive" a wallet: tombstone it so it syncs as deleted. */
    fun archiveWallet(ownerId: String, id: String) {
        writableDatabase.execSQL(
            "UPDATE wallets SET deleted = 1, updated_at = ?, dirty = 1 WHERE id = ? AND owner_id = ?",
            arrayOf<Any>(System.currentTimeMillis(), id, ownerId),
        )
    }

    // --- transactions --------------------------------------------------------

    /** [ownerId]'s live (non-deleted) transactions. Tombstones stay in the table for sync. */
    fun txns(ownerId: String): List<Txn> =
        readableDatabase.query(
            "transactions", null, "owner_id = ? AND deleted = 0", arrayOf(ownerId), null, null, null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.toTxn()) } }

    fun insertTxn(txn: Txn) {
        val values = txnEditValues(txn, System.currentTimeMillis()).apply {
            put("id", txn.id)
            put("owner_id", txn.ownerId)
            put("source", txn.source)
            put("status", txn.status)
        }
        writableDatabase.insertOrThrow("transactions", null, values)
    }

    /**
     * Updates only the fields the edit form owns. Receipt, VAT and invoice columns, `source` and
     * `status` are left as they are so an edit never wipes what a scan or notification filled in.
     */
    fun updateTxn(txn: Txn) {
        writableDatabase.update(
            "transactions",
            txnEditValues(txn, System.currentTimeMillis()),
            "id = ? AND owner_id = ?",
            arrayOf(txn.id, txn.ownerId),
        )
    }

    /** Tombstone, never a hard delete. */
    fun deleteTxn(ownerId: String, id: String) {
        writableDatabase.execSQL(
            "UPDATE transactions SET deleted = 1, updated_at = ?, dirty = 1 WHERE id = ? AND owner_id = ?",
            arrayOf<Any>(System.currentTimeMillis(), id, ownerId),
        )
    }

    // --- sync (RFC-003) ------------------------------------------------------
    // These move rows between this database and Supabase. They write `rev`, `dirty` and the
    // server's own `updated_at` as given, so they are only for the sync engine (RULES 10).

    /** [ownerId]'s rows waiting to be pushed, tombstones included, `inbox` transactions never. */
    fun dirtyRows(table: SyncTable, ownerId: String): List<Row> =
        readableDatabase.query(
            table.name, table.pushColumns.names(), SyncSql.dirtyWhere(table), arrayOf(ownerId), null, null, "updated_at, id",
        ).use { c -> buildList { while (c.moveToNext()) add(c.toRow(table.pushColumns)) } }

    /** How many rows of [ownerId] still need to be pushed. */
    fun pendingCount(ownerId: String): Int =
        SyncTables.inOrder.sumOf { table ->
            DatabaseUtils.queryNumEntries(readableDatabase, table.name, SyncSql.dirtyWhere(table), arrayOf(ownerId)).toInt()
        }

    /**
     * Stores rows from the server, each one only when [Lww.shouldApply] says the server copy is at
     * least as new as ours. A stored row takes the server's `rev` and loses its `dirty` flag. The
     * phone-only columns (`status`, `local_photo`) are left alone on update. One transaction.
     * Returns how many rows were stored.
     */
    fun applyRemote(table: SyncTable, rows: List<Row>): Int {
        val db = writableDatabase
        var applied = 0
        db.beginTransaction()
        try {
            for (row in rows) {
                val id = row["id"] as String
                val localUpdatedAt = updatedAtOf(db, table, id)
                if (!Lww.shouldApply(localUpdatedAt, row["updated_at"] as Long)) continue
                val values = ContentValues()
                table.columns.forEach { values.putColumn(it, row[it.name]) }
                values.put("dirty", 0)
                if (localUpdatedAt == null) {
                    db.insertOrThrow(table.name, null, values)
                } else {
                    values.remove("id")
                    db.update(table.name, values, "id = ?", arrayOf(id))
                }
                applied++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return applied
    }

    /** [ownerId]'s live transactions that have a photo on the phone but no uploaded copy yet. */
    fun photosToUpload(ownerId: String): List<PhotoJob> =
        readableDatabase.query(
            "transactions", arrayOf("id", "local_photo"), SyncSql.PHOTOS_WHERE, arrayOf(ownerId), null, null, "updated_at, id",
        ).use { c -> buildList { while (c.moveToNext()) add(PhotoJob(c.text("id"), c.text("local_photo"))) } }

    /** Records an uploaded photo as a normal edit: `updated_at` now, `dirty = 1`. False if there was nothing to set. */
    fun setReceiptPath(ownerId: String, id: String, path: String): Boolean {
        val values = ContentValues().apply {
            put("receipt_path", path)
            put("updated_at", System.currentTimeMillis())
            put("dirty", 1)
        }
        return writableDatabase.update("transactions", values, SyncSql.RECEIPT_PATH_WHERE, arrayOf(id, ownerId)) > 0
    }

    /** `group_id:user_id` of every cached group membership. */
    fun cachedMembers(): Set<String> =
        readableDatabase.query("group_members", arrayOf("group_id", "user_id"), null, null, null, null, null)
            .use { c -> buildSet { while (c.moveToNext()) add("${c.text("group_id")}:${c.text("user_id")}") } }

    /** The group membership cache is read-only on the phone: it is replaced wholesale from the server. */
    fun replaceMembers(rows: List<Row>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("group_members", null, null)
            for (row in rows) {
                val values = ContentValues().apply {
                    put("group_id", row["group_id"] as String)
                    put("user_id", row["user_id"] as String)
                    put("role", row["role"] as String)
                    put("joined_at", row["joined_at"] as String?)
                    put("dirty", 0)
                }
                db.insertOrThrow("group_members", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Drops cached rows that belong to anyone but [ownerId]. Rows with unsynced edits are kept, so
     * signing in as someone else never destroys the first person's unsent entries.
     */
    fun wipeOthers(ownerId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            SyncTables.inOrder.forEach { db.delete(it.name, SyncSql.WIPE_OTHERS_WHERE, arrayOf(ownerId)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun updatedAtOf(db: SQLiteDatabase, table: SyncTable, id: String): Long? =
        db.query(table.name, arrayOf("updated_at"), "id = ?", arrayOf(id), null, null, null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    private fun List<Col>.names(): Array<String> = map { it.name }.toTypedArray()

    private fun ContentValues.putColumn(col: Col, value: Any?) {
        when {
            value == null -> putNull(col.name)
            col.kind == Kind.TEXT -> put(col.name, value as String)
            else -> put(col.name, (value as Number).toLong())
        }
    }

    private fun Cursor.toRow(columns: List<Col>): Row = columns.associate { col ->
        val index = getColumnIndexOrThrow(col.name)
        col.name to when {
            isNull(index) -> null
            col.kind == Kind.TEXT -> getString(index)
            else -> getLong(index)
        }
    }

    // --- mapping -------------------------------------------------------------

    private fun walletValues(w: Wallet, now: Long) = ContentValues().apply {
        put("id", w.id)
        put("owner_id", w.ownerId)
        put("name", w.name)
        put("type", w.type.slug)
        put("opening_balance", w.openingBalance)
        put("deleted", if (w.deleted) 1 else 0)
        put("updated_at", now)
        put("dirty", 1)
    }

    private fun txnEditValues(t: Txn, now: Long) = ContentValues().apply {
        put("type", t.type.slug)
        put("wallet_id", t.walletId)
        put("to_wallet_id", t.toWalletId)
        put("amount", t.amount)
        put("date", t.date)
        put("category", t.category)
        put("merchant", t.merchant)
        put("note", t.note)
        put("is_business", if (t.isBusiness) 1 else 0)
        put("deleted", if (t.deleted) 1 else 0)
        put("updated_at", now)
        put("dirty", 1)
    }

    private fun Cursor.toWallet() = Wallet(
        id = text("id"),
        ownerId = text("owner_id"),
        name = text("name"),
        type = WalletType.fromSlug(text("type")),
        openingBalance = number("opening_balance"),
        updatedAt = number("updated_at"),
        deleted = number("deleted") != 0L,
    )

    private fun Cursor.toTxn() = Txn(
        id = text("id"),
        ownerId = text("owner_id"),
        type = TxnType.fromSlug(text("type")),
        walletId = text("wallet_id"),
        toWalletId = textOrNull("to_wallet_id"),
        amount = number("amount"),
        date = text("date"),
        category = textOrNull("category"),
        merchant = textOrNull("merchant"),
        note = textOrNull("note"),
        isBusiness = number("is_business") != 0L,
        source = text("source"),
        status = text("status"),
        updatedAt = number("updated_at"),
        deleted = number("deleted") != 0L,
    )

    private fun Cursor.text(column: String): String = getString(getColumnIndexOrThrow(column))

    private fun Cursor.textOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.number(column: String): Long = getLong(getColumnIndexOrThrow(column))

    companion object {
        const val NAME = "chimu.db"
    }
}
