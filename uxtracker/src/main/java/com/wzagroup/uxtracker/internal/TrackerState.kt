package com.wzagroup.uxtracker.internal

import org.json.JSONObject

/** Persisted identity, session and preferences (§7, §8, §9, §10.2). Accessed only on the state thread. */
internal class TrackerState(private val store: KeyValueStore) {

    var anonymousId: String?
        get() = store.getString(ANONYMOUS_ID)
        set(value) = store.putString(ANONYMOUS_ID, value)

    var userId: String?
        get() = store.getString(USER_ID)
        set(value) = store.putString(USER_ID, value)

    val distinctId: String? get() = userId ?: anonymousId

    var sessionId: String?
        get() = store.getString(SESSION_ID)
        set(value) = store.putString(SESSION_ID, value)

    var lastActivityAt: Long?
        get() = store.getLong(LAST_ACTIVITY_AT)
        set(value) = store.putLong(LAST_ACTIVITY_AT, value)

    var superProperties: JSONObject
        get() = json(SUPER_PROPERTIES)
        set(value) = store.putString(SUPER_PROPERTIES, value.toString())

    var groups: JSONObject
        get() = json(GROUPS)
        set(value) = store.putString(GROUPS, value.toString())

    /** null until the app (or config) decides */
    var optedOut: Boolean?
        get() = store.getString(OPTED_OUT)?.toBoolean()
        set(value) = store.putString(OPTED_OUT, value?.toString())

    var lastAppVersion: String?
        get() = store.getString(LAST_APP_VERSION)
        set(value) = store.putString(LAST_APP_VERSION, value)

    var lastAppBuild: String?
        get() = store.getString(LAST_APP_BUILD)
        set(value) = store.putString(LAST_APP_BUILD, value)

    private fun json(key: String): JSONObject =
        store.getString(key)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    private companion object {
        const val ANONYMOUS_ID = "anonymous_id"
        const val USER_ID = "user_id"
        const val SESSION_ID = "session_id"
        const val LAST_ACTIVITY_AT = "last_activity_at"
        const val SUPER_PROPERTIES = "super_properties"
        const val GROUPS = "groups"
        const val OPTED_OUT = "opted_out"
        const val LAST_APP_VERSION = "last_app_version"
        const val LAST_APP_BUILD = "last_app_build"
    }
}
