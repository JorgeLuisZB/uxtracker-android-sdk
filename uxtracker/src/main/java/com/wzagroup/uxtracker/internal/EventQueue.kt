package com.wzagroup.uxtracker.internal

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal data class QueuedEvent(val id: Long, val json: String)

/** Durable FIFO of serialized events (ingestion protocol §10.3). */
internal interface EventQueue {
    fun add(json: String)
    fun peek(limit: Int): List<QueuedEvent>
    fun remove(ids: Collection<Long>)
    fun count(): Int
    /** Drops the oldest events beyond [max]. @return how many were dropped */
    fun trimTo(max: Int): Int
    fun clear()
}

internal class SqliteEventQueue(context: Context) : EventQueue {

    private val helper = object : SQLiteOpenHelper(context, DATABASE, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, body TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    override fun add(json: String) {
        helper.writableDatabase.insert("events", null, ContentValues().apply { put("body", json) })
    }

    override fun peek(limit: Int): List<QueuedEvent> =
        helper.readableDatabase.rawQuery("SELECT id, body FROM events ORDER BY id LIMIT ?", arrayOf(limit.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(QueuedEvent(cursor.getLong(0), cursor.getString(1)))
            }
        }

    override fun remove(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.delete("events", "id = ?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun count(): Int =
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM events", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    override fun trimTo(max: Int): Int {
        val excess = count() - max
        if (excess <= 0) return 0
        helper.writableDatabase.execSQL(
            "DELETE FROM events WHERE id IN (SELECT id FROM events ORDER BY id LIMIT ?)", arrayOf<Any>(excess)
        )
        return excess
    }

    override fun clear() {
        helper.writableDatabase.delete("events", null, null)
    }

    private companion object {
        const val DATABASE = "uxtracker_events.db"
        const val VERSION = 1
    }
}

internal class InMemoryEventQueue : EventQueue {
    private var nextId = 1L
    private val events = ArrayList<QueuedEvent>()

    @Synchronized override fun add(json: String) { events.add(QueuedEvent(nextId++, json)) }
    @Synchronized override fun peek(limit: Int): List<QueuedEvent> = events.take(limit)
    @Synchronized override fun remove(ids: Collection<Long>) { events.removeAll { it.id in ids } }
    @Synchronized override fun count(): Int = events.size
    @Synchronized override fun trimTo(max: Int): Int {
        val excess = events.size - max
        if (excess <= 0) return 0
        repeat(excess) { events.removeAt(0) }
        return excess
    }
    @Synchronized override fun clear() { events.clear() }
}
