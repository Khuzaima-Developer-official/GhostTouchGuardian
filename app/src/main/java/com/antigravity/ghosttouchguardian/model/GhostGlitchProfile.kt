package com.antigravity.ghosttouchguardian.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * GreenSafeZone — Interactive stimulus zone where user can touch to provoke post-touch glitches.
 * Touches inside this zone are NEVER classified as ghost touches.
 */
data class GreenSafeZone(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val label: String = "Safe Pad"
) {
    fun contains(x: Float, y: Float): Boolean {
        return x in left..right && y in top..bottom
    }
}

/**
 * GhostGlitchProfile — Stores learned hardware glitch characteristics.
 * Uses range envelopes rather than rigid single values so that frequency fluctuations
 * and varying intervals are all captured as the same ghost touch TYPE.
 */
data class GhostGlitchProfile(
    val minDwell: Int = 8,
    val maxDwell: Int = 95,
    val minIntervalMs: Long = 25L,
    val maxIntervalMs: Long = 650L,
    val maxAccelDelta: Float = 0.016f,
    val postInteractionEchoWindowMs: Long = 1500L,
    val sampleCount: Int = 0,
    val greenZones: List<GreenSafeZone> = emptyList()
) {
    val isValid: Boolean get() = sampleCount >= 2

    fun matchesGlitchType(
        dwell: Int,
        accelDelta: Float,
        intervalFromPrevMs: Long = -1L,
        timeSinceLastHumanUp: Long = -1L
    ): Boolean {
        // Zero physical momentum is mandatory for hardware noise:
        if (accelDelta >= maxAccelDelta) return false

        // Dwell must fall within the electrical transient spectrum:
        val inDwellSpectrum = dwell in minDwell..maxDwell

        // Trailing post-interaction echo (dielectric surface charge):
        val isTrailingEcho = timeSinceLastHumanUp in 2L..postInteractionEchoWindowMs && dwell <= 100

        // Frequency interval match: if part of an ongoing stochastic burst
        val inFrequencyBand = intervalFromPrevMs in minIntervalMs..maxIntervalMs && dwell <= maxDwell

        return (inDwellSpectrum && (inFrequencyBand || isTrailingEcho || dwell <= 45))
    }

    companion object {
        fun defaultProfile() = GhostGlitchProfile(
            minDwell = 8,
            maxDwell = 95,
            minIntervalMs = 25L,
            maxIntervalMs = 650L,
            maxAccelDelta = 0.016f,
            postInteractionEchoWindowMs = 1500L,
            sampleCount = 0 // Uncalibrated by default
        )

        fun fromEntries(entries: List<GhostTouchEntry>, greenZones: List<GreenSafeZone> = emptyList()): GhostGlitchProfile {
            if (entries.isEmpty()) return defaultProfile()

            val dwells = entries.map { it.dwell }
            val intervals = entries.map { it.intervalFromPrevMs }.filter { it in 15L..2000L }
            val accels = entries.map { it.accelDelta }

            val minD = (dwells.minOrNull() ?: 8).coerceAtLeast(4)
            val maxD = (dwells.maxOrNull() ?: 85).coerceIn(40, 110)

            val minI = if (intervals.isNotEmpty()) (intervals.minOrNull() ?: 25L).coerceAtLeast(15L) else 25L
            val maxI = if (intervals.isNotEmpty()) ((intervals.maxOrNull() ?: 600L) * 1.3f).toLong().coerceIn(300L, 1200L) else 650L

            val maxAcc = if (accels.isNotEmpty()) ((accels.maxOrNull() ?: 0.012f) + 0.004f).coerceIn(0.012f, 0.020f) else 0.016f

            return GhostGlitchProfile(
                minDwell = minD,
                maxDwell = maxD,
                minIntervalMs = minI,
                maxIntervalMs = maxI,
                maxAccelDelta = maxAcc,
                postInteractionEchoWindowMs = 1500L,
                sampleCount = entries.size,
                greenZones = greenZones
            )
        }

        fun toJson(profile: GhostGlitchProfile): String {
            val zonesArray = JSONArray()
            for (z in profile.greenZones) {
                zonesArray.put(JSONObject().apply {
                    put("l", z.left.toDouble())
                    put("t", z.top.toDouble())
                    put("r", z.right.toDouble())
                    put("b", z.bottom.toDouble())
                    put("lbl", z.label)
                })
            }
            return JSONObject().apply {
                put("minDwell", profile.minDwell)
                put("maxDwell", profile.maxDwell)
                put("minIntervalMs", profile.minIntervalMs)
                put("maxIntervalMs", profile.maxIntervalMs)
                put("maxAccelDelta", profile.maxAccelDelta.toDouble())
                put("postInteractionEchoWindowMs", profile.postInteractionEchoWindowMs)
                put("sampleCount", profile.sampleCount)
                put("greenZones", zonesArray)
            }.toString()
        }

        fun fromJson(jsonStr: String): GhostGlitchProfile {
            if (jsonStr.isBlank()) return defaultProfile()
            return try {
                val obj = JSONObject(jsonStr)
                val zonesList = mutableListOf<GreenSafeZone>()
                val zonesArray = obj.optJSONArray("greenZones")
                if (zonesArray != null) {
                    for (i in 0 until zonesArray.length()) {
                        val zo = zonesArray.getJSONObject(i)
                        zonesList.add(GreenSafeZone(
                            left = zo.optDouble("l", 0.0).toFloat(),
                            top = zo.optDouble("t", 0.0).toFloat(),
                            right = zo.optDouble("r", 0.0).toFloat(),
                            bottom = zo.optDouble("b", 0.0).toFloat(),
                            label = zo.optString("lbl", "Safe Zone")
                        ))
                    }
                }
                GhostGlitchProfile(
                    minDwell = obj.optInt("minDwell", 8),
                    maxDwell = obj.optInt("maxDwell", 95),
                    minIntervalMs = obj.optLong("minIntervalMs", 25L),
                    maxIntervalMs = obj.optLong("maxIntervalMs", 650L),
                    maxAccelDelta = obj.optDouble("maxAccelDelta", 0.016).toFloat(),
                    postInteractionEchoWindowMs = obj.optLong("postInteractionEchoWindowMs", 1500L),
                    sampleCount = obj.optInt("sampleCount", 0),
                    greenZones = zonesList
                )
            } catch (_: Exception) {
                defaultProfile()
            }
        }
    }
}
