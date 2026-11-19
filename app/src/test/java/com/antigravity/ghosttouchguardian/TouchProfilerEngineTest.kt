package com.antigravity.ghosttouchguardian

import android.view.MotionEvent
import com.antigravity.ghosttouchguardian.engine.TouchProfilerEngine
import com.antigravity.ghosttouchguardian.model.CompactTouchBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TouchProfilerEngineTest {

    private lateinit var engine: TouchProfilerEngine

    @Before
    fun setUp() {
        engine = TouchProfilerEngine()
    }

    @Test
    fun testFeatherTouchTinyIconPassesAsHuman() {
        // User softly taps tiny 'X' close button (15ms click, tiny size, tiny pressure)
        engine.processTouch(
            action = MotionEvent.ACTION_DOWN,
            toolType = MotionEvent.TOOL_TYPE_FINGER,
            x = 100f, y = 100f,
            eventTime = 1000L,
            size = 0.01f,
            pressure = 0.01f
        )
        val result = engine.processTouch(
            action = MotionEvent.ACTION_UP,
            toolType = MotionEvent.TOOL_TYPE_FINGER,
            x = 100f, y = 100f,
            eventTime = 1015L,
            downTime = 1000L,
            size = 0.01f,
            pressure = 0.01f
        )

        assertEquals(CompactTouchBuffer.TYPE_HUMAN_FAST_TAP, result)
        assertEquals(1L, engine.buffer.totalHumanCount)
        assertEquals(0L, engine.buffer.totalGhostCount)
    }

    @Test
    fun testCheapPhoneAllZeroFieldsPassesAsHuman() {
        // Budget phone reports 0f for size and pressure
        engine.processTouch(
            action = MotionEvent.ACTION_DOWN,
            x = 250f, y = 400f,
            eventTime = 2000L,
            size = 0f,
            pressure = 0f
        )
        val result = engine.processTouch(
            action = MotionEvent.ACTION_UP,
            x = 250f, y = 400f,
            eventTime = 2030L,
            downTime = 2000L,
            size = 0f,
            pressure = 0f
        )

        assertEquals(CompactTouchBuffer.TYPE_HUMAN_FAST_TAP, result)
        assertEquals(1L, engine.buffer.totalHumanCount)
        assertEquals(0L, engine.buffer.totalGhostCount)
    }

    @Test
    fun testNormalHumanTouchPasses() {
        // Normal 120ms human button press
        engine.processTouch(
            action = MotionEvent.ACTION_DOWN,
            x = 300f, y = 500f,
            eventTime = 3000L
        )
        val result = engine.processTouch(
            action = MotionEvent.ACTION_UP,
            x = 302f, y = 501f,
            eventTime = 3120L,
            downTime = 3000L
        )

        assertEquals(CompactTouchBuffer.TYPE_HUMAN_FINGER, result)
        assertEquals(1L, engine.buffer.totalHumanCount)
    }

    @Test
    fun testDielectricEchoIsDetectedAndBlocked() {
        // 1. Valid human tap and lift at (200, 200)
        engine.processTouch(MotionEvent.ACTION_DOWN, x = 200f, y = 200f, eventTime = 4000L)
        engine.processTouch(MotionEvent.ACTION_UP, x = 200f, y = 200f, eventTime = 4050L, downTime = 4000L)

        // 2. Ghost dielectric echo arrives 10ms later at (205, 202) -> dist < 28px
        engine.processTouch(MotionEvent.ACTION_DOWN, x = 205f, y = 202f, eventTime = 4060L)
        val ghostResult = engine.processTouch(
            MotionEvent.ACTION_UP,
            x = 205f, y = 202f,
            eventTime = 4070L,
            downTime = 4060L
        )

        assertEquals(CompactTouchBuffer.TYPE_GHOST_ECHO, ghostResult)
        assertEquals(1L, engine.buffer.totalHumanCount)
        assertEquals(1L, engine.buffer.totalGhostCount)
        assertEquals(1L, engine.buffer.totalEchoCount)
    }

    @Test
    fun testRapidTypingDifferentKeyNotEcho() {
        // 1. Left thumb taps at (100, 500)
        engine.processTouch(MotionEvent.ACTION_DOWN, x = 100f, y = 500f, eventTime = 5000L)
        engine.processTouch(MotionEvent.ACTION_UP, x = 100f, y = 500f, eventTime = 5040L, downTime = 5000L)

        // 2. Right thumb taps at (450, 500) 15ms later
        engine.processTouch(MotionEvent.ACTION_DOWN, x = 450f, y = 500f, eventTime = 5055L)
        val rightThumbResult = engine.processTouch(
            MotionEvent.ACTION_UP,
            x = 450f, y = 500f,
            eventTime = 5095L,
            downTime = 5055L
        )

        // Must NOT be ghost echo because distance is 350px > 28px
        assertEquals(CompactTouchBuffer.TYPE_HUMAN_FAST_TAP, rightThumbResult)
        assertEquals(2L, engine.buffer.totalHumanCount)
        assertEquals(0L, engine.buffer.totalGhostCount)
    }

    @Test
    fun testKinematicTeleportationIsDetected() {
        var teleportDetected = false
        engine.onAnomalyDetected = { type, _, _, _, _ ->
            if (type == CompactTouchBuffer.TYPE_GHOST_TELEPORT) {
                teleportDetected = true
            }
        }

        // Event at (100, 100)
        engine.processTouch(MotionEvent.ACTION_DOWN, x = 100f, y = 100f, eventTime = 6000L)

        // Impossible jump to (600, 800) in 3ms
        engine.processTouch(MotionEvent.ACTION_DOWN, x = 600f, y = 800f, eventTime = 6003L)

        assertTrue("Kinematic jump > 250px in 3ms must trigger teleport anomaly", teleportDetected)
    }

    @Test
    fun testSPenStylusAlwaysPasses() {
        engine.processTouch(
            action = MotionEvent.ACTION_DOWN,
            toolType = MotionEvent.TOOL_TYPE_STYLUS,
            x = 100f, y = 100f,
            eventTime = 7000L
        )
        val result = engine.processTouch(
            action = MotionEvent.ACTION_UP,
            toolType = MotionEvent.TOOL_TYPE_STYLUS,
            x = 100f, y = 100f,
            eventTime = 7050L,
            downTime = 7000L
        )

        assertEquals(CompactTouchBuffer.TYPE_SPEN, result)
    }

    @Test
    fun testHandsFreeDeskModeBlocksAll() {
        engine.isHandsFreeScanActive = true

        engine.processTouch(MotionEvent.ACTION_DOWN, x = 100f, y = 100f, eventTime = 8000L)
        val result = engine.processTouch(
            MotionEvent.ACTION_UP,
            x = 100f, y = 100f,
            eventTime = 8050L,
            downTime = 8000L
        )

        assertEquals(CompactTouchBuffer.TYPE_GHOST_IDLE, result)
        assertEquals(1L, engine.buffer.totalGhostCount)
        assertEquals(0L, engine.buffer.totalHumanCount)
    }

    @Test
    fun testGhostOnlyModeSuppressesHumanEvents() {
        engine.isGhostOnlyMode = true
        var ignoredFingerDwell = -1
        engine.onHumanIgnoredInGhostMode = { _, _, dwell ->
            ignoredFingerDwell = dwell
        }

        // Real human tap
        engine.processTouch(MotionEvent.ACTION_DOWN, x = 150f, y = 150f, eventTime = 9000L)
        val result = engine.processTouch(
            MotionEvent.ACTION_UP,
            x = 150f, y = 150f,
            eventTime = 9045L,
            downTime = 9000L
        )

        assertEquals(CompactTouchBuffer.TYPE_HUMAN_FAST_TAP, result)
        assertEquals(45, ignoredFingerDwell)
        // In ghost only mode, human touches are NOT pushed to buffer
        assertEquals(0, engine.buffer.size)
    }

    @Test
    fun testStaticDropletLockIsDetected() {
        engine.processTouch(MotionEvent.ACTION_DOWN, x = 300f, y = 300f, eventTime = 10000L)

        // Simulate 6 MOVE samples over 600ms with almost 0 drift (water droplet)
        for (i in 1..6) {
            engine.processTouch(
                MotionEvent.ACTION_MOVE,
                x = 300.2f, y = 300.1f,
                eventTime = 10000L + (i * 100L),
                downTime = 10000L
            )
        }

        val result = engine.processTouch(
            MotionEvent.ACTION_UP,
            x = 300.2f, y = 300.1f,
            eventTime = 10650L,
            downTime = 10000L
        )

        assertEquals(CompactTouchBuffer.TYPE_STATIC_LOCK, result)
        assertEquals(1L, engine.buffer.totalGhostCount)
    }

    @Test
    fun testCollinearITOSportDetected() {
        var itcShortDetected = false
        engine.onAnomalyDetected = { type, _, _, _, _ ->
            if (type == CompactTouchBuffer.TYPE_GRID_COLINEAR) {
                itcShortDetected = true
            }
        }

        // Two points on exact same X line (|dx| < 0.5px) separated by 300px along Y
        engine.processTouch(
            action = MotionEvent.ACTION_POINTER_DOWN,
            x = 200f, y = 100f,
            eventTime = 11000L,
            pointerCount = 2,
            p1X = 200.1f,
            p1Y = 450f
        )

        assertTrue("Collinear touches on same grid line must trigger ITO short", itcShortDetected)
    }

    @Test
    fun testCalibratedHotspotGlitchWithoutShockwaveIsBlocked() {
        val hotspot = com.antigravity.ghosttouchguardian.engine.HotspotZone(800f, 100f, 1000f, 300f, 5)
        engine.calibratedHotspots = listOf(hotspot)

        // Ghost fires in hotspot without physical finger shockwave
        engine.processTouch(MotionEvent.ACTION_DOWN, x = 850f, y = 150f, eventTime = 12000L)
        val result = engine.processTouch(
            action = MotionEvent.ACTION_UP,
            x = 850f, y = 150f,
            eventTime = 12035L,
            downTime = 12000L,
            forcedImpulse = false
        )

        assertEquals(CompactTouchBuffer.TYPE_GHOST_HOTSPOT, result)
        assertEquals(1L, engine.buffer.totalGhostCount)
    }

    @Test
    fun testRealFingerInHotspotWithShockwavePasses() {
        val hotspot = com.antigravity.ghosttouchguardian.engine.HotspotZone(800f, 100f, 1000f, 300f, 5)
        engine.calibratedHotspots = listOf(hotspot)

        // User taps inside the hotspot, but physical shockwave is confirmed
        engine.processTouch(MotionEvent.ACTION_DOWN, x = 850f, y = 150f, eventTime = 13000L)
        val result = engine.processTouch(
            action = MotionEvent.ACTION_UP,
            x = 850f, y = 150f,
            eventTime = 13035L,
            downTime = 13000L,
            forcedImpulse = true
        )

        assertEquals(CompactTouchBuffer.TYPE_HUMAN_FAST_TAP, result)
        assertEquals(1L, engine.buffer.totalHumanCount)
        assertEquals(0L, engine.buffer.totalGhostCount)
    }

    @Test
    fun testClockPeriodicGlitchIsBlocked() {
        engine.calibratedAvgPeriodMs = 100L
        engine.calibratedVariancePercent = 2.0f

        // First event
        engine.processTouch(MotionEvent.ACTION_DOWN, x = 500f, y = 500f, eventTime = 14000L)
        engine.processTouch(MotionEvent.ACTION_UP, x = 500f, y = 500f, eventTime = 14030L, downTime = 14000L, forcedImpulse = true)

        // Periodic glitch fires exactly 100ms later without physical shockwave
        engine.processTouch(MotionEvent.ACTION_DOWN, x = 500f, y = 500f, eventTime = 14130L)
        val result = engine.processTouch(
            MotionEvent.ACTION_UP,
            x = 500f, y = 500f,
            eventTime = 14160L,
            downTime = 14130L,
            forcedImpulse = false
        )

        assertEquals(CompactTouchBuffer.TYPE_GHOST_IDLE, result)
    }
}
