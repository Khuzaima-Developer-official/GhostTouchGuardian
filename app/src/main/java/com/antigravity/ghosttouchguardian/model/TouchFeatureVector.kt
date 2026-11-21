package com.antigravity.ghosttouchguardian.model

import android.view.MotionEvent
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * TouchFeatureVector — 8-Dimensional mathematical feature representation of a touch event.
 *
 * x1: Dwell time on glass (ms)
 * x2: Contact surface area (flesh deformation size: 0.0 .. 1.0)
 * x3: Pressure tensor (capacitive peak voltage: 0.0 .. 1.0)
 * x4: Newtonian chassis shockwave (accelerometer delta: m/s^2)
 * x5: Inter-arrival interval from previous touch (ms)
 * x6: Tremor dispersion / velocity (px / ms)
 * x7: Eccentricity ratio (touchMajor / touchMinor)
 * x8: Time since last human or stylus stimulus (ms)
 */
data class TouchFeatureVector(
    val dwell: Float,               // x1 (ms)
    val size: Float,                // x2 (normalized contact area)
    val pressure: Float,            // x3 (normalized pressure)
    val accelDelta: Float,          // x4 (m/s^2)
    val interval: Float,            // x5 (ms)
    val velocity: Float,            // x6 (px/ms)
    val eccentricity: Float,        // x7 (major/minor ratio)
    val timeSinceStimulus: Float    // x8 (ms)
) {
    fun toArray(): FloatArray {
        return floatArrayOf(
            dwell,
            size,
            pressure,
            accelDelta,
            interval,
            velocity,
            eccentricity,
            timeSinceStimulus
        )
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("dwell", dwell.toDouble())
            put("size", size.toDouble())
            put("pressure", pressure.toDouble())
            put("accelDelta", accelDelta.toDouble())
            put("interval", interval.toDouble())
            put("velocity", velocity.toDouble())
            put("eccentricity", eccentricity.toDouble())
            put("timeSinceStimulus", timeSinceStimulus.toDouble())
        }
    }

    companion object {
        const val DIMENSIONS = 8

        fun fromJson(json: JSONObject): TouchFeatureVector {
            return TouchFeatureVector(
                dwell = json.optDouble("dwell", 0.0).toFloat(),
                size = json.optDouble("size", 0.0).toFloat(),
                pressure = json.optDouble("pressure", 0.0).toFloat(),
                accelDelta = json.optDouble("accelDelta", 0.0).toFloat(),
                interval = json.optDouble("interval", 0.0).toFloat(),
                velocity = json.optDouble("velocity", 0.0).toFloat(),
                eccentricity = json.optDouble("eccentricity", 1.0).toFloat(),
                timeSinceStimulus = json.optDouble("timeSinceStimulus", -1.0).toFloat()
            )
        }

        fun extract(
            event: MotionEvent,
            dwellMs: Int,
            downX: Float,
            downY: Float,
            accelDelta: Float,
            intervalMs: Long,
            timeSinceStimulusMs: Long
        ): TouchFeatureVector {
            val dx = event.x - downX
            val dy = event.y - downY
            val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            val velocity = if (dwellMs > 0) dist / dwellMs.toFloat() else 0f

            val major = event.touchMajor.coerceAtLeast(1f)
            val minor = event.touchMinor.coerceAtLeast(1f)
            val eccentricity = (major / minor).coerceIn(1.0f, 10.0f)

            return TouchFeatureVector(
                dwell = dwellMs.toFloat().coerceAtLeast(0f),
                size = event.size.coerceIn(0f, 1f),
                pressure = event.pressure.coerceIn(0f, 1f),
                accelDelta = accelDelta.coerceAtLeast(0f),
                interval = intervalMs.toFloat().coerceAtLeast(0f),
                velocity = velocity.coerceIn(0f, 50f),
                eccentricity = eccentricity,
                timeSinceStimulus = timeSinceStimulusMs.toFloat().coerceAtLeast(-1f)
            )
        }
    }
}
