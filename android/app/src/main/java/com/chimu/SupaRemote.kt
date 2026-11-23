package com.chimu

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import io.ktor.client.plugins.timeout
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.io.IOException
import java.math.BigDecimal
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [SyncRemote] over the app's one Supabase client ([Supa]). Everything runs as the signed-in user,
 * so RLS decides what comes back (RULES 12, 13).
 *
 * This is the only place supabase-kt's errors are interpreted. Network trouble, timeouts, 5xx and an
 * expired sign-in become [SyncUnavailable] (retry later); any other HTTP error from the server
 * becomes [SyncRejected] (those rows won't be accepted as they are).
 */
class SupaRemote(private val client: SupabaseClient) : SyncRemote {

    override suspend fun upsert(table: SyncTable, rows: List<Row>): List<Row> = guard {
        val body = rows.map { SyncRows.toJson(table, it) }
        // `select()` makes the server return the rows as stored. When its last-writer-wins trigger
        // discards a write as too old, that is the stored (newer) row, not the one we sent.
        val result = client.postgrest.from(table.name).upsert(body) { select() }
        Json.parseToJsonElement(result.data).jsonArray.map { SyncRows.fromJson(table, it.jsonObject) }
    }

    override suspend fun pull(table: SyncTable, afterRev: Long, limit: Int): List<Row> = guard {
        val result = client.postgrest.from(table.name).select {
            filter { gt("rev", afterRev) }
            order("rev", Order.ASCENDING)
            limit(limit.toLong())
        }
        Json.parseToJsonElement(result.data).jsonArray.map { SyncRows.fromJson(table, it.jsonObject) }
    }

    override suspend fun members(): List<Row> = guard {
        val result = client.postgrest.from("group_members").select(Columns.list("group_id", "user_id", "role", "joined_at"))
        Json.parseToJsonElement(result.data).jsonArray.map { element ->
            val obj = element.jsonObject
            mapOf(
                "group_id" to obj.getValue("group_id").jsonPrimitive.content,
                "user_id" to obj.getValue("user_id").jsonPrimitive.content,
                "role" to obj.getValue("role").jsonPrimitive.content,
                "joined_at" to obj["joined_at"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    override suspend fun groups(): List<Row> = guard {
        val result = client.postgrest.from("groups").select(Columns.list("id", "name", "invite_code", "owner_id", "created_at"))
        Json.parseToJsonElement(result.data).jsonArray.map { element ->
            val obj = element.jsonObject
            mapOf(
                "id" to obj.getValue("id").jsonPrimitive.content,
                "name" to obj.getValue("name").jsonPrimitive.content,
                "invite_code" to obj.getValue("invite_code").jsonPrimitive.content,
                "owner_id" to obj.getValue("owner_id").jsonPrimitive.content,
                "created_at" to obj["created_at"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    override suspend fun profiles(): List<Row> = guard {
        val result = client.postgrest.from("profiles").select(Columns.list("id", "display_name"))
        Json.parseToJsonElement(result.data).jsonArray.map { element ->
            val obj = element.jsonObject
            mapOf(
                "id" to obj.getValue("id").jsonPrimitive.content,
                "display_name" to (obj["display_name"]?.jsonPrimitive?.contentOrNull ?: ""),
            )
        }
    }

    // --- group RPCs (RFC-006). The only way a membership changes (RULES 16). ---------------------

    suspend fun createGroup(name: String): Group =
        groupFrom(rpc("create_group", buildJsonObject { put("name", JsonPrimitive(name)) }))

    suspend fun joinGroup(code: String): Group =
        groupFrom(rpc("join_group", buildJsonObject { put("code", JsonPrimitive(code)) }))

    suspend fun leaveGroup(groupId: String) {
        rpc("leave_group", buildJsonObject { put("group_id", JsonPrimitive(groupId)) })
    }

    suspend fun removeMember(groupId: String, userId: String) {
        rpc(
            "remove_member",
            buildJsonObject {
                put("group_id", JsonPrimitive(groupId))
                put("user_id", JsonPrimitive(userId))
            },
        )
    }

    /** Calls a group function and returns its JSON answer (empty for `void`). A refusal comes back as [SyncRejected] with words for the user. */
    private suspend fun rpc(function: String, params: kotlinx.serialization.json.JsonObject): String =
        guard(describe = ::groupMessage) { client.postgrest.rpc(function, params).data }

    private fun groupFrom(body: String): Group {
        val parsed = Json.parseToJsonElement(body)
        val obj = (if (parsed is kotlinx.serialization.json.JsonArray) parsed.first() else parsed).jsonObject
        return Group(
            id = obj.getValue("id").jsonPrimitive.content,
            name = obj.getValue("name").jsonPrimitive.content,
            inviteCode = obj.getValue("invite_code").jsonPrimitive.content,
            ownerId = obj.getValue("owner_id").jsonPrimitive.content,
        )
    }

    /** PostgREST sends the server's own message (`raise exception ...` in the RPC); [GroupText.serverWords] turns it into words. */
    private fun groupMessage(e: RestException): String =
        GroupText.serverWords(listOfNotNull(e.error, e.description).joinToString(" "))

    override suspend fun uploadPhoto(path: String, bytes: ByteArray) = guard {
        // upsert = true: a retry after a crash between the upload and recording the path must not fail.
        client.storage.from(BUCKET).upload(path, bytes) {
            upsert = true
            contentType = ContentType.Image.JPEG
        }
        Unit
    }

    override suspend fun photoUrl(path: String): String = guard {
        client.storage.from(BUCKET).createSignedUrl(path, 1.hours)
    }

    /** The photo's bytes. RLS decides whose photos the caller may read (own, or a group member's). */
    suspend fun downloadPhoto(path: String): ByteArray = guard {
        client.storage.from(BUCKET).downloadAuthenticated(path)
    }

    override suspend fun extract(path: String): ExtractedReceipt =
        callExtract(buildJsonObject { put("mode", JsonPrimitive("image")); put("path", JsonPrimitive(path)) }.toString())

    /**
     * Reads one notification or SMS with the same function in `text` mode (RFC-005). Only [text] and the
     * source's name go over the wire. Errors are the same as [extract]: [SyncUnavailable] means try later.
     */
    suspend fun read(text: String, source: String): ExtractedReceipt =
        callExtract(
            buildJsonObject {
                put("mode", JsonPrimitive("text"))
                put("text", JsonPrimitive(text))
                put("source", JsonPrimitive(source))
            }.toString(),
        )

    private suspend fun callExtract(requestBody: String): ExtractedReceipt {
        val body = guard(describe = ::extractMessage) {
            client.functions.invoke("extract") {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
                // The client's default request timeout is 10 s. Reading a photo takes longer than that.
                timeout {
                    requestTimeoutMillis = EXTRACT_TIMEOUT_MS
                    socketTimeoutMillis = EXTRACT_TIMEOUT_MS
                }
            }.bodyAsText()
        }
        return try {
            val fields = Json.parseToJsonElement(body).jsonObject.mapValues { (_, value) ->
                when {
                    value is JsonNull -> null
                    value is JsonPrimitive && value.isString -> value.content
                    value is JsonPrimitive -> BigDecimal(value.content) // a JSON number, kept exact
                    else -> null
                }
            }
            ReceiptReader.parse(fields)
        } catch (e: IllegalArgumentException) { // a reply that isn't JSON, or has none of the fields (also NumberFormatException)
            throw SyncRejected(422, "The receipt reader sent back something the app couldn't understand.", e)
        }
    }

    /** The function's errors are `{"error": code, "message": words}`; the words are written for the user. */
    private fun extractMessage(e: RestException): String {
        val words = runCatching { Json.parseToJsonElement(e.error).jsonObject["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        return words ?: "The receipt couldn't be read (${e.statusCode})."
    }

    private suspend fun <T> guard(
        describe: ((RestException) -> String)? = null,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RestException) {
        val status = e.statusCode
        val text = describe?.invoke(e) ?: e.description ?: e.error
        if (status == 401 || status == 408 || status == 429 || status >= 500) {
            throw SyncUnavailable("The server answered $status: $text", e)
        }
        // A caller with its own words for the user (extract) gets them as they are; the rest get a technical line.
        throw SyncRejected(status, if (describe != null) text else "The server refused the change ($status): $text", e)
    } catch (e: HttpRequestException) {
        throw SyncUnavailable("Couldn't reach the server", e)
    } catch (e: IOException) { // timeouts and dropped connections
        throw SyncUnavailable("Couldn't reach the server", e)
    }

    private companion object {
        const val BUCKET = "receipts"

        /** Longer than the function's own 60 s wait for the model, so its answer is the one that gets back. */
        const val EXTRACT_TIMEOUT_MS = 75_000L
    }
}

/** [GroupApi] over the app's one Supabase client, built only when used so a phone without keys never creates it. */
object SupaGroupApi : GroupApi {
    override suspend fun createGroup(name: String) = SupaRemote(Supa.client).createGroup(name)

    override suspend fun joinGroup(code: String) = SupaRemote(Supa.client).joinGroup(code)

    override suspend fun leaveGroup(groupId: String) = SupaRemote(Supa.client).leaveGroup(groupId)

    override suspend fun removeMember(groupId: String, userId: String) =
        SupaRemote(Supa.client).removeMember(groupId, userId)
}
