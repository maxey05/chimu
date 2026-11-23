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
        // Each step runs in the same change as the matching Supabase migration (if any) and the
        // DESIGN.md update (RULES 11). Steps are cumulative, so an old install walks through all of them.
        if (oldVersion < 2) Schema.UPGRADE_1_TO_2.forEach { db.execSQL(it) }
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
            put("local_photo", txn.localPhoto) // set for a scan (RFC-004); null for a manual entry
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

    /** One transaction by id, tombstones included. Null if the phone has no such row for [ownerId]. */
    fun txn(ownerId: String, id: String): Txn? =
        readableDatabase.query(
            "transactions", null, "id = ? AND owner_id = ?", arrayOf(id, ownerId), null, null, null,
        ).use { c -> if (c.moveToFirst()) c.toTxn() else null }

    /**
     * Saves the Review screen: the edit fields plus `status = confirmed`, and clears any extraction
     * error. The receipt, VAT and invoice columns stay as the reader filled them. A normal edit
     * (`updated_at` now, `dirty = 1`) so it syncs.
     */
    fun confirmTxn(txn: Txn) {
        val values = txnEditValues(txn, System.currentTimeMillis()).apply {
            put("status", "confirmed")
            putNull("extract_error")
        }
        writableDatabase.update("transactions", values, "id = ? AND owner_id = ?", arrayOf(txn.id, txn.ownerId))
    }

    /**
     * Joins a scan to an existing payment ("attach to this payment", RFC-004). In one database
     * transaction the payment takes the scan's photo and receipt fields
     * (a normal edit), and the scan row is tombstoned so it syncs as deleted. The photo file stays
     * where it is: the payment now owns it. Only fills what the payment lacks, and never replaces a
     * photo it already has. Returns false (and changes nothing) if either row is missing or the payment already has a photo.
     */
    fun attachScan(ownerId: String, scanId: String, paymentId: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val scan = txn(ownerId, scanId)
            val payment = txn(ownerId, paymentId)
            if (scan == null || payment == null || scan.deleted || payment.deleted) return false
            if (scanId == paymentId || !payment.receiptPath.isNullOrEmpty() || !payment.localPhoto.isNullOrEmpty()) return false
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("local_photo", scan.localPhoto)
                // The uploaded copy belongs to the scan's id, so the payment uploads its own copy under its own id.
                putNull("receipt_path")
                put("ref_no", payment.refNo ?: scan.refNo)
                put("seller_tin", payment.sellerTin ?: scan.sellerTin)
                put("invoice_no", payment.invoiceNo ?: scan.invoiceNo)
                putLong("vatable_sales", payment.vatableSales ?: scan.vatableSales)
                putLong("vat", payment.vat ?: scan.vat)
                putLong("vat_exempt", payment.vatExempt ?: scan.vatExempt)
                put("merchant", payment.merchant ?: scan.merchant)
                put("category", payment.category ?: scan.category)
                put("updated_at", now)
                put("dirty", 1)
            }
            db.update("transactions", values, "id = ? AND owner_id = ?", arrayOf(paymentId, ownerId))
            db.execSQL(
                "UPDATE transactions SET deleted = 1, updated_at = ?, dirty = 1, local_photo = NULL WHERE id = ? AND owner_id = ?",
                arrayOf<Any>(now, scanId, ownerId),
            )
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    // --- receipt scanning (RFC-004) ------------------------------------------

    /** Scans that are ready to be read: photo uploaded, still `pending`. Failed ones only when [includeFailed]. */
    fun pendingExtractions(ownerId: String, includeFailed: Boolean): List<ExtractJob> =
        readableDatabase.query(
            "transactions", arrayOf("id", "receipt_path"), SyncSql.extractWhere(includeFailed), arrayOf(ownerId), null, null, "updated_at, id",
        ).use { c -> buildList { while (c.moveToNext()) add(ExtractJob(c.text("id"), c.text("receipt_path"))) } }

    /**
     * Fills a waiting scan with what the reader found and marks it `extracted`, as a normal edit
     * (`updated_at` now, `dirty = 1`). Fields the reader couldn't find keep their placeholder
     * value (amount 0, today's date) for the Review screen to highlight. False if the scan is no longer
     * waiting: the user may have filled it in by hand in the meantime, and that wins.
     */
    fun applyExtraction(ownerId: String, id: String, r: ExtractedReceipt): Boolean {
        val values = ContentValues().apply {
            put("type", r.type.slug)
            if (r.amount != null) put("amount", r.amount)
            if (r.date != null) put("date", r.date)
            put("merchant", r.merchant)
            put("category", r.category)
            put("ref_no", r.refNo)
            put("seller_tin", r.sellerTin)
            put("invoice_no", r.invoiceNo)
            putLong("vatable_sales", r.vatableSales)
            putLong("vat", r.vat)
            putLong("vat_exempt", r.vatExempt)
            put("status", "extracted")
            putNull("extract_error")
            put("updated_at", System.currentTimeMillis())
            put("dirty", 1)
        }
        return writableDatabase.update("transactions", values, SyncSql.EXTRACT_APPLY_WHERE, arrayOf(id, ownerId)) > 0
    }

    /** Remembers why reading failed. Phone only: it changes neither `updated_at` nor `dirty`. */
    fun markExtractionFailed(ownerId: String, id: String, message: String) {
        val values = ContentValues().apply { put("extract_error", message.take(300)) }
        writableDatabase.update("transactions", values, SyncSql.EXTRACT_APPLY_WHERE, arrayOf(id, ownerId))
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
     * phone-only columns (`status`, `local_photo`, `extract_error`) are left alone on update. One transaction.
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

    private fun ContentValues.putLong(key: String, value: Long?) {
        if (value == null) putNull(key) else put(key, value)
    }

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
        refNo = textOrNull("ref_no"),
        receiptPath = textOrNull("receipt_path"),
        sellerTin = textOrNull("seller_tin"),
        invoiceNo = textOrNull("invoice_no"),
        vatableSales = numberOrNull("vatable_sales"),
        vat = numberOrNull("vat"),
        vatExempt = numberOrNull("vat_exempt"),
        localPhoto = textOrNull("local_photo"),
        extractError = textOrNull("extract_error"),
    )

    private fun Cursor.text(column: String): String = getString(getColumnIndexOrThrow(column))

    private fun Cursor.textOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.number(column: String): Long = getLong(getColumnIndexOrThrow(column))

    private fun Cursor.numberOrNull(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    companion object {
        const val NAME = "chimu.db"
    }
}
