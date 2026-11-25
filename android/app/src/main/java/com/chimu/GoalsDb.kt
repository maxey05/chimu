package com.chimu

import android.content.ContentValues
import android.database.Cursor

/*
 * Goal queries for the phone's SQLite (RFC-011), kept out of Db.kt. The `goals` table and its sync have existed since
 * RFC-001 / RFC-003, so there is no schema change. Like every write helper in Db, these set `updated_at` to now and
 * `dirty = 1` so the sync engine pushes them (RULES 8, 10), never hard-delete (RULES 9), and use parameters only (RULES 18).
 */

/** [ownerId]'s live goals. Other members' goals can be cached too (RFC-006) and are not returned. */
fun Db.goals(ownerId: String): List<Goal> =
    readableDatabase.query("goals", null, GoalSql.OWN_LIVE_WHERE, arrayOf(ownerId), null, null, null)
        .use { c -> buildList { while (c.moveToNext()) add(c.toGoal()) } }

fun Db.insertGoal(goal: Goal) {
    val values = goalEditValues(goal, System.currentTimeMillis()).apply {
        put("id", goal.id)
        put("owner_id", goal.ownerId)
        put("deleted", 0)
    }
    writableDatabase.insertOrThrow("goals", null, values)
}

/** Updates name, target, deadline and wallet; id, owner and the tombstone never change here. */
fun Db.updateGoal(goal: Goal) {
    writableDatabase.update(
        "goals",
        goalEditValues(goal, System.currentTimeMillis()),
        GoalSql.OWN_ROW_WHERE,
        arrayOf(goal.id, goal.ownerId),
    )
}

/** Tombstone one of [ownerId]'s own goals so it syncs as deleted (RULES 9). Never a hard delete. */
fun Db.deleteGoal(ownerId: String, id: String) {
    writableDatabase.execSQL(GoalSql.TOMBSTONE, arrayOf<Any>(System.currentTimeMillis(), id, ownerId))
}

/**
 * Saves a goal and, when [newWallet] is given, the savings wallet it points at, in ONE database transaction: either both
 * are stored or neither is, so a failure never leaves an orphan wallet behind. [walletExists] says the wallet row is
 * already there (a retry), which makes it an update instead of an insert.
 */
fun Db.saveGoal(goal: Goal, isNew: Boolean, newWallet: Wallet?, walletExists: Boolean) {
    val db = writableDatabase
    db.beginTransaction()
    try {
        if (newWallet != null) {
            if (walletExists) updateWallet(newWallet) else insertWallet(newWallet)
        }
        if (isNew) insertGoal(goal) else updateGoal(goal)
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

private fun goalEditValues(g: Goal, now: Long) = ContentValues().apply {
    put("name", g.name)
    put("target", g.target)
    put("deadline", g.deadline)
    put("wallet_id", g.walletId)
    put("updated_at", now)
    put("dirty", 1)
}

private fun Cursor.toGoal(): Goal {
    val deadlineIndex = getColumnIndexOrThrow("deadline")
    return Goal(
        id = getString(getColumnIndexOrThrow("id")),
        ownerId = getString(getColumnIndexOrThrow("owner_id")),
        name = getString(getColumnIndexOrThrow("name")),
        target = getLong(getColumnIndexOrThrow("target")),
        deadline = if (isNull(deadlineIndex)) null else getString(deadlineIndex),
        walletId = getString(getColumnIndexOrThrow("wallet_id")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
        deleted = getLong(getColumnIndexOrThrow("deleted")) != 0L,
    )
}
