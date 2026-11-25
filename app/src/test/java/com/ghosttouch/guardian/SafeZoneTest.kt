package com.ghosttouch.guardian

import org.junit.Test
import org.junit.Assert.*

class SafeZoneTest {
    
    @Test
    fun `Z has() detects point inside`() {
        val zone = Z(x=100f, y=100f, w=50f, h=50f)
        assertTrue(zone.has(125f, 125f))
    }
    
    @Test
    fun `Z has() rejects point outside`() {
        val zone = Z(x=100f, y=100f, w=50f, h=50f)
        assertFalse(zone.has(50f, 50f))
        assertFalse(zone.has(200f, 200f))
    }
    
    @Test
    fun `Z has() handles boundary`() {
        val zone = Z(x=100f, y=100f, w=50f, h=50f)
        assertTrue(zone.has(100f, 100f))
        assertTrue(zone.has(150f, 150f))
    }
}
