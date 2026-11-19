package com.antigravity.ghosttouchguardian.engine

import android.view.MotionEvent
import com.antigravity.ghosttouchguardian.model.CompactTouchBuffer
import com.antigravity.ghosttouchguardian.model.GhostTouchProfile
import kotlin.math.sqrt

class TouchProfilerEngine(val buffer: CompactTouchBuffer = CompactTouchBuffer(128)) {

    var profile: GhostTouchProfile = GhostTouchProfile.BALANCED

    // When true: Human finger clicks are filtered out, ONLY authentic ghost touches are captured!
    var isGhostOnlyMode: Boolean = false

    // When true: Phone is resting on table/stand, any touch detected is 100% ghost touch
    var isHandsFreeScanActive: Boolean = false

    // Telemetry callbacks
    var onAnomalyDetected: ((type: Byte, x: Float, y: Float, dwell: Int, factorReason: String) -> Unit)? = null
    var onHumanIgnoredInGhostMode: ((x: Float, y: Float, dwell: Int) -> Unit)? = null

    private var lastHumanUpTime: Long = 0L
    private var lastHumanUpX: Float = -1f
    private var lastHumanUpY: Float = -1f

    private var lastDownTime: Long = 0L
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var downPressure: Float = 0f
    private var downTouchMajor: Float = 0f
    private var downSize: Float = 0f

    // Teleportation tracking
    private var lastAnyEventTime: Long = 0L
    private var lastAnyEventX: Float = -1f
    private var lastAnyEventY: Float = -1f

    fun processTouchEvent(event: MotionEvent): Byte {
        val action = event.actionMasked
        val toolType = event.getToolType(0)

        // Stylus bypass
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS) {
            if (action == MotionEvent.ACTION_UP && !isGhostOnlyMode) {
                buffer.push(
                    event.x, event.y, event.eventTime,
                    (event.eventTime - event.downTime).toInt(),
                    event.size, event.pressure, CompactTouchBuffer.TYPE_SPEN
                )
            }
            return CompactTouchBuffer.TYPE_SPEN
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                lastDownTime = event.eventTime
                downX = event.x
                downY = event.y
                downPressure = event.pressure
                downSize = event.size
                downTouchMajor = try { event.touchMajor } catch (_: Exception) { 0f }

                // Check impossible teleportation (EMI charger noise)
                if (lastAnyEventTime > 0L) {
                    val dt = event.eventTime - lastAnyEventTime
                    if (dt in 1L..7L && lastAnyEventX >= 0f) {
                        val dx = event.x - lastAnyEventX
                        val dy = event.y - lastAnyEventY
                        val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                        // Impossible jump (>350px in <8ms)
                        if (dist > 350f) {
                            onAnomalyDetected?.invoke(
                                CompactTouchBuffer.TYPE_GHOST_TELEPORT,
                                event.x, event.y, dt.toInt(), "EMI Teleport (${dist.toInt()}px in ${dt}ms)"
                            )
                        }
                    }
                }

                lastAnyEventTime = event.eventTime
                lastAnyEventX = event.x
                lastAnyEventY = event.y
            }

            MotionEvent.ACTION_UP -> {
                val dwell = (event.eventTime - lastDownTime).toInt()
                val deltaSinceLastUp = if (lastHumanUpTime > 0) lastDownTime - lastHumanUpTime else 9999L
                val touchSize = event.size
                val pressure = event.pressure
                val touchMajor = try { event.touchMajor } catch (_: Exception) { 0f }
                val touchMinor = try { event.touchMinor } catch (_: Exception) { 0f }

                // Micro displacement
                val dx = event.x - downX
                val dy = event.y - downY
                val displacement = sqrt((dx * dx + dy * dy).toDouble()).toFloat()

                val distFromLastUp = if (lastHumanUpX >= 0f) {
                    val ex = event.x - lastHumanUpX
                    val ey = event.y - lastHumanUpY
                    sqrt((ex * ex + ey * ey).toDouble()).toFloat()
                } else 9999f

                lastAnyEventTime = event.eventTime
                lastAnyEventX = event.x
                lastAnyEventY = event.y

                // -----------------------------------------------------------------
                // MULTI-FACTOR EVALUATION: NEVER RELY ON DWELL TIME ALONE
                // -----------------------------------------------------------------

                // Biological Indicators:
                // 1. Physical pressure (>0.04f): Hand/finger clicks exert real physical force
                // 2. Contact ellipse (touchMajor >= 10f): Flesh flattens into an ellipse
                // 3. Contact size (>0.015f): Detectable capacitive area
                val hasBiologicalPressure = pressure >= 0.04f || downPressure >= 0.04f
                val hasBiologicalEllipse = touchMajor >= 10f || touchMinor >= 6f || downTouchMajor >= 10f
                val hasBiologicalSize = touchSize >= 0.015f || downSize >= 0.015f
                val isDefiniteHumanFinger = hasBiologicalPressure || hasBiologicalEllipse || hasBiologicalSize

                // Dielectric Echo: Occurs strictly within 2-18ms of release at the same locus with NO biological footprint
                val isDielectricEcho = deltaSinceLastUp in 2L..18L &&
                        distFromLastUp <= 35f &&
                        !isDefiniteHumanFinger &&
                        dwell < 18

                // Hands-Free Scan: User placed phone on table, any touch is authentic ghost
                val isHandsFreeGhost = isHandsFreeScanActive

                // Zero-physics electrical spark
                val isZeroPhysicsSpark = dwell < 10 &&
                        displacement == 0f &&
                        pressure == 0f &&
                        touchSize == 0f &&
                        touchMajor == 0f

                val type: Byte
                val factorReason: String

                when {
                    isHandsFreeGhost -> {
                        type = CompactTouchBuffer.TYPE_GHOST_IDLE
                        factorReason = "Hands-Free Phantom ($dwell ms)"
                    }
                    isDielectricEcho -> {
                        type = CompactTouchBuffer.TYPE_GHOST_ECHO
                        factorReason = "Dielectric Echo (${deltaSinceLastUp}ms post-lift)"
                    }
                    isZeroPhysicsSpark -> {
                        type = CompactTouchBuffer.TYPE_GHOST_IDLE
                        factorReason = "Zero-Area Spark ($dwell ms)"
                    }
                    dwell < 75 -> {
                        // Any click from 10ms to 75ms (fast or hard click) is 100% human!
                        type = CompactTouchBuffer.TYPE_HUMAN_FAST_TAP
                        factorReason = "Human Fast Click ($dwell ms)"
                        lastHumanUpTime = event.eventTime
                        lastHumanUpX = event.x
                        lastHumanUpY = event.y
                    }
                    else -> {
                        type = CompactTouchBuffer.TYPE_HUMAN_FINGER
                        factorReason = "Human Touch ($dwell ms)"
                        lastHumanUpTime = event.eventTime
                        lastHumanUpX = event.x
                        lastHumanUpY = event.y
                    }
                }

                val isGhost = (type == CompactTouchBuffer.TYPE_GHOST_IDLE ||
                               type == CompactTouchBuffer.TYPE_GHOST_ECHO ||
                               type == CompactTouchBuffer.TYPE_GHOST_TELEPORT)

                // Ghost-Only Isolation Mode Handling
                if (isGhostOnlyMode) {
                    if (isGhost) {
                        buffer.push(event.x, event.y, event.eventTime, dwell, touchSize, pressure, type)
                        onAnomalyDetected?.invoke(type, event.x, event.y, dwell, factorReason)
                    } else {
                        // Real finger clicks are ignored/filtered in this mode!
                        onHumanIgnoredInGhostMode?.invoke(event.x, event.y, dwell)
                    }
                } else {
                    // Diagnostic Mode: Displays all touches with full classification
                    buffer.push(event.x, event.y, event.eventTime, dwell, touchSize, pressure, type)
                    if (isGhost) {
                        onAnomalyDetected?.invoke(type, event.x, event.y, dwell, factorReason)
                    }
                }

                return type
            }
        }

        return CompactTouchBuffer.TYPE_HUMAN_FINGER
    }

    fun flush() {
        buffer.flush()
        lastHumanUpTime = 0L
        lastHumanUpX = -1f
        lastHumanUpY = -1f
        lastDownTime = 0L
        lastAnyEventTime = 0L
        lastAnyEventX = -1f
        lastAnyEventY = -1f
    }
}
