package com.wzagroup.uxtracker.internal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class JsonValuesTest {

    private val logger = RecordingLogger()
    private val json = JsonValues(logger)

    @Test
    fun convertsSupportedTypes() {
        val result = json.properties(mapOf(
            "text" to "monday", "int" to 3, "long" to 4L, "double" to 1.5, "bool" to true, "none" to null,
            "list" to listOf(1, "a"), "map" to mapOf("k" to "v"), "date" to Date(0), "array" to arrayOf("x"),
        ), "properties")

        assertEquals("monday", result.getString("text"))
        assertEquals(3, result.getInt("int"))
        assertEquals(JSONObject.NULL, result.get("none"))
        assertEquals("a", result.getJSONArray("list").getString(1))
        assertEquals("v", result.getJSONObject("map").getString("k"))
        assertEquals("1970-01-01T00:00:00.000Z", result.getString("date"))
        assertEquals("x", result.getJSONArray("array").getString(0))
    }

    @Test
    fun dropsWhatTheServerWouldReject() {
        val result = json.properties(mapOf(
            "nan" to Double.NaN,
            "object" to Any(),
            "" to 1,
            "\$plan" to "pro",
            "\$screen_name" to "Dashboard",
        ), "properties")

        assertEquals(setOf("\$screen_name"), result.keys().asSequence().toSet())
        assertEquals(4, logger.warnings.size)
    }

    @Test
    fun allowsThreeLevelsOfNestingAndNoMore() {
        val allowed = json.properties(mapOf("a" to mapOf("b" to listOf(1))), "properties")
        assertTrue(allowed.getJSONObject("a").has("b"))

        // a → b → [0] is three levels; the object under "c" would be a fourth.
        val tooDeep = json.properties(mapOf("a" to mapOf("b" to listOf(mapOf("c" to mapOf("d" to 1))))), "properties")
        assertFalse(tooDeep.getJSONObject("a").getJSONArray("b").getJSONObject(0).has("c"))
    }

    @Test
    fun truncatesLongStrings() {
        val result = json.properties(mapOf("note" to "x".repeat(10_000)), "properties")
        assertEquals(JsonValues.MAX_STRING_LENGTH, result.getString("note").length)
    }

    @Test
    fun capsTheNumberOfKeys() {
        val result = json.properties((0 until 300).associate { "p$it" to it }, "properties")
        assertEquals(JsonValues.MAX_PROPERTIES, result.length())
        assertFalse(logger.warnings.isEmpty())
    }
}
