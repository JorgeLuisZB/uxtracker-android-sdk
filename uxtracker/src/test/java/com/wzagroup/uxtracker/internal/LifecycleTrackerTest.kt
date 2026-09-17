package com.wzagroup.uxtracker.internal

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.wzagroup.uxtracker.UxTrackerConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LifecycleTrackerTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val clock = FakeClock()
    private val runner = ManualRunner(clock)
    private val queue = InMemoryEventQueue()
    private val context = FakeContextProvider(online = false)
    private val logger = RecordingLogger()

    private val core = run {
        val config = UxTrackerConfig.Builder("uxt_pk_test_mx_key", "https://ingest.test").build()
        val uploader = Uploader("https://ingest.test/v1/batch", config.writeKey, LibraryInfo("t", "t", null), queue,
            FakeTransport(), context, clock, runner, logger, Random(1))
        TrackerCore(config, TrackerState(InMemoryStore()), queue, uploader, context, runner, runner, logger)
    }

    private fun lifecycleEvents() = queue.peek(100).map { JSONObject(it.json) }
        .map { it.getString("name") + (it.getJSONObject("properties").optBoolean("from_background").let { b -> if (it.getString("name") == "\$app_opened") ":$b" else "" }) }
        .filter { it != "\$app_installed" }

    @Test
    fun initializedBeforeAnyActivityTracksOpensAndBackgrounds() {
        val foreground = LifecycleTracker(core, clock).register(app)
        core.start(foreground, clock.now)

        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        activity.pause().stop()
        clock.now += 5_000
        activity.start().resume()

        assertEquals(listOf("\$app_opened:false", "\$app_backgrounded", "\$app_opened:true"), lifecycleEvents())
    }

    @Test
    fun aRotationIsNotABackground() {
        val foreground = LifecycleTracker(core, clock).register(app)
        core.start(foreground, clock.now)

        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        activity.configurationChange(android.content.res.Configuration().apply {
            orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE
        })

        assertEquals(listOf("\$app_opened:false"), lifecycleEvents())
    }

    @Test
    fun initializedAfterTheActivityStartedStillSeesTheBackground() {
        // Flutter/React Native: the activity is already visible when the SDK starts.
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val tracker = LifecycleTracker(core, clock)
        tracker.register(app)
        core.start(true, clock.now)

        activity.pause().stop()

        assertEquals(listOf("\$app_opened:false", "\$app_backgrounded"), lifecycleEvents())
    }

    @Test
    fun aProcessStartedInTheBackgroundOpensWithoutFromBackground() {
        // E.g. launched to handle a push: not visible at registration.
        val tracker = LifecycleTracker(core, clock)
        core.start(false, clock.now)
        tracker.onActivityStarted(Robolectric.buildActivity(Activity::class.java).get())

        assertEquals(listOf("\$app_opened:false"), lifecycleEvents())
    }
}
