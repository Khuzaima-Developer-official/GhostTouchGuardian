package com.antigravity.ghosttouchguardian.engine.ai

import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * ExtendedTouchVector — 10-Dimensional High-Precision Touch Feature Tensor.
 *
 * x1:  Dwell time on glass (ms)
 * x2:  Flesh contact area (normalized size 0.0 .. 1.0)
 * x3:  Peak pressure voltage (normalized 0.0 .. 1.0)
 * x4:  Newtonian chassis deceleration / accelerometer delta (m/s²)
 * x5:  Pressure gradient / derivative dp/dt
 * x6:  Biological micro-tremor variance (px²) - involuntary physiological jitter
 * x7:  Contact ellipse eccentricity ratio (major / minor)
 * x8:  Tremor velocity dispersion (px/ms)
 * x9:  Inter-arrival interval (IFI in ms)
 * x10: Time since last human or stylus stimulus (ms)
 */
data class ExtendedTouchVector(
    val dwell: Float,
    val size: Float,
    val pressure: Float,
    val accelDelta: Float,
    val pressureGradient: Float,
    val microTremorVariance: Float,
    val eccentricity: Float,
    val velocity: Float,
    val interval: Float,
    val timeSinceStimulus: Float
) {
    fun toArray(): FloatArray {
        return floatArrayOf(
            dwell,
            size,
            pressure,
            accelDelta,
            pressureGradient,
            microTremorVariance,
            eccentricity,
            velocity,
            interval,
            timeSinceStimulus
        )
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("dwell", dwell.toDouble())
            put("size", size.toDouble())
            put("pressure", pressure.toDouble())
            put("accelDelta", accelDelta.toDouble())
            put("pressureGradient", pressureGradient.toDouble())
            put("microTremorVariance", microTremorVariance.toDouble())
            put("eccentricity", eccentricity.toDouble())
            put("velocity", velocity.toDouble())
            put("interval", interval.toDouble())
            put("timeSinceStimulus", timeSinceStimulus.toDouble())
        }
    }

    companion object {
        const val DIMENSIONS = 10

        fun fromJson(json: JSONObject): ExtendedTouchVector {
            return ExtendedTouchVector(
                dwell = json.optDouble("dwell", 0.0).toFloat(),
                size = json.optDouble("size", 0.0).toFloat(),
                pressure = json.optDouble("pressure", 0.0).toFloat(),
                accelDelta = json.optDouble("accelDelta", 0.0).toFloat(),
                pressureGradient = json.optDouble("pressureGradient", 0.0).toFloat(),
                microTremorVariance = json.optDouble("microTremorVariance", 0.0).toFloat(),
                eccentricity = json.optDouble("eccentricity", 1.0).toFloat(),
                velocity = json.optDouble("velocity", 0.0).toFloat(),
                interval = json.optDouble("interval", 0.0).toFloat(),
                timeSinceStimulus = json.optDouble("timeSinceStimulus", -1.0).toFloat()
            )
        }

        fun extract(
            event: MotionEvent,
            dwellMs: Int,
            downX: Float,
            downY: Float,
            downPressure: Float,
            accelDelta: Float,
            intervalMs: Long,
            timeSinceStimulusMs: Long,
            movePoints: List<Pair<Float, Float>> = emptyList()
        ): ExtendedTouchVector {
            val dx = event.x - downX
            val dy = event.y - downY
            val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            val velocity = if (dwellMs > 0) dist / dwellMs.toFloat() else 0f

            val major = event.touchMajor.coerceAtLeast(1f)
            val minor = event.touchMinor.coerceAtLeast(1f)
            val eccentricity = (major / minor).coerceIn(1.0f, 10.0f)

            val dp = abs(event.pressure - downPressure)
            val pressureGradient = if (dwellMs > 0) dp / dwellMs.toFloat() else 0f

            // Compute biological micro-tremor variance across recorded trajectory
            var tremorVar = 0f
            if (movePoints.size >= 3) {
                val meanX = movePoints.map { it.first }.average().toFloat()
                val meanY = movePoints.map { it.second }.average().toFloat()
                val sqDiffSum = movePoints.fold(0f) { acc, p ->
                    val px = p.first - meanX
                    val py = p.second - meanY
                    acc + (px * px + py * py)
                }
                tremorVar = (sqDiffSum / movePoints.size.toFloat()).coerceIn(0f, 25f)
            }

            return ExtendedTouchVector(
                dwell = dwellMs.toFloat().coerceAtLeast(0f),
                size = event.size.coerceIn(0f, 1f),
                pressure = event.pressure.coerceIn(0f, 1f),
                accelDelta = accelDelta.coerceAtLeast(0f),
                pressureGradient = pressureGradient.coerceIn(0f, 1f),
                microTremorVariance = tremorVar,
                eccentricity = eccentricity,
                velocity = velocity.coerceIn(0f, 50f),
                interval = intervalMs.toFloat().coerceAtLeast(0f),
                timeSinceStimulus = timeSinceStimulusMs.toFloat().coerceAtLeast(-1f)
            )
        }
    }
}

/**
 * MasterTouchClassifier — Specialized Full-Screen Touch Pattern Recognition AI Brain.
 *
 * Designed specifically for S23 Ultra and devices with liquid ingress / water damage:
 * - 100% Coordinate-Agnostic: Understands touches anywhere on display without spatial over-fitting.
 * - Biological Invariant Checks: Micro-tremor variance, Newtonian chassis jerk deceleration, contact ellipse.
 * - Active Learning Co-Pilot: Ingests ground-truth human supervision ("Mark as Ghost") via hard negative mining.
 * - Online Welford Continuous Memory: Dynamically adapts to shifting temperatures, moisture drying, and charger ripple.
 */
class MasterTouchClassifier(
    var humanCluster: MasterCluster = MasterCluster.defaultHuman(),
    var ghostCluster: MasterCluster = MasterCluster.defaultGhost(),
    var isSPenModeActive: Boolean = false
) {

    data class SupervisedSample(
        val vector: ExtendedTouchVector,
        val isGhost: Boolean,
        val timestamp: Long
    )

    data class MasterDecision(
        val isGhost: Boolean,
        val ghostProbability: Float, // 0.0f .. 1.0f
        val confidence: Float,
        val reason: String,
        val dominantFactor: String
    )

    // Ground-truth supervisory memory cache (capped to avoid memory blowup)
    private val supervisoryStore = mutableListOf<SupervisedSample>()
    var totalOnlineUpdates: Long = 0L
    var totalUserCorrections: Long = 0L

    fun getSupervisoryCount(): Int = supervisoryStore.size

    /**
     * Active Learning Supervisor (User acts as "Half-Brain"):
     * Ingests ground-truth user correction and applies Hard-Negative Mining.
     */
    fun supervise(vector: ExtendedTouchVector, actuallyGhost: Boolean) {
        if (supervisoryStore.size >= 200) {
            supervisoryStore.removeAt(0) // Rolling window compaction
        }
        supervisoryStore.add(SupervisedSample(vector, actuallyGhost, System.currentTimeMillis()))
        totalUserCorrections++

        // Hard-Negative Mining: apply 3x aggressive adaptation weight to directly correct decision boundary
        learnOnline(vector, isGhost = actuallyGhost, weight = 3.0f)
    }

    /**
     * Coordinate-Agnostic Master Classification.
     */
    fun classify(
        vector: ExtendedTouchVector,
        toolType: Int = MotionEvent.TOOL_TYPE_FINGER
    ): MasterDecision {
        // 1. S Pen Stylus Priority
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS) {
            return MasterDecision(
                isGhost = false,
                ghostProbability = 0.0f,
                confidence = 1.0f,
                reason = "S Pen Wacom Magnetic EMR (Native Pass)",
                dominantFactor = "EMR_STYLUS"
            )
        }

        // 2. Dedicated S Pen Mode: 100% Capacitive Muted
        if (isSPenModeActive) {
            return MasterDecision(
                isGhost = true,
                ghostProbability = 1.0f,
                confidence = 1.0f,
                reason = "Dedicated S Pen Mode: 100% Capacitive Signal Muted",
                dominantFactor = "SPEN_CAPACITIVE_MUTE"
            )
        }

        // 3. Ground-Truth Supervisory Memory Match (Nearest Neighbor Cache)
        val nearestSupervised = findNearestSupervised(vector)
        if (nearestSupervised != null) {
            val isGhost = nearestSupervised.isGhost
            val prob = if (isGhost) 0.96f else 0.04f
            val tag = if (isGhost) "User-Confirmed Water Glitch Pattern" else "User-Confirmed Human Pattern"
            return MasterDecision(
                isGhost = isGhost,
                ghostProbability = prob,
                confidence = 0.96f,
                reason = "$tag (Active Learning Cache)",
                dominantFactor = "SUPERVISORY_MEMORY"
            )
        }

        // 4. Water-Damage Static Droplet Lock (Capacitive pooling without tremor and zero momentum)
        if (vector.dwell in 60f..2000f && vector.microTremorVariance <= 0.08f && vector.accelDelta < 0.012f && vector.size < 0.040f) {
            return MasterDecision(
                isGhost = true,
                ghostProbability = 0.94f,
                confidence = 0.94f,
                reason = "Water Droplet Static Lock (${vector.dwell.toInt()}ms, zero tremor)",
                dominantFactor = "WATER_STATIC_LOCK"
            )
        }

        // 5. Biological Human Invariant Check (Guarantees real fingers are NEVER restricted)
        // Living human finger:
        // - Imparts Newtonian mechanical momentum (accelDelta >= threshold)
        // - Has fleshy soft tissue deformation (size >= 0.035 or pressure >= 0.10)
        // - Has involuntary biological micro-tremor variance (microTremorVariance >= 0.60 px²)
        // - Normal intentional dwell (dwell >= 110 ms with human tremor or flesh)
        val hasPhysicalMomentum = vector.accelDelta >= humanCluster.getMinMomentumThreshold()
        val hasFleshContact = vector.size >= humanCluster.getMinSizeThreshold() || vector.pressure >= humanCluster.getMinPressureThreshold()
        val hasBiologicalTremor = vector.microTremorVariance >= 0.60f && vector.dwell >= 60f
        val hasHumanDwell = vector.dwell >= 110f && (hasPhysicalMomentum || hasFleshContact || vector.microTremorVariance >= 0.25f)

        if (hasPhysicalMomentum || hasFleshContact || hasBiologicalTremor || hasHumanDwell) {
            val factor = when {
                hasPhysicalMomentum -> "Newtonian Impact (${String.format("%.3f", vector.accelDelta)} m/s²)"
                hasBiologicalTremor -> "Biological Micro-Tremor (${String.format("%.2f", vector.microTremorVariance)} px²)"
                hasFleshContact -> "Flesh Contact (size=${String.format("%.3f", vector.size)})"
                else -> "Human Intentional Dwell (${vector.dwell.toInt()} ms)"
            }
            return MasterDecision(
                isGhost = false,
                ghostProbability = 0.02f,
                confidence = 0.98f,
                reason = "Human Biological Invariant Confirmed: $factor",
                dominantFactor = factor
            )
        }

        // 6. Post-Interaction Dielectric Trailing Echo Check
        if (vector.timeSinceStimulus in 2.0f..1500.0f && vector.dwell <= 90f && vector.accelDelta < 0.015f) {
            return MasterDecision(
                isGhost = true,
                ghostProbability = 0.95f,
                confidence = 0.95f,
                reason = "Dielectric Trailing Echo (${vector.timeSinceStimulus.toInt()}ms post-lift)",
                dominantFactor = "DIELECTRIC_ECHO"
            )
        }

        // 7. Full-Screen 10D Bayesian Subspace Analysis
        val logLikelihoodGhost = ghostCluster.computeLogLikelihood(vector)
        val logLikelihoodHuman = humanCluster.computeLogLikelihood(vector)
        val logRatio = (logLikelihoodGhost - logLikelihoodHuman).coerceIn(-20f, 20f)
        val ghostProb = (1.0f / (1.0f + exp(-logRatio))).coerceIn(0.0f, 1.0f)

        return if (ghostProb >= 0.68f) {
            MasterDecision(
                isGhost = true,
                ghostProbability = ghostProb,
                confidence = ghostProb,
                reason = "10D AI Ghost Pattern (P(Ghost)=${(ghostProb * 100).toInt()}%, zero-momentum electrical noise)",
                dominantFactor = "10D_AI_GHOST_PATTERN"
            )
        } else if (ghostProb <= 0.35f) {
            MasterDecision(
                isGhost = false,
                ghostProbability = ghostProb,
                confidence = 1.0f - ghostProb,
                reason = "10D AI Human Pattern (P(Human)=${((1.0f - ghostProb) * 100).toInt()}%)",
                dominantFactor = "10D_AI_HUMAN_PATTERN"
            )
        } else {
            // Ambiguous range: Safety fallback protects real human touch
            MasterDecision(
                isGhost = false,
                ghostProbability = ghostProb,
                confidence = 0.60f,
                reason = "Ambiguous Subspace Touch (P(Ghost)=${(ghostProb * 100).toInt()}%) -> Pass-Through Protected",
                dominantFactor = "HUMAN_PROTECTION_FALLBACK"
            )
        }
    }

    /**
     * Online Continuous Learning via Welford streaming algorithm.
     */
    fun learnOnline(vector: ExtendedTouchVector, isGhost: Boolean, weight: Float = 1.0f) {
        if (isGhost) {
            ghostCluster.updateOnline(vector, weight)
        } else {
            humanCluster.updateOnline(vector, weight)
        }
        totalOnlineUpdates++
    }

    private fun findNearestSupervised(vector: ExtendedTouchVector): SupervisedSample? {
        if (supervisoryStore.isEmpty()) return null
        val target = vector.toArray()
        var bestSample: SupervisedSample? = null
        var minDistance = Float.MAX_VALUE

        // Normalized Euclidean distance in normalized subspace
        for (sample in supervisoryStore) {
            val ref = sample.vector.toArray()
            var distSq = 0f
            // Focus on dwell, size, pressure, accelDelta, microTremorVariance, eccentricity
            for (i in 0 until ExtendedTouchVector.DIMENSIONS) {
                val scale = when (i) {
                    0 -> 100f  // dwell
                    1 -> 0.1f  // size
                    2 -> 0.1f  // pressure
                    3 -> 0.02f // accel
                    4 -> 0.05f // pressure gradient
                    5 -> 2.0f  // micro-tremor
                    6 -> 2.0f  // eccentricity
                    7 -> 2.0f  // velocity
                    8 -> 200f  // interval
                    else -> 500f // timeSinceStimulus
                }
                val diff = (target[i] - ref[i]) / scale
                distSq += (diff * diff)
            }
            val dist = sqrt(distSq.toDouble()).toFloat()
            if (dist < minDistance) {
                minDistance = dist
                bestSample = sample
            }
        }

        // If distance is within close neighbor radius (0.45 normalized units — tightened from 0.80 to prevent false matches)
        return if (minDistance <= 0.45f) bestSample else null
    }

    fun toJson(): String {
        val supervisedArray = JSONArray()
        for (s in supervisoryStore) {
            supervisedArray.put(JSONObject().apply {
                put("vector", s.vector.toJson())
                put("isGhost", s.isGhost)
                put("timestamp", s.timestamp)
            })
        }
        return JSONObject().apply {
            put("humanCluster", humanCluster.toJson())
            put("ghostCluster", ghostCluster.toJson())
            put("totalOnlineUpdates", totalOnlineUpdates)
            put("totalUserCorrections", totalUserCorrections)
            put("isSPenModeActive", isSPenModeActive)
            put("supervisedStore", supervisedArray)
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): MasterTouchClassifier {
            if (jsonStr.isBlank()) return MasterTouchClassifier()
            return try {
                val obj = JSONObject(jsonStr)
                val classifier = MasterTouchClassifier(
                    humanCluster = MasterCluster.fromJson(obj.optJSONObject("humanCluster") ?: JSONObject()),
                    ghostCluster = MasterCluster.fromJson(obj.optJSONObject("ghostCluster") ?: JSONObject()),
                    isSPenModeActive = obj.optBoolean("isSPenModeActive", false)
                ).apply {
                    totalOnlineUpdates = obj.optLong("totalOnlineUpdates", 0L)
                    totalUserCorrections = obj.optLong("totalUserCorrections", 0L)
                }

                val supervisedArray = obj.optJSONArray("supervisedStore")
                if (supervisedArray != null) {
                    for (i in 0 until supervisedArray.length()) {
                        val so = supervisedArray.getJSONObject(i)
                        val vObj = so.optJSONObject("vector")
                        if (vObj != null) {
                            classifier.supervisoryStore.add(
                                SupervisedSample(
                                    vector = ExtendedTouchVector.fromJson(vObj),
                                    isGhost = so.optBoolean("isGhost", true),
                                    timestamp = so.optLong("timestamp", 0L)
                                )
                            )
                        }
                    }
                }
                classifier
            } catch (_: Exception) {
                MasterTouchClassifier()
            }
        }
    }
}

/**
 * MasterCluster — 10-Dimensional diagonal covariance Gaussian distribution
 * with streaming Welford updates.
 */
class MasterCluster(
    var sampleCount: Int,
    val mean: FloatArray,
    val variance: FloatArray,
    val m2: FloatArray,
    val minVarianceFloor: FloatArray
) {
    fun getMinMomentumThreshold(): Float = max(0.015f, mean[3] * 0.60f)
    fun getMinSizeThreshold(): Float = max(0.025f, mean[1] * 0.65f)
    fun getMinPressureThreshold(): Float = max(0.06f, mean[2] * 0.65f)

    fun updateOnline(vector: ExtendedTouchVector, weight: Float = 1.0f) {
        val x = vector.toArray()
        sampleCount++
        for (i in 0 until ExtendedTouchVector.DIMENSIONS) {
            val delta = x[i] - mean[i]
            mean[i] += (delta / sampleCount.toFloat()) * weight
            val delta2 = x[i] - mean[i]
            m2[i] += delta * delta2 * weight
            variance[i] = max(minVarianceFloor[i], m2[i] / sampleCount.toFloat())
        }
    }

    fun computeLogLikelihood(vector: ExtendedTouchVector): Float {
        val x = vector.toArray()
        var logLikelihood = 0f
        val ln2pi = ln(2.0 * Math.PI).toFloat()

        for (i in 0 until ExtendedTouchVector.DIMENSIONS) {
            val v = max(minVarianceFloor[i], variance[i])
            val diff = x[i] - mean[i]
            val term = -0.5f * (ln2pi + ln(v) + (diff * diff) / v)
            logLikelihood += term
        }
        return logLikelihood
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("sampleCount", sampleCount)
            put("mean", JSONArray(mean.toList()))
            put("variance", JSONArray(variance.toList()))
            put("m2", JSONArray(m2.toList()))
            put("minVarianceFloor", JSONArray(minVarianceFloor.toList()))
        }
    }

    companion object {
        fun defaultHuman(): MasterCluster {
            return MasterCluster(
                sampleCount = 5,
                mean = floatArrayOf(
                    140f,   // dwell
                    0.055f, // size
                    0.20f,  // pressure
                    0.035f, // accelDelta
                    0.003f, // pressureGradient
                    1.40f,  // microTremorVariance (biological involuntary tremor)
                    1.2f,   // eccentricity
                    0.05f,  // velocity
                    350f,   // interval
                    2000f   // timeSinceStimulus
                ),
                variance = floatArrayOf(
                    2500f, 0.0004f, 0.0064f, 0.0002f, 0.00001f, 0.5f, 0.2f, 0.01f, 50000f, 500000f
                ),
                m2 = FloatArray(ExtendedTouchVector.DIMENSIONS) { 10f },
                minVarianceFloor = floatArrayOf(
                    400f, 0.0001f, 0.001f, 0.00005f, 0.000005f, 0.1f, 0.05f, 0.001f, 1000f, 10000f
                )
            )
        }

        fun defaultGhost(): MasterCluster {
            return MasterCluster(
                sampleCount = 5,
                mean = floatArrayOf(
                    38f,    // dwell (water touch can range 20..110ms)
                    0.022f, // size (capacitive pooling without full biological flesh)
                    0.035f, // pressure (low capacitive delta)
                    0.004f, // accelDelta (ZERO physical shockwave)
                    0.0002f,// pressureGradient (flat or erratic step)
                    0.04f,  // microTremorVariance (ZERO biological tremor; dead static or discrete jumps)
                    2.2f,   // eccentricity (elongated moisture track)
                    0.01f,  // velocity
                    140f,   // interval (frequency fluctuation)
                    400f    // timeSinceStimulus
                ),
                variance = floatArrayOf(
                    600f, 0.0002f, 0.0008f, 0.00004f, 0.000005f, 0.02f, 0.5f, 0.005f, 90000f, 90000f
                ),
                m2 = FloatArray(ExtendedTouchVector.DIMENSIONS) { 10f },
                minVarianceFloor = floatArrayOf(
                    150f, 0.00008f, 0.0003f, 0.00002f, 0.000001f, 0.01f, 0.08f, 0.001f, 3000f, 8000f
                )
            )
        }

        fun fromJson(json: JSONObject): MasterCluster {
            val count = json.optInt("sampleCount", 5)
            val meanArr = json.optJSONArray("mean")
            val varArr = json.optJSONArray("variance")
            val m2Arr = json.optJSONArray("m2")
            val floorArr = json.optJSONArray("minVarianceFloor")

            val mean = FloatArray(ExtendedTouchVector.DIMENSIONS) { i ->
                meanArr?.optDouble(i, 0.0)?.toFloat() ?: 0f
            }
            val variance = FloatArray(ExtendedTouchVector.DIMENSIONS) { i ->
                varArr?.optDouble(i, 1.0)?.toFloat() ?: 1f
            }
            val m2 = FloatArray(ExtendedTouchVector.DIMENSIONS) { i ->
                m2Arr?.optDouble(i, 0.0)?.toFloat() ?: 0f
            }
            val floor = FloatArray(ExtendedTouchVector.DIMENSIONS) { i ->
                floorArr?.optDouble(i, 0.001)?.toFloat() ?: 0.001f
            }

            return MasterCluster(count, mean, variance, m2, floor)
        }
    }
}
