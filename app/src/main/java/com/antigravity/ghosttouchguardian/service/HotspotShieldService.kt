package com.antigravity.ghosttouchguardian.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.antigravity.ghosttouchguardian.R
import com.antigravity.ghosttouchguardian.data.CompactTouchRepository
import com.antigravity.ghosttouchguardian.data.GuardianPreferences
import com.antigravity.ghosttouchguardian.engine.GhostCalibrationEngine
import com.antigravity.ghosttouchguardian.engine.HotspotZone
import com.antigravity.ghosttouchguardian.engine.ai.ExtendedTouchVector
import com.antigravity.ghosttouchguardian.engine.ai.GhostBrainEngine
import com.antigravity.ghosttouchguardian.engine.ai.GhostMindCore
import com.antigravity.ghosttouchguardian.engine.ai.MasterTouchClassifier
import com.antigravity.ghosttouchguardian.model.GhostFingerprint
import com.antigravity.ghosttouchguardian.model.GhostGlitchProfile
import com.antigravity.ghosttouchguardian.model.HumanFingerProfile
import com.antigravity.ghosttouchguardian.model.TouchFeatureVector
import com.antigravity.ghosttouchguardian.sensor.MechanicalImpulseSensor
import com.antigravity.ghosttouchguardian.ui.MainActivity
import kotlin.math.max

/**
 * HotspotShieldService (v3.1) — Precision Screen Shield & Pure S Pen EMR Gate
 *
 * Implements:
 * 1. Dedicated S Pen Mode: 100% Capacitive Muting. All finger touches, palms, water droplets,
 *    and parasitic capacitive leaks are completely dropped/swallowed at the window level.
 *    Wacom magnetic EMR hover/touch activates seamless native pass-through.
 * 2. Finger Touch Mode: Real human touches pass with zero disruption, while water-damaged
 *    static pools and micro-tremor-free glitches are swallowed.
 * 3. Smart State Machine: Prevents mid-gesture window mutations.
 */
class HotspotShieldService : Service() {

    private lateinit var prefs: GuardianPreferences
    private lateinit var windowManager: WindowManager
    private lateinit var impulseSensor: MechanicalImpulseSensor

    private var overlayView: HotspotOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var activeZones: List<HotspotZone> = emptyList()

    // AI Mind Core & 8D Bayesian Brain & Master Touch Classifier
    private val aiCore = GhostMindCore()
    private var brainEngine: GhostBrainEngine = GhostBrainEngine()
    private var masterClassifier: MasterTouchClassifier = MasterTouchClassifier()
    private lateinit var repository: CompactTouchRepository

    private val handler = Handler(Looper.getMainLooper())
    private var isTouchThrough = false

    // Screen wake guard
    @Volatile private var isScreenWakeGuardActive = false
    private val screenWakeGuardOff = Runnable { isScreenWakeGuardActive = false }

    // Human interaction tracking
    @Volatile private var lastHumanInteractionTime: Long = 0L
    @Volatile private var isPointerDownOnScreen: Boolean = false

    // S Pen debounce runnable: re-arms the shield 350ms after pen leaves hover/screen
    private val penExitRunnable = Runnable {
        if (prefs.isSPenModeActive) {
            reArmShieldDirect()
            overlayView?.postInvalidate()
        }
    }

    // Normal mode re-arm runnable
    private val reArmRunnable: Runnable = object : Runnable {
        override fun run() {
            if (prefs.isSPenModeActive) {
                reArmShieldDirect()
                return
            }
            val now = android.os.SystemClock.uptimeMillis()
            val timeSinceHuman = now - lastHumanInteractionTime

            // Never re-arm while pointer is physically held down or during rapid human typing
            if (isPointerDownOnScreen || timeSinceHuman < 650L) {
                handler.postDelayed(this, 350L)
            } else {
                reArmShieldDirect()
            }
        }
    }

    private var screenReceiver: ScreenStateReceiver? = null

    override fun onCreate() {
        super.onCreate()
        prefs = GuardianPreferences(this)
        repository = CompactTouchRepository(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        impulseSensor = MechanicalImpulseSensor(this).apply {
            onHumanMotionDetected = {
                // In S Pen mode, phone motion must NEVER unblock capacitive touch!
                if (!prefs.isSPenModeActive && !isTouchThrough) {
                    passThroughTemporarily()
                }
            }
            start()
        }

        // Direct Screen State Receiver:
        // Automatically manages sensor sleep & screen wake overlay activation
        screenReceiver = ScreenStateReceiver(
            onScreenOn = {
                // 1. Re-activate sensor
                impulseSensor.start()
                // 2. Ensure lock screen / app overlay is active
                if (checkOverlayPermission()) {
                    if (overlayView == null) {
                        attachOverlay()
                    }
                }
                // 3. Screen wake ghost-burst guard
                isScreenWakeGuardActive = true
                handler.removeCallbacks(screenWakeGuardOff)
                handler.postDelayed(screenWakeGuardOff, 2000L)
                reArmShieldDirect()
                overlayView?.postInvalidate()
            },
            onScreenOff = {
                // Screen turned off: Stop impulse sensor to completely eliminate battery & CPU drain
                impulseSensor.stop()
                reArmShieldDirect()
            }
        )

        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            screenReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        reloadProfiles()
        if (checkOverlayPermission()) {
            attachOverlay()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {}
        isShieldRunning = true

        when (intent?.action) {
            ACTION_START -> {
                reloadProfiles()
                if (checkOverlayPermission()) attachOverlay()
                updateNotification()
            }
            ACTION_STOP -> {
                prefs.isHotspotShieldEnabled = false
                isShieldRunning = false
                removeOverlay()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                removeOverlay()
                updateNotification()
            }
            ACTION_RESUME -> {
                reloadProfiles()
                if (checkOverlayPermission()) attachOverlay()
                updateNotification()
            }
            ACTION_REFRESH_ZONES -> {
                reloadProfiles()
                removeOverlay()
                if (checkOverlayPermission()) attachOverlay()
                updateNotification()
            }
            ACTION_SCREEN_WAKE_GUARD -> {
                isScreenWakeGuardActive = true
                handler.removeCallbacks(screenWakeGuardOff)
                handler.postDelayed(screenWakeGuardOff, 2000L)
                reArmShieldDirect()
            }
            ACTION_SCREEN_OFF -> {
                reArmShieldDirect()
            }
            ACTION_ENABLE_SPEN_MODE -> {
                prefs.isSPenModeActive = true
                prefs.isHotspotShieldEnabled = true
                aiCore.isSPenModeActive = true
                brainEngine.isSPenModeActive = true
                masterClassifier.isSPenModeActive = true

                if (checkOverlayPermission()) {
                    if (overlayView == null) {
                        attachOverlay()
                    } else if (!overlayView!!.isFullScreen) {
                        // S Pen Mode demands 100% full-screen coverage
                        removeOverlay()
                        attachOverlay()
                    }
                    // Immediately lock down all capacitive touch!
                    reArmShieldDirect()
                    overlayView?.postInvalidate()
                }
                updateNotification()
            }
            ACTION_DISABLE_SPEN_MODE -> {
                prefs.isSPenModeActive = false
                aiCore.isSPenModeActive = false
                brainEngine.isSPenModeActive = false
                masterClassifier.isSPenModeActive = false
                removeOverlay()
                if (prefs.isHotspotShieldEnabled && checkOverlayPermission()) {
                    reloadProfiles()
                    attachOverlay()
                }
                updateNotification()
            }
            ACTION_TOGGLE_SPEN_MODE -> {
                prefs.isSPenModeActive = !prefs.isSPenModeActive
                aiCore.isSPenModeActive = prefs.isSPenModeActive
                brainEngine.isSPenModeActive = prefs.isSPenModeActive
                masterClassifier.isSPenModeActive = prefs.isSPenModeActive

                if (prefs.isSPenModeActive) {
                    prefs.isHotspotShieldEnabled = true
                    if (checkOverlayPermission()) {
                        removeOverlay()
                        attachOverlay()
                        reArmShieldDirect()
                    }
                } else {
                    removeOverlay()
                    if (prefs.isHotspotShieldEnabled && checkOverlayPermission()) {
                        reloadProfiles()
                        attachOverlay()
                    }
                }
                updateNotification()
                overlayView?.postInvalidate()
            }
            ACTION_RESET_DATA -> {
                prefs.resetAllLearnedProfiles()
                repository.resetBrain()
                aiCore.humanProfile = com.antigravity.ghosttouchguardian.model.HumanFingerProfile()
                aiCore.ghostProfile = com.antigravity.ghosttouchguardian.model.GhostGlitchProfile()
                brainEngine = com.antigravity.ghosttouchguardian.engine.ai.GhostBrainEngine()
                masterClassifier = com.antigravity.ghosttouchguardian.engine.ai.MasterTouchClassifier()
                reloadProfiles()
                updateNotification()
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ghost Touch Active Shield",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "24/7 Persistent Touch Interception & S Pen Gate"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingLaunch = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Stop action: Turns shield off, removes overlay and dismisses notification
        val stopIntent = Intent(this, HotspotShieldService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 10, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title: String
        val text: String
        val spenButtonText: String
        val spenPendingIntent: PendingIntent

        if (prefs.isSPenModeActive) {
            title = "🖊️ S Pen Exclusive: ON"
            text = "Fingers 100% Muted • Only S Pen Active"
            val disableSPenIntent = Intent(this, HotspotShieldService::class.java).apply {
                action = ACTION_DISABLE_SPEN_MODE
            }
            spenButtonText = "🖐️ Switch to Finger Mode"
            spenPendingIntent = PendingIntent.getService(
                this, 11, disableSPenIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            val blocked = prefs.totalBlockedGhosts
            title = "🛡️ Ghost Touch Shield: ON"
            text = "45ms Spam Filter Active • $blocked Glitches Swallowed"
            val enableSPenIntent = Intent(this, HotspotShieldService::class.java).apply {
                action = ACTION_ENABLE_SPEN_MODE
            }
            spenButtonText = "🖊️ Switch to S-Pen Only"
            spenPendingIntent = PendingIntent.getService(
                this, 12, enableSPenIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true) // Un-swipeable! NEVER DISAPPEARS UNTIL TURNED OFF
            .setAutoCancel(false)
            .setContentIntent(pendingLaunch)
            .addAction(R.drawable.ic_shield, spenButtonText, spenPendingIntent)
            .addAction(R.drawable.ic_shield, "🛑 Turn Off Shield", pendingStop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {}
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
        brainEngine = GhostBrainEngine.fromJson(prefs.ghostBrainEngineJson).apply {
            isSPenModeActive = prefs.isSPenModeActive
        }
        masterClassifier = repository.loadBrain().apply {
            isSPenModeActive = prefs.isSPenModeActive
        }
    }

    private fun attachOverlay() {
        if (overlayView != null) return

        // FULL SCREEN MANDATE: In both Normal Ghost Shield and Dedicated S Pen Mode,
        // the shield covers 100% of the display to capture random moisture glitches anywhere on screen.
        val isFullScreen = true
        val boxWidth = WindowManager.LayoutParams.MATCH_PARENT
        val boxHeight = WindowManager.LayoutParams.MATCH_PARENT

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
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager.addView(view, params)
            overlayView = view
            overlayParams = params

            if (prefs.isSPenModeActive) {
                // In S Pen Mode: Start FULLY ARMED from millisecond 0!
                isTouchThrough = false
            } else {
                passThroughTemporarily()
            }
        } catch (_: Exception) {}
    }

    private fun enableStylusPassThrough() {
        handler.removeCallbacks(penExitRunnable)
        if (!isTouchThrough) {
            isTouchThrough = true
            overlayParams?.let { p ->
                p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                try {
                    overlayView?.let { windowManager.updateViewLayout(it, p) }
                } catch (_: Exception) {}
            }
        }
        // Keep pass-through open while S Pen is active; re-arm 250ms after pen stops
        handler.postDelayed(penExitRunnable, 250L)
    }

    private fun scheduleReArmAfterStylus() {
        handler.removeCallbacks(penExitRunnable)
        // Fast 150ms snap shut when pen leaves hover range
        handler.postDelayed(penExitRunnable, 150L)
    }

    fun reArmShieldDirect() {
        handler.removeCallbacks(penExitRunnable)
        handler.removeCallbacks(reArmRunnable)
        if (isTouchThrough) {
            isTouchThrough = false
            overlayParams?.let { p ->
                p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                try {
                    overlayView?.let { windowManager.updateViewLayout(it, p) }
                } catch (_: Exception) {}
            }
        }
    }

    private fun passThroughTemporarily() {
        if (prefs.isSPenModeActive) return

        lastHumanInteractionTime = android.os.SystemClock.uptimeMillis()
        if (!isTouchThrough) {
            isTouchThrough = true
            overlayParams?.let { p ->
                p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                try {
                    overlayView?.let { windowManager.updateViewLayout(it, p) }
                } catch (_: Exception) {}
            }
        }
        handler.removeCallbacks(reArmRunnable)
        handler.postDelayed(reArmRunnable, 650L) // Fast 650ms typing grace window
    }

    private fun removeOverlay() {
        handler.removeCallbacks(penExitRunnable)
        handler.removeCallbacks(reArmRunnable)
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
        overlayParams = null
        isTouchThrough = false
    }

    private fun triggerHapticAlert(isGhost: Boolean) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (isGhost) {
                    VibrationEffect.createOneShot(35L, VibrationEffect.DEFAULT_AMPLITUDE)
                } else {
                    VibrationEffect.createOneShot(15L, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(if (isGhost) 35L else 15L)
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        isShieldRunning = false
        screenReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        screenReceiver = null
        removeOverlay()
        impulseSensor.stop()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // If shield or S Pen mode is active, DO NOT STOP!
        // Swiping app from Recent Apps must NEVER kill active touch protection!
        if (prefs.isHotspotShieldEnabled || prefs.isSPenModeActive) {
            val restartIntent = Intent(applicationContext, HotspotShieldService::class.java).apply {
                action = if (prefs.isSPenModeActive) ACTION_ENABLE_SPEN_MODE else ACTION_START
            }
            try {
                androidx.core.content.ContextCompat.startForegroundService(applicationContext, restartIntent)
            } catch (_: Exception) {}

            val restartPendingIntent = PendingIntent.getService(
                applicationContext, 1001, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
            alarmManager?.set(
                android.app.AlarmManager.ELAPSED_REALTIME,
                android.os.SystemClock.elapsedRealtime() + 500L,
                restartPendingIntent
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------------------
    // Overlay View with GhostMind AI Classifier & S Pen Pure EMR Gate
    // ---------------------------------------------------------------------------
    private inner class HotspotOverlayView(context: Context, val isFullScreen: Boolean) : View(context) {

        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(if (isFullScreen) 0 else 25, 255, 23, 68)
            style = Paint.Style.FILL
        }

        // HUD Paints for S Pen Mode visual feedback
        private val hudBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val hudBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 28f
            isFakeBoldText = true
        }
        private val hudSubTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 20f
        }

        private var pendingDownTime: Long = 0L
        private var pendingDownX: Float = 0f
        private var pendingDownY: Float = 0f
        private var pendingDownPressure: Float = 0f
        private val movePoints = mutableListOf<Pair<Float, Float>>()
        private var lastStylusUpTime: Long = 0L
        private var lastHumanUpTime: Long = 0L
        private var lastTouchTime: Long = 0L

        // Visual telemetry state
        private var lastBlockedTime: Long = 0L
        private var lastStylusHoverTime: Long = 0L

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Screen remains 100% clean and unobstructed.
            // S Pen Mode indicator and toggle live directly in the persistent Notification.
        }

        override fun onHoverEvent(event: MotionEvent): Boolean {
            if (prefs.isSPenModeActive) {
                val toolType = event.getToolType(0)
                if (toolType == MotionEvent.TOOL_TYPE_STYLUS) {
                    lastStylusHoverTime = android.os.SystemClock.uptimeMillis()
                    when (event.actionMasked) {
                        MotionEvent.ACTION_HOVER_ENTER,
                        MotionEvent.ACTION_HOVER_MOVE -> {
                            // S Pen is hovering near glass! Temporarily set FLAG_NOT_TOUCHABLE
                            // so when pen tip touches down, touch goes directly to underlying app
                            enableStylusPassThrough()
                            return false
                        }
                        MotionEvent.ACTION_HOVER_EXIT -> {
                            scheduleReArmAfterStylus()
                            return false
                        }
                    }
                }
            }
            return super.onHoverEvent(event)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val toolType = event.getToolType(0)
            val now = event.eventTime

            // =========================================================================
            // TIER 0: DEDICATED S PEN MODE (100% CAPACITIVE TOUCH MUTING)
            // =========================================================================
            if (prefs.isSPenModeActive) {
                if (toolType == MotionEvent.TOOL_TYPE_STYLUS) {
                    // Stylus is physically touching down
                    lastStylusHoverTime = android.os.SystemClock.uptimeMillis()
                    lastStylusUpTime = now
                    enableStylusPassThrough()
                    return false // Let stylus stroke flow through natively
                } else {
                    // 100% CAPACITIVE TOUCH MUTING:
                    // Any finger touch, water droplet, or parasitic capacitive short is
                    // COMPLETELY CONSUMED HERE. Nothing reaches the underlying app!
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        prefs.totalBlockedGhosts = prefs.totalBlockedGhosts + 1
                        triggerHapticAlert(isGhost = true)
                    }
                    return true // Swallowed immediately!
                }
            }

            // =========================================================================
            // TIER 1: ULTRA-FAST RAPID SPAM FILTER (< 45ms INTERVAL OR MULTI-BURST)
            // =========================================================================
            // High-frequency water droplet capacitive resonance causes bursts of phantom
            // touches within 0-44ms (even 10 touches in 1ms!).
            // Conscious human tapping physical limit is > 70ms (absolute minimum ~55-60ms).
            // Any non-stylus touch occurring within < 45ms of the previous touch is pure electrical spam.
            // Swallow IMMEDIATELY with ZERO object allocations or vector extractions!
            if (toolType != MotionEvent.TOOL_TYPE_STYLUS) {
                val interval = if (lastTouchTime > 0L) now - lastTouchTime else Long.MAX_VALUE
                if (interval in 0L..44L) {
                    lastTouchTime = now
                    if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) {
                        prefs.totalBlockedGhosts = prefs.totalBlockedGhosts + 1
                        triggerHapticAlert(isGhost = true)
                    }
                    reArmShieldDirect()
                    return true // SWALLOW BURST INSTANTLY!
                }
            }
            lastTouchTime = now

            // =========================================================================
            // TIER 2: NORMAL FINGER TOUCH & AI GHOST SHIELD MODE
            // =========================================================================
            if (isTouchThrough) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                    isPointerDownOnScreen = true
                    lastHumanInteractionTime = now
                    handler.removeCallbacks(reArmRunnable)
                    handler.postDelayed(reArmRunnable, 650L)
                } else if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    isPointerDownOnScreen = false
                    lastHumanInteractionTime = now
                    handler.removeCallbacks(reArmRunnable)
                    handler.postDelayed(reArmRunnable, 650L)
                }
                return false
            }

            // Normal S Pen bypass outside S Pen mode
            if (toolType == MotionEvent.TOOL_TYPE_STYLUS) {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    lastStylusUpTime = event.eventTime
                }
                passThroughTemporarily()
                return false
            }

            // Screen Wake Guard: When phone wakes from screen off, digitizer produces wake bursts
            if (isScreenWakeGuardActive) {
                val hasImpulse = impulseSensor.hasMechanicalImpulse(event.eventTime, windowMs = 120L)
                val isFleshy = event.size >= 0.04f || event.pressure >= 0.12f
                if (!hasImpulse && !isFleshy) {
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        prefs.totalBlockedGhosts = prefs.totalBlockedGhosts + 1
                    }
                    return true // Swallow wake burst
                } else {
                    isScreenWakeGuardActive = false
                    passThroughTemporarily()
                    return false
                }
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isPointerDownOnScreen = true
                    lastHumanInteractionTime = event.eventTime
                    pendingDownTime = event.eventTime
                    pendingDownX = event.x
                    pendingDownY = event.y
                    pendingDownPressure = event.pressure
                    movePoints.clear()
                    movePoints.add(Pair(event.x, event.y))
                    val accelDelta = impulseSensor.getLastAccelDelta()
                    val hasImpulse = impulseSensor.hasMechanicalImpulse(now, windowMs = 80L)
                    val effectiveAccel = if (hasImpulse) max(accelDelta, 0.025f) else accelDelta

                    val inGreenZone = aiCore.ghostProfile.greenZones.any { it.contains(event.x, event.y) }

                    // Evaluates physical human evidence:
                    // 1. Accelerometer shockwave (phone chassis impulse from finger strike)
                    // 2. Soft-tissue spread (fleshy contact area size >= 0.035)
                    // 3. True capacitive pressure (voltage delta >= 0.10)
                    // 4. Safe green zone from calibration
                    // 5. Learned human profile match
                    val isHumanTap = hasImpulse || event.size >= 0.035f || event.pressure >= 0.10f || inGreenZone ||
                            (aiCore.humanProfile.isValid && aiCore.humanProfile.matchesHuman(0, event.size, event.pressure, effectiveAccel))

                    if (isHumanTap) {
                        lastHumanUpTime = now
                        passThroughTemporarily()
                        return false // Allow DOWN to pass to underlying app cleanly
                    }

                    // Ghost Candidate: Zero physical shockwave, tiny contact footprint, zero pressure.
                    // SWALLOW ACTION_DOWN completely so it NEVER reaches the underlying app!
                    // Full 10D tensor (micro-tremor, dwell, static lock) will be evaluated at ACTION_UP.
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    isPointerDownOnScreen = true
                    lastHumanInteractionTime = event.eventTime
                    movePoints.add(Pair(event.x, event.y))
                    if (isTouchThrough) {
                        handler.removeCallbacks(reArmRunnable)
                        handler.postDelayed(reArmRunnable, 650L)
                        return false
                    }
                    // Check for late mechanical shockwave or fleshy drag expansion
                    val lateShockwave = impulseSensor.hasMechanicalImpulse(event.eventTime, windowMs = 100L)
                    if (lateShockwave || event.size >= 0.035f || event.pressure >= 0.10f) {
                        lastHumanUpTime = event.eventTime
                        passThroughTemporarily()
                        return false
                    }
                    return true // Swallow ghost smear/drag
                }

                MotionEvent.ACTION_UP -> {
                    isPointerDownOnScreen = false
                    lastHumanInteractionTime = event.eventTime
                    val dwell = (event.eventTime - pendingDownTime).toInt()

                    // -----------------------------------------------------------------
                    // ULTRA-FAST DWELL GATE (< 45ms):
                    // Minimum physical human tap duration is > 60ms.
                    // Any contact starting and ending in < 45ms is physically impossible
                    // for human fingers — pure water / electrical capacitance glitch!
                    // -----------------------------------------------------------------
                    if (dwell < 45 && toolType != MotionEvent.TOOL_TYPE_STYLUS) {
                        prefs.totalBlockedGhosts = prefs.totalBlockedGhosts + 1
                        triggerHapticAlert(isGhost = true)
                        reArmShieldDirect()
                        return true // SWALLOW INSTANTLY!
                    }

                    val now = event.eventTime
                    val timeSinceHuman = if (lastHumanUpTime > 0L) pendingDownTime - lastHumanUpTime else -1L
                    val timeSinceStylus = if (lastStylusUpTime > 0L) pendingDownTime - lastStylusUpTime else -1L
                    val interval = if (lastTouchTime > 0L) pendingDownTime - lastTouchTime else dwell.toLong()

                    val accelDelta = impulseSensor.getLastAccelDelta()
                    val hasImpulse = impulseSensor.hasMechanicalImpulse(pendingDownTime, windowMs = 120L)
                    val effectiveAccel = if (hasImpulse) max(accelDelta, 0.025f) else accelDelta

                    val inGreenZone = aiCore.ghostProfile.greenZones.any { it.contains(event.x, event.y) }

                    // Extract 10D Extended Feature Vector for Master AI Brain
                    val extendedVector = ExtendedTouchVector.extract(
                        event = event,
                        dwellMs = dwell,
                        downX = pendingDownX,
                        downY = pendingDownY,
                        downPressure = pendingDownPressure,
                        accelDelta = effectiveAccel,
                        intervalMs = interval,
                        timeSinceStimulusMs = if (timeSinceHuman >= 0) timeSinceHuman else timeSinceStylus,
                        movePoints = movePoints
                    )

                    // 1. Check Master Touch Classifier (Active Learning & Micro-Tremor)
                    val masterDecision = masterClassifier.classify(extendedVector, toolType)

                    // Extract 8D Feature Vector for fallback Bayesian Engine
                    val featureVector = TouchFeatureVector.extract(
                        event = event,
                        dwellMs = dwell,
                        downX = pendingDownX,
                        downY = pendingDownY,
                        accelDelta = effectiveAccel,
                        intervalMs = interval,
                        timeSinceStimulusMs = if (timeSinceHuman >= 0) timeSinceHuman else timeSinceStylus
                    )

                    // FIXED: Removed bare `dwell >= 110` from human check — water damage can produce
                    // long static dwell (40..2000ms) so dwell alone is NOT a human discriminator.
                    // Human requires PHYSICAL evidence: momentum shockwave, flesh deformation, OR biological micro-tremor.
                    val isDefiniteHuman = hasImpulse || event.size >= 0.035f || event.pressure >= 0.10f ||
                            inGreenZone || extendedVector.microTremorVariance >= 0.60f ||
                            (dwell >= 110 && extendedVector.microTremorVariance >= 0.25f) || // Long dwell only human if tremor present
                            (aiCore.humanProfile.isValid && aiCore.humanProfile.matchesHuman(dwell, event.size, event.pressure, effectiveAccel)) ||
                            (!masterDecision.isGhost && masterDecision.confidence >= 0.85f)

                    if (isDefiniteHuman) {
                        lastHumanUpTime = now
                        brainEngine.learnOnline(featureVector, isGhost = false)
                        masterClassifier.learnOnline(extendedVector, isGhost = false)
                        if (!isTouchThrough) passThroughTemporarily()
                        return false
                    }

                    if (masterDecision.isGhost) {
                        prefs.totalBlockedGhosts = prefs.totalBlockedGhosts + 1
                        masterClassifier.learnOnline(extendedVector, isGhost = true)
                        brainEngine.learnOnline(featureVector, isGhost = true)

                        if (masterClassifier.totalOnlineUpdates % 10L == 0L) {
                            repository.saveBrain(masterClassifier)
                            prefs.ghostBrainEngineJson = brainEngine.toJson()
                        }

                        triggerHapticAlert(isGhost = true)
                        isPointerDownOnScreen = false
                        reArmShieldDirect()
                        return true
                    }

                    val brainDecision = brainEngine.classify(featureVector, toolType, inGreenZone)

                    if (!brainDecision.isGhost) {
                        lastHumanUpTime = now
                        if (brainDecision.confidence >= 0.70f) {
                            brainEngine.learnOnline(featureVector, isGhost = false)
                            masterClassifier.learnOnline(extendedVector, isGhost = false)
                        }
                        if (!isTouchThrough) passThroughTemporarily()
                        return false
                    }

                    prefs.totalBlockedGhosts = prefs.totalBlockedGhosts + 1
                    brainEngine.learnOnline(featureVector, isGhost = true)
                    masterClassifier.learnOnline(extendedVector, isGhost = true)

                    if (brainEngine.totalOnlineUpdates % 10L == 0L) {
                        repository.saveBrain(masterClassifier)
                        prefs.ghostBrainEngineJson = brainEngine.toJson()
                    }

                    triggerHapticAlert(isGhost = true)
                    isPointerDownOnScreen = false
                    reArmShieldDirect()
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    isPointerDownOnScreen = false
                    lastHumanInteractionTime = event.eventTime
                    return false
                }
            }

            return false
        }
    }

    companion object {
        const val NOTIFICATION_ID = 2302
        const val CHANNEL_ID = "ghost_touch_shield_channel"
        const val ACTION_START = "com.antigravity.guardian.SHIELD_START"
        const val ACTION_STOP = "com.antigravity.guardian.SHIELD_STOP"
        const val ACTION_REFRESH_ZONES = "com.antigravity.guardian.SHIELD_REFRESH"
        const val ACTION_PAUSE = "com.antigravity.guardian.SHIELD_PAUSE"
        const val ACTION_RESUME = "com.antigravity.guardian.SHIELD_RESUME"
        const val ACTION_SCREEN_WAKE_GUARD = "com.antigravity.guardian.SHIELD_WAKE_GUARD"
        const val ACTION_SCREEN_OFF = "com.antigravity.guardian.SHIELD_SCREEN_OFF"
        const val ACTION_ENABLE_SPEN_MODE = "com.antigravity.guardian.ENABLE_SPEN_MODE"
        const val ACTION_DISABLE_SPEN_MODE = "com.antigravity.guardian.DISABLE_SPEN_MODE"
        const val ACTION_TOGGLE_SPEN_MODE = "com.antigravity.guardian.TOGGLE_SPEN_FROM_NOTIF"
        const val ACTION_RESET_DATA = "com.antigravity.guardian.SHIELD_RESET_DATA"

        @Volatile
        var isShieldRunning: Boolean = false
            private set
    }
}
