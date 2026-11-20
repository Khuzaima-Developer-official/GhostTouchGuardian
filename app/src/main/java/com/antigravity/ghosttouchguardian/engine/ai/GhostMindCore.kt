package com.antigravity.ghosttouchguardian.engine.ai

import com.antigravity.ghosttouchguardian.model.CompactTouchBuffer
import com.antigravity.ghosttouchguardian.model.GhostGlitchProfile
import com.antigravity.ghosttouchguardian.model.HumanFingerProfile

data class AIClassification(
    val type: Byte,
    val isGhost: Boolean,
    val confidence: Float,
    val reason: String
) {
    companion object {
        fun human(confidence: Float = 0.95f, reason: String = "Human Intent"): AIClassification =
            AIClassification(CompactTouchBuffer.TYPE_HUMAN_FINGER, false, confidence, reason)

        fun fastHumanClick(confidence: Float = 0.90f, reason: String = "Human Fast Tap"): AIClassification =
            AIClassification(CompactTouchBuffer.TYPE_HUMAN_FAST_TAP, false, confidence, reason)

        fun ghostSpark(confidence: Float = 0.90f, reason: String = "Ghost Electrical Spark"): AIClassification =
            AIClassification(CompactTouchBuffer.TYPE_GHOST_IDLE, true, confidence, reason)

        fun ghostEcho(confidence: Float = 0.92f, reason: String = "Trailing Dielectric Echo"): AIClassification =
            AIClassification(CompactTouchBuffer.TYPE_GHOST_ECHO, true, confidence, reason)

        fun ghostSpectrum(confidence: Float = 0.88f, reason: String = "Ghost Spectrum Match"): AIClassification =
            AIClassification(CompactTouchBuffer.TYPE_GHOST_HOTSPOT, true, confidence, reason)

        fun spen(reason: String = "S Pen Stylus Input"): AIClassification =
            AIClassification(CompactTouchBuffer.TYPE_SPEN, false, 1.0f, reason)

        fun spenGhostSuppressed(reason: String = "S Pen Static Echo Suppressed"): AIClassification =
            AIClassification(CompactTouchBuffer.TYPE_GHOST_ECHO, true, 0.95f, reason)
    }
}

/**
 * GhostMindCore — Embedded AI Decision Engine.
 * Evaluates multi-factor touch tensors against learned Human and Ghost profiles.
 * Eliminates strict single-frequency checks in favor of stochastic distribution bands.
 */
class GhostMindCore(
    var humanProfile: HumanFingerProfile = HumanFingerProfile.defaultProfile(),
    var ghostProfile: GhostGlitchProfile = GhostGlitchProfile.defaultProfile(),
    var isSPenModeActive: Boolean = false
) {

    fun classify(
        toolType: Int,
        dwell: Int,
        size: Float,
        pressure: Float,
        accelDelta: Float,
        intervalFromPrevMs: Long = -1L,
        timeSinceLastHumanUp: Long = -1L,
        timeSinceLastStylusUp: Long = -1L,
        inGreenZone: Boolean = false
    ): AIClassification {

        // 1. Stylus Tool Priority
        if (toolType == android.view.MotionEvent.TOOL_TYPE_STYLUS) {
            return AIClassification.spen()
        }

        // 2. Green Safe Zone (Interactive Stimulus)
        if (inGreenZone) {
            return AIClassification.human(confidence = 1.0f, reason = "Green Safe Zone Stimulus")
        }

        // 3. Dedicated S Pen Mode Active Protection
        // In full-time S Pen mode, electrostatic discharge echoes and non-shockwave noise are clamped
        if (isSPenModeActive) {
            val isPostStylusWindow = timeSinceLastStylusUp in 2L..3000L
            val isZeroShock = accelDelta < 0.018f
            if ((isPostStylusWindow || isZeroShock) && dwell <= 100 && (size < 0.04f || pressure < 0.10f)) {
                return AIClassification.spenGhostSuppressed("Dedicated S Pen Mode Noise Block ($dwell ms)")
            }
        }

        // 4. Human Biometric Invariant Check
        // If biological mass momentum, flesh contact area, or intentional dwell is present:
        if (humanProfile.matchesHuman(dwell, size, pressure, accelDelta)) {
            return if (dwell < 75) {
                AIClassification.fastHumanClick(0.92f, "Human Quick Click ($dwell ms)")
            } else {
                AIClassification.human(0.98f, "Human Touch ($dwell ms)")
            }
        }

        // 5. Post-Interaction Dielectric Echo Check
        if (timeSinceLastHumanUp in 2L..ghostProfile.postInteractionEchoWindowMs && dwell <= 100 && accelDelta < 0.018f) {
            return AIClassification.ghostEcho(0.94f, "Dielectric Echo (${timeSinceLastHumanUp}ms post-lift)")
        }

        // 6. S Pen trailing discharge (if outside S Pen mode)
        if (timeSinceLastStylusUp in 2L..1500L && dwell <= 90 && accelDelta < 0.018f) {
            return AIClassification.ghostEcho(0.92f, "S Pen Electrostatic Echo (${timeSinceLastStylusUp}ms)")
        }

        // 7. Ghost Glitch Range Spectrum Match
        // Any touch with zero momentum whose dwell falls in the glitch envelope:
        if (ghostProfile.isValid && ghostProfile.matchesGlitchType(dwell, accelDelta, intervalFromPrevMs, timeSinceLastHumanUp)) {
            return AIClassification.ghostSpectrum(0.89f, "Glitch Spectrum [${ghostProfile.minDwell}..${ghostProfile.maxDwell}ms] ($dwell ms)")
        }

        // 8. General Zero-Momentum Fast Spark (active when ghost profile is calibrated)
        if (ghostProfile.isValid && accelDelta < 0.015f && dwell <= 45 && size < 0.035f && pressure < 0.10f) {
            return AIClassification.ghostSpark(0.87f, "Zero-Momentum Spark ($dwell ms)")
        }

        // 9. Default Fallback: Human Touch
        return AIClassification.human(0.60f, "Human Default ($dwell ms)")
    }
}
