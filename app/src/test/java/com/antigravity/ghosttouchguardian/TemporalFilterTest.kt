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
        filter.registerValidFingerRelease(fingerReleaseTime)

        // Stray ghost pulse arrives 25ms after finger release (within 45ms window)
        val ghostArrivalTime = 1025L
        val shouldSuppress = filter.shouldSuppressTouch(
            eventTime = ghostArrivalTime,
            isDown = true,
            isStylus = false
        )

        assertTrue("Ghost echo inside 45ms cooldown window must be suppressed", shouldSuppress)
    }

    @Test
    fun testTouchOutsideCooldownWindowIsAllowed() {
        val fingerReleaseTime = 1000L
        filter.registerValidFingerRelease(fingerReleaseTime)

        // Legitimate intentional next tap arrives 120ms later
        val legitimateTouchTime = 1120L
        val shouldSuppress = filter.shouldSuppressTouch(
            eventTime = legitimateTouchTime,
            isDown = true,
            isStylus = false
        )

        assertFalse("Legitimate tap outside echo window must not be suppressed", shouldSuppress)
    }

    @Test
    fun testSPenIsAlwaysAllowedEvenInsideWindow() {
        val fingerReleaseTime = 1000L
        filter.registerValidFingerRelease(fingerReleaseTime)

        // S Pen arrives 15ms after finger release
        val spenTime = 1015L
        val shouldSuppress = filter.shouldSuppressTouch(
            eventTime = spenTime,
            isDown = true,
            isStylus = true // S Pen Stylus!
        )

        assertFalse("S Pen stylus must NEVER be suppressed", shouldSuppress)
    }

    @Test
    fun testDisabledFilterDoesNotSuppress() {
        filter.isEnabled = false
        filter.registerValidFingerRelease(1000L)

        val shouldSuppress = filter.shouldSuppressTouch(
            eventTime = 1015L,
            isDown = true,
            isStylus = false
        )

        assertFalse("Disabled filter must allow all touches", shouldSuppress)
    }
}
