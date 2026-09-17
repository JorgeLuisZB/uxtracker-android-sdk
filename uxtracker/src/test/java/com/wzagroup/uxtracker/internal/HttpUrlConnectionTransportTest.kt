package com.wzagroup.uxtracker.internal

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream

class HttpUrlConnectionTransportTest {

    private val server = MockWebServer().apply { start() }
    private val transport = HttpUrlConnectionTransport()

    @After
    fun stop() = server.shutdown()

    @Test
    fun postsGzippedJsonWithHeaders() {
        server.enqueue(MockResponse().setBody("{\"accepted\":1,\"rejected\":[]}"))

        val response = transport.post(server.url("/v1/batch").toString(), mapOf("X-UxTracker-Key" to "uxt_pk_test_mx_k"), "{\"events\":[]}")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("gzip", request.getHeader("Content-Encoding"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("uxt_pk_test_mx_k", request.getHeader("X-UxTracker-Key"))
        val body = GZIPInputStream(request.body.inputStream()).readBytes().toString(Charsets.UTF_8)
        assertEquals("{\"events\":[]}", body)
        assertEquals(200, response.status)
        assertEquals("{\"accepted\":1,\"rejected\":[]}", response.body)
    }

    @Test
    fun returnsErrorBodiesAndRetryAfter() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "17").setBody("{\"error\":{\"code\":\"rate_limited\"}}"))

        val response = transport.post(server.url("/v1/batch").toString(), emptyMap(), "{}")

        assertEquals(429, response.status)
        assertEquals(17L, response.retryAfterSeconds)
        assertTrue(response.body!!.contains("rate_limited"))
    }

    @Test
    fun doesNotFollowRedirects() {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "https://elsewhere.example.com/v1/batch"))
        assertEquals(307, transport.post(server.url("/v1/batch").toString(), emptyMap(), "{}").status)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun retriesOnceWhenAKeptAliveConnectionWasAlreadyClosed() {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setBody("{\"accepted\":1,\"rejected\":[]}"))

        val response = transport.post(server.url("/v1/batch").toString(), emptyMap(), "{\"events\":[]}")

        assertEquals(200, response.status)
        assertEquals(2, server.requestCount)
    }
}
