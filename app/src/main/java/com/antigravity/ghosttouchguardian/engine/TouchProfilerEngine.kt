package com.antigravity.ghosttouchguardian.engine

import android.view.MotionEvent
import com.antigravity.ghosttouchguardian.model.CompactTouchBuffer

/**
 * High-precision, zero-allocation Touch Profiler Engine.
 * Runs at the native 240Hz touch sampling rate of Samsung Galaxy S23 Ultra.
 * Analyzes physical metrics: Dwell Time, Contact Size, Pressure, and Post-Touch Echo Latency.
 */
class TouchProfilerEngine(val buffer: CompactTouchBuffer = CompactTouchBuffer(128)) {

    // Thresholds calibrated for S23 Ultra AMOLED & Capacitive Digitizer
    var echoWindowMaxMs: Long = 70L       // Trailing echo window after finger release
    var ghostMaxDwellMs: Int = 25         // Micro-tap threshold (human taps are >= 60ms)
    var ghostMaxSize: Float = 0.04f       // Pinpoint electrical noise size threshold

    private var lastHumanUpTime: Long = 0L
    private var lastDownTime: Long = 0L
    private var isCurrentTouchStylus: Boolean = false

    var isIdleMonitoringActive: Boolean = false
    var isInteractiveEchoMonitoringActive: Boolean = false

    // Callback for UI updates (zero-allocation primitive event listener)
    var onAnomalyDetected: ((type: Byte, x: Float, y: Float, dwell: Int) -> Unit)? = null

    /**
     * Inspects a touch event. Called on the touch hotpath.
     * Uses only local stack variables to guarantee ZERO heap allocation.
     */
    fun processTouchEvent(event: MotionEvent): Byte {
        val action = event.actionMasked
        val toolType = event.getToolType(0)

        // 1. S Pen Hardware Bypass
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
                val deltaSinceLastUp = if (lastHumanUpTime > 0) event.eventTime - lastHumanUpTime else 9999L

                // Check for Trailing Echo (Ghost tap appearing immediately after lifting finger)
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

                val type: Byte = when {
                    // Trailing Echo Anomaly
                    deltaSinceLastUp in 2L..echoWindowMaxMs -> {
                        CompactTouchBuffer.TYPE_GHOST_ECHO
                    }
                    // Idle hands-free test mode
                    isIdleMonitoringActive -> {
                        CompactTouchBuffer.TYPE_GHOST_IDLE
                    }
                    // Physical signature anomaly: micro-duration or pinpoint electrical spark
                    dwell in 0..ghostMaxDwellMs || touchSize < ghostMaxSize -> {
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

    fun flush() {
        buffer.flush()
        lastHumanUpTime = 0L
        lastDownTime = 0L
    }
}
