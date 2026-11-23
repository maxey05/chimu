package com.chimu

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import java.io.IOException
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
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

    private suspend fun <T> guard(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RestException) {
        val status = e.statusCode
        val text = e.description ?: e.error
        if (status == 401 || status == 408 || status == 429 || status >= 500) {
            throw SyncUnavailable("The server answered $status: $text", e)
        }
        throw SyncRejected(status, "The server refused the change ($status): $text", e)
    } catch (e: HttpRequestException) {
        throw SyncUnavailable("Couldn't reach the server", e)
    } catch (e: IOException) { // timeouts and dropped connections
        throw SyncUnavailable("Couldn't reach the server", e)
    }

    private companion object {
        const val BUCKET = "receipts"
    }
}
