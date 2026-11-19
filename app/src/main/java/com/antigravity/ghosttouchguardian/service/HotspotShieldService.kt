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
 * v1.5.3 — TYPE-based blocking (class-level, not point-level):
 *   Ghost touches are blocked by their PHYSICAL TYPE signature, not just coordinate proximity:
 *   - TYPE_FAST_SPARK: zero shockwave + dwell <= 45ms (electrical transient)
 *   - TYPE_ECHO_PULSE: zero shockwave + dwell 46..110ms (dielectric/discharge echo)
 *   - TYPE_SPEN_BURST: zero shockwave + post-stylus <= 90ms
 *   - TYPE_WAKE_GUARD: zero shockwave + sub-100ms within 1.5s of screen waking
 *   Any touch matching a ghost TYPE is blocked anywhere on screen (not just in bounding box).
 *   Human invariant: dwell > 110ms OR shockwave OR flesh size/pressure = always pass through.
 *
 * Screen-off protection:
 *   On ACTION_SCREEN_ON, TouchGuardianService sends ACTION_SCREEN_WAKE_GUARD.
 *   For 1500ms after screen wake, any zero-shockwave sub-100ms touch is blocked globally —
 *   these are ghost screen-wake bursts from the damaged digitizer.
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

    // Screen-wake ghost burst guard: active for 1500ms after screen turns on
    @Volatile private var isScreenWakeGuardActive = false
    private val screenWakeGuardOff = Runnable { isScreenWakeGuardActive = false }

    private val reArmRunnable: Runnable = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.uptimeMillis()
            val hasRecentMotion = impulseSensor.hasMechanicalImpulse(now, windowMs = 1500L)
            val accelDelta = impulseSensor.getLastAccelDelta()

            if (hasRecentMotion || accelDelta >= 0.009f) {
                handler.postDelayed(this, 2000L)
            } else {
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

        refreshZones()
        if ((activeZones.isNotEmpty() || ghostFingerprint.isValid) && checkOverlayPermission()) {
            attachOverlay()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                refreshZones()
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
                refreshZones()
                if (checkOverlayPermission()) attachOverlay()
            }
            ACTION_REFRESH_ZONES -> {
                refreshZones()
                removeOverlay()
                if (checkOverlayPermission()) attachOverlay()
            }
            ACTION_SCREEN_WAKE_GUARD -> {
                // Screen just turned on — activate 1500ms ghost-burst suppressor
                isScreenWakeGuardActive = true
                handler.removeCallbacks(screenWakeGuardOff)
                handler.postDelayed(screenWakeGuardOff, 1500L)
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
    // Overlay view — ghost TYPE detection logic lives here
    // ---------------------------------------------------------------------------
    private inner class HotspotOverlayView(context: Context) : View(context) {

        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(25, 255, 23, 68)
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(80, 255, 23, 68)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        // Deferred classification state (DOWN→UP pipeline)
        private var pendingDownTime: Long = 0L
        private var pendingDownShockwave: Boolean = false
        private var pendingIsHuman: Boolean = false

        // Post-stylus burst tracking
        private var lastStylusUpTime: Long = 0L

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (isTouchThrough) return
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), boxPaint)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (isTouchThrough) return false

            // S Pen always passes through — track stylus lift for post-stylus echo window
            if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    lastStylusUpTime = event.eventTime
                }
                passThroughTemporarily()
                return false
            }

            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN -> {
                    pendingDownTime = event.eventTime
                    pendingDownShockwave = impulseSensor.hasMechanicalImpulse(event.eventTime, windowMs = 80L)
                    pendingIsHuman = pendingDownShockwave ||
                            event.size >= 0.035f || event.pressure >= 0.10f || event.pointerCount > 1

                    if (pendingIsHuman) passThroughTemporarily()
                    // Always pass ACTION_DOWN through — scroll gestures need it
                    return false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isTouchThrough) {
                        handler.removeCallbacks(reArmRunnable)
                        handler.postDelayed(reArmRunnable, 4000L)
                    }
                    // Late-arriving shockwave (vibration propagation delay)
                    if (!pendingIsHuman) {
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

                    // Consolidate all shockwave evidence across the entire touch lifecycle
                    val finalShockwave = pendingDownShockwave ||
                            impulseSensor.hasMechanicalImpulse(pendingDownTime, windowMs = 120L)

                    // Fleshy contact markers at ACTION_UP (fresh reading)
                    val finalIsHuman = pendingIsHuman || finalShockwave ||
                            event.size >= 0.035f || event.pressure >= 0.10f || event.pointerCount > 1

                    // ----------------------------------------------------------------
                    // HUMAN INVARIANT — always pass through, no matter the zone:
                    // - Any touch held > 110ms is biological (electrical sparks die ≤ 110ms)
                    // - Any touch with shockwave or flesh contact is biological
                    // ----------------------------------------------------------------
                    if (dwell > DWELL_ECHO_MAX || finalIsHuman) {
                        if (!isTouchThrough) passThroughTemporarily()
                        return false
                    }

                    // ----------------------------------------------------------------
                    // GHOST TYPE CLASSIFICATION — zero shockwave + zero flesh contact
                    // Block the entire TYPE — not just specific coordinates.
                    // Physical basis: ghost capacitive noise has TYPE-invariant signatures
                    // that apply across the ENTIRE damaged area, not just one point.
                    // ----------------------------------------------------------------

                    // TYPE 3 — S Pen Electrostatic Burst (highest priority: S Pen specific)
                    val timeSinceStylus = if (lastStylusUpTime > 0L) pendingDownTime - lastStylusUpTime else -1L
                    val isSPenEchoType = timeSinceStylus in 2L..SPEN_ECHO_WINDOW_MS && dwell <= SPEN_ECHO_DWELL_MAX

                    // TYPE 4 — Screen-Wake Ghost Burst (screen just turned on, digitizer firing)
                    val isWakeGuardType = isScreenWakeGuardActive && dwell <= WAKE_GUARD_DWELL_MAX

                    // TYPE 1 — Fast Electrical Spark (sub-45ms transient)
                    val isFastSparkType = dwell <= DWELL_SPARK_MAX

                    // TYPE 2 — Echo/Discharge Pulse (46..110ms, any location, zero shockwave)
                    // Note: dwell > DWELL_ECHO_MAX already passed through above
                    val isEchoPulseType = dwell in (DWELL_SPARK_MAX + 1)..DWELL_ECHO_MAX

                    // Block if ANY ghost TYPE matches — location is irrelevant
                    val isGhostType = isSPenEchoType || isWakeGuardType || isFastSparkType || isEchoPulseType

                    if (isGhostType) {
                        prefs.totalBlockedGhosts = prefs.totalBlockedGhosts + 1
                        val cancelEvent = MotionEvent.obtain(event).also {
                            it.action = MotionEvent.ACTION_CANCEL
                        }
                        try {
                            super.onTouchEvent(cancelEvent)
                        } catch (_: Exception) {}
                        cancelEvent.recycle()
                        return true // Ghost TYPE swallowed
                    }

                    // Should not reach here (covered by human invariant above), but be safe
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
        const val ACTION_SCREEN_WAKE_GUARD = "com.antigravity.guardian.SHIELD_WAKE_GUARD"

        // Ghost TYPE thresholds (physical basis for capacitive noise classification)
        // TYPE 1 — Fast Electrical Spark: capacitive transient, always dies < 45ms
        const val DWELL_SPARK_MAX = 45
        // TYPE 2 — Echo/Discharge Pulse: dielectric echo or S Pen static, 46..110ms
        const val DWELL_ECHO_MAX = 110
        // TYPE 3 — S Pen post-stylus burst: electrostatic discharge up to 1500ms after lift
        const val SPEN_ECHO_WINDOW_MS = 1500L
        const val SPEN_ECHO_DWELL_MAX = 90
        // TYPE 4 — Screen-wake ghost burst: damaged digitizer fires when screen wakes
        const val WAKE_GUARD_DWELL_MAX = 100
    }
}
