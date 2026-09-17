package com.wzagroup.uxtracker

import android.app.Application
import android.content.Context
import com.wzagroup.uxtracker.internal.AndroidContextProvider
import com.wzagroup.uxtracker.internal.AndroidLogger
import com.wzagroup.uxtracker.internal.ExecutorTaskRunner
import com.wzagroup.uxtracker.internal.HttpUrlConnectionTransport
import com.wzagroup.uxtracker.internal.LibraryInfo
import com.wzagroup.uxtracker.internal.LifecycleTracker
import com.wzagroup.uxtracker.internal.SharedPreferencesStore
import com.wzagroup.uxtracker.internal.SqliteEventQueue
import com.wzagroup.uxtracker.internal.SystemClock
import com.wzagroup.uxtracker.internal.TrackerCore
import com.wzagroup.uxtracker.internal.TrackerState
import com.wzagroup.uxtracker.internal.Uploader

/**
 * UxTracker Android SDK. Initialize once, ideally in `Application.onCreate`:
 *
 * ```kotlin
 * UxTracker.initialize(this, UxTrackerConfig.Builder(writeKey, serverUrl).build())
 * UxTracker.track("Menu day selected", mapOf("day" to "monday"))
 * ```
 *
 * Every method returns immediately and never throws. Calls made before [initialize] are kept (up to 1,000)
 * and applied once it runs.
 */
public object UxTracker {

    private const val LIBRARY_NAME = "uxtracker-android"
    private const val MAX_PENDING_CALLS = 1_000

    private val lock = Any()
    @Volatile private var core: TrackerCore? = null
    private val pending = ArrayList<(TrackerCore) -> Unit>()
    private var wrapper: Pair<String, String>? = null

    /** The SDK version sent in `context.library`. */
    @JvmStatic
    public val version: String get() = BuildConfig.SDK_VERSION

    @JvmStatic
    public fun initialize(context: Context, config: UxTrackerConfig) {
        val logger = AndroidLogger(config.debug)
        val problem = config.problem()
        if (problem != null) {
            logger.error("UxTracker not started: $problem")
            return
        }
        synchronized(lock) {
            if (core != null) {
                logger.warn("UxTracker.initialize called more than once; ignoring")
                return
            }
            val appContext = context.applicationContext
            val library = wrapper?.let { (name, wrapperVersion) -> LibraryInfo(name, wrapperVersion, "$LIBRARY_NAME/$version") }
                ?: LibraryInfo(LIBRARY_NAME, version, null)
            val contextProvider = AndroidContextProvider(appContext, library)
            val stateRunner = ExecutorTaskRunner("UxTracker-state", logger)
            val uploadRunner = ExecutorTaskRunner("UxTracker-upload", logger)
            val queue = SqliteEventQueue(appContext)
            val uploader = Uploader(
                batchUrl = "${config.serverUrl}/v1/batch",
                writeKey = config.writeKey,
                library = library,
                queue = queue,
                transport = HttpUrlConnectionTransport(),
                contextProvider = contextProvider,
                clock = SystemClock,
                runner = uploadRunner,
                logger = logger,
            )
            val newCore = TrackerCore(config, TrackerState(SharedPreferencesStore(appContext)), queue, uploader,
                contextProvider, stateRunner, uploadRunner, logger)

            val application = appContext as? Application
            val foreground = application?.let { LifecycleTracker(newCore, SystemClock).register(it) } ?: false
            newCore.start(foreground, SystemClock.nowMillis())

            pending.forEach { it(newCore) }
            pending.clear()
            core = newCore
        }
    }

    /** Records an action. Names starting with `$` are reserved. */
    @JvmStatic
    @JvmOverloads
    public fun track(name: String, properties: Map<String, Any?>? = null) {
        val at = SystemClock.nowMillis()
        call { it.track(name, properties, at) }
    }

    /** Records a screen view as `$screen` with `$screen_name`. */
    @JvmStatic
    @JvmOverloads
    public fun screen(name: String, properties: Map<String, Any?>? = null) {
        val at = SystemClock.nowMillis()
        call { it.screen(name, properties, at) }
    }

    /** Links this device to your app's user id (never an email or phone number) and sets profile traits. */
    @JvmStatic
    @JvmOverloads
    public fun identify(userId: String, traits: Map<String, Any?>? = null) {
        val at = SystemClock.nowMillis()
        call { it.identify(userId, traits, at) }
    }

    /** Puts the user in a group (company, team, clinic…); later events carry the membership. */
    @JvmStatic
    @JvmOverloads
    public fun group(groupType: String, groupKey: String, traits: Map<String, Any?>? = null) {
        val at = SystemClock.nowMillis()
        call { it.group(groupType, groupKey, traits, at) }
    }

    @JvmStatic
    public fun unsetGroup(groupType: String) {
        call { it.unsetGroup(groupType) }
    }

    /** Super properties: added to every later event's properties, and kept across launches. */
    @JvmStatic
    public fun register(properties: Map<String, Any?>) {
        call { it.register(properties) }
    }

    @JvmStatic
    public fun unregister(key: String) {
        call { it.unregister(key) }
    }

    /** Call on logout: new anonymous id and session, super properties and groups cleared. */
    @JvmStatic
    public fun reset() {
        val at = SystemClock.nowMillis()
        call { it.reset(at) }
    }

    /** Sends queued events now. [onComplete] runs on a background thread once they have been attempted. */
    @JvmStatic
    @JvmOverloads
    public fun flush(onComplete: Runnable? = null) {
        call { it.flush(onComplete?.let { runnable -> { runnable.run() } }) }
    }

    /** Stops all tracking and deletes queued events. Remembered across launches. */
    @JvmStatic
    public fun optOut() {
        call { it.optOut() }
    }

    @JvmStatic
    public fun optIn() {
        call { it.optIn() }
    }

    @JvmStatic
    public fun isOptedOut(): Boolean = core?.isOptedOut() ?: false

    /** The current distinct id: the user id after [identify], otherwise the anonymous id. Null before initialization completes. */
    @JvmStatic
    public fun distinctId(): String? = core?.distinctId

    /**
     * For SDK wrappers (Flutter, React Native): reports the wrapper as `context.library` with this SDK as
     * `library.core` (§10.6). Must be called before [initialize].
     */
    @JvmStatic
    public fun setWrapper(name: String, version: String) {
        synchronized(lock) {
            if (core == null) wrapper = name to version
        }
    }

    private fun call(action: (TrackerCore) -> Unit) {
        core?.let {
            action(it)
            return
        }
        synchronized(lock) {
            val ready = core
            if (ready != null) {
                action(ready)
            } else if (pending.size < MAX_PENDING_CALLS) {
                pending.add(action)
            }
        }
    }
}
