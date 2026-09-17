package com.wzagroup.uxtracker.internal

import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Sends the queue in FIFO batches and applies the protocol's response rules (§5.1) and retry policy (§10.4).
 * All methods run on the upload [TaskRunner], one request at a time.
 */
internal class Uploader(
    private val batchUrl: String,
    private val writeKey: String,
    private val library: LibraryInfo,
    private val queue: EventQueue,
    private val transport: HttpTransport,
    private val contextProvider: ContextProvider,
    private val clock: Clock,
    private val runner: TaskRunner,
    private val logger: Logger,
    private val random: Random = Random.Default,
) {
    private var attempt = 0
    private var notBeforeMillis = 0L
    private var retryScheduled = false
    private var maxBatchEvents = MAX_BATCH_EVENTS
    private val disabled = AtomicBoolean(false)

    val isDisabled: Boolean get() = disabled.get()

    /** Sends batches until the queue is empty or a request can't succeed now. */
    fun flush() {
        while (true) {
            if (disabled.get()) return
            val now = clock.nowMillis()
            if (now < notBeforeMillis) {
                scheduleRetry(notBeforeMillis - now)
                return
            }
            if (!contextProvider.isOnline()) {
                logger.debug("Offline; keeping ${queue.count()} events for later")
                return
            }

            val batch = nextBatch()
            if (batch.isEmpty()) {
                maxBatchEvents = MAX_BATCH_EVENTS
                return
            }

            val response = try {
                transport.post(batchUrl, headers(), envelope(batch, now))
            } catch (e: IOException) {
                logger.debug("Network error sending ${batch.size} events: ${e.message}")
                backOff(now)
                return
            }

            if (!handle(response, batch, now)) return
        }
    }

    /** @return true to keep sending */
    private fun handle(response: HttpResponse, batch: List<QueuedEvent>, now: Long): Boolean = when (response.status) {
        200 -> {
            queue.remove(batch.map { it.id })
            attempt = 0
            // After a 413 split, grow back so a large backlog isn't sent one event at a time.
            maxBatchEvents = minOf(MAX_BATCH_EVENTS, maxBatchEvents * 2)
            logRejections(response.body)
            true
        }
        413 -> {
            if (batch.size == 1) {
                logger.warn("Dropping an event the server refuses as too large")
                queue.remove(batch.map { it.id })
            } else {
                maxBatchEvents = maxOf(1, batch.size / 2)
            }
            true
        }
        401, 403 -> {
            disabled.set(true)
            logger.error("Server refused the write key (${response.status}): ${response.body}. Sending stops until the app restarts; events are kept.")
            false
        }
        429 -> {
            val waitSeconds = response.retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS
            notBeforeMillis = now + waitSeconds * 1000
            logger.debug("Rate limited; retrying in ${waitSeconds}s")
            scheduleRetry(waitSeconds * 1000)
            false
        }
        in 500..599 -> {
            backOff(now)
            false
        }
        in 400..499 -> {
            // Malformed batch: retrying can't fix it (§5.1).
            logger.error("Server rejected a batch of ${batch.size} events (${response.status}): ${response.body}")
            queue.remove(batch.map { it.id })
            true
        }
        else -> {
            backOff(now)
            false
        }
    }

    /** Up to [maxBatchEvents] events and well under the 1 MB body limit (§3.2). */
    private fun nextBatch(): List<QueuedEvent> {
        val candidates = queue.peek(maxBatchEvents)
        var bytes = ENVELOPE_OVERHEAD_BYTES
        val batch = ArrayList<QueuedEvent>(candidates.size)
        for (event in candidates) {
            val size = event.json.toByteArray(Charsets.UTF_8).size + 1 // plus the comma
            if (batch.isNotEmpty() && bytes + size > MAX_BODY_BYTES) break
            bytes += size
            batch.add(event)
        }
        return batch
    }

    /** `sent_at` is fresh on every attempt, retries included (§3.1). */
    private fun envelope(batch: List<QueuedEvent>, now: Long): String =
        batch.joinToString(separator = ",", prefix = "{\"sent_at\":\"${Iso8601.format(now)}\",\"events\":[", postfix = "]}") { it.json }

    private fun headers() = mapOf(
        "X-UxTracker-Key" to writeKey,
        "X-UxTracker-Library" to library.header,
    )

    /** Exponential backoff with full jitter: random(0, min(300, 2^attempt)) seconds (§10.4). */
    private fun backOff(now: Long) {
        attempt++
        val capSeconds = minOf(MAX_BACKOFF_SECONDS, 1L shl minOf(attempt, 30))
        val delayMillis = (random.nextDouble() * capSeconds * 1000).toLong()
        notBeforeMillis = now + delayMillis
        logger.debug("Send failed (attempt $attempt); retrying in ${delayMillis}ms")
        scheduleRetry(delayMillis)
    }

    private fun scheduleRetry(delayMillis: Long) {
        if (retryScheduled) return
        retryScheduled = true
        runner.schedule(maxOf(delayMillis, 0)) {
            retryScheduled = false
            flush()
        }
    }

    private fun logRejections(body: String?) {
        if (body.isNullOrEmpty()) return
        val rejected = runCatching { JSONObject(body).optJSONArray("rejected") }.getOrNull() ?: return
        for (i in 0 until rejected.length()) {
            val item = rejected.optJSONObject(i) ?: continue
            logger.warn("Server rejected event ${item.optString("event_id")}: ${item.optString("code")} (${item.optString("message")})")
        }
    }

    companion object {
        const val MAX_BATCH_EVENTS = 100
        const val MAX_BODY_BYTES = 900_000
        const val ENVELOPE_OVERHEAD_BYTES = 64
        const val MAX_BACKOFF_SECONDS = 300L
        const val DEFAULT_RETRY_AFTER_SECONDS = 60L
    }
}
