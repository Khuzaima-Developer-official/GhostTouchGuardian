package com.antigravity.ghosttouchguardian

import com.antigravity.ghosttouchguardian.engine.TouchProfilerEngine
import com.antigravity.ghosttouchguardian.model.CompactTouchBuffer
import com.antigravity.ghosttouchguardian.model.CustomBlockRule
import com.antigravity.ghosttouchguardian.model.GhostFingerprint
import com.antigravity.ghosttouchguardian.model.GhostTouchEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GhostFingerprintTest {

    private lateinit var engine: TouchProfilerEngine

    @Before
    fun setUp() {
        engine = TouchProfilerEngine()
    }

    @Test
    fun testFingerprintCalculationFromSamples() {
        // Create 5 realistic ghost samples around (300, 400) with low dwell (~25ms) and zero shockwave
        val samples = listOf(
            GhostTouchEntry(x = 300f, y = 400f, dwell = 22, size = 0.02f, pressure = 0.05f, touchMajor = 5f, historySize = 0, accelDelta = 0.01f, intervalFromPrevMs = 120L, displacementPx = 5f, timestamp = 1000L),
            GhostTouchEntry(x = 305f, y = 402f, dwell = 25, size = 0.02f, pressure = 0.04f, touchMajor = 5f, historySize = 0, accelDelta = 0.02f, intervalFromPrevMs = 118L, displacementPx = 6f, timestamp = 1120L),
            GhostTouchEntry(x = 298f, y = 399f, dwell = 20, size = 0.02f, pressure = 0.06f, touchMajor = 5f, historySize = 0, accelDelta = 0.01f, intervalFromPrevMs = 122L, displacementPx = 8f, timestamp = 1240L),
            GhostTouchEntry(x = 302f, y = 405f, dwell = 28, size = 0.03f, pressure = 0.05f, touchMajor = 5f, historySize = 0, accelDelta = 0.01f, intervalFromPrevMs = 125L, displacementPx = 7f, timestamp = 1365L),
            GhostTouchEntry(x = 301f, y = 401f, dwell = 24, size = 0.02f, pressure = 0.05f, touchMajor = 5f, historySize = 0, accelDelta = 0.01f, intervalFromPrevMs = 120L, displacementPx = 4f, timestamp = 1485L)
        )

        val fp = GhostFingerprint.fromEntries(samples)

        assertTrue("Fingerprint must be valid with >= 3 samples", fp.isValid)
        assertEquals(301.2f, fp.avgX, 1.0f)
        assertEquals(401.4f, fp.avgY, 1.0f)
        assertEquals(23.8f, fp.avgDwell, 1.0f)
        assertTrue("Average period should be ~120ms", fp.avgPeriodMs in 115L..125L)
        assertTrue("Period variance should be low (< 10%)", fp.periodVariance < 10f)

        // Matching test: a matching ghost touch
        val matchScore = fp.matchScore(x = 302f, y = 400f, dwell = 24, size = 0.02f, pressure = 0.05f, accelDelta = 0.01f)
        assertTrue("Matching ghost should score >= 0.80", matchScore >= 0.80f)

        // Non-matching test: a human touch far away with high dwell
        val humanScore = fp.matchScore(x = 800f, y = 900f, dwell = 120, size = 0.15f, pressure = 0.40f, accelDelta = 0.15f)
        assertTrue("Human touch far away should score < 0.30", humanScore < 0.30f)
    }

    @Test
    fun testJsonSerializationAndDeserialization() {
        val samples = listOf(
            GhostTouchEntry(x = 150f, y = 250f, dwell = 18, size = 0.01f, pressure = 0.02f, touchMajor = 3f, historySize = 0, accelDelta = 0.005f, intervalFromPrevMs = 80L, displacementPx = 2f, timestamp = 5000L),
            GhostTouchEntry(x = 152f, y = 251f, dwell = 20, size = 0.01f, pressure = 0.02f, touchMajor = 3f, historySize = 0, accelDelta = 0.005f, intervalFromPrevMs = 82L, displacementPx = 3f, timestamp = 5082L),
            GhostTouchEntry(x = 149f, y = 249f, dwell = 19, size = 0.01f, pressure = 0.02f, touchMajor = 3f, historySize = 0, accelDelta = 0.005f, intervalFromPrevMs = 79L, displacementPx = 2f, timestamp = 5161L)
        )
        val customRule = CustomBlockRule("Top Corner Glitch", 100f, 200f, 200f, 300f, 40)
        val fp = GhostFingerprint.fromEntries(samples, listOf(customRule))

        val json = GhostFingerprint.toJson(fp)
        assertTrue("JSON must contain avgX", json.contains("avgX"))
        assertTrue("JSON must contain customRules", json.contains("Top Corner Glitch"))

        val restored = GhostFingerprint.fromJson(json)
        assertTrue("Restored must be valid", restored.isValid)
        assertEquals(fp.avgX, restored.avgX, 0.1f)
        assertEquals(fp.avgY, restored.avgY, 0.1f)
        assertEquals(fp.avgDwell, restored.avgDwell, 0.1f)
        assertEquals(1, restored.customBlockRules.size)
        assertEquals("Top Corner Glitch", restored.customBlockRules[0].description)
    }

    @Test
    fun testGhostBlockedByLearnedFingerprintWhenNoShockwave() {
        // Learn fingerprint around (200, 300) with dwell 20ms
        val samples = listOf(
            GhostTouchEntry(200f, 300f, 20, 0.02f, 0.04f, 4f, 0, 0.01f, 100L, 2f, 1000L),
            GhostTouchEntry(202f, 301f, 22, 0.02f, 0.04f, 4f, 0, 0.01f, 100L, 2f, 1100L),
            GhostTouchEntry(198f, 299f, 19, 0.02f, 0.04f, 4f, 0, 0.01f, 100L, 2f, 1200L)
        )
        engine.ghostFingerprint = GhostFingerprint.fromEntries(samples)

        // Incoming ghost touch without shockwave in fingerprint area
        engine.processTouch(android.view.MotionEvent.ACTION_DOWN, x = 201f, y = 300f, eventTime = 5000L)
        val result = engine.processTouch(
            android.view.MotionEvent.ACTION_UP,
            x = 201f, y = 300f,
            eventTime = 5021L,
            downTime = 5000L,
            size = 0.02f,
            pressure = 0.04f,
            forcedImpulse = false
        )

        assertEquals("Ghost touch matching fingerprint without shockwave MUST be blocked", CompactTouchBuffer.TYPE_GHOST_HOTSPOT, result)
        assertEquals(1L, engine.buffer.totalGhostCount)
        assertEquals(0L, engine.buffer.totalHumanCount)
    }

    @Test
    fun testHumanFingerInFingerprintAreaAllowedWhenShockwavePresent() {
        // Same fingerprint loaded
        val samples = listOf(
            GhostTouchEntry(200f, 300f, 20, 0.02f, 0.04f, 4f, 0, 0.01f, 100L, 2f, 1000L),
            GhostTouchEntry(202f, 301f, 22, 0.02f, 0.04f, 4f, 0, 0.01f, 100L, 2f, 1100L),
            GhostTouchEntry(198f, 299f, 19, 0.02f, 0.04f, 4f, 0, 0.01f, 100L, 2f, 1200L)
        )
        engine.ghostFingerprint = GhostFingerprint.fromEntries(samples)

        // Real human finger taps at exact same coordinate (201, 300), BUT has mechanical shockwave
        engine.processTouch(android.view.MotionEvent.ACTION_DOWN, x = 201f, y = 300f, eventTime = 6000L)
        val result = engine.processTouch(
            android.view.MotionEvent.ACTION_UP,
            x = 201f, y = 300f,
            eventTime = 6050L,
            downTime = 6000L,
            size = 0.12f,
            pressure = 0.35f,
            forcedImpulse = true  // Physical tap shockwave confirmed!
        )

        assertEquals("Real finger with shockwave MUST pass even inside fingerprint zone", CompactTouchBuffer.TYPE_HUMAN_FAST_TAP, result)
        assertEquals(1L, engine.buffer.totalHumanCount)
        assertEquals(0L, engine.buffer.totalGhostCount)
    }

    @Test
    fun testFleshyFingerTapWithoutShockwaveAlsoAllowedInGlitchZone() {
        val samples = listOf(
            GhostTouchEntry(200f, 300f, 20, 0.02f, 0.04f, 4f, 0, 0.01f, 100L, 2f, 1000L),
            GhostTouchEntry(202f, 301f, 22, 0.02f, 0.04f, 4f, 0, 0.01f, 100L, 2f, 1100L),
            GhostTouchEntry(198f, 299f, 19, 0.02f, 0.04f, 4f, 0, 0.01f, 100L, 2f, 1200L)
        )
        engine.ghostFingerprint = GhostFingerprint.fromEntries(samples)

        // Real finger taps softly (e.g. phone on bed, so shockwave is absorbed), BUT contact has natural human flesh size and pressure
        engine.processTouch(android.view.MotionEvent.ACTION_DOWN, x = 201f, y = 300f, eventTime = 7000L)
        val result = engine.processTouch(
            android.view.MotionEvent.ACTION_UP,
            x = 201f, y = 300f,
            eventTime = 7060L,
            downTime = 7000L,
            size = 0.08f,     // Human pulp contact patch
            pressure = 0.22f, // Human contact pressure
            forcedImpulse = null // Natural sensor mode
        )

        assertEquals("Fleshy finger tap MUST pass smoothly without being blocked", CompactTouchBuffer.TYPE_HUMAN_FAST_TAP, result)
        assertEquals(1L, engine.buffer.totalHumanCount)
        assertEquals(0L, engine.buffer.totalGhostCount)
    }
}
