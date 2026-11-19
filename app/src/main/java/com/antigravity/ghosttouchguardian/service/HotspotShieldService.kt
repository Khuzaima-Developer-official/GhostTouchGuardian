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

/**
 * HotspotShieldService — System-Wide Background Ghost Touch Blocker
 *
 * Runs continuously in background with SYSTEM_ALERT_WINDOW overlay.
 * Uses 12-factor physics verification:
 * - If touch has mechanical shockwave (real finger) -> PASSED THROUGH to apps
 * - If touch matches calibrated zones or learned Ghost Fingerprint without shockwave -> BLOCKED/CONSUMED
 */
class HotspotShieldService : Service() {

    private lateinit var prefs: GuardianPreferences
    private lateinit var windowManager: WindowManager
    private lateinit var impulseSensor: MechanicalImpulseSensor

    private var overlayView: HotspotOverlayView? = null
    private var activeZones: List<HotspotZone> = emptyList()
    private var ghostFingerprint: GhostFingerprint = GhostFingerprint.empty()

    private val handler = Handler(Looper.getMainLooper())
    private var isSuspended = false

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
                updateOverlay()
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

        val view = HotspotOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
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
        }

        try {
            windowManager.addView(view, params)
            overlayView = view
        } catch (_: Exception) {}
    }

    private fun updateOverlay() {
        overlayView?.invalidate()
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        impulseSensor.stop()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Background shield overlay view
    private inner class HotspotOverlayView(context: Context) : View(context) {

        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(35, 255, 23, 68)
            style = Paint.Style.FILL
        }

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 255, 23, 68)
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }

        private val fpCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(25, 0, 229, 255)
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (isSuspended) return

            // Render calibrated zones
            for (z in activeZones) {
                canvas.drawRect(z.left, z.top, z.right, z.bottom, boxPaint)
                canvas.drawRect(z.left, z.top, z.right, z.bottom, borderPaint)
            }

            // Render ghost fingerprint region if available
            if (ghostFingerprint.isValid) {
                val radius = (ghostFingerprint.xStdDev * 2f).coerceIn(80f, 250f)
                canvas.drawCircle(ghostFingerprint.avgX, ghostFingerprint.avgY, radius, fpCirclePaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (isSuspended) return false

            val x = event.x
            val y = event.y

            // 1. Check if in calibrated bounding box
            val inHotspot = activeZones.any { it.contains(x, y) }

            // 2. Check if matches learned Ghost Fingerprint (multi-dimensional score)
            val accelDelta = impulseSensor.getLastAccelDelta()
            val fpScore = if (ghostFingerprint.isValid) {
                ghostFingerprint.matchScore(x, y, 35, event.size, event.pressure, accelDelta)
            } else 0f
            val matchesFingerprint = fpScore >= 0.60f

            // 3. Check custom user block rules
            val matchesCustom = ghostFingerprint.customBlockRules.any { it.matches(x, y, 50) }

            if (!inHotspot && !matchesFingerprint && !matchesCustom) {
                // Not in any ghost profile -> pass directly to underlying app
                return false
            }

            // 4. Critical Invariant: Check physical shockwave (Newtonian impulse)
            val hasShockwave = impulseSensor.hasMechanicalImpulse(event.eventTime)
            if (hasShockwave) {
                // REAL FINGER DETECTED with physical momentum -> NEVER BLOCK!
                return false
            }

            // Ghost touch confirmed (zero shockwave + matches ghost profile) -> CONSUME IT!
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
