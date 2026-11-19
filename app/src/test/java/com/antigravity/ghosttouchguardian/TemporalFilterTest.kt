package com.antigravity.ghosttouchguardian

import com.antigravity.ghosttouchguardian.engine.TemporalFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TemporalFilterTest {

    private lateinit var filter: TemporalFilter

    @Before
    fun setUp() {
        filter = TemporalFilter().apply {
            echoCooldownWindowMs = 45L
            isEnabled = true
        }
    }

    @Test
    fun testEchoWithinCooldownWindowIsSuppressed() {
        val fingerReleaseTime = 1000L
        filter.registerValidFingerRelease(fingerReleaseTime, 100f, 100f)

        // Stray ghost pulse arrives 10ms after finger release at same spot
        val ghostArrivalTime = 1010L
        val shouldSuppress = filter.shouldSuppressTouch(
            eventTime = ghostArrivalTime,
            isDown = true,
            isStylus = false,
            x = 104f,
            y = 103f
        )

        assertTrue("Ghost echo inside 2-25ms cooldown window must be suppressed", shouldSuppress)
    }

    @Test
    fun testTouchOutsideCooldownWindowIsAllowed() {
        val fingerReleaseTime = 1000L
        filter.registerValidFingerRelease(fingerReleaseTime, 100f, 100f)

        // Legitimate intentional next tap arrives 120ms later
        val legitimateTouchTime = 1120L
        val shouldSuppress = filter.shouldSuppressTouch(
            eventTime = legitimateTouchTime,
            isDown = true,
            isStylus = false,
            x = 100f,
            y = 100f
        )

        assertFalse("Legitimate tap outside echo window must not be suppressed", shouldSuppress)
    }

    @Test
    fun testSPenIsAlwaysAllowedEvenInsideWindow() {
        val fingerReleaseTime = 1000L
        filter.registerValidFingerRelease(fingerReleaseTime, 100f, 100f)

        // S Pen arrives 15ms after finger release
        val spenTime = 1015L
        val shouldSuppress = filter.shouldSuppressTouch(
            eventTime = spenTime,
            isDown = true,
            isStylus = true,
            x = 100f,
            y = 100f
        )

        assertFalse("S Pen stylus must NEVER be suppressed", shouldSuppress)
    }

    @Test
    fun testDisabledFilterDoesNotSuppress() {
        filter.isEnabled = false
        filter.registerValidFingerRelease(1000L, 100f, 100f)

        val shouldSuppress = filter.shouldSuppressTouch(
            eventTime = 1015L,
            isDown = true,
            isStylus = false,
            x = 100f,
            y = 100f
        )

        assertFalse("Disabled filter must allow all touches", shouldSuppress)
    }

    @Test
    fun testDifferentLocationTapIsNeverSuppressed() {
        filter.registerValidFingerRelease(1000L, 100f, 100f)

        // Rapid tap on another button (e.g. typing or gaming) 15ms later at x=250, y=100
        val nextKeyTime = 1015L
        val shouldSuppress = filter.shouldSuppressTouch(
            eventTime = nextKeyTime,
            isDown = true,
            isStylus = false,
            x = 250f,
            y = 100f
        )

        assertFalse("Rapid tap on different button (>28px away) must NEVER be suppressed", shouldSuppress)
    }

    @Test
    fun testHardClickFiftyMsIsNeverSuppressed() {
        filter.registerValidFingerRelease(1000L, 100f, 100f)

        // User does a hard click 50ms later
        val clickTime = 1050L
        val shouldSuppress = filter.shouldSuppressTouch(
            eventTime = clickTime,
            isDown = true,
            isStylus = false,
            x = 100f,
            y = 100f
        )

        assertFalse("50ms click must NEVER be suppressed", shouldSuppress)
    }

    @Test
    fun testFeatherTouchTinyIconNotSuppressed() {
        filter.registerValidFingerRelease(1000L, 100f, 100f)

        // Feather touch on tiny close button 70ms later with tiny contact size
        val clickTime = 1070L
        val shouldSuppress = filter.shouldSuppressTouch(
            eventTime = clickTime,
            isDown = true,
            isStylus = false,
            x = 100f,
            y = 100f,
            touchSize = 0.01f
        )

        assertFalse("Feather touch on tiny icon must NEVER be suppressed", shouldSuppress)
    }
}
