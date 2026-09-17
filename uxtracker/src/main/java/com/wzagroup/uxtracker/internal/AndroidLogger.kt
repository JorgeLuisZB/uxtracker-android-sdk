package com.wzagroup.uxtracker.internal

import android.util.Log

internal class AndroidLogger(@Volatile var debugEnabled: Boolean) : Logger {
    override fun debug(message: String) {
        if (debugEnabled) Log.d(TAG, message)
    }

    override fun warn(message: String) {
        Log.w(TAG, message)
    }

    override fun error(message: String, error: Throwable?) {
        Log.e(TAG, message, error)
    }

    private companion object {
        const val TAG = "UxTracker"
    }
}
