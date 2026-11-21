package com.antigravity.ghosttouchguardian.service.shield

import android.view.MotionEvent
import com.antigravity.ghosttouchguardian.model.FingerIntensityMargin
import com.antigravity.ghosttouchguardian.model.GreenSafeZone
import com.antigravity.ghosttouchguardian.model.HumanFingerProfile

/**
 * HumanImpulseGate — Single-Task Component for Physical Human Touch Verification.
 *
 * Responsibilities:
 * - Checks chassis accelerometer impulse shockwaves (Newtonian strike).
 * - Matches touch against personal FingerIntensityMargin boundaries.
 * - Enforces biological micro-tremor requirements.
 * - Rejects conductive water droplets (which produce voltage/pressure but ZERO physical momentum).
 */
class HumanImpulseGate {

    fun isHumanTap(
        event: MotionEvent,
        hasImpulse: Boolean,
        effectiveAccel: Float,
        greenZones: List<GreenSafeZone>,
        humanProfile: HumanFingerProfile,
        margin: FingerIntensityMargin
    ): Boolean {
        // 1. Calibrated Safe Zone
        val inGreenZone = greenZones.any { it.contains(event.x, event.y) }
        if (inGreenZone) return true

        // 2. Personal Intensity Margin Match (Verified chassis impulse + human envelope)
        if (hasImpulse && margin.sampleCount >= 3) {
            if (margin.isWithinHumanMargin(event.pressure, event.size, 50, effectiveAccel)) {
                return true
            }
        }

        // 3. Trained Profile Match with Physical Shockwave
        if (hasImpulse && humanProfile.isValid && humanProfile.matchesHuman(0, event.size, event.pressure, effectiveAccel)) {
            return true
        }

        // 4. Raw Chassis Shockwave (Physical finger strike on glass)
        if (hasImpulse && effectiveAccel >= 0.020f) {
            return true
        }

        return false
    }
}
