package com.wzagroup.uxtracker.internal

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

internal interface Clock {
    fun nowMillis(): Long
}

internal object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

internal interface Logger {
    fun debug(message: String)
    fun warn(message: String)
    fun error(message: String, error: Throwable? = null)
}

/** Serial background work. Tasks never throw into the host app (ingestion protocol §10.5). */
internal interface TaskRunner {
    fun execute(task: () -> Unit)
    fun schedule(delayMillis: Long, task: () -> Unit)
}

internal class ExecutorTaskRunner(name: String, private val logger: Logger) : TaskRunner {

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(ThreadFactory { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    })

    override fun execute(task: () -> Unit) {
        executor.execute { runSafely(task) }
    }

    override fun schedule(delayMillis: Long, task: () -> Unit) {
        executor.schedule({ runSafely(task) }, delayMillis, TimeUnit.MILLISECONDS)
    }

    private fun runSafely(task: () -> Unit) {
        try {
            task()
        } catch (t: Throwable) {
            logger.error("Unexpected error inside UxTracker", t)
        }
    }
}

internal object Iso8601 {
    /** RFC 3339 in UTC with milliseconds. SimpleDateFormat isn't thread-safe, so one per call (java.time needs API 26). */
    fun format(millis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(millis))
    }
}
