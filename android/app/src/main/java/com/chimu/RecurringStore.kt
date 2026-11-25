package com.chimu

import android.content.ContentValues
import android.database.Cursor
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The phone's `recurring` table (RFC-010): reads and writes of one user's rules. Kept apart from [Db] and [Repo] on purpose, so
 * the recurring feature is one set of files. Every write sets `updated_at` to now and `dirty = 1`, so the sync engine pushes
 * it (RULES 8, 10); nothing is ever hard-deleted (RULES 9). Every query is scoped by `owner_id` (group sync caches other
 * people's rules in the same table). All SQL is parameterised (RULES 18). Calls block on disk: run them off the main thread.
 */
class RecurringStore(private val db: Db) {
    /** [owner]'s live rules, soonest due first. A row this build can't read (an unknown frequency) is left out, not a crash. */
    fun rules(owner: String): List<RecurringRule> =
        db.readableDatabase.query("recurring", null, RecurringSql.LIVE_WHERE, arrayOf(owner), null, null, "next_date, id")
            .use { c -> buildList { while (c.moveToNext()) c.toRule()?.let { add(it) } } }

    fun find(owner: String, id: String): RecurringRule? = rules(owner).firstOrNull { it.id == id }

    fun insert(rule: RecurringRule) {
        val values = editValues(rule, System.currentTimeMillis()).apply {
            put("id", rule.id)
            put("owner_id", rule.ownerId)
            put("deleted", 0)
        }
        db.writableDatabase.insertOrThrow("recurring", null, values)
    }

    /** Updates everything the form can change; id and owner never change. Returns false when there was no such row of the owner's. */
    fun update(rule: RecurringRule): Boolean =
        db.writableDatabase.update(
            "recurring", editValues(rule, System.currentTimeMillis()), RecurringSql.OWN_ROW_WHERE, arrayOf(rule.id, rule.ownerId),
        ) > 0

    /** Tombstones one of [owner]'s own rules so it syncs as deleted. Never a hard delete (RULES 9). */
    fun tombstone(owner: String, id: String) {
        db.writableDatabase.execSQL(RecurringSql.TOMBSTONE, arrayOf<Any>(System.currentTimeMillis(), id, owner))
    }

    /** Ids of the rules that have a transaction dated [date], i.e. whose due date has just been logged. */
    fun loggedOn(owner: String, date: String): Set<String> =
        db.readableDatabase.rawQuery(RecurringSql.LOGGED_ON, arrayOf(owner, date))
            .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    private fun editValues(rule: RecurringRule, now: Long) = ContentValues().apply {
        put("updated_at", now)
        put("dirty", 1)
        put("type", rule.type.slug)
        put("wallet_id", rule.walletId)
        put("amount", rule.amount)
        put("category", rule.category)
        put("merchant", rule.merchant)
        put("note", rule.note)
        put("frequency", rule.frequency.slug)
        put("next_date", rule.nextDate)
        put("remind_days_before", rule.remindDaysBefore)
        put("active", if (rule.active) 1 else 0)
        if (rule.anchorDay == null) putNull("anchor_day") else put("anchor_day", rule.anchorDay)
    }

    private fun Cursor.toRule(): RecurringRule? {
        val frequency = RecurringFrequency.fromSlug(string("frequency")) ?: return null
        val type = TxnType.entries.firstOrNull { it.slug == string("type") && it != TxnType.TRANSFER } ?: return null
        return RecurringRule(
            id = string("id"),
            ownerId = string("owner_id"),
            type = type,
            walletId = string("wallet_id"),
            amount = long("amount"),
            category = stringOrNull("category"),
            merchant = stringOrNull("merchant"),
            note = stringOrNull("note"),
            frequency = frequency,
            nextDate = string("next_date"),
            remindDaysBefore = long("remind_days_before").toInt(),
            active = long("active") != 0L,
            anchorDay = longOrNull("anchor_day")?.toInt(),
            updatedAt = long("updated_at"),
            deleted = long("deleted") != 0L,
        )
    }

    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))

    private fun Cursor.stringOrNull(column: String): String? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }

    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

    private fun Cursor.longOrNull(column: String): Long? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }
}

/**
 * What the Recurring screens read and write: the open user's rules as a flow, and the edits. A failure is thrown, never swallowed:
 * the caller shows it and the form keeps what the user typed (RULES 20). The caller passes the signed-in user's id, so signing
 * out and in as someone else never shows or edits the first person's rules.
 */
class RecurringRepo(
    private val store: RecurringStore,
    /** Called after every local write, so the sync engine can send it soon (RFC-003). */
    private val onChanged: () -> Unit = {},
) {
    private val _rules = MutableStateFlow<List<RecurringRule>>(emptyList())

    /** The rules last loaded by [load], in display order. Always show them through [RecurringRules.visible] for the current user. */
    val rules: StateFlow<List<RecurringRule>> = _rules.asStateFlow()

    /** Reads [owner]'s rules again. Call it when a screen opens and after a sync changed the database underneath. */
    suspend fun load(owner: String) = withContext(Dispatchers.IO) {
        _rules.value = RecurringRules.sorted(store.rules(owner))
    }

    /** Adds or edits one of [owner]'s own rules. Throws [IllegalArgumentException] with words for the user when it is someone else's. */
    suspend fun save(owner: String, rule: RecurringRule) = withContext(Dispatchers.IO) {
        require(rule.ownerId == owner) { "That item belongs to someone else, so you can't change it." }
        if (store.find(owner, rule.id) != null) {
            check(store.update(rule)) { "That item is gone. Go back and try again." }
        } else {
            store.insert(rule)
        }
        load(owner)
        onChanged()
    }

    /**
     * Pauses or resumes one of [owner]'s rules. Resuming skips the dates that passed while it was paused ([RecurringRules.resume]).
     * Returns false when the rule is gone.
     */
    suspend fun setActive(owner: String, id: String, active: Boolean, today: LocalDate): Boolean = withContext(Dispatchers.IO) {
        val rule = store.find(owner, id) ?: return@withContext false
        val changed = if (active) RecurringRules.resume(rule, today) else rule.copy(active = false)
        store.update(changed)
        load(owner)
        onChanged()
        true
    }

    /** Deletes one of [owner]'s own rules (a tombstone, so it syncs). The entries it already logged stay. */
    suspend fun delete(owner: String, id: String) = withContext(Dispatchers.IO) {
        store.tombstone(owner, id)
        load(owner)
        onChanged()
    }

    /** What the daily reminder job needs for [owner]: their rules, and the ids of those whose due date was logged [today]. Blocking. */
    fun forReminders(owner: String, today: LocalDate): Pair<List<RecurringRule>, Set<String>> =
        store.rules(owner) to store.loggedOn(owner, today.toString())
}
