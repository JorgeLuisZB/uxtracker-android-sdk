package com.wzagroup.uxtracker.internal

import com.wzagroup.uxtracker.UxTrackerConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TrackerCoreTest {

    private val clock = FakeClock()
    private val runner = ManualRunner(clock)
    private val store = InMemoryStore()
    private val queue = InMemoryEventQueue()
    private val transport = FakeTransport()
    /** Offline unless a test is about sending, so queued events stay inspectable. */
    private val context = FakeContextProvider(online = false)
    private val logger = RecordingLogger()

    private fun core(configure: UxTrackerConfig.Builder.() -> Unit = {}): TrackerCore {
        val config = UxTrackerConfig.Builder("uxt_pk_test_mx_key", "https://ingest.test").flushAt(100).apply(configure).build()
        val uploader = Uploader("https://ingest.test/v1/batch", config.writeKey, LibraryInfo("uxtracker-android", "t", null),
            queue, transport, context, clock, runner, logger, Random(1))
        return TrackerCore(config, TrackerState(store), queue, uploader, context, runner, runner, logger)
    }

    private fun queued(): List<JSONObject> = queue.peek(1_000).map { JSONObject(it.json) }
    private fun names() = queued().map { if (it.getString("type") == "track") it.getString("name") else it.getString("type") }

    @Test
    fun firstLaunchSendsInstalledAndOpened() {
        core().start(appInForeground = true, at = clock.now)
        assertEquals(listOf("\$app_installed", "\$app_opened"), names())
        assertFalse(queued()[1].getJSONObject("properties").getBoolean("from_background"))
    }

    @Test
    fun anUpdatedAppSendsUpdatedWithPreviousVersion() {
        core().start(false, clock.now)
        queue.clear()
        context.version = AppVersion("1.1.0", "2")

        core().start(false, clock.now)

        val updated = queued().single()
        assertEquals("\$app_updated", updated.getString("name"))
        assertEquals("1.0.0", updated.getJSONObject("properties").getString("previous_version"))
        assertEquals("1", updated.getJSONObject("properties").getString("previous_build"))
    }

    @Test
    fun trackBuildsASpecEvent() {
        val core = core { trackAppLifecycle(false) }
        core.start(false, clock.now)
        core.register(mapOf("plan" to "free", "app_theme" to "dark"))
        core.track("Menu day selected", mapOf("day" to "monday", "plan" to "pro"), clock.now)

        val event = queued().single()
        assertEquals("track", event.getString("type"))
        assertTrue(event.getString("event_id").matches(Regex("[0-9a-f-]{36}")))
        assertEquals(core.distinctId, event.getString("distinct_id"))
        assertTrue(event.getString("timestamp").endsWith("Z"))
        assertTrue(event.has("session_id"))
        assertEquals("uxtracker-android", event.getJSONObject("context").getJSONObject("library").getString("name"))
        // Call-site properties override super properties.
        assertEquals("pro", event.getJSONObject("properties").getString("plan"))
        assertEquals("dark", event.getJSONObject("properties").getString("app_theme"))
    }

    @Test
    fun identifyLinksTheAnonymousIdOnceAndSwitchingUsersOmitsIt() {
        val core = core { trackAppLifecycle(false) }
        core.start(false, clock.now)
        val anonymous = core.distinctId

        core.identify("user_1", mapOf("plan" to "premium"), clock.now)
        core.track("After", null, clock.now)
        core.identify("user_1", null, clock.now) // same user, no traits: nothing sent
        core.identify("user_2", null, clock.now) // switched without reset()

        val events = queued()
        assertEquals(listOf("identify", "After", "identify"), names())
        assertEquals("user_1", events[0].getString("distinct_id"))
        assertEquals(anonymous, events[0].getString("anonymous_id"))
        assertEquals("premium", events[0].getJSONObject("traits").getString("plan"))
        assertEquals("user_1", events[1].getString("distinct_id"))
        assertFalse(events[2].has("anonymous_id"))
        assertEquals("user_2", core.distinctId)
    }

    @Test
    fun resetStartsANewAnonymousIdentityAndSession() {
        val core = core { trackAppLifecycle(false) }
        core.start(false, clock.now)
        core.identify("user_1", null, clock.now)
        core.register(mapOf("plan" to "pro"))
        core.group("clinic", "c1", null, clock.now)
        core.track("Before", null, clock.now)
        val before = queued().last()

        core.reset(clock.now)
        core.track("After", null, clock.now)
        val after = queued().last()

        assertNotEquals(before.getString("distinct_id"), after.getString("distinct_id"))
        assertNotEquals(before.getString("session_id"), after.getString("session_id"))
        assertFalse(after.getJSONObject("properties").has("plan"))
        assertFalse(after.has("groups"))
        // Events queued before reset keep their identity.
        assertEquals("user_1", queued().first { it.optString("name") == "Before" }.getString("distinct_id"))
    }

    @Test
    fun groupsAreAttachedToLaterTrackEvents() {
        val core = core { trackAppLifecycle(false) }
        core.start(false, clock.now)
        core.group("clinic", "clinic_417", mapOf("name" to "Centro"), clock.now)
        core.track("Viewed", null, clock.now)
        core.unsetGroup("clinic")
        core.track("Viewed again", null, clock.now)
        core.group("Clinic", "bad", null, clock.now)

        val events = queued()
        assertEquals(3, events.size)
        assertEquals("clinic", events[0].getString("group_type"))
        assertEquals("Centro", events[0].getJSONObject("traits").getString("name"))
        assertEquals("clinic_417", events[1].getJSONObject("groups").getString("clinic"))
        assertFalse(events[2].has("groups"))
    }

    @Test
    fun aNewSessionStartsAfterTheTimeoutInBackground() {
        val core = core { trackAppLifecycle(false); sessionTimeoutSeconds(60) }
        core.start(true, clock.now)
        core.track("A", null, clock.now)

        core.onBackground(clock.now)
        clock.now += 30_000
        core.onForeground(clock.now, fromBackground = true)
        core.track("B", null, clock.now)

        clock.now += 1_000
        core.onBackground(clock.now)
        clock.now += 61_000
        core.onForeground(clock.now, fromBackground = true)
        core.track("C", null, clock.now)

        val sessions = queued().map { it.getString("session_id") }
        assertEquals(sessions[0], sessions[1])
        assertNotEquals(sessions[1], sessions[2])
    }

    @Test
    fun aRelaunchWithinTheTimeoutKeepsTheSession() {
        val first = core { trackAppLifecycle(false) }
        first.start(true, clock.now)
        first.track("A", null, clock.now)
        first.onBackground(clock.now)

        clock.now += 10_000
        val second = core { trackAppLifecycle(false) }
        second.start(true, clock.now)
        second.track("B", null, clock.now)

        val sessions = queued().map { it.getString("session_id") }
        assertEquals(sessions[0], sessions[1])
    }

    @Test
    fun optOutDropsEverythingAndIsRemembered() {
        val core = core()
        core.start(false, clock.now)
        core.track("Before", null, clock.now)
        core.optOut()
        core.track("While out", null, clock.now)
        core.identify("user_1", null, clock.now)

        assertEquals(0, queue.count())
        assertTrue(core().apply { start(true, clock.now) }.isOptedOut())
        assertEquals(0, queue.count())

        core.optIn()
        core.track("Back", null, clock.now)
        assertEquals(listOf("Back"), names())
    }

    @Test
    fun optOutByDefaultWaitsForOptIn() {
        val core = core { optOutByDefault(true) }
        core.start(true, clock.now)
        core.track("Nope", null, clock.now)
        assertEquals(0, queue.count())
    }

    @Test
    fun invalidCallsAreIgnoredWithAWarning() {
        val core = core { trackAppLifecycle(false) }
        core.start(false, clock.now)
        core.track("", null, clock.now)
        core.track("\$purchase", null, clock.now)
        core.track("x".repeat(201), null, clock.now)
        core.identify("  ", null, clock.now)
        core.track("Too big", mapOf("a" to "x".repeat(8000), "b" to "x".repeat(8000), "c" to "x".repeat(8000), "d" to "x".repeat(8000), "e" to "x".repeat(8000)), clock.now)

        assertEquals(0, queue.count())
        assertEquals(5, logger.warnings.size)
    }

    @Test
    fun theQueueKeepsOnlyTheNewestEvents() {
        val core = core { trackAppLifecycle(false); maxQueueSize(100) }
        core.start(false, clock.now)
        repeat(105) { core.track("e$it", null, clock.now) }

        assertEquals(100, queue.count())
        assertEquals("e5", names().first())
    }

    @Test
    fun reachingFlushAtSends() {
        context.online = true
        val core = core { trackAppLifecycle(false); flushAt(3) }
        core.start(false, clock.now)
        transport.requests.clear()
        repeat(3) { core.track("e$it", null, clock.now) }

        assertEquals(1, transport.requests.size)
        assertEquals(0, queue.count())
    }

    @Test
    fun theTimerFlushesPeriodically() {
        val core = core { trackAppLifecycle(false); flushIntervalSeconds(30) }
        core.start(false, clock.now)
        core.track("e", null, clock.now)
        assertEquals(1, queue.count())

        context.online = true
        runner.advance(30_000)
        assertEquals(0, queue.count())
        runner.advance(30_000)
        assertTrue(runner.scheduledCount >= 1)
    }

    @Test
    fun backgroundingFlushes() {
        val core = core { trackAppLifecycle(true) }
        core.start(true, clock.now)
        context.online = true
        core.onBackground(clock.now)

        assertEquals(0, queue.count())
        val sent = transport.requests.flatMap { r -> (0 until r.body.getJSONArray("events").length()).map { r.body.getJSONArray("events").getJSONObject(it).getString("name") } }
        assertEquals(listOf("\$app_installed", "\$app_opened", "\$app_backgrounded"), sent)
    }

    @Test
    fun theIdentityIsReadableAsSoonAsStartReturns() {
        // A state thread that hasn't run anything yet, as right after initialize in a real app.
        val deferred = object : TaskRunner {
            val tasks = ArrayList<() -> Unit>()
            override fun execute(task: () -> Unit) { tasks.add(task) }
            override fun schedule(delayMillis: Long, task: () -> Unit) = Unit
        }
        val config = UxTrackerConfig.Builder("uxt_pk_test_mx_key", "https://ingest.test").optOutByDefault(true).build()
        val uploader = Uploader("https://ingest.test/v1/batch", config.writeKey, LibraryInfo("t", "t", null), queue, transport, context, clock, runner, logger)
        val core = TrackerCore(config, TrackerState(store), queue, uploader, context, deferred, runner, logger)

        core.start(false, clock.now)
        val early = core.distinctId
        assertTrue(early != null && core.isOptedOut())

        deferred.tasks.toList().forEach { it() }
        assertEquals(early, TrackerState(store).anonymousId)
    }
}
