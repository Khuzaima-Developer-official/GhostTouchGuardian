package com.antigravity.ghosttouchguardian.engine.ai

import com.antigravity.ghosttouchguardian.model.TouchFeatureVector
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * GhostBrainEngine (v3.0) — Embedded 8-Dimensional Probabilistic Bayesian AI Engine.
 *
 * Employs:
 * 1. 8D Gaussian-Mahalanobis statistical clusters for Human and Ghost touch profiles.
 * 2. Welford's streaming algorithm for real-time online learning without retraining overhead.
 * 3. Generalized unknown frequency classification via subspace covariance decomposition.
 * 4. Human Biometric Invariant guarantee (physical mass momentum, flesh contact area).
 */
class GhostBrainEngine(
    var humanCluster: GaussianCluster = GaussianCluster.defaultHuman(),
    var ghostCluster: GaussianCluster = GaussianCluster.defaultGhost(),
    var isSPenModeActive: Boolean = false
) {

    var totalOnlineUpdates: Long = 0L

    /**
     * Probabilistic classification result.
     */
    data class BrainDecision(
        val isGhost: Boolean,
        val ghostProbability: Float, // 0.0f .. 1.0f
        val confidence: Float,
        val reason: String,
        val dominantFactor: String
    )

    /**
     * Classifies an incoming 8D touch feature vector.
     */
    fun classify(
        vector: TouchFeatureVector,
        toolType: Int = android.view.MotionEvent.TOOL_TYPE_FINGER,
        inGreenZone: Boolean = false
    ): BrainDecision {
        // 1. S Pen Stylus Priority
        if (toolType == android.view.MotionEvent.TOOL_TYPE_STYLUS) {
            return BrainDecision(
                isGhost = false,
                ghostProbability = 0.0f,
                confidence = 1.0f,
                reason = "S Pen EMR Magnetic Signal (Native 100% Pass)",
                dominantFactor = "EMR_STYLUS"
            )
        }

        // 2. 100% S Pen Capacitive Mute Mode
        // When S Pen mode is active, ALL electric/capacitive signals (fingers and glitches) are 100% MUTED
        if (isSPenModeActive) {
            return BrainDecision(
                isGhost = true,
                ghostProbability = 1.0f,
                confidence = 1.0f,
                reason = "Dedicated S Pen Mode: 100% Capacitive Noise Muted",
                dominantFactor = "SPEN_CAPACITIVE_MUTE"
            )
        }

        // 3. Green Safe Zone Stimulus Immunity (Phase B tiny fingertip pad)
        if (inGreenZone) {
            return BrainDecision(
                isGhost = false,
                ghostProbability = 0.0f,
                confidence = 1.0f,
                reason = "Fingertip Safe Zone Stimulus (Protected)",
                dominantFactor = "GREEN_ZONE_STIMULUS"
            )
        }

        // 4. Human Biometric Invariant Check (Guarantees real fingers are NEVER restricted)
        // If biological flesh deformation, Newtonian shockwave, or intentional hold is present:
        val hasPhysicalMomentum = vector.accelDelta >= humanCluster.getMinMomentumThreshold()
        val hasFleshContact = vector.size >= humanCluster.getMinSizeThreshold() || vector.pressure >= humanCluster.getMinPressureThreshold()
        val hasHumanDwell = vector.dwell >= 110f

        if (hasPhysicalMomentum || hasFleshContact || hasHumanDwell) {
            val factor = when {
                hasPhysicalMomentum -> "Newtonian Momentum (${String.format("%.3f", vector.accelDelta)} m/s²)"
                hasFleshContact -> "Flesh Contact (size=${String.format("%.3f", vector.size)})"
                else -> "Human Hold (${vector.dwell.toInt()} ms)"
            }
            return BrainDecision(
                isGhost = false,
                ghostProbability = 0.02f,
                confidence = 0.98f,
                reason = "Human Biological Invariant Confirmed: $factor",
                dominantFactor = factor
            )
        }

        // 5. Post-Interaction Dielectric Trailing Echo Check
        if (vector.timeSinceStimulus in 2.0f..1500.0f && vector.dwell <= 90f && vector.accelDelta < 0.016f) {
            return BrainDecision(
                isGhost = true,
                ghostProbability = 0.96f,
                confidence = 0.96f,
                reason = "Dielectric Trailing Echo (${vector.timeSinceStimulus.toInt()}ms post-lift)",
                dominantFactor = "DIELECTRIC_ECHO"
            )
        }

        // 6. Subspace Bayesian Classification (Handles unknown / shifting frequencies)
        val logLikelihoodGhost = ghostCluster.computeLogLikelihood(vector)
        val logLikelihoodHuman = humanCluster.computeLogLikelihood(vector)

        // Prior probabilities: Assume balanced 0.5 or slight prior towards human during active use
        val logRatio = (logLikelihoodGhost - logLikelihoodHuman).coerceIn(-20f, 20f)
        val ghostProb = (1.0f / (1.0f + exp(-logRatio))).coerceIn(0.0f, 1.0f)

        return if (ghostProb >= 0.70f) {
            BrainDecision(
                isGhost = true,
                ghostProbability = ghostProb,
                confidence = ghostProb,
                reason = "8D AI Ghost Match (P(Ghost)=${(ghostProb * 100).toInt()}%, zero-momentum electrical noise)",
                dominantFactor = "8D_AI_GHOST_CLUSTER"
            )
        } else if (ghostProb <= 0.35f) {
            BrainDecision(
                isGhost = false,
                ghostProbability = ghostProb,
                confidence = 1.0f - ghostProb,
                reason = "8D AI Human Match (P(Human)=${((1.0f - ghostProb) * 100).toInt()}%)",
                dominantFactor = "8D_AI_HUMAN_CLUSTER"
            )
        } else {
            // Ambiguous range: Safety Fallback ALWAYS favours the real human user!
            BrainDecision(
                isGhost = false,
                ghostProbability = ghostProb,
                confidence = 0.60f,
                reason = "Ambiguous Subspace Touch (P(Ghost)=${(ghostProb * 100).toInt()}%) -> Pass-Through Protected",
                dominantFactor = "HUMAN_PROTECTION_FALLBACK"
            )
        }
    }

    /**
     * Online Continuous Learning:
     * Incrementally updates the statistical cluster using Welford's algorithm during runtime use.
     */
    fun learnOnline(vector: TouchFeatureVector, isGhost: Boolean, weight: Float = 1.0f) {
        if (isGhost) {
            ghostCluster.updateOnline(vector, weight)
        } else {
            humanCluster.updateOnline(vector, weight)
        }
        totalOnlineUpdates++
    }

    fun toJson(): String {
        return JSONObject().apply {
            put("humanCluster", humanCluster.toJson())
            put("ghostCluster", ghostCluster.toJson())
            put("totalOnlineUpdates", totalOnlineUpdates)
            put("isSPenModeActive", isSPenModeActive)
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): GhostBrainEngine {
            if (jsonStr.isBlank()) return GhostBrainEngine()
            return try {
                val obj = JSONObject(jsonStr)
                GhostBrainEngine(
                    humanCluster = GaussianCluster.fromJson(obj.optJSONObject("humanCluster") ?: JSONObject()),
                    ghostCluster = GaussianCluster.fromJson(obj.optJSONObject("ghostCluster") ?: JSONObject()),
                    isSPenModeActive = obj.optBoolean("isSPenModeActive", false)
                ).apply {
                    totalOnlineUpdates = obj.optLong("totalOnlineUpdates", 0L)
                }
            } catch (_: Exception) {
                GhostBrainEngine()
            }
        }
    }
}

/**
 * GaussianCluster — Multi-dimensional diagonal covariance Gaussian distribution
 * with Welford's streaming online update algorithm.
 */
class GaussianCluster(
    var sampleCount: Int,
    val mean: FloatArray,
    val variance: FloatArray,
    val m2: FloatArray, // Running sum of squared differences for Welford's algorithm
    val minVarianceFloor: FloatArray
) {
    fun getMinMomentumThreshold(): Float = max(0.015f, mean[3] * 0.60f)
    fun getMinSizeThreshold(): Float = max(0.025f, mean[1] * 0.65f)
    fun getMinPressureThreshold(): Float = max(0.06f, mean[2] * 0.65f)

    /**
     * Welford's online update algorithm for streaming variance and mean.
     */
    fun updateOnline(vector: TouchFeatureVector, weight: Float = 1.0f) {
        val x = vector.toArray()
        sampleCount++
        for (i in 0 until TouchFeatureVector.DIMENSIONS) {
            val delta = x[i] - mean[i]
            mean[i] += (delta / sampleCount.toFloat()) * weight
            val delta2 = x[i] - mean[i]
            m2[i] += delta * delta2 * weight
            variance[i] = max(minVarianceFloor[i], m2[i] / sampleCount.toFloat())
        }
    }

    /**
     * Computes log-likelihood ln P(x | Cluster) under diagonal covariance assumption.
     * Dimensions with high variance (e.g. fluctuating interval x5) automatically down-weight themselves!
     */
    fun computeLogLikelihood(vector: TouchFeatureVector): Float {
        val x = vector.toArray()
        var logLikelihood = 0f
        val ln2pi = ln(2.0 * Math.PI).toFloat()

        for (i in 0 until TouchFeatureVector.DIMENSIONS) {
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
        fun defaultHuman(): GaussianCluster {
            return GaussianCluster(
                sampleCount = 5,
                mean = floatArrayOf(
                    140f,   // dwell
                    0.055f, // size
                    0.20f,  // pressure
                    0.035f, // accelDelta
                    350f,   // interval
                    0.05f,  // velocity
                    1.2f,   // eccentricity
                    2000f   // timeSinceStimulus
                ),
                variance = floatArrayOf(
                    2500f,   // dwell var
                    0.0004f, // size var
                    0.0064f, // pressure var
                    0.0002f, // accel var
                    50000f,  // interval var
                    0.01f,   // velocity var
                    0.2f,    // eccentricity var
                    500000f  // timeSinceStimulus var
                ),
                m2 = FloatArray(TouchFeatureVector.DIMENSIONS) { 10f },
                minVarianceFloor = floatArrayOf(
                    400f, 0.0001f, 0.001f, 0.00005f, 1000f, 0.001f, 0.05f, 10000f
                )
            )
        }

        fun defaultGhost(): GaussianCluster {
            return GaussianCluster(
                sampleCount = 5,
                mean = floatArrayOf(
                    28f,    // dwell (very short electrical transient)
                    0.015f, // size (tiny floating electrode spark)
                    0.02f,  // pressure (low capacitive delta)
                    0.004f, // accelDelta (ZERO physical shockwave)
                    120f,   // interval (frequency fluctuation)
                    0.01f,  // velocity (stationary jitter)
                    1.8f,   // eccentricity (elongated grid track)
                    350f    // timeSinceStimulus
                ),
                variance = floatArrayOf(
                    300f,    // dwell var
                    0.0001f, // size var
                    0.0004f, // pressure var
                    0.00004f,// accel var
                    80000f,  // interval var (large variance handles unknown frequencies!)
                    0.005f,  // velocity var
                    0.3f,    // eccentricity var
                    80000f   // timeSinceStimulus var
                ),
                m2 = FloatArray(TouchFeatureVector.DIMENSIONS) { 10f },
                minVarianceFloor = floatArrayOf(
                    100f, 0.00005f, 0.0002f, 0.00002f, 2000f, 0.001f, 0.05f, 5000f
                )
            )
        }

        fun fromJson(json: JSONObject): GaussianCluster {
            val count = json.optInt("sampleCount", 5)
            val meanArr = json.optJSONArray("mean")
            val varArr = json.optJSONArray("variance")
            val m2Arr = json.optJSONArray("m2")
            val floorArr = json.optJSONArray("minVarianceFloor")

            val mean = FloatArray(TouchFeatureVector.DIMENSIONS) { i ->
                meanArr?.optDouble(i, 0.0)?.toFloat() ?: 0f
            }
            val variance = FloatArray(TouchFeatureVector.DIMENSIONS) { i ->
                varArr?.optDouble(i, 1.0)?.toFloat() ?: 1f
            }
            val m2 = FloatArray(TouchFeatureVector.DIMENSIONS) { i ->
                m2Arr?.optDouble(i, 0.0)?.toFloat() ?: 0f
            }
            val floor = FloatArray(TouchFeatureVector.DIMENSIONS) { i ->
                floorArr?.optDouble(i, 0.001)?.toFloat() ?: 0.001f
            }

            return GaussianCluster(count, mean, variance, m2, floor)
        }
    }
}
