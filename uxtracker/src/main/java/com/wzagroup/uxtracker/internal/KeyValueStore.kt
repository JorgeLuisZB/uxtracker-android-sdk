package com.wzagroup.uxtracker.internal

import android.content.Context
import android.content.SharedPreferences

internal interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getLong(key: String): Long?
    fun putLong(key: String, value: Long?)
}

internal class SharedPreferencesStore(context: Context) : KeyValueStore {

    private val prefs: SharedPreferences = context.getSharedPreferences("uxtracker_state", Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        // commit(): only ever called on the SDK's background thread, and identity must survive a crash right after.
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit()
    }

    override fun getLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0) else null

    override fun putLong(key: String, value: Long?) {
        prefs.edit().apply { if (value == null) remove(key) else putLong(key, value) }.commit()
    }
}

internal class InMemoryStore : KeyValueStore {
    private val values = HashMap<String, Any?>()

    @Synchronized override fun getString(key: String): String? = values[key] as String?
    @Synchronized override fun putString(key: String, value: String?) { values[key] = value }
    @Synchronized override fun getLong(key: String): Long? = values[key] as Long?
    @Synchronized override fun putLong(key: String, value: Long?) { values[key] = value }
}
