package com.antigravity.ghosttouchguardian.model

import org.json.JSONObject

/**
 * HumanFingerProfile — Stores calibrated biological touch baselines for the user.
 * Ensures the user's real clicks and typing are NEVER blocked.
 */
data class HumanFingerProfile(
    val minShockwaveThreshold: Float = 0.018f,
    val minContactSize: Float = 0.035f,
    val minPressure: Float = 0.08f,
    val minDwell: Int = 30,
    val maxTapDwell: Int = 250,
    val sampleCount: Int = 0
) {
    val isValid: Boolean get() = sampleCount >= 3

    fun matchesHuman(dwell: Int, size: Float, pressure: Float, accelDelta: Float): Boolean {
        // Any single definitive human biological marker guarantees human intent:
        // 1. Newtonian momentum: physical impact transferred to phone chassis
        if (accelDelta >= minShockwaveThreshold) return true
        // 2. Flesh contact: soft tissue deformation on glass
        if (size >= minContactSize || pressure >= minPressure) return true
        // 3. Human dwell: intentional hold or normal click duration
        if (dwell >= 110) return true
        return false
    }

    companion object {
        fun defaultProfile() = HumanFingerProfile(
            minShockwaveThreshold = 0.018f,
            minContactSize = 0.035f,
            minPressure = 0.08f,
            minDwell = 30,
            maxTapDwell = 250,
            sampleCount = 0 // Uncalibrated by default
        )

        fun fromSamples(samples: List<Pair<Float, Float>>): HumanFingerProfile {
            // samples: list of Pair(size, pressure) or similar
            if (samples.isEmpty()) return defaultProfile()
            val avgSize = samples.map { it.first }.average().toFloat().coerceAtLeast(0.030f)
            val avgPressure = samples.map { it.second }.average().toFloat().coerceAtLeast(0.070f)
            return HumanFingerProfile(
                minShockwaveThreshold = 0.016f,
                minContactSize = (avgSize * 0.70f).coerceIn(0.025f, 0.060f),
                minPressure = (avgPressure * 0.65f).coerceIn(0.05f, 0.15f),
                minDwell = 25,
                maxTapDwell = 280,
                sampleCount = samples.size
            )
        }

        fun toJson(profile: HumanFingerProfile): String {
            return JSONObject().apply {
                put("minShockwaveThreshold", profile.minShockwaveThreshold.toDouble())
                put("minContactSize", profile.minContactSize.toDouble())
                put("minPressure", profile.minPressure.toDouble())
                put("minDwell", profile.minDwell)
                put("maxTapDwell", profile.maxTapDwell)
                put("sampleCount", profile.sampleCount)
            }.toString()
        }

        fun fromJson(jsonStr: String): HumanFingerProfile {
            if (jsonStr.isBlank()) return defaultProfile()
            return try {
                val obj = JSONObject(jsonStr)
                HumanFingerProfile(
                    minShockwaveThreshold = obj.optDouble("minShockwaveThreshold", 0.018).toFloat(),
                    minContactSize = obj.optDouble("minContactSize", 0.035).toFloat(),
                    minPressure = obj.optDouble("minPressure", 0.08).toFloat(),
                    minDwell = obj.optInt("minDwell", 30),
                    maxTapDwell = obj.optInt("maxTapDwell", 250),
                    sampleCount = obj.optInt("sampleCount", 0)
                )
            } catch (_: Exception) {
                defaultProfile()
            }
        }
    }
}
