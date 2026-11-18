package com.antigravity.ghosttouchguardian.engine

import android.view.MotionEvent
import com.antigravity.ghosttouchguardian.model.CompactTouchBuffer
import com.antigravity.ghosttouchguardian.model.GhostTouchProfile
import kotlin.math.sqrt

class TouchProfilerEngine(val buffer: CompactTouchBuffer = CompactTouchBuffer(128)) {

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

    var onAnomalyDetected: ((type: Byte, x: Float, y: Float, dwell: Int) -> Unit)? = null

    fun processTouchEvent(event: MotionEvent): Byte {
        val action = event.actionMasked
        val toolType = event.getToolType(0)

        // Stylus bypass
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
                // Record down
                lastDownTime = event.eventTime
                downX = event.x
                downY = event.y

                val deltaSinceLastUp = if (lastHumanUpTime > 0) event.eventTime - lastHumanUpTime else 9999L

                // Echo check
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

                // Calculate displacement
                val dx = event.x - downX
                val dy = event.y - downY
                val displacement = sqrt((dx * dx + dy * dy).toDouble()).toFloat()

                // Classify touch
                val type: Byte = when {
                    deltaSinceLastUp in 2L..echoWindowMaxMs -> {
                        CompactTouchBuffer.TYPE_GHOST_ECHO
                    }
                    isIdleMonitoringActive && (dwell in 0..ghostMaxDwellMs) -> {
                        CompactTouchBuffer.TYPE_GHOST_IDLE
                    }
                    (dwell in 0..ghostMaxDwellMs) || (touchSize < ghostMaxSize && displacement < jitterThreshold) -> {
                        CompactTouchBuffer.TYPE_GHOST_IDLE
                    }
                    else -> {
                        lastHumanUpTime = event.eventTime
                        CompactTouchBuffer.TYPE_HUMAN_FINGER
                    }
                }

                // Push buffer
                buffer.push(event.x, event.y, event.eventTime, dwell, touchSize, pressure, type)

                // Trigger callback
                if (type == CompactTouchBuffer.TYPE_GHOST_IDLE || type == CompactTouchBuffer.TYPE_GHOST_ECHO) {
                    onAnomalyDetected?.invoke(type, event.x, event.y, dwell)
                }

                return type
            }
        }

        return CompactTouchBuffer.TYPE_HUMAN_FINGER
    }

    fun flush() {
        // Zero state
        buffer.flush()
        lastHumanUpTime = 0L
        lastDownTime = 0L
    }
}
