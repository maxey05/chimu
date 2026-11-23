package com.chimu

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a photo from a signed link (RFC-006: other members' receipt photos are fetched this way,
 * DESIGN.md -> Sync protocol step 5). Plain `java.net`, no library. The link carries its own permission,
 * so no sign-in header is sent.
 */
object SignedLink {
    /** The `receipts` bucket takes 5 MB at most (supabase/migrations/0002_storage.sql); anything bigger isn't one of our photos. */
    const val MAX_BYTES = 6 * 1024 * 1024
    const val TIMEOUT_MS = 20_000

    /**
     * The bytes behind [link]. A [SyncUnavailable] means try again later (no connection, a timeout, a server
     * fault, an expired link); a [SyncRejected] means the link won't ever give a photo.
     */
    fun fetch(link: String): ByteArray {
        val connection = try {
            URL(link).openConnection() as HttpURLConnection
        } catch (e: Exception) { // a malformed link or a scheme that isn't http(s)
            throw SyncRejected(400, "The photo's link isn't valid.", e)
        }
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            val status = connection.responseCode
            if (status == 401 || status == 408 || status == 429 || status >= 500) {
                throw SyncUnavailable("The photo server answered $status")
            }
            if (status != 200) throw SyncRejected(status, "The photo server answered $status")
            return connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_BYTES) throw SyncRejected(413, "The photo is bigger than expected")
                }
                out.toByteArray()
            }
        } catch (e: IOException) {
            throw SyncUnavailable("Couldn't reach the photo server", e)
        } finally {
            connection.disconnect()
        }
    }
}
