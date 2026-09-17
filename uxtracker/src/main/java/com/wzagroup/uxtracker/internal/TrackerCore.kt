package com.wzagroup.uxtracker.internal

import com.wzagroup.uxtracker.UxTrackerConfig
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The SDK's behaviour (ingestion protocol §7, §8, §9, §10). Platform-free so it can be tested on the JVM.
 *
 * Threading: every state change and queue write runs on [stateRunner], in call order; sending runs on
 * [uploadRunner]. Public calls capture their timestamp on the caller's thread and return immediately.
 */
internal class TrackerCore(
    private val config: UxTrackerConfig,
    private val state: TrackerState,
    private val queue: EventQueue,
    private val uploader: Uploader,
    private val contextProvider: ContextProvider,
    private val stateRunner: TaskRunner,
    private val uploadRunner: TaskRunner,
    private val logger: Logger,
) {
    private val json = JsonValues(logger)
    private val flushPending = AtomicBoolean(false)
    private var lastActivityPersistedAt = 0L
    private var lastActivityAt = 0L

    @Volatile private var optedOut = config.optOutByDefault
    @Volatile var distinctId: String? = null
        private set

    fun start(appInForeground: Boolean, at: Long) {
        // Read (and, on first launch, create) the identity on the caller's thread, so distinctId and isOptedOut
        // are right as soon as initialize returns. Writes still happen in order on the state thread.
        val newAnonymousId = if (state.anonymousId == null) newId() else null
        distinctId = state.distinctId ?: newAnonymousId
        optedOut = state.optedOut ?: config.optOutByDefault
        stateRunner.execute { startOnStateThread(appInForeground, at, newAnonymousId) }
    }

    private fun startOnStateThread(appInForeground: Boolean, at: Long, newAnonymousId: String?) {
        val firstLaunch = newAnonymousId != null
        if (firstLaunch) {
            state.anonymousId = newAnonymousId
        }
        lastActivityAt = state.lastActivityAt ?: 0L
        ensureSession(at)

        val current = contextProvider.appVersion()
        if (config.trackAppLifecycle && !optedOut) {
            if (firstLaunch) {
                enqueueTrack("\$app_installed", JSONObject(), at)
            } else if (state.lastAppVersion != null && (state.lastAppVersion != current.version || state.lastAppBuild != current.build)) {
                enqueueTrack("\$app_updated", JSONObject()
                    .put("previous_version", state.lastAppVersion)
                    .put("previous_build", state.lastAppBuild), at)
            }
        }
        state.lastAppVersion = current.version
        state.lastAppBuild = current.build

        if (appInForeground) {
            foreground(at, fromBackground = false)
        }
        scheduleTimer()
        requestFlush() // whatever the last run couldn't send
        logger.debug("Started: distinct_id=$distinctId session_id=${state.sessionId} opted_out=$optedOut queued=${queue.count()}")
    }

    // ─── Public API (§10.2) ─────────────────────────────────────────────────

    fun track(name: String, properties: Map<String, Any?>?, at: Long) {
        val props = json.properties(properties, "properties")
        stateRunner.execute {
            if (optedOut) return@execute
            if (!validName(name)) return@execute
            enqueueTrack(name, props, at)
        }
    }

    fun screen(name: String, properties: Map<String, Any?>?, at: Long) {
        val props = json.properties(properties, "properties")
        stateRunner.execute {
            if (optedOut) return@execute
            props.put("\$screen_name", name.take(JsonValues.MAX_STRING_LENGTH))
            enqueueTrack("\$screen", props, at)
        }
    }

    fun identify(userId: String, traits: Map<String, Any?>?, at: Long) {
        val traitsJson = json.properties(traits, "traits")
        val id = userId.trim()
        val valid = id.isNotEmpty() && id.length <= MAX_ID_LENGTH
        // Visible to distinctId() right away; the event itself is built in call order on the state thread.
        if (valid && !optedOut) distinctId = id
        stateRunner.execute {
            if (optedOut) return@execute
            if (!valid) {
                logger.warn("identify ignored: userId must be 1..$MAX_ID_LENGTH characters")
                return@execute
            }
            val currentUser = state.userId
            if (id == currentUser) {
                if (traitsJson.length() > 0) enqueue(identifyEvent(null, traitsJson, at), at)
                return@execute
            }
            // anonymous_id only when coming from the anonymous state; switching users without reset() omits it (§4.6).
            val anonymousId = if (currentUser == null) state.anonymousId else null
            state.userId = id
            enqueue(identifyEvent(anonymousId, traitsJson, at), at)
        }
    }

    fun group(groupType: String, groupKey: String, traits: Map<String, Any?>?, at: Long) {
        val traitsJson = json.properties(traits, "traits")
        stateRunner.execute {
            if (optedOut) return@execute
            if (!GROUP_TYPE.matches(groupType)) {
                logger.warn("group ignored: groupType '$groupType' must match ${GROUP_TYPE.pattern}")
                return@execute
            }
            if (groupKey.isEmpty() || groupKey.length > MAX_ID_LENGTH) {
                logger.warn("group ignored: groupKey must be 1..$MAX_ID_LENGTH characters")
                return@execute
            }
            val groups = state.groups
            if (!groups.has(groupType) && groups.length() >= MAX_GROUP_TYPES) {
                logger.warn("group ignored: at most $MAX_GROUP_TYPES group types")
                return@execute
            }
            state.groups = groups.put(groupType, groupKey)
            val event = baseEvent("group", at)
                .put("group_type", groupType)
                .put("group_key", groupKey)
            if (traitsJson.length() > 0) event.put("traits", traitsJson)
            enqueue(event, at)
        }
    }

    fun unsetGroup(groupType: String) = stateRunner.execute {
        val groups = state.groups
        groups.remove(groupType)
        state.groups = groups
    }

    fun register(properties: Map<String, Any?>) {
        val props = json.properties(properties, "super properties")
        stateRunner.execute {
            val merged = state.superProperties
            props.keys().forEach { merged.put(it, props.get(it)) }
            state.superProperties = merged
        }
    }

    fun unregister(key: String) = stateRunner.execute {
        val merged = state.superProperties
        merged.remove(key)
        state.superProperties = merged
    }

    /** §8.4: new anonymous id and session; queued events keep the identity they were created with. */
    fun reset(at: Long) {
        val newAnonymousId = newId()
        distinctId = newAnonymousId
        stateRunner.execute {
            state.anonymousId = newAnonymousId
            state.userId = null
            state.superProperties = JSONObject()
            state.groups = JSONObject()
            newSession(at)
        }
    }

    fun optOut() = stateRunner.execute {
        optedOut = true
        state.optedOut = true
        queue.clear()
    }

    fun optIn() = stateRunner.execute {
        optedOut = false
        state.optedOut = false
    }

    fun isOptedOut(): Boolean = optedOut

    /** Runs after every call made before it, then sends; [onComplete] runs once the queue has been attempted. */
    fun flush(onComplete: (() -> Unit)?) = stateRunner.execute {
        uploadRunner.execute {
            uploader.flush()
            onComplete?.invoke()
        }
    }

    // ─── App lifecycle ──────────────────────────────────────────────────────

    /** @param fromBackground false the first time the app becomes visible in this process */
    fun onForeground(at: Long, fromBackground: Boolean) = stateRunner.execute { foreground(at, fromBackground) }

    fun onBackground(at: Long) = stateRunner.execute {
        if (config.trackAppLifecycle && !optedOut) {
            enqueueTrack("\$app_backgrounded", JSONObject(), at)
        }
        lastActivityAt = at
        persistActivity(at, force = true)
        requestFlush()
    }

    private fun foreground(at: Long, fromBackground: Boolean) {
        ensureSession(at)
        if (config.trackAppLifecycle && !optedOut) {
            enqueueTrack("\$app_opened", JSONObject().put("from_background", fromBackground), at)
        }
    }

    // ─── Sessions (§7) ──────────────────────────────────────────────────────

    private fun ensureSession(at: Long) {
        val expired = lastActivityAt == 0L || at - lastActivityAt > config.sessionTimeoutSeconds * 1000
        if (state.sessionId == null || expired) {
            newSession(at)
        }
    }

    private fun newSession(at: Long) {
        state.sessionId = newId()
        lastActivityAt = at
        persistActivity(at, force = true)
        logger.debug("New session ${state.sessionId}")
    }

    /** Written at most every few seconds; background and new sessions force it. */
    private fun persistActivity(at: Long, force: Boolean) {
        if (force || at - lastActivityPersistedAt >= ACTIVITY_PERSIST_INTERVAL_MS) {
            state.lastActivityAt = lastActivityAt
            lastActivityPersistedAt = at
        }
    }

    // ─── Events ─────────────────────────────────────────────────────────────

    private fun enqueueTrack(name: String, properties: JSONObject, at: Long) {
        val merged = state.superProperties
        properties.keys().forEach { merged.put(it, properties.get(it)) }
        val event = baseEvent("track", at).put("name", name).put("properties", merged)
        val groups = state.groups
        if (groups.length() > 0) event.put("groups", groups)
        enqueue(event, at)
    }

    private fun identifyEvent(anonymousId: String?, traits: JSONObject, at: Long): JSONObject {
        val event = baseEvent("identify", at)
        if (anonymousId != null) event.put("anonymous_id", anonymousId)
        if (traits.length() > 0) event.put("traits", traits)
        return event
    }

    private fun baseEvent(type: String, at: Long): JSONObject = JSONObject()
        .put("event_id", newId())
        .put("type", type)
        .put("distinct_id", state.distinctId)
        .put("timestamp", Iso8601.format(at))
        .put("session_id", state.sessionId)
        .put("context", contextProvider.context())

    private fun enqueue(event: JSONObject, at: Long) {
        val body = event.toString()
        if (body.toByteArray(Charsets.UTF_8).size > MAX_EVENT_BYTES) {
            logger.warn("Event dropped: larger than ${MAX_EVENT_BYTES / 1024} KB")
            return
        }
        queue.add(body)
        val dropped = queue.trimTo(config.maxQueueSize)
        if (dropped > 0) logger.warn("Queue full; dropped the $dropped oldest events")
        lastActivityAt = maxOf(lastActivityAt, at)
        persistActivity(at, force = false)
        logger.debug("Queued ${event.optString("type")} ${event.optString("name")}")
        if (queue.count() >= config.flushAt) requestFlush()
    }

    private fun validName(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) {
            logger.warn("track ignored: name must be 1..$MAX_NAME_LENGTH characters")
            return false
        }
        if (name.startsWith("$") && name !in RESERVED_EVENT_NAMES) {
            logger.warn("track ignored: names starting with \$ are reserved ($name)")
            return false
        }
        return true
    }

    private fun requestFlush() {
        if (!flushPending.compareAndSet(false, true)) return
        uploadRunner.execute {
            flushPending.set(false)
            uploader.flush()
        }
    }

    private fun scheduleTimer() {
        uploadRunner.schedule(config.flushIntervalSeconds * 1000) {
            uploader.flush()
            scheduleTimer()
        }
    }

    private fun newId(): String = UUID.randomUUID().toString()

    companion object {
        const val MAX_NAME_LENGTH = 200
        const val MAX_ID_LENGTH = 255
        const val MAX_EVENT_BYTES = 32 * 1024
        const val MAX_GROUP_TYPES = 5
        const val ACTIVITY_PERSIST_INTERVAL_MS = 5_000L
        val GROUP_TYPE = Regex("^[a-z][a-z0-9_]{0,49}$")
        val RESERVED_EVENT_NAMES = setOf("\$app_installed", "\$app_updated", "\$app_opened", "\$app_backgrounded", "\$screen", "\$pageview")
    }
}
