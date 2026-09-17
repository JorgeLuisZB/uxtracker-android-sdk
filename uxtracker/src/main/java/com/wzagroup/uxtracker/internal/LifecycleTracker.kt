package com.wzagroup.uxtracker.internal

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Bundle

/**
 * Foreground/background from activity callbacks, with no androidx dependency. Callbacks run on the main thread.
 *
 * The SDK may be initialized after an activity already started (Flutter and React Native initialize from their
 * runtime), so a process that is visible at registration counts as one "assumed" visible activity until real
 * callbacks take over.
 */
internal class LifecycleTracker(private val core: TrackerCore, private val clock: Clock) : Application.ActivityLifecycleCallbacks {

    private val started = HashSet<Int>()
    private var assumedVisible = false
    private var configurationChange = false
    /** Whether the app has been visible in this process: a process started for a push isn't "back" from anywhere. */
    private var hasBeenForeground = false

    /** @return whether the app was in the foreground at registration */
    fun register(application: Application): Boolean {
        assumedVisible = isProcessForeground()
        hasBeenForeground = assumedVisible
        application.registerActivityLifecycleCallbacks(this)
        return assumedVisible
    }

    private val isForeground: Boolean get() = started.isNotEmpty() || assumedVisible

    override fun onActivityStarted(activity: Activity) {
        val wasForeground = isForeground
        // A real start replaces the assumption: the assumed activity is this one or already on its way out.
        assumedVisible = false
        started.add(System.identityHashCode(activity))
        if (configurationChange) {
            configurationChange = false
            return
        }
        if (!wasForeground) {
            core.onForeground(clock.nowMillis(), fromBackground = hasBeenForeground)
            hasBeenForeground = true
        }
    }

    override fun onActivityStopped(activity: Activity) {
        val key = System.identityHashCode(activity)
        if (!started.remove(key)) {
            // Started before registration: it was the assumed visible activity.
            if (!assumedVisible) return
            assumedVisible = false
        }
        if (isForeground) return
        if (activity.isChangingConfigurations) {
            configurationChange = true
            return
        }
        core.onBackground(clock.nowMillis())
    }

    private fun isProcessForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
