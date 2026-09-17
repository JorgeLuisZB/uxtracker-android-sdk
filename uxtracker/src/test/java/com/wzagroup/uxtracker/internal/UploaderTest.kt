package com.wzagroup.uxtracker.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class UploaderTest {

    private val clock = FakeClock()
    private val runner = ManualRunner(clock)
    private val queue = InMemoryEventQueue()
    private val transport = FakeTransport()
    private val context = FakeContextProvider()
    private val logger = RecordingLogger()
    private val library = LibraryInfo("uxtracker-flutter", "1.0.0", "uxtracker-android/1.0.0")
    private val uploader = Uploader("https://ingest.test/v1/batch", "uxt_pk_test_mx_key", library, queue, transport,
        context, clock, runner, logger, Random(42))

    private fun enqueue(count: Int, size: Int = 10) = repeat(count) {
        queue.add("{\"event_id\":\"$it\",\"pad\":\"${"x".repeat(size)}\"}")
    }

    @Test
    fun sendsInBatchesOfAtMostOneHundredWithHeaders() {
        enqueue(230)
        uploader.flush()

        assertEquals(listOf(100, 100, 30), transport.requests.indices.map { transport.eventsOf(it).length() })
        assertEquals(0, queue.count())
        val request = transport.requests.first()
        assertEquals("https://ingest.test/v1/batch", request.url)
        assertEquals("uxt_pk_test_mx_key", request.headers["X-UxTracker-Key"])
        assertEquals("uxtracker-flutter/1.0.0", request.headers["X-UxTracker-Library"])
        assertTrue(request.body.getString("sent_at").endsWith("Z"))
    }

    @Test
    fun keepsBatchesUnderTheBodyLimit() {
        enqueue(60, size = 30_000)
        uploader.flush()

        assertTrue(transport.requests.size >= 2)
        assertTrue(transport.requests.all { it.body.toString().length < Uploader.MAX_BODY_BYTES })
        assertEquals(0, queue.count())
    }

    @Test
    fun serverErrorsKeepEventsAndBackOffWithJitter() {
        enqueue(3)
        transport.respond(503)
        uploader.flush()

        assertEquals(3, queue.count())
        val delay = runner.nextDelay()!!
        assertTrue("first retry within 2s, was $delay", delay in 0..2_000)

        runner.advance(delay)
        assertEquals(2, transport.requests.size)
        assertEquals(0, queue.count())
    }

    @Test
    fun retriesRefreshSentAt() {
        enqueue(1)
        transport.failWithNetworkError()
        uploader.flush()
        val firstSentAt = transport.requests[0].body.getString("sent_at")

        runner.advance(5_000)
        assertTrue(transport.requests.size == 2)
        assertTrue(firstSentAt != transport.requests[1].body.getString("sent_at"))
    }

    @Test
    fun backoffIsCappedAtFiveMinutes() {
        enqueue(1)
        repeat(20) {
            transport.respond(500)
            uploader.flush()
            runner.advance(runner.nextDelay() ?: 0)
        }
        transport.respond(500)
        uploader.flush()
        assertTrue((runner.nextDelay() ?: 0) <= 300_000)
    }

    @Test
    fun rateLimitedWaitsForRetryAfter() {
        enqueue(2)
        transport.respond(429, retryAfter = 30)
        uploader.flush()

        runner.advance(29_000)
        assertEquals(1, transport.requests.size)
        runner.advance(1_000)
        assertEquals(2, transport.requests.size)
        assertEquals(0, queue.count())
    }

    @Test
    fun anInvalidKeyStopsSendingButKeepsEvents() {
        enqueue(2)
        transport.respond(401, "{\"error\":{\"code\":\"invalid_write_key\"}}")
        uploader.flush()
        uploader.flush()

        assertEquals(1, transport.requests.size)
        assertEquals(2, queue.count())
        assertTrue(uploader.isDisabled)
        assertEquals(1, logger.errors.size)
    }

    @Test
    fun tooLargeSplitsTheBatchAndFinallyDropsASingleEvent() {
        enqueue(4)
        transport.respond(413)
        transport.respond(413)
        transport.respond(413)
        uploader.flush()

        // 4 → 2 → 1 refused → that event dropped, then the rest goes through.
        assertEquals(listOf(4, 2, 1, 1, 2), transport.requests.indices.map { transport.eventsOf(it).length() })
        assertEquals(0, queue.count())
    }

    @Test
    fun malformedBatchesAreDroppedNotRetried() {
        enqueue(2)
        transport.respond(400, "{\"error\":{\"code\":\"malformed_batch\"}}")
        uploader.flush()

        assertEquals(0, queue.count())
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun rejectedEventsAreRemovedAndLogged() {
        enqueue(2)
        transport.respond(200, "{\"accepted\":1,\"rejected\":[{\"index\":1,\"event_id\":\"1\",\"code\":\"invalid_name\",\"message\":\"x\"}]}")
        uploader.flush()

        assertEquals(0, queue.count())
        assertTrue(logger.warnings.single().contains("invalid_name"))
    }

    @Test
    fun doesNotSendWhileOffline() {
        enqueue(1)
        context.online = false
        uploader.flush()
        assertEquals(0, transport.requests.size)
        assertEquals(1, queue.count())
    }
}
