package com.antigravity.ghosttouchguardian.model

import org.json.JSONObject

/**
 * FingerIntensityMargin — Personal Dynamic Measurement Envelope for Human Fingers.
 *
 * Measures and records real human finger touch characteristics across varied intensities:
 * - Light taps (feather touches, gentle navigation)
 * - Normal taps (typing, scrolling)
 * - Firm presses (buttons, holds, dragging)
 *
 * Stores the empirical boundaries (min & max) to guarantee that the AI knows
 * the exact physical spectrum of the user's hand without false ghost positives.
 */
data class FingerIntensityMargin(
    var minPressure: Float = 0.08f,
    var maxPressure: Float = 0.95f,
    var minSize: Float = 0.030f,
    var maxSize: Float = 0.35f,
    var minDwellMs: Int = 45,
    var maxDwellMs: Int = 1800,
    var minImpulseG: Float = 0.015f,
    var maxImpulseG: Float = 0.450f,
    var minMicroTremorVariance: Float = 0.35f,
    var sampleCount: Int = 0
) {

    /**
     * Ingests a verified human tap and expands/calibrates the dynamic margin envelope.
     */
    fun recordSample(
        pressure: Float,
        size: Float,
        dwellMs: Int,
        impulseG: Float,
        tremorVar: Float
    ) {
        if (sampleCount == 0) {
            minPressure = pressure
            maxPressure = pressure
            minSize = size
            maxSize = size
            minDwellMs = dwellMs
            maxDwellMs = dwellMs
            minImpulseG = impulseG
            maxImpulseG = impulseG
            minMicroTremorVariance = tremorVar
        } else {
            minPressure = minOf(minPressure, pressure)
            maxPressure = maxOf(maxPressure, pressure)
            minSize = minOf(minSize, size)
            maxSize = maxOf(maxSize, size)
            minDwellMs = minOf(minDwellMs, dwellMs)
            maxDwellMs = maxOf(maxDwellMs, dwellMs)
            minImpulseG = minOf(minImpulseG, impulseG)
            maxImpulseG = maxOf(maxImpulseG, impulseG)
            minMicroTremorVariance = minOf(minMicroTremorVariance, tremorVar)
        }
        sampleCount++
    }

    fun isWithinHumanMargin(
        pressure: Float,
        size: Float,
        dwellMs: Int,
        impulseG: Float
    ): Boolean {
        if (sampleCount < 3) return false // Not enough calibration yet
        val pressureMatches = pressure in (minPressure * 0.85f)..(maxPressure * 1.15f)
        val sizeMatches = size in (minSize * 0.85f)..(maxSize * 1.15f)
        val dwellMatches = dwellMs in (minDwellMs - 15)..(maxDwellMs + 200)
        val impulseMatches = impulseG >= (minImpulseG * 0.80f)
        return impulseMatches && (pressureMatches || sizeMatches || dwellMatches)
    }

    fun toJson(): String {
        return JSONObject().apply {
            put("minPressure", minPressure.toDouble())
            put("maxPressure", maxPressure.toDouble())
            put("minSize", minSize.toDouble())
            put("maxSize", maxSize.toDouble())
            put("minDwellMs", minDwellMs)
            put("maxDwellMs", maxDwellMs)
            put("minImpulseG", minImpulseG.toDouble())
            put("maxImpulseG", maxImpulseG.toDouble())
            put("minMicroTremorVariance", minMicroTremorVariance.toDouble())
            put("sampleCount", sampleCount)
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): FingerIntensityMargin {
            if (jsonStr.isBlank()) return FingerIntensityMargin()
            return try {
                val json = JSONObject(jsonStr)
                FingerIntensityMargin(
                    minPressure = json.optDouble("minPressure", 0.08).toFloat(),
                    maxPressure = json.optDouble("maxPressure", 0.95).toFloat(),
                    minSize = json.optDouble("minSize", 0.030).toFloat(),
                    maxSize = json.optDouble("maxSize", 0.35).toFloat(),
                    minDwellMs = json.optInt("minDwellMs", 45),
                    maxDwellMs = json.optInt("maxDwellMs", 1800),
                    minImpulseG = json.optDouble("minImpulseG", 0.015).toFloat(),
                    maxImpulseG = json.optDouble("maxImpulseG", 0.450).toFloat(),
                    minMicroTremorVariance = json.optDouble("minMicroTremorVariance", 0.35).toFloat(),
                    sampleCount = json.optInt("sampleCount", 0)
                )
            } catch (_: Exception) {
                FingerIntensityMargin()
            }
        }
    }
}
