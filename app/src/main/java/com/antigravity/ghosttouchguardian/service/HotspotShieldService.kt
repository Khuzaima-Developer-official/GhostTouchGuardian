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
import com.antigravity.ghosttouchguardian.model.GhostFingerprint
import com.antigravity.ghosttouchguardian.sensor.MechanicalImpulseSensor
import kotlin.math.max

/**
 * HotspotShieldService — System-Wide Background Ghost Touch Blocker
 *
 * Positions a targeted overlay specifically over the calibrated damaged/glitch zone.
 * - The rest of the screen (90%+) is untouched, with ZERO interference for normal apps.
 * - In the glitch zone:
 *   - Human finger taps (shockwave, flesh size >= 0.035, pressure >= 0.10) unlock pass-through
 *     for 3 seconds so the user can easily tap, double-tap, and type.
 *   - Ghost touches (no shockwave, tiny contact size, zero pressure) are instantly swallowed.
 */
class HotspotShieldService : Service() {

    private lateinit var prefs: GuardianPreferences
    private lateinit var windowManager: WindowManager
    private lateinit var impulseSensor: MechanicalImpulseSensor

    private var overlayView: HotspotOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var activeZones: List<HotspotZone> = emptyList()
    private var ghostFingerprint: GhostFingerprint = GhostFingerprint.empty()

    private val handler = Handler(Looper.getMainLooper())
    private var isTouchThrough = false

    private val reArmRunnable = Runnable {
        reArmShield()
    }

    override fun onCreate() {
        super.onCreate()
        prefs = GuardianPreferences(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        impulseSensor = MechanicalImpulseSensor(this)
        impulseSensor.start()

        refreshZones()
        if ((activeZones.isNotEmpty() || ghostFingerprint.isValid) && checkOverlayPermission()) {
            attachOverlay()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                refreshZones()
                if (checkOverlayPermission()) {
                    attachOverlay()
                }
            }
            ACTION_STOP -> {
                removeOverlay()
                stopSelf()
            }
            ACTION_PAUSE -> {
                removeOverlay()
            }
            ACTION_RESUME -> {
                refreshZones()
                if (checkOverlayPermission()) {
                    attachOverlay()
                }
            }
            ACTION_REFRESH_ZONES -> {
                refreshZones()
                removeOverlay()
                if (checkOverlayPermission()) {
                    attachOverlay()
                }
            }
        }
        return START_STICKY
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun refreshZones() {
        activeZones = GhostCalibrationEngine.parseZonesFromJson(prefs.savedHotspotsJson)
        ghostFingerprint = GhostFingerprint.fromJson(prefs.ghostFingerprintJson)
    }

    private fun attachOverlay() {
        if (overlayView != null) return

        // Calculate targeted bounding box of the glitch region
        var boxLeft = 0
        var boxTop = 0
        var boxWidth = 0
        var boxHeight = 0

        if (activeZones.isNotEmpty()) {
            val minX = activeZones.minOf { it.left }.toInt().coerceAtLeast(0)
            val minY = activeZones.minOf { it.top }.toInt().coerceAtLeast(0)
            val maxX = activeZones.maxOf { it.right }.toInt()
            val maxY = activeZones.maxOf { it.bottom }.toInt()
            boxLeft = max(0, minX - 10)
            boxTop = max(0, minY - 10)
            boxWidth = max(100, (maxX - minX) + 20)
            boxHeight = max(100, (maxY - minY) + 20)
        } else if (ghostFingerprint.isValid) {
            val radius = (ghostFingerprint.xStdDev * 2.5f).coerceIn(80f, 250f).toInt()
            boxLeft = max(0, ghostFingerprint.avgX.toInt() - radius)
            boxTop = max(0, ghostFingerprint.avgY.toInt() - radius)
            boxWidth = max(100, radius * 2)
            boxHeight = max(100, radius * 2)
        }

        if (boxWidth <= 0 || boxHeight <= 0) return

        val view = HotspotOverlayView(this)
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
            x = boxLeft
            y = boxTop
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

        // Quick 500ms unlock window: enough for human tap/double-tap to pass,
        // but immediately re-arms before trailing post-finger ghost echoes can escape!
        handler.removeCallbacks(reArmRunnable)
        handler.postDelayed(reArmRunnable, 500L)
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
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
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

    // Glitch zone overlay view
    private inner class HotspotOverlayView(context: Context) : View(context) {

        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(25, 255, 23, 68) // Subtle translucent shield
            style = Paint.Style.FILL
        }

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(80, 255, 23, 68)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        // State for deferred ghost classification (ACTION_DOWN -> ACTION_UP pipeline)
        private var pendingDownTime: Long = 0L
        private var pendingDownSize: Float = 0f
        private var pendingDownPressure: Float = 0f
        private var pendingDownShockwave: Boolean = false
        private var pendingIsHuman: Boolean = false

        // Post-Stylus electrostatic burst tracking
        private var lastStylusUpTime: Long = 0L

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (isTouchThrough) return
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), boxPaint)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (isTouchThrough) return false

            // S Pen Bypass & Post-Stylus Burst Tracking
            if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    lastStylusUpTime = event.eventTime
                }
                passThroughTemporarily()
                return false
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Classify at DOWN only for quick human unlock — but ALWAYS pass through DOWN
                    // so the underlying app sees ACTION_DOWN and scrolls work normally.
                    pendingDownTime = event.eventTime
                    pendingDownSize = event.size
                    pendingDownPressure = event.pressure
                    pendingDownShockwave = impulseSensor.hasMechanicalImpulse(event.eventTime, windowMs = 80L)
                    pendingIsHuman = pendingDownShockwave ||
                            event.size >= 0.035f || event.pressure >= 0.10f || event.pointerCount > 1

                    if (pendingIsHuman) {
                        // Real finger confirmed at DOWN — unlock immediately
                        passThroughTemporarily()
                    }
                    // ALWAYS return false on ACTION_DOWN so scroll gestures start normally
                    return false
                }

                MotionEvent.ACTION_MOVE -> {
                    // NEVER consume MOVE — this is what breaks scrolling
                    // If we were in pass-through, stay pass-through
                    if (!pendingIsHuman) {
                        // Check again on MOVE in case shockwave arrives late (device vibration propagation delay)
                        val nowShockwave = impulseSensor.hasMechanicalImpulse(event.eventTime, windowMs = 100L)
                        if (nowShockwave) {
                            pendingIsHuman = true
                            passThroughTemporarily()
                        }
                    }
                    return false
                }

                MotionEvent.ACTION_UP -> {
                    val dwell = (event.eventTime - pendingDownTime).toInt()

                    // Final shockwave check at ACTION_UP window too
                    val finalShockwave = pendingDownShockwave ||
                            impulseSensor.hasMechanicalImpulse(pendingDownTime, windowMs = 120L)

                    // Final fleshy check — also include UP event's fresh size/pressure reading
                    val finalIsHuman = pendingIsHuman || finalShockwave ||
                            event.size >= 0.035f || event.pressure >= 0.10f || event.pointerCount > 1

                    // Check S Pen electrostatic discharge burst (zero shockwave, within 1.5s of stylus lift)
                    val timeSinceStylus = if (lastStylusUpTime > 0L) pendingDownTime - lastStylusUpTime else -1L
                    val isPostStylusEcho = timeSinceStylus in 2L..1500L

                    if (isPostStylusEcho && !finalShockwave && dwell <= 90) {
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

                    if (finalIsHuman) {
                        // Human confirmed — ensure pass-through is active, let UP through
                        if (!isTouchThrough) passThroughTemporarily()
                        return false
                    }

                    // Ghost confirmed: zero shockwave + sub-45ms dwell + zero flesh contact
                    // Ghost touches always have dwell <= 110ms. Longer = human long-press.
                    if (dwell <= 110) {
                        prefs.totalBlockedGhosts = prefs.totalBlockedGhosts + 1
                        // Suppress the UP — the underlying app already received ACTION_DOWN
                        // but we inject ACTION_CANCEL to roll back its gesture state
                        val cancelEvent = MotionEvent.obtain(event).also {
                            it.action = MotionEvent.ACTION_CANCEL
                        }
                        try {
                            super.onTouchEvent(cancelEvent)
                        } catch (_: Exception) {}
                        cancelEvent.recycle()
                        return true // Swallow the ghost ACTION_UP
                    }

                    // Dwell > 110ms with no shockwave = human long-press with low pressure sensor
                    // Let it pass safely
                    if (!isTouchThrough) passThroughTemporarily()
                    return false
                }

                MotionEvent.ACTION_CANCEL -> {
                    pendingIsHuman = false
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
    }
}
