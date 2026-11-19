package com.antigravity.ghosttouchguardian.model

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A single recorded ghost touch sample with all 12-factor dimensions.
 * Captured during Identify Mode where phantom touches are profiled.
 */
data class GhostTouchEntry(
    val x: Float,
    val y: Float,
    val dwell: Int,
    val size: Float,
    val pressure: Float,
    val touchMajor: Float,
    val historySize: Int,
    val accelDelta: Float,        // Accelerometer delta magnitude at touch time (0 = no shockwave)
    val intervalFromPrevMs: Long, // ms since previous touch
    val displacementPx: Float,    // px distance from previous touch
    val timestamp: Long,
    val timeSinceFingerMs: Long = -1L, // ms since human finger tap stimulus
    val velocityPxPerMs: Float = if (intervalFromPrevMs > 0) displacementPx / intervalFromPrevMs else 0f,
    val factorNotes: String = ""  // e.g. "postFingerGlitch(120ms), spark, noShockwave"
)

/**
 * A custom block rule manually configured or derived from a specific screen zone.
 */
data class CustomBlockRule(
    val description: String,
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
    val maxDwellMs: Int
) {
    fun matches(x: Float, y: Float, dwell: Int): Boolean {
        return x in minX..maxX && y in minY..maxY && dwell <= maxDwellMs
    }
}

/**
 * Statistical 12-Factor Ghost Fingerprint model.
 * Profiles device-specific electrical glitch signatures for precision blocking.
 */
data class GhostFingerprint(
    val entries: List<GhostTouchEntry>,
    // Factor 1: Spatial Centroid & Variance
    val avgX: Float,
    val avgY: Float,
    val xStdDev: Float,
    val yStdDev: Float,
    // Factor 2: Dwell Time Envelope
    val avgDwell: Float,
    val dwellStdDev: Float,
    // Factor 3: Contact Surface Patch
    val avgSize: Float,
    val sizeStdDev: Float,
    // Factor 4: Contact Force Gradient
    val avgPressure: Float,
    // Factor 5: Physical Momentum (Accelerometer Delta)
    val avgAccelDelta: Float,
    // Factor 6: Digitizer Historical Vector Depth
    val avgHistorySize: Float,
    // Factor 7: Hardware Clock Periodicity
    val avgPeriodMs: Long,
    val periodVariance: Float,
    // Factor 8: Post-Finger Trailing Echo Delay
    val avgPostFingerDelayMs: Long,
    val postFingerEchoCount: Int,
    // Factor 9: Touch Major Axis
    val avgTouchMajor: Float,
    // Factor 10: Kinematic Velocity Jump Limit
    val maxVelocity: Float,
    // User Rules
    val customBlockRules: List<CustomBlockRule>
) {
    val isValid: Boolean get() = entries.size >= 3

    /**
     * Comprehensive 12-Factor Matching Algorithm.
     * Evaluates incoming touch across physical, biological, and temporal dimensions.
     * Returns a [0..1] probability score. Score >= 0.55 confirms a ghost match.
     */
    fun matchScore(
        x: Float,
        y: Float,
        dwell: Int,
        size: Float,
        pressure: Float,
        accelDelta: Float,
        historySize: Int = 0,
        timeSinceFingerMs: Long = -1L,
        intervalFromPrevMs: Long = -1L
    ): Float {
        if (!isValid) return 0f
        var score = 0f
        var total = 0f

        // Factor 1: Newtonian Momentum (absent in capacitive noise)
        total += 2.0f
        if (accelDelta < 0.04f) score += 2.0f
        else if (accelDelta < 0.08f) score += 1.0f

        // Factor 2: Biological Contact Flesh Patch (ghosts are sub-flesh)
        total += 1.5f
        val sizeMax = (avgSize + sizeStdDev * 2.5f).coerceAtLeast(0.035f)
        if (size <= sizeMax) score += 1.5f

        // Factor 3: Contact Pressure (ghosts have near-zero physical force)
        total += 1.5f
        if (pressure <= 0.12f) score += 1.5f
        else if (pressure <= 0.20f) score += 0.5f

        // Factor 4: Dwell Time distribution
        total += 1.5f
        val dwellRange = (dwellStdDev * 2.5f).coerceAtLeast(25f)
        if (abs(dwell - avgDwell) <= dwellRange) score += 1.5f
        else if (dwell <= 40) score += 1.0f // Electrical spark fallback

        // Factor 5: Spatial Proximity to Ghost Centroid or Cluster
        total += 1.5f
        val dx = x - avgX
        val dy = y - avgY
        val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        val spatialRadius = ((xStdDev + yStdDev) * 2.0f).coerceIn(120f, 350f)
        if (dist <= spatialRadius) {
            score += 1.5f * (1f - dist / spatialRadius)
        }

        // Factor 6: Digitizer Batch Trajectory (ghosts enter and exit in single sub-sample)
        total += 1.0f
        if (historySize == 0) score += 1.0f

        // Factor 7: Hardware Clock Periodicity
        if (avgPeriodMs in 10L..3000L && periodVariance < 15f && intervalFromPrevMs > 0L) {
            total += 1.5f
            val tol = (avgPeriodMs * 0.15f).coerceAtLeast(15f)
            if (abs(intervalFromPrevMs - avgPeriodMs) <= tol) score += 1.5f
        }

        // Factor 8: Post-Finger Trailing Echo Timing
        if (timeSinceFingerMs in 2L..800L && postFingerEchoCount > 0) {
            total += 1.5f
            score += 1.5f
        }

        return score / total
    }

    companion object {
        fun fromEntries(entries: List<GhostTouchEntry>, customRules: List<CustomBlockRule> = emptyList()): GhostFingerprint {
            if (entries.isEmpty()) return empty()

            fun List<Float>.mean() = if (isEmpty()) 0f else sum() / size
            fun List<Float>.stdDev(): Float {
                if (size < 2) return 0f
                val m = mean()
                return sqrt(map { (it - m) * (it - m) }.sum() / size).toFloat()
            }

            val xs = entries.map { it.x }
            val ys = entries.map { it.y }
            val dwells = entries.map { it.dwell.toFloat() }
            val sizes = entries.map { it.size }
            val pressures = entries.map { it.pressure }
            val accels = entries.map { it.accelDelta }
            val histories = entries.map { it.historySize.toFloat() }
            val majors = entries.map { it.touchMajor }
            val vels = entries.map { it.velocityPxPerMs }

            // Period stats
            val intervals = entries.map { it.intervalFromPrevMs }.filter { it in 5L..5000L }
            val avgPeriod = if (intervals.isEmpty()) 0L else (intervals.sum() / intervals.size)
            val periodVariance = if (intervals.size < 2 || avgPeriod == 0L) 100f else {
                val diffs = intervals.map { abs(it - avgPeriod).toFloat() }
                (diffs.sum() / diffs.size / avgPeriod * 100f)
            }

            // Post-finger echo latency
            val postFingerDelays = entries.map { it.timeSinceFingerMs }.filter { it in 2L..1500L }
            val avgPostFingerDelay = if (postFingerDelays.isEmpty()) 0L else (postFingerDelays.sum() / postFingerDelays.size)

            return GhostFingerprint(
                entries = entries,
                avgX = xs.mean(),
                avgY = ys.mean(),
                xStdDev = xs.stdDev(),
                yStdDev = ys.stdDev(),
                avgDwell = dwells.mean(),
                dwellStdDev = dwells.stdDev(),
                avgSize = sizes.mean(),
                sizeStdDev = sizes.stdDev(),
                avgPressure = pressures.mean(),
                avgAccelDelta = accels.mean(),
                avgHistorySize = histories.mean(),
                avgPeriodMs = avgPeriod,
                periodVariance = periodVariance,
                avgPostFingerDelayMs = avgPostFingerDelay,
                postFingerEchoCount = postFingerDelays.size,
                avgTouchMajor = majors.mean(),
                maxVelocity = if (vels.isEmpty()) 0f else vels.maxOrNull() ?: 0f,
                customBlockRules = customRules
            )
        }

        fun empty(): GhostFingerprint = GhostFingerprint(
            entries = emptyList(),
            avgX = 0f, avgY = 0f, xStdDev = 0f, yStdDev = 0f,
            avgDwell = 0f, dwellStdDev = 0f,
            avgSize = 0f, sizeStdDev = 0f,
            avgPressure = 0f,
            avgAccelDelta = 0f, avgHistorySize = 0f,
            avgPeriodMs = 0L, periodVariance = 100f,
            avgPostFingerDelayMs = 0L, postFingerEchoCount = 0,
            avgTouchMajor = 0f, maxVelocity = 0f,
            customBlockRules = emptyList()
        )

        // ---- JSON Serialization ----

        fun toJson(fp: GhostFingerprint): String {
            val root = JSONObject()
            root.put("avgX", fp.avgX)
            root.put("avgY", fp.avgY)
            root.put("xStdDev", fp.xStdDev)
            root.put("yStdDev", fp.yStdDev)
            root.put("avgDwell", fp.avgDwell)
            root.put("dwellStdDev", fp.dwellStdDev)
            root.put("avgSize", fp.avgSize)
            root.put("sizeStdDev", fp.sizeStdDev)
            root.put("avgPressure", fp.avgPressure)
            root.put("avgAccelDelta", fp.avgAccelDelta)
            root.put("avgHistorySize", fp.avgHistorySize)
            root.put("avgPeriodMs", fp.avgPeriodMs)
            root.put("periodVariance", fp.periodVariance)
            root.put("avgPostFingerDelayMs", fp.avgPostFingerDelayMs)
            root.put("postFingerEchoCount", fp.postFingerEchoCount)
            root.put("avgTouchMajor", fp.avgTouchMajor)
            root.put("maxVelocity", fp.maxVelocity)

            val entriesArr = JSONArray()
            fp.entries.takeLast(60).forEach { e ->
                val ej = JSONObject()
                ej.put("x", e.x); ej.put("y", e.y)
                ej.put("dwell", e.dwell)
                ej.put("size", e.size); ej.put("pressure", e.pressure)
                ej.put("touchMajor", e.touchMajor)
                ej.put("historySize", e.historySize)
                ej.put("accelDelta", e.accelDelta)
                ej.put("intervalFromPrevMs", e.intervalFromPrevMs)
                ej.put("displacementPx", e.displacementPx)
                ej.put("timestamp", e.timestamp)
                ej.put("timeSinceFingerMs", e.timeSinceFingerMs)
                ej.put("velocityPxPerMs", e.velocityPxPerMs)
                ej.put("factorNotes", e.factorNotes)
                entriesArr.put(ej)
            }
            root.put("entries", entriesArr)

            val rulesArr = JSONArray()
            fp.customBlockRules.forEach { r ->
                val rj = JSONObject()
                rj.put("description", r.description)
                rj.put("minX", r.minX); rj.put("maxX", r.maxX)
                rj.put("minY", r.minY); rj.put("maxY", r.maxY)
                rj.put("maxDwellMs", r.maxDwellMs)
                rulesArr.put(rj)
            }
            root.put("customRules", rulesArr)

            return root.toString()
        }

        fun fromJson(json: String): GhostFingerprint {
            if (json.isBlank()) return empty()
            return try {
                val root = JSONObject(json)
                val entriesArr = root.optJSONArray("entries") ?: JSONArray()
                val entries = (0 until entriesArr.length()).map { i ->
                    val ej = entriesArr.getJSONObject(i)
                    GhostTouchEntry(
                        x = ej.getDouble("x").toFloat(),
                        y = ej.getDouble("y").toFloat(),
                        dwell = ej.getInt("dwell"),
                        size = ej.getDouble("size").toFloat(),
                        pressure = ej.getDouble("pressure").toFloat(),
                        touchMajor = ej.optDouble("touchMajor", 0.0).toFloat(),
                        historySize = ej.optInt("historySize", 0),
                        accelDelta = ej.getDouble("accelDelta").toFloat(),
                        intervalFromPrevMs = ej.getLong("intervalFromPrevMs"),
                        displacementPx = ej.getDouble("displacementPx").toFloat(),
                        timestamp = ej.getLong("timestamp"),
                        timeSinceFingerMs = ej.optLong("timeSinceFingerMs", -1L),
                        velocityPxPerMs = ej.optDouble("velocityPxPerMs", 0.0).toFloat(),
                        factorNotes = ej.optString("factorNotes", "")
                    )
                }

                val rulesArr = root.optJSONArray("customRules") ?: JSONArray()
                val rules = (0 until rulesArr.length()).map { i ->
                    val rj = rulesArr.getJSONObject(i)
                    CustomBlockRule(
                        description = rj.getString("description"),
                        minX = rj.getDouble("minX").toFloat(),
                        maxX = rj.getDouble("maxX").toFloat(),
                        minY = rj.getDouble("minY").toFloat(),
                        maxY = rj.getDouble("maxY").toFloat(),
                        maxDwellMs = rj.getInt("maxDwellMs")
                    )
                }

                GhostFingerprint(
                    entries = entries,
                    avgX = root.optDouble("avgX", 0.0).toFloat(),
                    avgY = root.optDouble("avgY", 0.0).toFloat(),
                    xStdDev = root.optDouble("xStdDev", 0.0).toFloat(),
                    yStdDev = root.optDouble("yStdDev", 0.0).toFloat(),
                    avgDwell = root.optDouble("avgDwell", 0.0).toFloat(),
                    dwellStdDev = root.optDouble("dwellStdDev", 0.0).toFloat(),
                    avgSize = root.optDouble("avgSize", 0.0).toFloat(),
                    sizeStdDev = root.optDouble("sizeStdDev", 0.0).toFloat(),
                    avgPressure = root.optDouble("avgPressure", 0.0).toFloat(),
                    avgAccelDelta = root.optDouble("avgAccelDelta", 0.0).toFloat(),
                    avgHistorySize = root.optDouble("avgHistorySize", 0.0).toFloat(),
                    avgPeriodMs = root.optLong("avgPeriodMs", 0L),
                    periodVariance = root.optDouble("periodVariance", 100.0).toFloat(),
                    avgPostFingerDelayMs = root.optLong("avgPostFingerDelayMs", 0L),
                    postFingerEchoCount = root.optInt("postFingerEchoCount", 0),
                    avgTouchMajor = root.optDouble("avgTouchMajor", 0.0).toFloat(),
                    maxVelocity = root.optDouble("maxVelocity", 0.0).toFloat(),
                    customBlockRules = rules
                )
            } catch (e: Exception) {
                empty()
            }
        }
    }
}
