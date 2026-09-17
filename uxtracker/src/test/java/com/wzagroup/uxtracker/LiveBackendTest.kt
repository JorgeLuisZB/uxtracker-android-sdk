package com.wzagroup.uxtracker

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/**
 * Sends real events through the public API to a running UxTracker backend. Skipped unless
 * UXTRACKER_E2E_SERVER_URL and UXTRACKER_E2E_WRITE_KEY are set, e.g.:
 *
 *     UXTRACKER_E2E_SERVER_URL=http://localhost:7002 UXTRACKER_E2E_WRITE_KEY=uxt_pk_test_mx_… \
 *         ./gradlew :uxtracker:testDebugUnitTest --tests '*LiveBackendTest*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveBackendTest {

    @Test
    fun sendsEveryEventTypeToTheBackend() {
        val serverUrl = System.getenv("UXTRACKER_E2E_SERVER_URL")
        val writeKey = System.getenv("UXTRACKER_E2E_WRITE_KEY")
        assumeTrue("Live backend not configured", serverUrl != null && writeKey != null)

        val app = ApplicationProvider.getApplicationContext<Application>()
        UxTracker.track("Called before initialize", mapOf("buffered" to true))
        UxTracker.initialize(app, UxTrackerConfig.Builder(writeKey!!, serverUrl!!).debug(true).build())

        UxTracker.register(mapOf("app_theme" to "dark"))
        UxTracker.track("Menu day selected", mapOf("day" to "monday", "menu_id" to 1234, "tags" to listOf("vegan")))
        UxTracker.screen("Dashboard")
        UxTracker.identify("android_e2e_user", mapOf("plan" to "premium"))
        UxTracker.group("clinic", "clinic_417", mapOf("name" to "Clínica Centro"))
        UxTracker.track("Plan day selected", mapOf("day" to 3))

        val done = CountDownLatch(1)
        UxTracker.flush { done.countDown() }
        assertTrue(done.await(30, TimeUnit.SECONDS), "flush didn't complete")
        println("E2E distinct_id=${UxTracker.distinctId()}")
    }
}
