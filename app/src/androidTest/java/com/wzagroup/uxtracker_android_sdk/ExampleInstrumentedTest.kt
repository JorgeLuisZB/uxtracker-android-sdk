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

        // 2. Initialize SDK
        val setup = UXTrackerSetup(flushInterval = 5, batchSize = 10)
        UXTracker.shared().initialize(context, apiKey = "local-test-key", setup = setup)

        // 3. Track first 2 events
        for (i in 0 until 2) {
            UXTracker.shared().track(
                eventName = "test_event_local_api_$i",
                userProperties = mapOf("version" to "1.0")
            )
        }

        // Wait so auto-flush can trigger
        delay(10_000)

        // 4. Identify user
        UXTracker.shared().identify(userId = "ce93d891-119a-49c7-a83d-197d68c81105")

        delay(1_000)

        // 5. Track more events
        for (i in 2 until 24) {
            UXTracker.shared().track(
                eventName = "test_event_local_api_$i",
                userProperties = mapOf("version" to "1.0")
            )
        }

        // 6. Wait long enough for flush
        delay(300_000) // 5 minutes
    }
}