package com.antigravity.ghosttouchguardian.engine

import com.antigravity.ghosttouchguardian.model.GhostTouchProfile
import kotlin.math.sqrt

class TemporalFilter {

    @Volatile
    var isEnabled: Boolean = true

    var echoCooldownWindowMs: Long = GhostTouchProfile.BALANCED.echoWindowMs
    var minValidDwellTimeMs: Int = GhostTouchProfile.BALANCED.maxDwellMs

    private var lastValidReleaseTimestamp: Long = 0L
    private var lastValidReleaseX: Float = -1f
    private var lastValidReleaseY: Float = -1f

    fun applyProfile(profile: GhostTouchProfile) {
        // Set profile
        echoCooldownWindowMs = profile.echoWindowMs
        minValidDwellTimeMs = profile.maxDwellMs
    }

    fun shouldSuppressTouch(
        eventTime: Long,
        isDown: Boolean,
        isStylus: Boolean,
        x: Float = 0f,
        y: Float = 0f,
        touchSize: Float = 0f
    ): Boolean {
        // Stylus bypass
        if (!isEnabled || isStylus) return false

        // Biological pass
        if (touchSize >= 0.06f) return false

        if (isDown && lastValidReleaseTimestamp > 0L) {
            val delta = eventTime - lastValidReleaseTimestamp
            if (delta in 2L..echoCooldownWindowMs) {
                // Proximity check
                if (lastValidReleaseX >= 0f && lastValidReleaseY >= 0f) {
                    val dx = x - lastValidReleaseX
                    val dy = y - lastValidReleaseY
                    val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    // Localized echo
                    if (dist <= 45f) return true
                } else {
                    return true
                }
            }
        }
        return false
    }

    fun registerValidFingerRelease(eventTime: Long, x: Float = -1f, y: Float = -1f) {
        // Record release
        lastValidReleaseTimestamp = eventTime
        lastValidReleaseX = x
        lastValidReleaseY = y
    }

    fun flush() {
        // Reset timestamp
        lastValidReleaseTimestamp = 0L
        lastValidReleaseX = -1f
        lastValidReleaseY = -1f
    }
}
