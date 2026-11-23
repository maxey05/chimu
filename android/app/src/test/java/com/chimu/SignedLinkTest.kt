package com.chimu

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Fetching another member's receipt photo through a signed link (RFC-006), against a real local HTTP server. */
class SignedLinkTest {
    private var server: HttpServer? = null

    @After
    fun stop() {
        server?.stop(0)
    }

    /** Starts a server that answers every request with [status] and [body]; returns the link to it. */
    private fun serve(status: Int, body: ByteArray, header: Pair<String, String>? = null): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { exchange ->
            header?.let { exchange.responseHeaders.add(it.first, it.second) }
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}/receipts/ana/t1.jpg?token=abc"
    }

    @Test
    fun aPhotoComesBackByteForByte() {
        val photo = ByteArray(50_000) { (it % 251).toByte() }
        assertArrayEquals(photo, SignedLink.fetch(serve(200, photo)))
    }

    @Test
    fun anExpiredOrForbiddenLink_isRefused_notRetriedForever() {
        for (status in listOf(400, 403, 404)) {
            try {
                SignedLink.fetch(serve(status, "no".toByteArray()))
                fail("expected a refusal for $status")
            } catch (e: SyncRejected) {
                assertEquals(status, e.status)
            } finally {
                server?.stop(0)
            }
        }
    }

    @Test
    fun aServerFault_meansTryAgainLater() {
        for (status in listOf(401, 408, 429, 500, 503)) {
            try {
                SignedLink.fetch(serve(status, "later".toByteArray()))
                fail("expected 'try later' for $status")
            } catch (e: SyncUnavailable) {
                // expected
            } finally {
                server?.stop(0)
            }
        }
    }

    @Test
    fun aRedirectIsNotFollowed() {
        try {
            SignedLink.fetch(serve(302, ByteArray(0), "Location" to "http://127.0.0.1:1/elsewhere"))
            fail("expected a refusal")
        } catch (e: SyncRejected) {
            assertEquals(302, e.status)
        }
    }

    @Test
    fun aHugeAnswer_isNotReadToTheEnd() {
        try {
            SignedLink.fetch(serve(200, ByteArray(SignedLink.MAX_BYTES + 1_000)))
            fail("expected the size limit")
        } catch (e: SyncRejected) {
            assertEquals(413, e.status)
        }
    }

    @Test
    fun noServerAtAll_meansTryAgainLater() {
        val link = serve(200, ByteArray(1))
        server?.stop(0) // the port is now closed
        try {
            SignedLink.fetch(link)
            fail("expected 'try later'")
        } catch (e: SyncUnavailable) {
            // expected
        }
    }

    @Test
    fun aLinkThatIsNotHttp_isRefused() {
        for (link in listOf("not a url", "file:///etc/passwd", "")) {
            try {
                SignedLink.fetch(link)
                fail("expected a refusal for '$link'")
            } catch (e: SyncRejected) {
                // expected
            }
        }
    }
}
