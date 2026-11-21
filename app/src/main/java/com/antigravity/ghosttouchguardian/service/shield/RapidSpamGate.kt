package com.antigravity.ghosttouchguardian.service.shield

import android.view.MotionEvent

/**
 * RapidSpamGate — Single-Task Component for Sub-45ms High-Frequency Burst Rejection.
 *
 * Responsibilities:
 * - Detects capacitive resonance chatter (< 45ms intervals, up to 10 touches in 1ms).
 * - Enforces zero-allocation instant drops.
 * - Triggers immediate shield re-arming if pass-through was momentarily open.
 */
class RapidSpamGate {

    private var lastTouchTime: Long = 0L

    /**
     * Checks whether an incoming touch is a high-frequency ghost burst.
     *
     * Returns true if the touch is spam (<45ms) and must be swallowed instantly!
     */
    fun isSpamBurst(event: MotionEvent, isStylus: Boolean): Boolean {
        if (isStylus) {
            lastTouchTime = event.eventTime
            return false
        }

        val now = event.eventTime
        val interval = if (lastTouchTime > 0L) (now - lastTouchTime).coerceAtLeast(0L) else Long.MAX_VALUE
        lastTouchTime = now

        return interval in 0L..44L
    }

    fun getLastInterval(now: Long): Long {
        return if (lastTouchTime > 0L) (now - lastTouchTime).coerceAtLeast(1L) else 25L
    }

    fun reset() {
        lastTouchTime = 0L
    }
}
