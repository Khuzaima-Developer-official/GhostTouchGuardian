package com.antigravity.ghosttouchguardian.engine

import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class HotspotZone(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val count: Int
) {
    fun contains(x: Float, y: Float): Boolean {
        // Point check
        return x in left..right && y in top..bottom
    }

    fun toRectF(): RectF = RectF(left, top, right, bottom)
}

data class CalibrationResult(
    val zones: List<HotspotZone>,
    val totalGhostCount: Int,
    val avgPeriodMs: Long,
    val periodVariancePercent: Float
)

class GhostCalibrationEngine {

    private val samplesX = ArrayList<Float>()
    private val samplesY = ArrayList<Float>()
    private val sampleTimes = ArrayList<Long>()

    var isCalibrating: Boolean = false
        private set

    fun startCalibration() {
        // Start calibration
        samplesX.clear()
        samplesY.clear()
        sampleTimes.clear()
        isCalibrating = true
    }

    fun recordTouch(x: Float, y: Float, eventTime: Long) {
        // Record touch
        if (!isCalibrating) return
        samplesX.add(x)
        samplesY.add(y)
        sampleTimes.add(eventTime)
    }

    fun finishCalibration(): CalibrationResult {
        // Finish calibration
        isCalibrating = false
        val count = samplesX.size
        if (count == 0) {
            return CalibrationResult(emptyList(), 0, 0L, 100f)
        }

        // Calculate intervals
        var totalInterval = 0L
        val intervals = ArrayList<Long>()
        for (i in 1 until count) {
            val dt = sampleTimes[i] - sampleTimes[i - 1]
            if (dt in 5L..3000L) {
                intervals.add(dt)
                totalInterval += dt
            }
        }

        val avgPeriodMs = if (intervals.isNotEmpty()) totalInterval / intervals.size else 0L
        val variancePercent = if (intervals.size >= 3 && avgPeriodMs > 0) {
            var sumDiffSq = 0.0
            for (dt in intervals) {
                val diff = dt - avgPeriodMs
                sumDiffSq += (diff * diff)
            }
            val stdDev = sqrt(sumDiffSq / intervals.size)
            ((stdDev / avgPeriodMs) * 100f).toFloat()
        } else 100f

        // Cluster points
        val zones = clusterPointsIntoZones()
        return CalibrationResult(zones, count, avgPeriodMs, variancePercent)
    }

    private fun clusterPointsIntoZones(): List<HotspotZone> {
        // Cluster zones
        val clusters = ArrayList<ArrayList<Int>>()
        val count = samplesX.size
        val visited = BooleanArray(count)

        for (i in 0 until count) {
            if (visited[i]) continue
            visited[i] = true
            val currentCluster = ArrayList<Int>()
            currentCluster.add(i)

            for (j in (i + 1) until count) {
                if (!visited[j]) {
                    val dx = samplesX[i] - samplesX[j]
                    val dy = samplesY[i] - samplesY[j]
                    val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    if (dist <= CLUSTER_RADIUS_PX) {
                        visited[j] = true
                        currentCluster.add(j)
                    }
                }
            }
            clusters.add(currentCluster)
        }

        // Build zones
        val zones = ArrayList<HotspotZone>()
        for (cluster in clusters) {
            if (cluster.isEmpty()) continue
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE
            var maxY = Float.MIN_VALUE

            for (idx in cluster) {
                val x = samplesX[idx]
                val y = samplesY[idx]
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
            }

            // Pad bounding
            val paddedLeft = max(0f, minX - PADDING_PX)
            val paddedTop = max(0f, minY - PADDING_PX)
            val paddedRight = maxX + PADDING_PX
            val paddedBottom = maxY + PADDING_PX

            zones.add(HotspotZone(paddedLeft, paddedTop, paddedRight, paddedBottom, cluster.size))
        }

        return mergeOverlappingZones(zones)
    }

    private fun mergeOverlappingZones(zones: List<HotspotZone>): List<HotspotZone> {
        // Merge zones
        val result = ArrayList<HotspotZone>()
        for (zone in zones) {
            var merged = false
            for (k in 0 until result.size) {
                val existing = result[k]
                // Check overlap
                if (zone.left <= existing.right && zone.right >= existing.left &&
                    zone.top <= existing.bottom && zone.bottom >= existing.top
                ) {
                    val newLeft = min(zone.left, existing.left)
                    val newTop = min(zone.top, existing.top)
                    val newRight = max(zone.right, existing.right)
                    val newBottom = max(zone.bottom, existing.bottom)
                    result[k] = HotspotZone(newLeft, newTop, newRight, newBottom, existing.count + zone.count)
                    merged = true
                    break
                }
            }
            if (!merged) {
                result.add(zone)
            }
        }
        return result
    }

    companion object {
        private const val CLUSTER_RADIUS_PX = 85f
        private const val PADDING_PX = 32f

        fun serializeZonesToJson(zones: List<HotspotZone>): String {
            // Serialize json
            val array = JSONArray()
            for (z in zones) {
                val obj = JSONObject().apply {
                    put("left", z.left.toDouble())
                    put("top", z.top.toDouble())
                    put("right", z.right.toDouble())
                    put("bottom", z.bottom.toDouble())
                    put("count", z.count)
                }
                array.put(obj)
            }
            return array.toString()
        }

        fun parseZonesFromJson(jsonStr: String): List<HotspotZone> {
            // Parse json
            val result = ArrayList<HotspotZone>()
            if (jsonStr.isBlank()) return result
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    result.add(
                        HotspotZone(
                            left = obj.getDouble("left").toFloat(),
                            top = obj.getDouble("top").toFloat(),
                            right = obj.getDouble("right").toFloat(),
                            bottom = obj.getDouble("bottom").toFloat(),
                            count = obj.optInt("count", 1)
                        )
                    )
                }
            } catch (_: Exception) {}
            return result
        }
    }
}
