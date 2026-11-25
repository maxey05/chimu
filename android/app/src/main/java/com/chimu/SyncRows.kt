package com.chimu

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * One row as the sync engine sees it: column name to value, in the phone's local representation.
 * Values are `String`, `Long` or null. Booleans are `1L` / `0L`, exactly as stored in SQLite.
 */
typealias Row = Map<String, Any?>

enum class Kind { TEXT, INT, BOOL }

/** A column that exists both on the server and in the phone's SQLite table. */
class Col(val name: String, val kind: Kind, val nullable: Boolean = true)

/**
 * A syncable table: the server columns it shares with the phone, in DESIGN.md -> Data model order.
 *
 * `rev` is stamped by the server and only ever read here. The phone-only columns (`dirty`, and
 * `status` / `local_photo` on transactions) are not part of [columns]: they never go to the server
 * and a pulled row never overwrites them.
 *
 * @property holdsInbox true when the table has a `status` column where `inbox` rows (auto-logged,
 *   unconfirmed) must stay on the phone (RULES 17).
 */
class SyncTable(
    val name: String,
    private val own: List<Col>,
    val holdsInbox: Boolean = false,
) {
    /** Every column the server and the phone share, conventions first. */
    val columns: List<Col> = COMMON + own

    /** The columns sent on push: everything except `rev`, which the server's trigger sets. */
    val pushColumns: List<Col> = columns.filter { it.name != "rev" }

    private companion object {
        val COMMON = listOf(
            Col("id", Kind.TEXT, nullable = false),
            Col("owner_id", Kind.TEXT, nullable = false),
            Col("updated_at", Kind.INT, nullable = false),
            Col("deleted", Kind.BOOL, nullable = false),
            Col("rev", Kind.INT, nullable = false),
        )
    }
}

/** The five syncable tables, in the order DESIGN.md -> Sync protocol says to move them. */
object SyncTables {
    val wallets = SyncTable(
        "wallets",
        listOf(
            Col("name", Kind.TEXT, nullable = false),
            Col("type", Kind.TEXT, nullable = false),
            Col("opening_balance", Kind.INT, nullable = false),
        ),
    )

    val transactions = SyncTable(
        "transactions",
        listOf(
            Col("type", Kind.TEXT, nullable = false),
            Col("wallet_id", Kind.TEXT, nullable = false),
            Col("to_wallet_id", Kind.TEXT),
            Col("amount", Kind.INT, nullable = false),
            Col("date", Kind.TEXT, nullable = false),
            Col("category", Kind.TEXT),
            Col("merchant", Kind.TEXT),
            Col("note", Kind.TEXT),
            Col("is_business", Kind.BOOL, nullable = false),
            Col("source", Kind.TEXT, nullable = false),
            Col("ref_no", Kind.TEXT),
            Col("recurring_id", Kind.TEXT),
            Col("receipt_path", Kind.TEXT),
            Col("seller_tin", Kind.TEXT),
            Col("invoice_no", Kind.TEXT),
            Col("vatable_sales", Kind.INT),
            Col("vat", Kind.INT),
            Col("vat_exempt", Kind.INT),
        ),
        holdsInbox = true,
    )

    val budgets = SyncTable(
        "budgets",
        listOf(
            Col("group_id", Kind.TEXT),
            Col("category", Kind.TEXT),
            Col("monthly_limit", Kind.INT, nullable = false),
        ),
    )

    val recurring = SyncTable(
        "recurring",
        listOf(
            Col("type", Kind.TEXT, nullable = false),
            Col("wallet_id", Kind.TEXT, nullable = false),
            Col("amount", Kind.INT, nullable = false),
            Col("category", Kind.TEXT),
            Col("merchant", Kind.TEXT),
            Col("note", Kind.TEXT),
            Col("frequency", Kind.TEXT, nullable = false),
            Col("next_date", Kind.TEXT, nullable = false),
            Col("remind_days_before", Kind.INT, nullable = false),
            Col("active", Kind.BOOL, nullable = false),
            Col("anchor_day", Kind.INT),
        ),
    )

    val goals = SyncTable(
        "goals",
        listOf(
            Col("name", Kind.TEXT, nullable = false),
            Col("target", Kind.INT, nullable = false),
            Col("deadline", Kind.TEXT),
            Col("wallet_id", Kind.TEXT, nullable = false),
        ),
    )

    /** Dependency order: a wallet must exist on the server before the rows that point at it. */
    val inOrder: List<SyncTable> = listOf(wallets, transactions, budgets, recurring, goals)
}

/**
 * Converts between the server's JSON and the phone's [Row]s. Pure, so the type rules are tested
 * without a network: bigint stays a `Long`, booleans become 0/1, dates stay `YYYY-MM-DD` text.
 *
 * A server row that breaks the table's shape (a required column missing or the wrong type) throws
 * instead of being stored half-understood. The sync stops with the data and cursor untouched.
 */
object SyncRows {
    fun fromJson(table: SyncTable, json: JsonObject): Row {
        val row = LinkedHashMap<String, Any?>()
        for (col in table.columns) {
            val element = json[col.name]
            if (element == null || element is JsonNull) {
                require(col.nullable) { "${table.name}.${col.name} is missing in a server row" }
                row[col.name] = null
                continue
            }
            row[col.name] = try {
                val primitive = element.jsonPrimitive
                when (col.kind) {
                    Kind.TEXT -> primitive.content
                    Kind.INT -> primitive.long
                    Kind.BOOL -> if (primitive.boolean) 1L else 0L
                }
            } catch (e: IllegalArgumentException) { // also NumberFormatException
                throw IllegalArgumentException("${table.name}.${col.name} has an unexpected value in a server row", e)
            } catch (e: IllegalStateException) { // JsonPrimitive.boolean on a non-boolean
                throw IllegalArgumentException("${table.name}.${col.name} has an unexpected value in a server row", e)
            }
        }
        return row
    }

    /** The JSON sent on push: [SyncTable.pushColumns] only. Nulls are sent as explicit JSON nulls so a cleared field syncs. */
    fun toJson(table: SyncTable, row: Row): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>()
        for (col in table.pushColumns) {
            val value = row[col.name]
            fields[col.name] = when {
                value == null -> {
                    require(col.nullable) { "${table.name}.${col.name} is null in a local row" }
                    JsonNull
                }
                col.kind == Kind.TEXT -> JsonPrimitive(value as String)
                col.kind == Kind.INT -> JsonPrimitive((value as Number).toLong())
                else -> JsonPrimitive((value as Number).toLong() != 0L)
            }
        }
        return JsonObject(fields)
    }
}

/**
 * The `WHERE` clauses the phone's database uses for sync, kept as plain strings so they can be
 * checked against a real SQLite without Android. Only plain SQLite 3.18 syntax (Android 8, minSdk 26):
 * no `ON CONFLICT DO UPDATE`, no `RETURNING`.
 */
object SyncSql {
    /** Rows to push for one owner: edited since the last push, and never `inbox` items (RULES 17). Args: owner id. */
    fun dirtyWhere(table: SyncTable): String =
        "owner_id = ? AND dirty = 1" + if (table.holdsInbox) " AND status <> 'inbox'" else ""

    /** Other people's cached rows with no unsynced edits of their own. Args: owner id. */
    const val WIPE_OTHERS_WHERE = "owner_id <> ? AND dirty = 0"

    /** Receipt photos still to upload. Args: owner id. */
    const val PHOTOS_WHERE =
        "owner_id = ? AND deleted = 0 AND status <> 'inbox' AND local_photo IS NOT NULL AND local_photo <> '' AND receipt_path IS NULL"

    /**
     * Scans whose photo is up and that still wait to be read. Args: owner id. A scan that failed
     * (`extract_error` set) is only included when the caller asks to retry, see [extractWhere].
     */
    fun extractWhere(includeFailed: Boolean): String =
        "owner_id = ? AND deleted = 0 AND source = 'receipt' AND status = 'pending' AND receipt_path IS NOT NULL AND receipt_path <> ''" +
            if (includeFailed) "" else " AND extract_error IS NULL"

    /** Guards [Db.applyExtraction]: the scan must still be waiting. Args: id, owner id. */
    const val EXTRACT_APPLY_WHERE = "id = ? AND owner_id = ? AND deleted = 0 AND status = 'pending'"

    /** Guards [Db.setReceiptPath] so an existing path is never replaced. Args: id, owner id. */
    const val RECEIPT_PATH_WHERE = "id = ? AND owner_id = ? AND receipt_path IS NULL"
}
