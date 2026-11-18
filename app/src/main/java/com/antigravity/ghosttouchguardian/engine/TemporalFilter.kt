package com.antigravity.ghosttouchguardian.engine

import com.antigravity.ghosttouchguardian.model.GhostTouchProfile

class TemporalFilter {

    @Volatile
    var isEnabled: Boolean = true

    var echoCooldownWindowMs: Long = GhostTouchProfile.BALANCED.echoWindowMs
    var minValidDwellTimeMs: Int = GhostTouchProfile.BALANCED.maxDwellMs

    private var lastValidReleaseTimestamp: Long = 0L

    fun applyProfile(profile: GhostTouchProfile) {
        // Set profile
        echoCooldownWindowMs = profile.echoWindowMs
        minValidDwellTimeMs = profile.maxDwellMs
    }

    fun shouldSuppressTouch(eventTime: Long, isDown: Boolean, isStylus: Boolean): Boolean {
        // Stylus bypass
        if (!isEnabled || isStylus) return false

        if (isDown) {
            val delta = eventTime - lastValidReleaseTimestamp
            // Cooldown check
            if (lastValidReleaseTimestamp > 0L && delta in 2L..echoCooldownWindowMs) {
                return true
            }
        }
        return false
    }

    fun registerValidFingerRelease(eventTime: Long) {
        // Record release
        lastValidReleaseTimestamp = eventTime
    }

    fun flush() {
        // Reset timestamp
        lastValidReleaseTimestamp = 0L
    }
}
