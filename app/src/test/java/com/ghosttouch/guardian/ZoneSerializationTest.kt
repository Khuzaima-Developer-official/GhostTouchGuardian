package com.ghosttouch.guardian

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONArray

class ZoneSerializationTest {
    
    @Test
    fun `ZStore toJson serializes zones`() {
        val zones = listOf(
            Z(100f, 100f, 50f, 50f),
            Z(200f, 200f, 75f, 75f)
        )
        val json = ZStore.toJson(zones)
        assertTrue(json.contains("100"))
        assertTrue(json.contains("200"))
    }
    
    @Test
    fun `ZStore fromJson deserializes zones`() {
        val json = """[{"x":100,"y":100,"w":50,"h":50}]"""
        val zones = ZStore.fromJson(json)
        assertEquals(1, zones.size)
        assertEquals(100f, zones[0].x, 0.01f)
    }
    
    @Test
    fun `ZStore fromJson handles malformed JSON`() {
        val zones = ZStore.fromJson("invalid")
        assertTrue(zones.isEmpty())
    }
}
