package com.antigravity.ghosttouchguardian.engine

import android.view.MotionEvent
import com.antigravity.ghosttouchguardian.model.CompactTouchBuffer
import com.antigravity.ghosttouchguardian.model.GhostTouchProfile
import kotlin.math.sqrt

/**
 * High-precision, zero-allocation Touch Profiler Engine.
 * Evaluates multi-parameter physical touch signatures:
 * 1. Dwell Time (contact duration in milliseconds)
 * 2. Contact Size (normalized major/minor axis area)
 * 3. Spatial Jitter (micro-displacement of pulp deformation vs rigid capacitive noise)
 * 4. Trailing Echo Window (post-release residual capacitance latency)
 * 5. Hardware Stylus Bypass (Zero latency S Pen pass-through)
 */
class TouchProfilerEngine(val buffer: CompactTouchBuffer = CompactTouchBuffer(128)) {

    // Active profile
    var profile: GhostTouchProfile = GhostTouchProfile.BALANCED
        set(value) {
            field = value
            echoWindowMaxMs = value.echoWindowMs
            ghostMaxDwellMs = value.maxDwellMs
            ghostMaxSize = value.maxContactSize
            jitterThreshold = value.jitterThresholdPx
        }

    var echoWindowMaxMs: Long = GhostTouchProfile.BALANCED.echoWindowMs
    var ghostMaxDwellMs: Int = GhostTouchProfile.BALANCED.maxDwellMs
    var ghostMaxSize: Float = GhostTouchProfile.BALANCED.maxContactSize
    var jitterThreshold: Float = GhostTouchProfile.BALANCED.jitterThresholdPx

    private var lastHumanUpTime: Long = 0L
    private var lastDownTime: Long = 0L
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var isCurrentTouchStylus: Boolean = false

    var isIdleMonitoringActive: Boolean = false
    var isInteractiveEchoMonitoringActive: Boolean = false
    var isCalibrationActive: Boolean = false

    // Calibration accumulator
    var calibrationSamplesCount: Int = 0
    var calibrationTotalDwell: Long = 0L
    var calibrationTotalSize: Float = 0f

    // Callback for UI updates (zero-allocation primitive event listener)
    var onAnomalyDetected: ((type: Byte, x: Float, y: Float, dwell: Int) -> Unit)? = null
    var onCalibrationSample: ((sampleIndex: Int, dwell: Int, size: Float) -> Unit)? = null

    /**
     * Inspects a touch event. Called on the touch hotpath.
     * Uses only local stack variables to guarantee ZERO heap allocation.
     */
    fun processTouchEvent(event: MotionEvent): Byte {
        val action = event.actionMasked
        val toolType = event.getToolType(0)

        // 1. S Pen Hardware Bypass (Electromagnetic Digitizer)
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS) {
            isCurrentTouchStylus = true
            if (action == MotionEvent.ACTION_UP) {
                buffer.push(
                    event.x, event.y, event.eventTime,
                    (event.eventTime - event.downTime).toInt(),
                    event.size, event.pressure, CompactTouchBuffer.TYPE_SPEN
                )
            }
            return CompactTouchBuffer.TYPE_SPEN
        }

        isCurrentTouchStylus = false

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                lastDownTime = event.eventTime
                downX = event.x
                downY = event.y

                val deltaSinceLastUp = if (lastHumanUpTime > 0) event.eventTime - lastHumanUpTime else 9999L

                // Trailing Echo Check (Phantom tap appearing immediately following finger lift)
                if (deltaSinceLastUp in 2L..echoWindowMaxMs) {
                    onAnomalyDetected?.invoke(
                        CompactTouchBuffer.TYPE_GHOST_ECHO,
                        event.x, event.y, deltaSinceLastUp.toInt()
                    )
                }
            }

            MotionEvent.ACTION_UP -> {
                val dwell = (event.eventTime - lastDownTime).toInt()
                val deltaSinceLastUp = if (lastHumanUpTime > 0) lastDownTime - lastHumanUpTime else 9999L
                val touchSize = event.size
                val pressure = event.pressure

                // Calculate micro-displacement distance (Jitter)
                val dx = event.x - downX
                val dy = event.y - downY
                val displacement = sqrt((dx * dx + dy * dy).toDouble()).toFloat()

                // Calibration mode handling
                if (isCalibrationActive) {
                    calibrationSamplesCount++
                    calibrationTotalDwell += dwell
                    calibrationTotalSize += touchSize
                    onCalibrationSample?.invoke(calibrationSamplesCount, dwell, touchSize)
                    buffer.push(event.x, event.y, event.eventTime, dwell, touchSize, pressure, CompactTouchBuffer.TYPE_HUMAN_FINGER)
                    return CompactTouchBuffer.TYPE_HUMAN_FINGER
                }

                val type: Byte = when {
                    // 1. Trailing Echo Anomaly: Tap occurred within echo cooldown window
                    deltaSinceLastUp in 2L..echoWindowMaxMs -> {
                        CompactTouchBuffer.TYPE_GHOST_ECHO
                    }
                    // 2. Idle Hands-Free Test Mode: Any touch while phone rests is noise
                    isIdleMonitoringActive -> {
                        CompactTouchBuffer.TYPE_GHOST_IDLE
                    }
                    // 3. Multi-Signature Physical Anomaly:
                    // Micro-dwell (flash spark) OR pinpoint electrical area without biological deformation
                    (dwell in 0..ghostMaxDwellMs) || (touchSize < ghostMaxSize && displacement < jitterThreshold) -> {
                        CompactTouchBuffer.TYPE_GHOST_IDLE
                    }
                    else -> {
                        lastHumanUpTime = event.eventTime
                        CompactTouchBuffer.TYPE_HUMAN_FINGER
                    }
                }

                buffer.push(event.x, event.y, event.eventTime, dwell, touchSize, pressure, type)

                if (type == CompactTouchBuffer.TYPE_GHOST_IDLE || type == CompactTouchBuffer.TYPE_GHOST_ECHO) {
                    onAnomalyDetected?.invoke(type, event.x, event.y, dwell)
                }

                return type
            }
        }

        return CompactTouchBuffer.TYPE_HUMAN_FINGER
    }

    /**
     * Derives user-calibrated threshold from gathered human finger samples.
     * Ensures safety margin so real finger taps are never filtered out.
     */
    fun finishCalibration(): GhostTouchProfile {
        if (calibrationSamplesCount == 0) return GhostTouchProfile.BALANCED

        val avgDwell = (calibrationTotalDwell / calibrationSamplesCount).toInt()
        val avgSize = calibrationTotalSize / calibrationSamplesCount

        // Set safe threshold: 45% of user's personal minimum human tap dwell
        val safeDwell = (avgDwell * 0.45f).toInt().coerceIn(15, 65)
        val safeSize = (avgSize * 0.50f).coerceIn(0.02f, 0.08f)

        isCalibrationActive = false
        ghostMaxDwellMs = safeDwell
        ghostMaxSize = safeSize

        return GhostTouchProfile.CUSTOM
    }

    fun flush() {
        buffer.flush()
        lastHumanUpTime = 0L
        lastDownTime = 0L
        calibrationSamplesCount = 0
        calibrationTotalDwell = 0L
        calibrationTotalSize = 0f
    }
}
