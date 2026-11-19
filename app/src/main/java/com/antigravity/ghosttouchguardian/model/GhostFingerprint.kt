package com.antigravity.ghosttouchguardian.model

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * A single recorded ghost touch sample with all 12-factor dimensions.
 * Captured during Identify Mode where every touch is assumed ghost.
 */
data class GhostTouchEntry(
    val x: Float,
    val y: Float,
    val dwell: Int,
    val size: Float,
    val pressure: Float,
    val touchMajor: Float,
    val historySize: Int,
    val accelDelta: Float,        // Accelerometer Z-delta at touch time (0 = no shockwave)
    val intervalFromPrevMs: Long, // ms since previous ghost touch
    val displacementPx: Float,    // px distance from previous ghost touch
    val timestamp: Long,
    val factorNotes: String = "" // e.g. "periodic, inHotspot"
)

/**
 * A custom rule the user manually added on top of the learned fingerprint.
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
 * Statistical ghost fingerprint derived from all GhostTouchEntry samples.
 * Used by TouchProfilerEngine for multi-dimensional ghost matching.
 */
data class GhostFingerprint(
    val entries: List<GhostTouchEntry>,
    // Spatial
    val avgX: Float,
    val avgY: Float,
    val xStdDev: Float,
    val yStdDev: Float,
    // Dwell
    val avgDwell: Float,
    val dwellStdDev: Float,
    // Size
    val avgSize: Float,
    val sizeStdDev: Float,
    // Pressure
    val avgPressure: Float,
    // Period
    val avgPeriodMs: Long,
    val periodVariance: Float,
    // Extra
    val avgAccelDelta: Float,
    val avgHistorySize: Float,
    // User rules
    val customBlockRules: List<CustomBlockRule>
) {
    /** True if this fingerprint has enough data to be useful (at least 3 entries). */
    val isValid: Boolean get() = entries.size >= 3

    /**
     * Returns a [0..1] probability that the given touch matches this ghost fingerprint.
     * Score >= 0.6 is considered a ghost match.
     */
    fun matchScore(x: Float, y: Float, dwell: Int, size: Float, pressure: Float, accelDelta: Float): Float {
        if (!isValid) return 0f
        var score = 0f
        var total = 0f

        // Factor 1: Dwell within mean ± 2.5σ
        total += 1f
        val dwellRange = (dwellStdDev * 2.5f).coerceAtLeast(20f)
        if (kotlin.math.abs(dwell - avgDwell) <= dwellRange) score += 1f

        // Factor 2: Size within profile range
        total += 1f
        val sizeRange = (sizeStdDev * 2.5f).coerceAtLeast(0.03f)
        if (kotlin.math.abs(size - avgSize) <= sizeRange) score += 1f

        // Factor 3: Spatial proximity to ghost centroid (within 150px)
        total += 1f
        val dx = x - avgX; val dy = y - avgY
        val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (dist <= 150f) score += 1f * (1f - dist / 150f)

        // Factor 4: Near-zero accel delta (no shockwave = ghost indicator)
        total += 1f
        if (accelDelta < 0.08f) score += 1f

        // Factor 5: Low pressure (ghost = usually 0.0–0.15)
        total += 1f
        if (pressure <= 0.18f) score += 1f

        return score / total
    }

    companion object {
        /**
         * Build a GhostFingerprint from a list of raw entries.
         * This computes all statistical parameters automatically.
         */
        fun fromEntries(entries: List<GhostTouchEntry>, customRules: List<CustomBlockRule> = emptyList()): GhostFingerprint {
            if (entries.isEmpty()) return empty()

            fun List<Float>.mean() = sum() / size
            fun List<Float>.stdDev(): Float {
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

            // Period stats
            val intervals = entries.map { it.intervalFromPrevMs }.filter { it in 5L..5000L }
            val avgPeriod = if (intervals.isEmpty()) 0L else (intervals.sum() / intervals.size)
            val periodVariance = if (intervals.size < 2 || avgPeriod == 0L) 100f else {
                val diffs = intervals.map { kotlin.math.abs(it - avgPeriod).toFloat() }
                (diffs.sum() / diffs.size / avgPeriod * 100f)
            }

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
                avgPeriodMs = avgPeriod,
                periodVariance = periodVariance,
                avgAccelDelta = accels.mean(),
                avgHistorySize = histories.mean(),
                customBlockRules = customRules
            )
        }

        fun empty(): GhostFingerprint = GhostFingerprint(
            entries = emptyList(),
            avgX = 0f, avgY = 0f, xStdDev = 0f, yStdDev = 0f,
            avgDwell = 0f, dwellStdDev = 0f,
            avgSize = 0f, sizeStdDev = 0f,
            avgPressure = 0f,
            avgPeriodMs = 0L, periodVariance = 100f,
            avgAccelDelta = 0f, avgHistorySize = 0f,
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
            root.put("avgPeriodMs", fp.avgPeriodMs)
            root.put("periodVariance", fp.periodVariance)
            root.put("avgAccelDelta", fp.avgAccelDelta)
            root.put("avgHistorySize", fp.avgHistorySize)

            // Entries (last 50 only to keep storage small)
            val entriesArr = JSONArray()
            fp.entries.takeLast(50).forEach { e ->
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
                ej.put("factorNotes", e.factorNotes)
                entriesArr.put(ej)
            }
            root.put("entries", entriesArr)

            // Custom rules
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
                        touchMajor = ej.getDouble("touchMajor").toFloat(),
                        historySize = ej.getInt("historySize"),
                        accelDelta = ej.getDouble("accelDelta").toFloat(),
                        intervalFromPrevMs = ej.getLong("intervalFromPrevMs"),
                        displacementPx = ej.getDouble("displacementPx").toFloat(),
                        timestamp = ej.getLong("timestamp"),
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

                // Rebuild stats from stored values (or recompute from entries)
                GhostFingerprint(
                    entries = entries,
                    avgX = root.getDouble("avgX").toFloat(),
                    avgY = root.getDouble("avgY").toFloat(),
                    xStdDev = root.getDouble("xStdDev").toFloat(),
                    yStdDev = root.getDouble("yStdDev").toFloat(),
                    avgDwell = root.getDouble("avgDwell").toFloat(),
                    dwellStdDev = root.getDouble("dwellStdDev").toFloat(),
                    avgSize = root.getDouble("avgSize").toFloat(),
                    sizeStdDev = root.getDouble("sizeStdDev").toFloat(),
                    avgPressure = root.getDouble("avgPressure").toFloat(),
                    avgPeriodMs = root.getLong("avgPeriodMs"),
                    periodVariance = root.getDouble("periodVariance").toFloat(),
                    avgAccelDelta = root.getDouble("avgAccelDelta").toFloat(),
                    avgHistorySize = root.getDouble("avgHistorySize").toFloat(),
                    customBlockRules = rules
                )
            } catch (e: Exception) {
                empty()
            }
        }
    }
}
