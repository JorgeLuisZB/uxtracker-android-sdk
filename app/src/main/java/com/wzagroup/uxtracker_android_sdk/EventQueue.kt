package com.wzagroup.uxtracker_android_sdk

import java.util.LinkedList
import java.util.Queue

class EventQueue {
    private val queue: Queue<Map<String, Any>> = LinkedList()

    @Synchronized
    fun enqueue(event: Map<String, Any>) {
        queue.add(event)
    }

    @Synchronized
    fun dequeueBatch(max: Int): List<Map<String, Any>> {
        val batch = mutableListOf<Map<String, Any>>()
        while (batch.size < max && queue.isNotEmpty()) {
            queue.poll()?.let { batch.add(it) }
        }
        return batch
    }

    @Synchronized
    fun isEmpty(): Boolean = queue.isEmpty()

    @Synchronized
    fun size(): Int = queue.size
}
