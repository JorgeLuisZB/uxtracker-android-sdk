package com.wzagroup.uxtracker.internal

import org.json.JSONObject
import java.io.IOException

internal class FakeClock(var now: Long = 1_758_000_000_000L) : Clock {
    override fun nowMillis(): Long = now
}

/** Runs tasks inline; scheduled tasks run when [advance] reaches them. */
internal class ManualRunner(private val clock: FakeClock) : TaskRunner {
    private data class Scheduled(val at: Long, val task: () -> Unit)
    private val scheduled = ArrayList<Scheduled>()

    override fun execute(task: () -> Unit) = task()

    override fun schedule(delayMillis: Long, task: () -> Unit) {
        scheduled.add(Scheduled(clock.now + delayMillis, task))
    }

    val scheduledCount: Int get() = scheduled.size
    fun nextDelay(): Long? = scheduled.minOfOrNull { it.at - clock.now }

    fun advance(millis: Long) {
        clock.now += millis
        while (true) {
            val due = scheduled.filter { it.at <= clock.now }.minByOrNull { it.at } ?: return
            scheduled.remove(due)
            due.task()
        }
    }
}

internal class RecordingLogger : Logger {
    val warnings = ArrayList<String>()
    val errors = ArrayList<String>()
    override fun debug(message: String) = Unit
    override fun warn(message: String) { warnings.add(message) }
    override fun error(message: String, error: Throwable?) { errors.add(message) }
}

internal class FakeContextProvider(var online: Boolean = true, var version: AppVersion = AppVersion("1.0.0", "1")) : ContextProvider {
    override fun context(): JSONObject = JSONObject()
        .put("library", JSONObject().put("name", "uxtracker-android").put("version", "test"))
        .put("os", JSONObject().put("name", "Android").put("version", "15"))
    override fun appVersion(): AppVersion = version
    override fun isOnline(): Boolean = online
}

internal class FakeTransport : HttpTransport {
    data class Request(val url: String, val headers: Map<String, String>, val body: JSONObject)

    val requests = ArrayList<Request>()
    private val responses = ArrayDeque<() -> HttpResponse>()

    fun respond(status: Int, body: String? = "{\"accepted\":0,\"rejected\":[]}", retryAfter: Long? = null) {
        responses.add { HttpResponse(status, body, retryAfter) }
    }

    fun failWithNetworkError() {
        responses.add { throw IOException("offline") }
    }

    override fun post(url: String, headers: Map<String, String>, json: String): HttpResponse {
        requests.add(Request(url, headers, JSONObject(json)))
        val next = responses.removeFirstOrNull() ?: return HttpResponse(200, "{\"accepted\":0,\"rejected\":[]}", null)
        return next()
    }

    fun eventsOf(request: Int) = requests[request].body.getJSONArray("events")
}
