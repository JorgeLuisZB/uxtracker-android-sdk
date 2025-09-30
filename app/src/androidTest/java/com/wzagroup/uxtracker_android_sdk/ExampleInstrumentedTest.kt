package com.wzagroup.uxtracker_android_sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wzagroup.uxtracker_android_sdk.models.UXTrackerSetup
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UXTrackerTest {

    @Test
    fun exampleTest() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val setup = UXTrackerSetup(flushInterval = 20, batchSize = 20)
        UXTracker.shared().initialize(context, apiKey = "local-test-key", setup = setup)

        for (i in 0 until 5) {
            UXTracker.shared().track(
                eventName = "android_test_event_local_api_$i",
                userProperties = mapOf("version" to "1.0")
            )
        }

        delay(10_000)

        UXTracker.shared().identify(userId = "ce93d891-119a-49c7-a83d-197d68c81105")

        delay(1_000)

        for (i in 5 until 24) {
            UXTracker.shared().track(
                eventName = "android_test_event_local_api_$i",
                userProperties = mapOf("version" to "1.0")
            )
        }

        delay(75_000)
    }
}