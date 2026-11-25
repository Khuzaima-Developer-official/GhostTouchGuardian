package com.ghosttouch.guardian

import org.junit.Test
import org.junit.Assert.*
import kotlin.math.sqrt

class SpatialJitterTest {
    
    @Test
    fun `sj returns -1 for fewer than 4 points`() {
        val pts = listOf(0f to 0f, 1f to 1f, 2f to 2f)
        assertEquals(-1f, sj(pts), 0.01f)
    }
    
    @Test
    fun `sj calculates RMS for stable touch`() {
        val pts = listOf(
            100f to 100f, 101f to 100f,
            100f to 101f, 99f to 100f
        )
        val jitter = sj(pts)
        assertTrue(jitter in 0.5f..1.0f)
    }
    
    @Test
    fun `sj calculates RMS for jittery touch`() {
        val pts = listOf(
            100f to 100f, 110f to 95f,
            90f to 105f, 105f to 90f
        )
        val jitter = sj(pts)
        assertTrue(jitter > 5f)
    }
}
