package com.wzagroup.uxtracker

/**
 * SDK configuration (ingestion protocol §10.1). Build it with [Builder]:
 *
 * ```kotlin
 * val config = UxTrackerConfig.Builder(writeKey = "uxt_pk_live_mx_…", serverUrl = "https://ingest.mx.example.com")
 *     .debug(BuildConfig.DEBUG)
 *     .build()
 * ```
 */
public class UxTrackerConfig private constructor(builder: Builder) {

    /** Project write key (`uxt_pk_…`). Public by design: it can only send data. */
    public val writeKey: String = builder.writeKey.trim()

    /** Ingestion host of the project's region. `https://` is required unless [debug] is on. */
    public val serverUrl: String = builder.serverUrl.trim().trimEnd('/')

    /** Seconds between automatic flushes. Minimum 5. */
    public val flushIntervalSeconds: Long = maxOf(5, builder.flushIntervalSeconds)

    /** Flush as soon as this many events are queued. 1–100. */
    public val flushAt: Int = builder.flushAt.coerceIn(1, 100)

    /** Queue capacity; the oldest events are dropped beyond it. Minimum 100. */
    public val maxQueueSize: Int = maxOf(100, builder.maxQueueSize)

    /** Seconds in background after which returning to the app starts a new session. Minimum 60. */
    public val sessionTimeoutSeconds: Long = maxOf(60, builder.sessionTimeoutSeconds)

    /** Sends `$app_installed`, `$app_updated`, `$app_opened` and `$app_backgrounded` automatically. */
    public val trackAppLifecycle: Boolean = builder.trackAppLifecycle

    /** Starts opted out until [UxTracker.optIn] is called, for apps that ask for consent first. */
    public val optOutByDefault: Boolean = builder.optOutByDefault

    /** Verbose logging under the `UxTracker` tag, and allows `http://` servers for local development. */
    public val debug: Boolean = builder.debug

    public class Builder(internal val writeKey: String, internal val serverUrl: String) {
        internal var flushIntervalSeconds: Long = 30
        internal var flushAt: Int = 20
        internal var maxQueueSize: Int = 10_000
        internal var sessionTimeoutSeconds: Long = 1_800
        internal var trackAppLifecycle: Boolean = true
        internal var optOutByDefault: Boolean = false
        internal var debug: Boolean = false

        public fun flushIntervalSeconds(seconds: Long): Builder = apply { flushIntervalSeconds = seconds }
        public fun flushAt(events: Int): Builder = apply { flushAt = events }
        public fun maxQueueSize(events: Int): Builder = apply { maxQueueSize = events }
        public fun sessionTimeoutSeconds(seconds: Long): Builder = apply { sessionTimeoutSeconds = seconds }
        public fun trackAppLifecycle(enabled: Boolean): Builder = apply { trackAppLifecycle = enabled }
        public fun optOutByDefault(enabled: Boolean): Builder = apply { optOutByDefault = enabled }
        public fun debug(enabled: Boolean): Builder = apply { debug = enabled }
        public fun build(): UxTrackerConfig = UxTrackerConfig(this)
    }

    /** @return why the SDK can't run with this configuration, or null when it can */
    internal fun problem(): String? = when {
        !writeKey.startsWith("uxt_pk_") -> "writeKey must be a project write key (uxt_pk_…)"
        !serverUrl.startsWith("https://") && !(debug && serverUrl.startsWith("http://")) ->
            "serverUrl must start with https:// (http:// is only allowed with debug(true))"
        else -> null
    }
}
