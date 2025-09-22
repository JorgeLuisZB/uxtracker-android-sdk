package com.wzagroup.uxtracker_android_sdk

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.google.gson.Gson
import com.wzagroup.uxtracker_android_sdk.models.UXTrackerSetup
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class EventDispatcher(
    private val baseUrl: String,
    private val uxTrackerSetup: UXTrackerSetup
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private var flushJob: Job? = null

    fun startAutoFlush(scope: CoroutineScope, eventQueue: EventQueue) {
        stopAutoFlush()
        flushJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(uxTrackerSetup.flushInterval * 1000)
                flush(eventQueue)
            }
        }
    }

    fun stopAutoFlush() {
        flushJob?.cancel()
        flushJob = null
    }

    @Synchronized
    fun flush(eventQueue: EventQueue) {
        val batch = eventQueue.dequeueBatch(uxTrackerSetup.batchSize)
        if (batch.isEmpty()) return

        val json = gson.toJson(batch)
        val request = Request.Builder()
            .url("$baseUrl/events/track/batch")
            .post(json.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        Log.d("EventDispatcher", "Sending batch: $json")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("EventDispatcher", "Failed to send events", e)
                // Re-enqueue if fail
                batch.forEach { eventQueue.enqueue(it) }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        Log.e("EventDispatcher", "Failed with status: ${response.code}")
                        batch.forEach { eventQueue.enqueue(it) }
                    } else {
                        Log.d("EventDispatcher", "Batch sent successfully")
                    }
                }
            }
        })
    }
}
