package com.antigravity.ghosttouchguardian.engine

import android.view.MotionEvent
import com.antigravity.ghosttouchguardian.model.CompactTouchBuffer
import com.antigravity.ghosttouchguardian.model.GhostFingerprint
import com.antigravity.ghosttouchguardian.model.GhostTouchProfile
import com.antigravity.ghosttouchguardian.sensor.MechanicalImpulseSensor
import kotlin.math.abs
import kotlin.math.sqrt

class TouchProfilerEngine(val buffer: CompactTouchBuffer = CompactTouchBuffer(128)) {

    var profile: GhostTouchProfile = GhostTouchProfile.BALANCED

    // Mode flags
    var isGhostOnlyMode: Boolean = false
    var isHandsFreeScanActive: Boolean = false

    // Hardware sensors
    var impulseSensor: MechanicalImpulseSensor? = null
    var calibratedHotspots: List<HotspotZone> = emptyList()
    var calibratedAvgPeriodMs: Long = 0L
    var calibratedVariancePercent: Float = 100f

    /** Learned ghost fingerprint from Identify Mode (multi-dimensional ghost profile) */
    var ghostFingerprint: GhostFingerprint = GhostFingerprint.empty()

    /** v2.0 GhostMind AI Core */
    var ghostMindCore: com.antigravity.ghosttouchguardian.engine.ai.GhostMindCore =
        com.antigravity.ghosttouchguardian.engine.ai.GhostMindCore()

    // Telemetry callbacks
    var onAnomalyDetected: ((type: Byte, x: Float, y: Float, dwell: Int, factorReason: String) -> Unit)? = null
    var onHumanIgnoredInGhostMode: ((x: Float, y: Float, dwell: Int) -> Unit)? = null

    // Human tracking
    private var lastHumanUpTime: Long = 0L
    private var lastHumanUpX: Float = -1f
    private var lastHumanUpY: Float = -1f
    private var lastAnyUpTime: Long = 0L

    // S Pen discharge tracking — post-stylus electrostatic echo suppression
    private var lastStylusUpTime: Long = 0L
    private var lastStylusUpX: Float = -1f
    private var lastStylusUpY: Float = -1f

    // Touch down tracking
    private var lastDownTime: Long = 0L
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var isCurrentTouchEcho: Boolean = false

    // Static lock tracking
    private var moveSampleCount: Int = 0
    private var moveVarianceSum: Float = 0f
    private var isStaticLockDetected: Boolean = false

    // Teleport tracking
    private var lastAnyDownTime: Long = 0L
    private var lastAnyDownX: Float = -1f
    private var lastAnyDownY: Float = -1f

    fun processTouchEvent(event: MotionEvent): Byte {
        val action = event.actionMasked
        val toolType = event.getToolType(0)
        val p1x = if (event.pointerCount > 1) event.getX(1) else -1f
        val p1y = if (event.pointerCount > 1) event.getY(1) else -1f

        return processTouch(
            action = action,
            toolType = toolType,
            x = event.x,
            y = event.y,
            eventTime = event.eventTime,
            downTime = event.downTime,
            size = event.size,
            pressure = event.pressure,
            pointerCount = event.pointerCount,
            p1X = p1x,
            p1Y = p1y
        )
    }

    fun processTouch(
        action: Int,
        toolType: Int = MotionEvent.TOOL_TYPE_FINGER,
        x: Float,
        y: Float,
        eventTime: Long,
        downTime: Long = eventTime,
        size: Float = 0f,
        pressure: Float = 0f,
        pointerCount: Int = 1,
        p1X: Float = -1f,
        p1Y: Float = -1f,
        forcedImpulse: Boolean? = null
    ): Byte {
        // Stylus bypass
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS) {
            if (action == MotionEvent.ACTION_UP) {
                lastStylusUpTime = eventTime
                lastStylusUpX = x
                lastStylusUpY = y
                if (!isGhostOnlyMode) {
                    buffer.push(
                        x, y, eventTime,
                        (eventTime - downTime).toInt(),
                        size, pressure, CompactTouchBuffer.TYPE_SPEN
                    )
                }
            }
            return CompactTouchBuffer.TYPE_SPEN
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                lastDownTime = eventTime
                downX = x
                downY = y
                moveSampleCount = 0
                moveVarianceSum = 0f
                isStaticLockDetected = false

                // Check echo
                val deltaSinceLastUp = if (lastHumanUpTime > 0L) eventTime - lastHumanUpTime else 9999L
                val distFromLastUp = if (lastHumanUpX >= 0f) {
                    val ex = x - lastHumanUpX
                    val ey = y - lastHumanUpY
                    sqrt((ex * ex + ey * ey).toDouble()).toFloat()
                } else 9999f

                isCurrentTouchEcho = lastHumanUpTime > 0L && deltaSinceLastUp in 2L..25L && distFromLastUp <= 28f

                // Check teleport
                if (lastAnyDownTime > 0L && lastAnyDownX >= 0f) {
                    val dt = eventTime - lastAnyDownTime
                    if (dt in 1L..6L) {
                        val dx = x - lastAnyDownX
                        val dy = y - lastAnyDownY
                        val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                        if (dist > 250f) {
                            onAnomalyDetected?.invoke(
                                CompactTouchBuffer.TYPE_GHOST_TELEPORT,
                                x, y, dt.toInt(), "Kinematic Jump (${dist.toInt()}px in ${dt}ms)"
                            )
                        }
                    }
                }

                lastAnyDownTime = eventTime
                lastAnyDownX = x
                lastAnyDownY = y
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Check collinear
                if (pointerCount >= 2 && p1X >= 0f && p1Y >= 0f) {
                    val dx = abs(x - p1X)
                    val dy = abs(y - p1Y)
                    val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    if (dist > 120f && (dx < 0.5f || dy < 0.5f)) {
                        onAnomalyDetected?.invoke(
                            CompactTouchBuffer.TYPE_GRID_COLINEAR,
                            x, y, 0, "ITO Grid Short (${dist.toInt()}px)"
                        )
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                // Track micro-tremor
                val moveDwell = (eventTime - lastDownTime).toInt()
                val dx = x - downX
                val dy = y - downY
                val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                moveSampleCount++
                moveVarianceSum += (dist * dist)

                if (moveDwell >= 500 && moveSampleCount >= 5 && !isStaticLockDetected) {
                    val meanVariance = moveVarianceSum / moveSampleCount
                    if (dist <= 2.5f && meanVariance <= 1.0f) {
                        isStaticLockDetected = true
                        onAnomalyDetected?.invoke(
                            CompactTouchBuffer.TYPE_STATIC_LOCK,
                            x, y, moveDwell, "Static Droplet ($moveDwell ms)"
                        )
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                val dwell = (eventTime - lastDownTime).toInt()
                val deltaSinceLastUp = if (lastHumanUpTime > 0L) lastDownTime - lastHumanUpTime else 9999L

                // Check shockwave & physical contact markers
                val hasImpulse = forcedImpulse ?: (impulseSensor?.hasMechanicalImpulse(lastDownTime, windowMs = 80L) ?: false)
                val isFleshyHuman = (size >= 0.04f && pressure >= 0.12f) || pointerCount > 1
                val isHumanConfirmed = if (forcedImpulse == false) false else (hasImpulse || isFleshyHuman)

                // Check post-stylus electrostatic discharge echo
                val timeSinceStylusMs = if (lastStylusUpTime > 0L) lastDownTime - lastStylusUpTime else -1L
                val isSPenDischargeEcho = timeSinceStylusMs in 2L..1500L

                // Check hotspot
                val inHotspot = calibratedHotspots.any { it.contains(x, y) }

                // Check fingerprint match (multi-dimensional ghost profile from Identify Mode)
                val accelDelta = impulseSensor?.getLastAccelDelta() ?: 0f
                val fingerprintScore = if (ghostFingerprint.isValid) {
                    ghostFingerprint.matchScore(x, y, dwell, size, pressure, accelDelta)
                } else 0f
                val matchesFingerprint = fingerprintScore >= 0.60f

                // Check custom block rules
                val matchesCustomRule = ghostFingerprint.customBlockRules.any { it.matches(x, y, dwell) }

                // Check periodicity (measured up-to-up, lift-to-down, or down-to-down)
                val intervalUpToUp = if (lastAnyUpTime > 0L) eventTime - lastAnyUpTime else 0L
                val intervalLiftToDown = if (lastAnyUpTime > 0L) lastDownTime - lastAnyUpTime else 0L
                val intervalDownToDown = if (lastAnyDownTime > 0L) lastDownTime - lastAnyDownTime else 0L
                val matchesPeriodicity = if (calibratedAvgPeriodMs in 10L..2500L && calibratedVariancePercent < 15f) {
                    val tol = (calibratedAvgPeriodMs * 0.15f).coerceAtLeast(12f)
                    abs(intervalUpToUp - calibratedAvgPeriodMs) <= tol ||
                    abs(intervalLiftToDown - calibratedAvgPeriodMs) <= tol ||
                    abs(intervalDownToDown - calibratedAvgPeriodMs) <= tol
                } else false

                val type: Byte
                val factorReason: String

                when {
                    isHandsFreeScanActive -> {
                        type = CompactTouchBuffer.TYPE_GHOST_IDLE
                        factorReason = "Hands-Free Desk ($dwell ms)"
                    }
                    isCurrentTouchEcho -> {
                        type = CompactTouchBuffer.TYPE_GHOST_ECHO
                        factorReason = "Dielectric Echo (${deltaSinceLastUp}ms post-lift)"
                    }
                    isSPenDischargeEcho && !isHumanConfirmed && dwell <= 70 -> {
                        type = CompactTouchBuffer.TYPE_GHOST_ECHO
                        factorReason = "S Pen Static Echo (${timeSinceStylusMs}ms post-pen)"
                    }
                    isStaticLockDetected && !isHumanConfirmed -> {
                        type = CompactTouchBuffer.TYPE_STATIC_LOCK
                        factorReason = "Static Droplet ($dwell ms)"
                    }
                    dwell >= 120 && isHumanConfirmed -> {
                        type = CompactTouchBuffer.TYPE_HUMAN_FINGER
                        factorReason = "Human Long Press ($dwell ms)"
                        lastHumanUpTime = eventTime
                        lastHumanUpX = x
                        lastHumanUpY = y
                    }
                    inHotspot && !isHumanConfirmed && dwell in 8..110 -> {
                        // Glitch in hotspot
                        type = CompactTouchBuffer.TYPE_GHOST_HOTSPOT
                        factorReason = "Calibrated Ghost ($dwell ms, zero shockwave)"
                    }
                    matchesPeriodicity && !isHumanConfirmed && dwell in 8..90 -> {
                        // Periodic machine glitch
                        type = CompactTouchBuffer.TYPE_GHOST_IDLE
                        factorReason = "Periodic Ghost ($dwell ms, ${intervalLiftToDown}ms period)"
                    }
                    matchesFingerprint && !isHumanConfirmed -> {
                        // Fingerprint matched — multi-factor ghost (learned from Identify Mode)
                        type = CompactTouchBuffer.TYPE_GHOST_HOTSPOT
                        factorReason = "Fingerprint Ghost (score=${String.format("%.2f", fingerprintScore)}, dwell=${dwell}ms)"
                    }
                    // v2.0 GhostMind AI range classification:
                    // If AI model is calibrated and classifies as ghost (stochastic frequency or dwell spectrum)
                    ghostMindCore.ghostProfile.isValid && !isHumanConfirmed &&
                            ghostMindCore.classify(toolType, dwell, size, pressure, if (forcedImpulse == false) 0.005f else accelDelta, intervalLiftToDown, deltaSinceLastUp, timeSinceStylusMs).isGhost -> {
                        val aiResult = ghostMindCore.classify(toolType, dwell, size, pressure, if (forcedImpulse == false) 0.005f else accelDelta, intervalLiftToDown, deltaSinceLastUp, timeSinceStylusMs)
                        type = aiResult.type
                        factorReason = aiResult.reason
                    }
                    matchesCustomRule && !isHumanConfirmed -> {
                        // User's custom block rule matched
                        type = CompactTouchBuffer.TYPE_GHOST_IDLE
                        factorReason = "Custom Rule Block (dwell=${dwell}ms)"
                    }
                    dwell < 75 -> {
                        // Human tap
                        type = CompactTouchBuffer.TYPE_HUMAN_FAST_TAP
                        factorReason = "Human Fast Click ($dwell ms)"
                        lastHumanUpTime = eventTime
                        lastHumanUpX = x
                        lastHumanUpY = y
                    }
                    else -> {
                        // Human touch
                        type = CompactTouchBuffer.TYPE_HUMAN_FINGER
                        factorReason = "Human Touch ($dwell ms)"
                        lastHumanUpTime = eventTime
                        lastHumanUpX = x
                        lastHumanUpY = y
                    }
                }

                lastAnyUpTime = eventTime

                val isGhost = (type == CompactTouchBuffer.TYPE_GHOST_IDLE ||
                        type == CompactTouchBuffer.TYPE_GHOST_ECHO ||
                        type == CompactTouchBuffer.TYPE_GHOST_TELEPORT ||
                        type == CompactTouchBuffer.TYPE_STATIC_LOCK ||
                        type == CompactTouchBuffer.TYPE_GRID_COLINEAR ||
                        type == CompactTouchBuffer.TYPE_GHOST_HOTSPOT)

                // Dispatch event
                if (isGhostOnlyMode) {
                    if (isGhost) {
                        buffer.push(x, y, eventTime, dwell, size, pressure, type)
                        onAnomalyDetected?.invoke(type, x, y, dwell, factorReason)
                    } else {
                        onHumanIgnoredInGhostMode?.invoke(x, y, dwell)
                    }
                } else {
                    buffer.push(x, y, eventTime, dwell, size, pressure, type)
                    if (isGhost) {
                        onAnomalyDetected?.invoke(type, x, y, dwell, factorReason)
                    }
                }

                return type
            }
        }

        return CompactTouchBuffer.TYPE_HUMAN_FINGER
    }

    fun flush() {
        // Reset state
        buffer.flush()
        lastHumanUpTime = 0L
        lastHumanUpX = -1f
        lastHumanUpY = -1f
        lastStylusUpTime = 0L
        lastStylusUpX = -1f
        lastStylusUpY = -1f
        lastDownTime = 0L
        lastAnyDownTime = 0L
        lastAnyDownX = -1f
        lastAnyDownY = -1f
        lastAnyUpTime = 0L
        moveSampleCount = 0
        moveVarianceSum = 0f
        isStaticLockDetected = false
        isCurrentTouchEcho = false
    }
}
