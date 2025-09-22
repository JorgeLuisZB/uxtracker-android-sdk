package com.wzagroup.uxtracker_android_sdk

import android.util.Log
import com.google.gson.Gson
import com.wzagroup.uxtracker_android_sdk.utils.apiUrl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class IdentitiesController {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Synchronized
    fun identifyUser(event: Map<String, Any>) {
        val json = gson.toJson(event)
        val request = Request.Builder()
            .url("$apiUrl/profile/identify")
            .post(json.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        Log.d("IdentifyUser", "Sending identify: $json")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("IdentifyUser", "Failed to send identify", e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        Log.e("IdentifyUser", "Failed with status: ${response.code}")
                    } else {
                        Log.d("IdentifyUser", "identify sent successfully")
                    }
                }
            }
        })
    }
}