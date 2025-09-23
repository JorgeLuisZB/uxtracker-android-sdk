package com.wzagroup.uxtracker_android_sdk

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.wzagroup.uxtracker_android_sdk.models.UXTrackerSetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.*
import androidx.core.content.edit
import com.wzagroup.uxtracker_android_sdk.models.DefaultProperties
import com.wzagroup.uxtracker_android_sdk.utils.apiUrl

class UXTracker private constructor(
) {
    companion object {
        @Volatile private var instance: UXTracker? = null

        fun shared(): UXTracker {
            return instance ?: synchronized(this) {
                instance ?: UXTracker().also {
                    instance = it
                }
            }
        }
    }
    private var context: Context? = null
    private var prefs: SharedPreferences? = null
    private val eventQueue = EventQueue()
    private var dispatcher = EventDispatcher(apiUrl, UXTrackerSetup())
    private val identitiesController = IdentitiesController()

    private var distinctId: String? = null
    private val sessionId: String = generateSessionId()

    private fun generateSessionId(): String {
        val timestamp = System.currentTimeMillis()
        val rand = UUID.randomUUID().toString().take(8)
        return "$timestamp-$rand"
    }

    fun initialize(context: Context, apiKey: String, setup: UXTrackerSetup = UXTrackerSetup()) {
        this.context = context.applicationContext
        prefs = this.context?.getSharedPreferences("uxtracker_prefs", Context.MODE_PRIVATE)
        dispatcher = EventDispatcher(apiUrl, setup)
        distinctId = prefs?.getString("distinctid", null) ?: UUID.randomUUID().toString().also {
            prefs?.edit() { putString("distinctid", it) }
        }
        dispatcher.startAutoFlush(CoroutineScope(Dispatchers.IO), eventQueue)
    }

    fun identify(userId: String) {
        val anonymousId = distinctId
        distinctId = userId
        prefs?.edit() { putString("distinctid", userId) }

        val payload = mapOf(
            "type" to "Identify",
            "event" to "Identify",
            "defaultproperties" to DefaultProperties.collect(context, sessionId, distinctId ?: ""),
            "userproperties" to buildMap {
                put("userid", userId)
                if (anonymousId != userId) put("anonymousdistinctid", anonymousId)
            }
        )

        eventQueue.enqueue(payload)

        identitiesController.identifyUser(payload)
    }

    fun track(eventName: String, userProperties: Map<String, Any> = emptyMap()) {
        val payload = mapOf(
            "type" to "Event",
            "event" to eventName,
            "defaultproperties" to DefaultProperties.collect(context, sessionId, distinctId ?: ""),
            "userproperties" to userProperties
        )
        Log.d("UXTracker", "Queued event: $payload")
        eventQueue.enqueue(payload)
    }

    fun flush() {
        dispatcher.flush(eventQueue)
    }

    fun reset() {
        distinctId = UUID.randomUUID().toString()
        prefs?.edit() { putString("distinctid", distinctId) }
    }
}
