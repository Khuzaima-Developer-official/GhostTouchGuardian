package com.antigravity.ghosttouchguardian.engine.ai

import com.antigravity.ghosttouchguardian.model.FingerIntensityMargin
import org.json.JSONObject

/**
 * GhostTypeClassifier — Single-Task Broad-Spectrum Ghost Type & Archetype Classifier.
 *
 * Implements User Requirement:
 * "wo uss specific frequency nhin poori type ko block krne ki capablity rakhe
 *  chahe real time ho ya mode mein identify ho."
 *
 * Categorizes incoming touches by physical TYPE rather than isolated narrow frequencies or single coordinates:
 * 1. Ghost Type:
 *    - Zero chassis momentum shockwave (accelDelta < 0.018g & no impulse)
 *    - Static / liquid pooling with zero biological micro-tremor (< 0.28 px²)
 *    - Sub-human frequency chatter (< 48ms interval or < 45ms dwell)
 *    - Matches broad dynamic ghost archetype envelope
 * 2. Human Type:
 *    - Confirmed physical chassis impact (Newtonian strike)
 *    - Physiological micro-tremor variance (8-12Hz involuntary tremor)
 *    - Calibrated FingerIntensityMargin match
 *    - Calibrated Green Safe Zone
 */
class GhostTypeClassifier {

    // Dynamic Ghost Archetype Boundaries (Generalizes across the entire class)
    var ghostSampleCount: Int = 0
        private set
    var maxGhostImpulse: Float = 0.015f
        private set
    var maxGhostTremor: Float = 0.25f
        private set
    var minGhostInterval: Long = 1L
        private set
    var maxGhostInterval: Long = 65L
        private set

    /**
     * Ingests a confirmed ghost touch (from S-Pen mode, profiler mode, or real-time)
     * and broadens the Ghost Type boundary so ALL similar touches are blocked everywhere.
     */
    fun learnGhostType(accelDelta: Float, tremorVar: Float, intervalMs: Long) {
        ghostSampleCount++
        maxGhostImpulse = maxOf(maxGhostImpulse, accelDelta.coerceAtMost(0.018f))
        maxGhostTremor = maxOf(maxGhostTremor, tremorVar.coerceAtMost(0.28f))
        if (intervalMs in 1L..120L) {
            maxGhostInterval = maxOf(maxGhostInterval, intervalMs)
        }
    }

    /**
     * Determines whether an incoming touch belongs to the GHOST TYPE across the entire screen.
     *
     * Returns true if the touch is categorically a ghost and MUST BE BLOCKED!
     */
    fun isGhostType(
        dwellMs: Int,
        size: Float,
        pressure: Float,
        accelDelta: Float,
        hasImpulse: Boolean,
        microTremorVar: Float,
        intervalMs: Long,
        isStylus: Boolean,
        inGreenZone: Boolean,
        fingerMargin: FingerIntensityMargin
    ): Boolean {
        // Stylus is never a ghost (Wacom EMR magnetic input)
        if (isStylus) return false

        // Calibrated green safe zone is explicitly protected
        if (inGreenZone) return false

        // 1. Sub-Human Burst Type: Any touch < 48ms interval or < 45ms dwell is physically impossible for human
        if (intervalMs in 0L..47L || (dwellMs in 1..44)) {
            return true // 100% Ghost Type
        }

        // 2. Personal Human Margin Check: If touch exhibits verified human impulse & tremor from profiler
        val isVerifiedHumanByMargin = hasImpulse && fingerMargin.sampleCount >= 3 &&
                fingerMargin.isWithinHumanMargin(pressure, size, dwellMs, accelDelta) &&
                microTremorVar >= (fingerMargin.minMicroTremorVariance * 0.75f)

        if (isVerifiedHumanByMargin) {
            return false // Confirmed human
        }

        // 3. True Human Invariants: Requires chassis momentum OR biological tremor
        val hasPhysicalMomentum = hasImpulse && accelDelta >= 0.020f
        val hasBiologicalTremor = microTremorVar >= 0.38f && dwellMs >= 55
        val hasHumanDwellWithMomentum = dwellMs >= 100 && (hasImpulse || microTremorVar >= 0.28f)

        if (hasPhysicalMomentum || hasBiologicalTremor || hasHumanDwellWithMomentum) {
            return false // Confirmed human
        }

        // 4. Ghost Type Invariant: Zero momentum AND zero biological tremor
        // Any contact with no chassis momentum and static liquid jitter is 100% GHOST TYPE!
        val isZeroMomentum = !hasImpulse && accelDelta < 0.018f
        val isZeroTremor = microTremorVar < 0.28f

        if (isZeroMomentum && isZeroTremor) {
            return true // 100% GHOST TYPE — BLOCK EVERYWHERE!
        }

        // 5. Broad Learned Ghost Archetype Match
        if (accelDelta <= maxGhostImpulse && microTremorVar <= maxGhostTremor) {
            return true // Matches generalized ghost archetype
        }

        return false
    }

    fun toJson(): String {
        return JSONObject().apply {
            put("ghostSampleCount", ghostSampleCount)
            put("maxGhostImpulse", maxGhostImpulse.toDouble())
            put("maxGhostTremor", maxGhostTremor.toDouble())
            put("minGhostInterval", minGhostInterval)
            put("maxGhostInterval", maxGhostInterval)
        }.toString()
    }

    fun loadFromJson(jsonStr: String) {
        if (jsonStr.isBlank()) return
        try {
            val json = JSONObject(jsonStr)
            ghostSampleCount = json.optInt("ghostSampleCount", 0)
            maxGhostImpulse = json.optDouble("maxGhostImpulse", 0.015).toFloat()
            maxGhostTremor = json.optDouble("maxGhostTremor", 0.25).toFloat()
            minGhostInterval = json.optLong("minGhostInterval", 1L)
            maxGhostInterval = json.optLong("maxGhostInterval", 65L)
        } catch (_: Exception) {}
    }
}
