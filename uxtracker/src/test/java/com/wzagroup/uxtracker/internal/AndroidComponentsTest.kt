package com.wzagroup.uxtracker.internal

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidComponentsTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun sqliteQueueIsFifoAndSurvivesReopening() {
        val queue = SqliteEventQueue(app)
        queue.clear()
        (1..5).forEach { queue.add("{\"n\":$it}") }

        val reopened = SqliteEventQueue(app)
        val firstTwo = reopened.peek(2)
        assertEquals(listOf("{\"n\":1}", "{\"n\":2}"), firstTwo.map { it.json })

        reopened.remove(firstTwo.map { it.id })
        assertEquals(3, reopened.count())
        assertEquals(1, reopened.trimTo(2))
        assertEquals(listOf("{\"n\":4}", "{\"n\":5}"), reopened.peek(10).map { it.json })
        reopened.clear()
        assertEquals(0, reopened.count())
    }

    @Test
    fun sharedPreferencesStoreRoundTrips() {
        val store = SharedPreferencesStore(app)
        store.putString("k", "v")
        store.putLong("n", 42L)
        assertEquals("v", SharedPreferencesStore(app).getString("k"))
        assertEquals(42L, SharedPreferencesStore(app).getLong("n"))
        store.putString("k", null)
        assertNull(store.getString("k"))
    }

    @Test
    fun contextMatchesTheProtocol() {
        val provider = AndroidContextProvider(app, LibraryInfo("uxtracker-flutter", "1.0.0", "uxtracker-android/1.0.0"))
        val context = provider.context()

        assertEquals("uxtracker-flutter", context.getJSONObject("library").getString("name"))
        assertEquals("uxtracker-android/1.0.0", context.getJSONObject("library").getString("core"))
        assertEquals("Android", context.getJSONObject("os").getString("name"))
        assertEquals(app.packageName, context.getJSONObject("app").getString("namespace"))
        assertTrue(context.getJSONObject("device").getString("type") in setOf("phone", "tablet", "tv", "watch"))
        assertTrue(context.getString("locale").isNotEmpty())
        assertTrue(context.getString("timezone").isNotEmpty())
        // No ACCESS_NETWORK_STATE in the test app: no network info, and sending isn't blocked.
        assertTrue(!context.has("network"))
        assertTrue(provider.isOnline())
        // Each call is a copy.
        provider.context().put("locale", "changed")
        assertTrue(provider.context().getString("locale") != "changed")
    }
}
