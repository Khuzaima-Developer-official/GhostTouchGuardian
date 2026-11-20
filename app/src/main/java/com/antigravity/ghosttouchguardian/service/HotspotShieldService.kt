package com.antigravity.ghosttouchguardian.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.antigravity.ghosttouchguardian.data.GuardianPreferences
import com.antigravity.ghosttouchguardian.engine.GhostCalibrationEngine
import com.antigravity.ghosttouchguardian.engine.HotspotZone
import com.antigravity.ghosttouchguardian.engine.ai.GhostMindCore
import com.antigravity.ghosttouchguardian.model.GhostFingerprint
import com.antigravity.ghosttouchguardian.model.GhostGlitchProfile
import com.antigravity.ghosttouchguardian.model.HumanFingerProfile
import com.antigravity.ghosttouchguardian.sensor.MechanicalImpulseSensor
import kotlin.math.max

/**
 * HotspotShieldService (v2.0) — GhostMind AI Full-Screen & Targeted Screen Shield
 *
 * Implements:
 * 1. Finger Touch Mode: Real human touches pass with zero disruption, while ghost touches are swallowed in the background.
 * 2. Dedicated S Pen Mode: Full-time active stylus priority + continuous electrostatic noise suppression.
 * 3. Smart State Machine: Prevents mid-gesture window mutations so 120Hz scrolling and typing remain buttery smooth.
 * 4. Stochastic Frequency Range: Catches ghost touches across the entire learned frequency band [minInterval..maxInterval].
 */
class HotspotShieldService : Service() {

    private lateinit var prefs: GuardianPreferences
    private lateinit var windowManager: WindowManager
    private lateinit var impulseSensor: MechanicalImpulseSensor

    private var overlayView: HotspotOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var activeZones: List<HotspotZone> = emptyList()

    // AI Mind Core
    private val aiCore = GhostMindCore()

    private val handler = Handler(Looper.getMainLooper())
    private var isTouchThrough = false

    // Screen wake guard
    @Volatile private var isScreenWakeGuardActive = false
    private val screenWakeGuardOff = Runnable { isScreenWakeGuardActive = false }

    // Human micro-motion aware re-arm
    private val reArmRunnable: Runnable = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.uptimeMillis()
            val hasRecentMotion = impulseSensor.hasMechanicalImpulse(now, windowMs = 1500L)
            val accelDelta = impulseSensor.getLastAccelDelta()

            if (hasRecentMotion || accelDelta >= 0.009f) {
                // User is still holding or interacting with the phone — postpone re-arm!
                handler.postDelayed(this, 2000L)
            } else {
                // Phone is undisturbed and quiet — safe to engage shield
                reArmShield()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = GuardianPreferences(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        impulseSensor = MechanicalImpulseSensor(this)
        impulseSensor.start()

        reloadProfiles()
        if (checkOverlayPermission()) {
            attachOverlay()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                reloadProfiles()
                if (checkOverlayPermission()) attachOverlay()
            }
            ACTION_STOP -> {
                removeOverlay()
                stopSelf()
            }
            ACTION_PAUSE -> {
                removeOverlay()
            }
            ACTION_RESUME -> {
                reloadProfiles()
                if (checkOverlayPermission()) attachOverlay()
            }
            ACTION_REFRESH_ZONES -> {
                reloadProfiles()
                removeOverlay()
                if (checkOverlayPermission()) attachOverlay()
            }
            ACTION_SCREEN_WAKE_GUARD -> {
                isScreenWakeGuardActive = true
                handler.removeCallbacks(screenWakeGuardOff)
                handler.postDelayed(screenWakeGuardOff, 1500L)
            }
            ACTION_ENABLE_SPEN_MODE -> {
                prefs.isSPenModeActive = true
                aiCore.isSPenModeActive = true
            }
            ACTION_DISABLE_SPEN_MODE -> {
                prefs.isSPenModeActive = false
                aiCore.isSPenModeActive = false
            }
        }
        return START_STICKY
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun reloadProfiles() {
        activeZones = GhostCalibrationEngine.parseZonesFromJson(prefs.savedHotspotsJson)
        aiCore.humanProfile = HumanFingerProfile.fromJson(prefs.humanFingerProfileJson)
        aiCore.ghostProfile = GhostGlitchProfile.fromJson(prefs.ghostGlitchProfileJson)
        aiCore.isSPenModeActive = prefs.isSPenModeActive
    }

    private fun attachOverlay() {
        if (overlayView != null) return

        val isFullScreen = activeZones.isEmpty() // If no localized zones calibrated, shield covers entire screen!

        var boxLeft = 0
        var boxTop = 0
        var boxWidth = WindowManager.LayoutParams.MATCH_PARENT
        var boxHeight = WindowManager.LayoutParams.MATCH_PARENT

        if (!isFullScreen) {
            val minX = activeZones.minOf { it.left }.toInt().coerceAtLeast(0)
            val minY = activeZones.minOf { it.top }.toInt().coerceAtLeast(0)
            val maxX = activeZones.maxOf { it.right }.toInt()
            val maxY = activeZones.maxOf { it.bottom }.toInt()
            boxLeft = max(0, minX - 10)
            boxTop = max(0, minY - 10)
            boxWidth = max(100, (maxX - minX) + 20)
            boxHeight = max(100, (maxY - minY) + 20)
        }

        val view = HotspotOverlayView(this, isFullScreen)
        val params = WindowManager.LayoutParams(
            boxWidth,
            boxHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (!isFullScreen) {
                x = boxLeft
                y = boxTop
            }
        }

        try {
            windowManager.addView(view, params)
            overlayView = view
            overlayParams = params
        } catch (_: Exception) {}
    }

    private fun passThroughTemporarily() {
        isTouchThrough = true
        overlayParams?.let { p ->
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try {
                overlayView?.let { windowManager.updateViewLayout(it, p) }
            } catch (_: Exception) {}
        }
        handler.removeCallbacks(reArmRunnable)
        handler.postDelayed(reArmRunnable, 4000L)
    }

    private fun reArmShield() {
        isTouchThrough = false
        overlayParams?.let { p ->
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try {
                overlayView?.let { windowManager.updateViewLayout(it, p) }
            } catch (_: Exception) {}
        }
    }

    private fun removeOverlay() {
        handler.removeCallbacks(reArmRunnable)
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
        overlayParams = null
        isTouchThrough = false
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        impulseSensor.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------------------
    // Overlay View with GhostMind AI Classifier
    // ---------------------------------------------------------------------------
    private inner class HotspotOverlayView(context: Context, val isFullScreen: Boolean) : View(context) {

        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(if (isFullScreen) 0 else 25, 255, 23, 68) // Invisible if full screen
            style = Paint.Style.FILL
        }

        private var pendingDownTime: Long = 0L
        private var lastStylusUpTime: Long = 0L
        private var lastHumanUpTime: Long = 0L
        private var lastTouchTime: Long = 0L

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (isTouchThrough || isFullScreen) return
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), boxPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (isTouchThrough) return false

            val toolType = event.getToolType(0)

            // S Pen Bypass: S Pen touches ALWAYS pass through cleanly
            if (toolType == MotionEvent.TOOL_TYPE_STYLUS) {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    lastStylusUpTime = event.eventTime
                }
                passThroughTemporarily()
                return false
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pendingDownTime = event.eventTime
                    val now = event.eventTime
                    val interval = if (lastTouchTime > 0L) now - lastTouchTime else -1L
                    lastTouchTime = now

                    val accelDelta = impulseSensor.getLastAccelDelta()
                    val hasImpulse = impulseSensor.hasMechanicalImpulse(now, windowMs = 80L)
                    val effectiveAccel = if (hasImpulse) max(accelDelta, 0.025f) else accelDelta

                    val timeSinceHuman = if (lastHumanUpTime > 0L) now - lastHumanUpTime else -1L
                    val timeSinceStylus = if (lastStylusUpTime > 0L) now - lastStylusUpTime else -1L

                    // Check if inside any calibrated Green Safe Zone (interactive stimulus)
                    val inGreenZone = aiCore.ghostProfile.greenZones.any { it.contains(event.x, event.y) }

                    // Classify at ACTION_DOWN with AI Mind
                    val classification = aiCore.classify(
                        toolType = toolType,
                        dwell = 0, // at DOWN, dwell is 0
                        size = event.size,
                        pressure = event.pressure,
                        accelDelta = effectiveAccel,
                        intervalFromPrevMs = interval,
                        timeSinceLastHumanUp = timeSinceHuman,
                        timeSinceLastStylusUp = timeSinceStylus,
                        inGreenZone = inGreenZone
                    )

                    if (!classification.isGhost) {
                        // Real human finger touch confirmed!
                        lastHumanUpTime = now
                        passThroughTemporarily()
                        return false // Allow DOWN to pass to underlying app cleanly
                    }

                    // Ghost touch detected at ACTION_DOWN -> Swallow it!
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isTouchThrough) {
                        handler.removeCallbacks(reArmRunnable)
                        handler.postDelayed(reArmRunnable, 4000L)
                        return false
                    }
                    // Late shockwave arrival check
                    val lateShockwave = impulseSensor.hasMechanicalImpulse(event.eventTime, windowMs = 100L)
                    if (lateShockwave || event.size >= 0.035f || event.pressure >= 0.10f) {
                        lastHumanUpTime = event.eventTime
                        passThroughTemporarily()
                        return false
                    }
                    return true // Swallow ghost drag
                }

                MotionEvent.ACTION_UP -> {
                    val dwell = (event.eventTime - pendingDownTime).toInt()
                    val now = event.eventTime
                    val timeSinceHuman = if (lastHumanUpTime > 0L) pendingDownTime - lastHumanUpTime else -1L
                    val timeSinceStylus = if (lastStylusUpTime > 0L) pendingDownTime - lastStylusUpTime else -1L

                    val accelDelta = impulseSensor.getLastAccelDelta()
                    val hasImpulse = impulseSensor.hasMechanicalImpulse(pendingDownTime, windowMs = 120L)
                    val effectiveAccel = if (hasImpulse) max(accelDelta, 0.025f) else accelDelta

                    val inGreenZone = aiCore.ghostProfile.greenZones.any { it.contains(event.x, event.y) }

                    val classification = aiCore.classify(
                        toolType = toolType,
                        dwell = dwell,
                        size = event.size,
                        pressure = event.pressure,
                        accelDelta = effectiveAccel,
                        intervalFromPrevMs = dwell.toLong(),
                        timeSinceLastHumanUp = timeSinceHuman,
                        timeSinceLastStylusUp = timeSinceStylus,
                        inGreenZone = inGreenZone
                    )

                    // Human Invariant: If classified as human or held > 110ms
                    if (!classification.isGhost || dwell > 110) {
                        lastHumanUpTime = now
                        if (!isTouchThrough) passThroughTemporarily()
                        return false
                    }

                    // Ghost confirmed: Swallow it!
                    prefs.totalBlockedGhosts = prefs.totalBlockedGhosts + 1
                    val cancelEvent = MotionEvent.obtain(event).also {
                        it.action = MotionEvent.ACTION_CANCEL
                    }
                    try {
                        super.onTouchEvent(cancelEvent)
                    } catch (_: Exception) {}
                    cancelEvent.recycle()
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    return false
                }
            }

            return false
        }
    }

    companion object {
        const val ACTION_START = "com.antigravity.guardian.SHIELD_START"
        const val ACTION_STOP = "com.antigravity.guardian.SHIELD_STOP"
        const val ACTION_REFRESH_ZONES = "com.antigravity.guardian.SHIELD_REFRESH"
        const val ACTION_PAUSE = "com.antigravity.guardian.SHIELD_PAUSE"
        const val ACTION_RESUME = "com.antigravity.guardian.SHIELD_RESUME"
        const val ACTION_SCREEN_WAKE_GUARD = "com.antigravity.guardian.SHIELD_WAKE_GUARD"
        const val ACTION_ENABLE_SPEN_MODE = "com.antigravity.guardian.ENABLE_SPEN_MODE"
        const val ACTION_DISABLE_SPEN_MODE = "com.antigravity.guardian.DISABLE_SPEN_MODE"
    }
}
