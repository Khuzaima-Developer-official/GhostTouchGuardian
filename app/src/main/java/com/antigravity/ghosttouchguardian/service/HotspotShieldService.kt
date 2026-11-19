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
import kotlin.math.min

/**
 * HotspotShieldService — System-Wide Background Ghost Touch Blocker
 *
 * Positions a targeted overlay specifically over the calibrated damaged/glitch zone.
 * - The rest of the screen (90%+) is untouched, with ZERO interference for normal apps.
 * - In the glitch zone:
 *   - Phantom glitches (zero shockwave) are swallowed.
 *   - Real finger taps (with shockwave) trigger temporary pass-through (1.5s) so buttons/keys can be tapped.
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
        if (isTouchThrough) return
        isTouchThrough = true

        overlayParams?.let { p ->
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try {
                overlayView?.let { windowManager.updateViewLayout(it, p) }
            } catch (_: Exception) {}
        }

        handler.removeCallbacks(reArmRunnable)
        handler.postDelayed(reArmRunnable, 1500L) // 1.5 seconds unlock for human typing/tapping
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
            color = Color.argb(30, 255, 23, 68) // Subtle translucent shield
            style = Paint.Style.FILL
        }

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 255, 23, 68)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (isTouchThrough) return
            // Draw targeted shield zone
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), boxPaint)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (isTouchThrough) return false

            // Check physical shockwave
            val hasShockwave = impulseSensor.hasMechanicalImpulse(event.eventTime)
            if (hasShockwave) {
                // REAL FINGER TAP: Unlock shield immediately so finger reaches underlying app
                passThroughTemporarily()
                return false
            }

            // GHOST TOUCH CONFIRMED: Zero mechanical shockwave in glitch zone -> SWALLOW IT!
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                prefs.totalBlockedGhosts = prefs.totalBlockedGhosts + 1
            }
            return true
        }
    }

    companion object {
        const val ACTION_START = "com.antigravity.guardian.SHIELD_START"
        const val ACTION_STOP = "com.antigravity.guardian.SHIELD_STOP"
        const val ACTION_REFRESH_ZONES = "com.antigravity.guardian.SHIELD_REFRESH"
    }
}
