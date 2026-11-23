package com.chimu

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The phone's local SQLite database, the offline source of truth (DESIGN.md -> Architecture).
 *
 * Every write helper sets `updated_at` to now and `dirty = 1` so the sync engine (RFC-003) knows
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
