package com.wzagroup.uxtracker.internal

import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

/**
 * Converts host-app values to protocol-valid JSON (§4.3, §3.2) instead of letting the server reject events.
 * Invalid entries are dropped with a warning; long strings are truncated. Never throws.
 */
internal class JsonValues(private val logger: Logger) {

    fun properties(values: Map<String, Any?>?, where: String, reservedKeys: Set<String> = RESERVED_PROPERTY_KEYS): JSONObject {
        val result = JSONObject()
        if (values == null) return result
        for ((key, value) in values) {
            if (result.length() >= MAX_PROPERTIES) {
                logger.warn("$where has more than $MAX_PROPERTIES keys; dropping the rest")
                break
            }
            if (!validKey(key, where)) continue
            if (key.startsWith("$") && key !in reservedKeys) {
                logger.warn("$where.$key dropped: keys starting with \$ are reserved")
                continue
            }
            convert(value, depth = 1, path = "$where.$key")?.let { result.put(key, it) }
        }
        return result
    }

    /** @return the JSON value, [JSONObject.NULL] for null, or null when the value must be dropped */
    private fun convert(value: Any?, depth: Int, path: String): Any? = when (value) {
        null -> JSONObject.NULL
        is String -> if (value.length > MAX_STRING_LENGTH) value.substring(0, MAX_STRING_LENGTH) else value
        is Boolean -> value
        is Double -> finite(value.isFinite(), value, path)
        is Float -> finite(value.isFinite(), value.toDouble(), path)
        is Number -> value
        is Char -> value.toString()
        is Date -> Iso8601.format(value.time)
        is Enum<*> -> value.name
        is Map<*, *> -> container(depth, path) {
            val obj = JSONObject()
            for ((k, v) in value) {
                if (k !is String || !validKey(k, path)) continue
                convert(v, depth + 1, "$path.$k")?.let { obj.put(k, it) }
            }
            obj
        }
        is Collection<*> -> container(depth, path) { array(value.toList(), depth, path) }
        is Array<*> -> container(depth, path) { array(value.asList(), depth, path) }
        is IntArray -> container(depth, path) { array(value.toList(), depth, path) }
        is LongArray -> container(depth, path) { array(value.toList(), depth, path) }
        is DoubleArray -> container(depth, path) { array(value.toList(), depth, path) }
        else -> {
            logger.warn("$path dropped: ${value.javaClass.simpleName} can't be sent; use strings, numbers, booleans, maps or lists")
            null
        }
    }

    private fun array(values: List<*>, depth: Int, path: String): JSONArray {
        val array = JSONArray()
        values.forEachIndexed { index, v -> convert(v, depth + 1, "$path[$index]")?.let { array.put(it) } }
        return array
    }

    private inline fun container(depth: Int, path: String, build: () -> Any): Any? {
        if (depth > MAX_DEPTH) {
            logger.warn("$path dropped: objects and lists can nest at most $MAX_DEPTH levels")
            return null
        }
        return build()
    }

    private fun finite(isFinite: Boolean, value: Double, path: String): Any? {
        if (isFinite) return value
        logger.warn("$path dropped: NaN and infinity can't be sent")
        return null
    }

    private fun validKey(key: String, where: String): Boolean {
        if (key.isEmpty() || key.length > MAX_KEY_LENGTH) {
            logger.warn("A key in $where was dropped: keys must be 1..$MAX_KEY_LENGTH characters")
            return false
        }
        return true
    }

    companion object {
        const val MAX_PROPERTIES = 255
        const val MAX_KEY_LENGTH = 255
        const val MAX_STRING_LENGTH = 8192
        const val MAX_DEPTH = 3
        val RESERVED_PROPERTY_KEYS = setOf("\$screen_name", "\$url", "\$path", "\$referrer", "\$title")
    }
}
