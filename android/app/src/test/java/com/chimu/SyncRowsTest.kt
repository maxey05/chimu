package com.chimu

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncRowsTest {
    private val serverTransaction: JsonObject = buildJsonObject {
        put("id", "11111111-1111-4111-8111-111111111111")
        put("owner_id", "22222222-2222-4222-8222-222222222222")
        put("updated_at", 1_795_000_000_123L)
        put("deleted", false)
        put("rev", 42L)
        put("type", "expense")
        put("wallet_id", "33333333-3333-4333-8333-333333333333")
        put("to_wallet_id", JsonNull)
        put("amount", 123_450L)
        put("date", "2026-11-10")
        put("category", "food")
        put("merchant", "Mang Inasal")
        put("note", JsonNull)
        put("is_business", true)
        put("source", "receipt")
        put("ref_no", JsonNull)
        put("recurring_id", JsonNull)
        put("receipt_path", "22222222-2222-4222-8222-222222222222/11111111-1111-4111-8111-111111111111.jpg")
        put("seller_tin", "123-456-789-00000")
        put("invoice_no", "SI-0001")
        put("vatable_sales", 110_223L)
        put("vat", 13_227L)
        put("vat_exempt", JsonNull)
    }

    // --- server JSON -> phone row ------------------------------------------------

    @Test
    fun serverJson_becomesALocalRow() {
        val row = SyncRows.fromJson(SyncTables.transactions, serverTransaction)

        assertEquals("11111111-1111-4111-8111-111111111111", row["id"])
        assertEquals("bigint stays a Long, centavos untouched", 123_450L, row["amount"])
        assertEquals(1_795_000_000_123L, row["updated_at"])
        assertEquals("false is stored as 0", 0L, row["deleted"])
        assertEquals("true is stored as 1", 1L, row["is_business"])
        assertEquals("dates stay text", "2026-11-10", row["date"])
        assertNull(row["note"])
        assertEquals(13_227L, row["vat"])
        assertNull(row["vat_exempt"])
        assertEquals(42L, row["rev"])
    }

    @Test
    fun aRequiredColumnThatIsMissing_isRefusedNotStoredHalfUnderstood() {
        val broken = JsonObject(serverTransaction - "amount")
        try {
            SyncRows.fromJson(SyncTables.transactions, broken)
            fail("expected a refusal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.contains("transactions.amount"))
        }
    }

    @Test
    fun aRequiredColumnThatIsNull_isRefused() {
        val broken = JsonObject(serverTransaction + ("date" to JsonNull))
        try {
            SyncRows.fromJson(SyncTables.transactions, broken)
            fail("expected a refusal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("transactions.date"))
        }
    }

    @Test
    fun aNumberColumnThatIsNotANumber_isRefused() {
        val broken = JsonObject(serverTransaction + ("amount" to JsonPrimitive("lots")))
        try {
            SyncRows.fromJson(SyncTables.transactions, broken)
            fail("expected a refusal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("transactions.amount"))
        }
    }

    @Test
    fun aFlagColumnThatIsNotABoolean_isRefused() {
        val broken = JsonObject(serverTransaction + ("is_business" to JsonPrimitive("maybe")))
        try {
            SyncRows.fromJson(SyncTables.transactions, broken)
            fail("expected a refusal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("transactions.is_business"))
        }
    }

    @Test
    fun columnsTheServerAddsLater_areIgnored() {
        val row = SyncRows.fromJson(SyncTables.transactions, JsonObject(serverTransaction + ("brand_new" to JsonPrimitive("x"))))
        assertFalse("brand_new" in row)
    }

    // --- phone row -> server JSON --------------------------------------------------

    @Test
    fun aRowSentToTheServer_roundTripsExactly() {
        val row = SyncRows.fromJson(SyncTables.transactions, serverTransaction)

        val json = SyncRows.toJson(SyncTables.transactions, row)

        assertEquals("everything except rev comes back identical", JsonObject(serverTransaction - "rev"), json)
    }

    @Test
    fun theServersOwnColumnsAndThePhonesBookkeeping_neverGoUp() {
        val row = SyncRows.fromJson(SyncTables.transactions, serverTransaction) + mapOf(
            "dirty" to 1L, "status" to "inbox", "local_photo" to "/data/x.jpg",
        )

        val json = SyncRows.toJson(SyncTables.transactions, row)

        assertFalse("rev is set by the server's trigger", "rev" in json)
        assertFalse("dirty" in json)
        assertFalse("status" in json)
        assertFalse("local_photo" in json)
    }

    @Test
    fun aClearedField_goesUpAsAnExplicitNull() {
        val row = SyncRows.fromJson(SyncTables.transactions, serverTransaction) + ("receipt_path" to null)

        val json = SyncRows.toJson(SyncTables.transactions, row)

        assertEquals("so clearing a note or a path on the phone clears it on the server too", JsonNull, json["receipt_path"])
    }

    @Test
    fun booleans_goUpAsRealBooleans() {
        val json = SyncRows.toJson(SyncTables.transactions, SyncRows.fromJson(SyncTables.transactions, serverTransaction))

        assertEquals(JsonPrimitive(true), json["is_business"])
        assertEquals(JsonPrimitive(false), json["deleted"])
    }

    @Test
    fun aNullInARequiredColumn_isRefusedBeforeItGoesUp() {
        val row = SyncRows.fromJson(SyncTables.transactions, serverTransaction) + ("amount" to null)
        try {
            SyncRows.toJson(SyncTables.transactions, row)
            fail("expected a refusal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("transactions.amount"))
        }
    }

    @Test
    fun everySyncTable_roundTripsItsOwnShape() {
        for (table in SyncTables.inOrder) {
            val json = buildJsonObject {
                table.columns.forEach { col ->
                    when (col.kind) {
                        Kind.TEXT -> put(col.name, "x-${col.name}")
                        Kind.INT -> put(col.name, 7L)
                        Kind.BOOL -> put(col.name, true)
                    }
                }
            }
            val sent = SyncRows.toJson(table, SyncRows.fromJson(table, json))
            assertEquals(table.name, JsonObject(json - "rev"), sent)
        }
    }

    // --- the table list agrees with the phone's own schema -----------------------------

    /** `CREATE TABLE name ( col TYPE ..., ... )` -> name to its column names, read from [Schema] itself. */
    private fun schemaColumns(): Map<String, List<String>> =
        Schema.CREATE_STATEMENTS.filter { it.startsWith("CREATE TABLE") }.associate { sql ->
            val name = Regex("""CREATE TABLE (\w+)""").find(sql)!!.groupValues[1]
            val body = sql.substringAfter("(").substringBeforeLast(")")
            val cols = body.lines().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("PRIMARY KEY") }
                .map { it.substringBefore(" ") }
            name to cols
        }

    @Test
    fun everySyncedColumn_existsInThePhoneDatabase_andNothingIsForgotten() {
        val local = schemaColumns()
        val phoneOnly = setOf("dirty", "status", "local_photo")
        for (table in SyncTables.inOrder) {
            val phone = local.getValue(table.name)
            val synced = table.columns.map { it.name }
            assertTrue("${table.name}: ${synced - phone.toSet()} not in the local schema", phone.containsAll(synced))
            assertEquals(
                "${table.name}: local columns that are neither synced nor phone-only",
                emptySet<String>(),
                phone.toSet() - synced.toSet() - phoneOnly,
            )
        }
    }

    @Test
    fun tablesAreSyncedInDependencyOrder() {
        assertEquals(
            listOf("wallets", "transactions", "budgets", "recurring", "goals"),
            SyncTables.inOrder.map { it.name },
        )
    }

    @Test
    fun onlyTransactionsHoldInboxItems() {
        assertEquals(listOf("transactions"), SyncTables.inOrder.filter { it.holdsInbox }.map { it.name })
    }

    @Test
    fun theWhereClausesKeepInboxItemsHome() {
        assertEquals("owner_id = ? AND dirty = 1 AND status <> 'inbox'", SyncSql.dirtyWhere(SyncTables.transactions))
        assertEquals("owner_id = ? AND dirty = 1", SyncSql.dirtyWhere(SyncTables.wallets))
        assertTrue(SyncSql.PHOTOS_WHERE.contains("status <> 'inbox'"))
    }
}
