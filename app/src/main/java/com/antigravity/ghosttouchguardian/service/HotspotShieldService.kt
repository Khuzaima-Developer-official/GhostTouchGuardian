package com.antigravity.ghosttouchguardian.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.antigravity.ghosttouchguardian.data.CompactTouchRepository
import com.antigravity.ghosttouchguardian.data.GuardianPreferences
import com.antigravity.ghosttouchguardian.engine.GhostCalibrationEngine
import com.antigravity.ghosttouchguardian.engine.HotspotZone
import com.antigravity.ghosttouchguardian.engine.ai.AiStorageOrganizer
import com.antigravity.ghosttouchguardian.engine.ai.ExtendedTouchVector
import com.antigravity.ghosttouchguardian.engine.ai.GhostBrainEngine
import com.antigravity.ghosttouchguardian.engine.ai.GhostLeakSuppressionEngine
import com.antigravity.ghosttouchguardian.engine.ai.GhostMindCore
import com.antigravity.ghosttouchguardian.engine.ai.GhostTypeClassifier
import com.antigravity.ghosttouchguardian.engine.ai.MasterTouchClassifier
import com.antigravity.ghosttouchguardian.model.FingerIntensityMargin
import com.antigravity.ghosttouchguardian.model.GhostGlitchProfile
import com.antigravity.ghosttouchguardian.model.HumanFingerProfile
import com.antigravity.ghosttouchguardian.model.TouchFeatureVector
import com.antigravity.ghosttouchguardian.sensor.MechanicalImpulseSensor
import com.antigravity.ghosttouchguardian.service.shield.GhostHapticFeedback
import com.antigravity.ghosttouchguardian.service.shield.HumanImpulseGate
import com.antigravity.ghosttouchguardian.service.shield.OverlayWindowManager
import com.antigravity.ghosttouchguardian.service.shield.RapidSpamGate
import com.antigravity.ghosttouchguardian.service.shield.SPenGate
import com.antigravity.ghosttouchguardian.service.shield.ShieldNotificationManager
import kotlin.math.max

/**
 * HotspotShieldService (v3.8.0) — Precision Screen Shield & Pure S Pen EMR Gate
 *
 * Single-Task Modular Architecture:
 * - Window Management: OverlayWindowManager (Cutout-safe, 100% full screen)
 * - Notifications: ShieldNotificationManager (Un-swipeable, instant toggles)
 * - Stylus Hardware Routing: SPenGate (Pure Wacom EMR detection, 35-45ms re-arm)
 * - Burst Rejection: RapidSpamGate (<45ms zero-allocation burst filter)
 * - Physical Biometrics: HumanImpulseGate (Chassis momentum, FingerIntensityMargin)
 * - Throttled Alerts: GhostHapticFeedback (Max 1 per 250ms)
 * - AI Leak Choke: GhostLeakSuppressionEngine (Active frequency & spatial hotspot tracking)
 * - Dynamic Organizer: AiStorageOrganizer (Compaction & contamination purge)
 */
class HotspotShieldService : Service() {

    private lateinit var prefs: GuardianPreferences
    private lateinit var impulseSensor: MechanicalImpulseSensor
    private lateinit var repository: CompactTouchRepository

    // AI Mind Core & 8D Bayesian Brain & Master Touch Classifier
    private val aiCore = GhostMindCore()
    private var brainEngine: GhostBrainEngine = GhostBrainEngine()
    private var masterClassifier: MasterTouchClassifier = MasterTouchClassifier()
    private var fingerMargin: FingerIntensityMargin = FingerIntensityMargin()
    private var activeZones: List<HotspotZone> = emptyList()

    private val aiExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val leakSuppressionEngine = GhostLeakSuppressionEngine()
    private val ghostTypeClassifier = GhostTypeClassifier()
    private lateinit var storageOrganizer: AiStorageOrganizer

    // Single-Task Modular Components
    private lateinit var overlayManager: OverlayWindowManager
    private lateinit var notificationManager: ShieldNotificationManager
    private lateinit var spenGate: SPenGate
    private val rapidSpamGate = RapidSpamGate()
    private val humanImpulseGate = HumanImpulseGate()
    private lateinit var hapticFeedback: GhostHapticFeedback

    private val handler = Handler(Looper.getMainLooper())

    // Screen wake guard
    @Volatile private var isScreenWakeGuardActive = false
    private val screenWakeGuardOff = Runnable { isScreenWakeGuardActive = false }

    // Human interaction tracking
    @Volatile private var lastHumanInteractionTime: Long = 0L
    @Volatile private var isPointerDownOnScreen: Boolean = false

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
        storageOrganizer = AiStorageOrganizer(repository, prefs)
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Initialize single-task components
        overlayManager = OverlayWindowManager(this, wm)
        notificationManager = ShieldNotificationManager(this, prefs)
        hapticFeedback = GhostHapticFeedback(this)
        spenGate = SPenGate(
            onEnablePassThrough = { overlayManager.setTouchThrough(true) },
            onReArmShield = { overlayManager.setTouchThrough(false) }
        )

        aiExecutor.execute {
            try {
                storageOrganizer.organizeAndCompact(masterClassifier)
            } catch (_: Exception) {}
        }

        impulseSensor = MechanicalImpulseSensor(this).apply {
            onHumanMotionDetected = {
                // In S Pen mode, phone motion must NEVER unblock capacitive touch!
                if (!prefs.isSPenModeActive && !overlayManager.isTouchThrough) {
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
                if (overlayManager.checkOverlayPermission()) {
                    if (overlayManager.overlayView == null) {
                        attachOverlay()
                    }
                }
                // 3. Samsung One UI Keyguard Lock Screen Protection
                val km = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
                if (km != null && km.isKeyguardLocked) {
                    val lockIntent = Intent(this@HotspotShieldService, com.antigravity.ghosttouchguardian.ui.LockScreenShieldActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    try {
                        startActivity(lockIntent)
                    } catch (_: Exception) {}
                }
                // 4. Screen wake ghost-burst guard
                isScreenWakeGuardActive = true
                handler.removeCallbacks(screenWakeGuardOff)
                handler.postDelayed(screenWakeGuardOff, 2000L)
                reArmShieldDirect()
                overlayManager.overlayView?.postInvalidate()
            },
            onScreenOff = {
                // Screen turned off: Stop impulse sensor to completely eliminate battery & CPU drain
                impulseSensor.stop()
                reArmShieldDirect()
                com.antigravity.ghosttouchguardian.ui.LockScreenShieldActivity.dismissIfRunning()
            },
            onUserPresent = {
                // Phone unlocked by user: dismiss lock screen shield activity
                com.antigravity.ghosttouchguardian.ui.LockScreenShieldActivity.dismissIfRunning()
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
        if (overlayManager.checkOverlayPermission()) {
            attachOverlay()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notificationManager.createNotificationChannel()
        val notification = notificationManager.buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(ShieldNotificationManager.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ShieldNotificationManager.NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {}
        isShieldRunning = true

        when (intent?.action) {
            ACTION_START -> {
                reloadProfiles()
                if (overlayManager.checkOverlayPermission()) attachOverlay()
                notificationManager.updateNotification()
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
                notificationManager.updateNotification()
            }
            ACTION_RESUME -> {
                reloadProfiles()
                if (overlayManager.checkOverlayPermission()) attachOverlay()
                notificationManager.updateNotification()
            }
            ACTION_REFRESH_ZONES -> {
                reloadProfiles()
                removeOverlay()
                if (overlayManager.checkOverlayPermission()) attachOverlay()
                notificationManager.updateNotification()
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

                if (overlayManager.checkOverlayPermission()) {
                    if (overlayManager.overlayView == null) {
                        attachOverlay()
                    }
                    reArmShieldDirect()
                    overlayManager.overlayView?.postInvalidate()
                }
                notificationManager.updateNotification()
            }
            ACTION_DISABLE_SPEN_MODE -> {
                prefs.isSPenModeActive = false
                aiCore.isSPenModeActive = false
                brainEngine.isSPenModeActive = false
                masterClassifier.isSPenModeActive = false
                removeOverlay()
                if (prefs.isHotspotShieldEnabled && overlayManager.checkOverlayPermission()) {
                    reloadProfiles()
                    attachOverlay()
                }
                notificationManager.updateNotification()
            }
            ACTION_TOGGLE_SPEN_MODE -> {
                prefs.isSPenModeActive = !prefs.isSPenModeActive
                aiCore.isSPenModeActive = prefs.isSPenModeActive
                brainEngine.isSPenModeActive = prefs.isSPenModeActive
                masterClassifier.isSPenModeActive = prefs.isSPenModeActive

                if (prefs.isSPenModeActive) {
                    prefs.isHotspotShieldEnabled = true
                    if (overlayManager.checkOverlayPermission()) {
                        removeOverlay()
                        attachOverlay()
                        reArmShieldDirect()
                    }
                } else {
                    removeOverlay()
                    if (prefs.isHotspotShieldEnabled && overlayManager.checkOverlayPermission()) {
                        reloadProfiles()
                        attachOverlay()
                    }
                }
                notificationManager.updateNotification()
                overlayManager.overlayView?.postInvalidate()
            }
            ACTION_RESET_DATA -> {
                prefs.resetAllLearnedProfiles()
                repository.resetBrain()
                aiCore.humanProfile = HumanFingerProfile()
                aiCore.ghostProfile = GhostGlitchProfile()
                fingerMargin = FingerIntensityMargin()
                ghostTypeClassifier.loadFromJson("")
                brainEngine = GhostBrainEngine()
                masterClassifier = MasterTouchClassifier()
                leakSuppressionEngine.releaseStorm()
                reloadProfiles()
                notificationManager.updateNotification()
            }
        }
        return START_STICKY
    }

    private fun reloadProfiles() {
        activeZones = GhostCalibrationEngine.parseZonesFromJson(prefs.savedHotspotsJson)
        fingerMargin = FingerIntensityMargin.fromJson(prefs.fingerIntensityMarginJson)
        ghostTypeClassifier.loadFromJson(prefs.ghostTypeClassifierJson)
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
        if (overlayManager.overlayView != null) return

        val view = HotspotOverlayView(this)
        overlayManager.attachOverlay(view)

        if (prefs.isSPenModeActive) {
            overlayManager.setTouchThrough(false)
        } else {
            passThroughTemporarily()
        }
    }

    fun reArmShieldDirect() {
        spenGate.cancelPendingReArm()
        handler.removeCallbacks(reArmRunnable)
        overlayManager.setTouchThrough(false)
    }

    private fun passThroughTemporarily() {
        if (prefs.isSPenModeActive) return

        lastHumanInteractionTime = android.os.SystemClock.uptimeMillis()
        overlayManager.setTouchThrough(true)
        handler.removeCallbacks(reArmRunnable)
        handler.postDelayed(reArmRunnable, 350L) // Fast 350ms typing grace window
    }

    private fun removeOverlay() {
        spenGate.cancelPendingReArm()
        handler.removeCallbacks(reArmRunnable)
        overlayManager.removeOverlay()
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
        try { aiExecutor.shutdown() } catch (_: Exception) {}
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

            val restartPendingIntent = android.app.PendingIntent.getService(
                applicationContext, 1001, restartIntent,
                android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
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
    private inner class HotspotOverlayView(context: Context) : View(context) {

        private var pendingDownTime: Long = 0L
        private var pendingDownX: Float = 0f
        private var pendingDownY: Float = 0f
        private var pendingDownPressure: Float = 0f
        private val movePoints = mutableListOf<Pair<Float, Float>>()
        private var lastStylusUpTime: Long = 0L
        private var lastHumanUpTime: Long = 0L

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Screen remains 100% clean and unobstructed.
            // S Pen Mode indicator and toggle live directly in the persistent Notification.
        }

        override fun onHoverEvent(event: MotionEvent): Boolean {
            if (prefs.isSPenModeActive) {
                spenGate.handleHover(event, isPointerDownOnScreen)
                return false
            }
            return super.onHoverEvent(event)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val now = event.eventTime
            val toolType = event.getToolType(0)
            val hasOnlyStylus = spenGate.hasOnlyStylus(event)

            // =========================================================================
            // TIER 0: DEDICATED S PEN MODE (100% CAPACITIVE TOUCH MUTING & CONTINUOUS GHOST LEARN)
            // =========================================================================
            if (prefs.isSPenModeActive) {
                if (hasOnlyStylus) {
                    // Stylus is physically touching down
                    lastStylusUpTime = now
                    spenGate.onStylusTouchDown()
                    if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        isPointerDownOnScreen = false
                        spenGate.onStylusTouchUp()
                    } else {
                        isPointerDownOnScreen = true
                    }
                    return false // Let stylus stroke flow through natively
                } else {
                    // ANY non-stylus touch in S Pen Mode is guaranteed 100% water ghost touch!
                    isPointerDownOnScreen = false

                    val intervalMs = rapidSpamGate.getLastInterval(now)

                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            pendingDownTime = now
                            pendingDownX = event.x
                            pendingDownY = event.y
                            pendingDownPressure = event.pressure
                            movePoints.clear()
                            movePoints.add(Pair(event.x, event.y))
                        }
                        MotionEvent.ACTION_MOVE -> {
                            movePoints.add(Pair(event.x, event.y))
                        }
                    }

                    if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) {
                        prefs.totalBlockedGhosts = prefs.totalBlockedGhosts + 1
                        hapticFeedback.triggerAlert(isGhost = true)

                        val dwell = if (event.actionMasked == MotionEvent.ACTION_UP && pendingDownTime > 0L) {
                            (now - pendingDownTime).toInt().coerceIn(1, 5000)
                        } else 25

                        val curX = event.x
                        val curY = event.y
                        val curPressure = event.pressure
                        val curSize = event.size
                        val curMajor = event.touchMajor.coerceAtLeast(1f)
                        val curMinor = event.touchMinor.coerceAtLeast(1f)
                        val curDownX = pendingDownX
                        val curDownY = pendingDownY
                        val curDownPressure = pendingDownPressure
                        val accelDelta = impulseSensor.getLastAccelDelta()
                        val pointsCopy = movePoints.toList()

                        // Asynchronous non-blocking AI learning: zero UI frame drops or IPC congestion!
                        aiExecutor.execute {
                            try {
                                val dx = curX - curDownX
                                val dy = curY - curDownY
                                val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                                val velocity = if (dwell > 0) dist / dwell.toFloat() else 0f
                                val eccentricity = (curMajor / curMinor).coerceIn(1.0f, 10.0f)
                                val dp = kotlin.math.abs(curPressure - curDownPressure)
                                val pressureGradient = if (dwell > 0) dp / dwell.toFloat() else 0f

                                var tremorVar = 0f
                                if (pointsCopy.size >= 3) {
                                    val meanX = pointsCopy.map { it.first }.average().toFloat()
                                    val meanY = pointsCopy.map { it.second }.average().toFloat()
                                    val sqDiffSum = pointsCopy.fold(0f) { acc, p ->
                                        val px = p.first - meanX
                                        val py = p.second - meanY
                                        acc + (px * px + py * py)
                                    }
                                    tremorVar = (sqDiffSum / pointsCopy.size.toFloat()).coerceIn(0f, 25f)
                                }

                                val featureVector = TouchFeatureVector(
                                    dwell = dwell.toFloat(),
                                    size = curSize.coerceIn(0f, 1f),
                                    pressure = curPressure.coerceIn(0f, 1f),
                                    accelDelta = accelDelta.coerceAtLeast(0f),
                                    interval = intervalMs.toFloat().coerceAtLeast(0f),
                                    velocity = velocity.coerceIn(0f, 50f),
                                    eccentricity = eccentricity,
                                    timeSinceStimulus = 100f
                                )
                                val extendedVector = ExtendedTouchVector(
                                    dwell = dwell.toFloat(),
                                    size = curSize.coerceIn(0f, 1f),
                                    pressure = curPressure.coerceIn(0f, 1f),
                                    accelDelta = accelDelta.coerceAtLeast(0f),
                                    pressureGradient = pressureGradient.coerceIn(0f, 1f),
                                    microTremorVariance = tremorVar,
                                    eccentricity = eccentricity,
                                    velocity = velocity.coerceIn(0f, 50f),
                                    interval = intervalMs.toFloat().coerceAtLeast(0f),
                                    timeSinceStimulus = 100f
                                )

                                leakSuppressionEngine.registerGhostTouch(curX, curY, intervalMs, width, height)
                                ghostTypeClassifier.learnGhostType(accelDelta, tremorVar, intervalMs)
                                brainEngine.learnOnline(featureVector, isGhost = true)
                                masterClassifier.learnOnline(extendedVector, isGhost = true)

                                if (masterClassifier.totalOnlineUpdates % 25L == 0L) {
                                    storageOrganizer.organizeAndCompact(masterClassifier)
                                    prefs.ghostBrainEngineJson = brainEngine.toJson()
                                    prefs.ghostTypeClassifierJson = ghostTypeClassifier.toJson()
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    return true // Swallowed immediately with 0 delay!
                }
            }

            // =========================================================================
            // TIER 1: ULTRA-FAST RAPID SPAM FILTER (< 45ms INTERVAL OR MULTI-BURST)
            // =========================================================================
            if (rapidSpamGate.isSpamBurst(event, hasOnlyStylus)) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) {
                    prefs.totalBlockedGhosts = prefs.totalBlockedGhosts + 1
                    hapticFeedback.triggerAlert(isGhost = true)
                }
                if (overlayManager.isTouchThrough) {
                    reArmShieldDirect()
                }
                return true // SWALLOW BURST INSTANTLY!
            }

            val interval = rapidSpamGate.getLastInterval(now)

            // =========================================================================
            // TIER 2: NORMAL FINGER TOUCH & AI GHOST SHIELD MODE
            // =========================================================================
            if (overlayManager.isTouchThrough) {
                val accelDelta = impulseSensor.getLastAccelDelta()
                val hasImpulse = impulseSensor.hasMechanicalImpulse(now, windowMs = 80L)

                // ACTIVE AI MIND: REAL-TIME GHOST TYPE & LEAK CHOKE
                val isGhostType = ghostTypeClassifier.isGhostType(
                    dwellMs = 50,
                    size = event.size,
                    pressure = event.pressure,
                    accelDelta = accelDelta,
                    hasImpulse = hasImpulse,
                    microTremorVar = 0f,
                    intervalMs = interval,
                    isStylus = hasOnlyStylus,
                    inGreenZone = false,
                    fingerMargin = fingerMargin
                )
                val isLeak = isGhostType || leakSuppressionEngine.evaluateLeak(event, now, interval, accelDelta, hasImpulse, width, height)
                if (isLeak) {
                    prefs.totalBlockedGhosts = prefs.totalBlockedGhosts + 1
                    hapticFeedback.triggerAlert(isGhost = true)
                    reArmShieldDirect()
                    ghostTypeClassifier.learnGhostType(accelDelta, 0f, interval)
                    leakSuppressionEngine.registerGhostTouch(event.x, event.y, interval, width, height)
                    return true // CHOKE LEAK IMMEDIATELY!
                }

                if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                    isPointerDownOnScreen = true
                    lastHumanInteractionTime = now
                    handler.removeCallbacks(reArmRunnable)
                    handler.postDelayed(reArmRunnable, 350L)
                } else if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    isPointerDownOnScreen = false
                    lastHumanInteractionTime = now
                    handler.removeCallbacks(reArmRunnable)
                    handler.postDelayed(reArmRunnable, 350L)
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
                    // ACTIVE STORM SUPPRESSION: Clamped down during high-frequency glitch storms
                    if (leakSuppressionEngine.isStormSuppressionActive) {
                        prefs.totalBlockedGhosts = prefs.totalBlockedGhosts + 1
                        return true
                    }

                    val accelDelta = impulseSensor.getLastAccelDelta()
                    val hasImpulse = impulseSensor.hasMechanicalImpulse(now, windowMs = 80L)

                    val inMoistureHotspot = leakSuppressionEngine.isInMoistureHotspot(event.x, event.y, width, height)
                    if (inMoistureHotspot && !hasImpulse) {
                        // High-density moisture zone without physical chassis impulse: swallow immediately!
                        prefs.totalBlockedGhosts = prefs.totalBlockedGhosts + 1
                        hapticFeedback.triggerAlert(isGhost = true)
                        return true
                    }

                    isPointerDownOnScreen = true
                    lastHumanInteractionTime = event.eventTime
                    pendingDownTime = event.eventTime
                    pendingDownX = event.x
                    pendingDownY = event.y
                    pendingDownPressure = event.pressure
                    movePoints.clear()
                    movePoints.add(Pair(event.x, event.y))
                    val effectiveAccel = if (hasImpulse) max(accelDelta, 0.025f) else accelDelta

                    val inGreenZone = aiCore.ghostProfile.greenZones.any { it.contains(event.x, event.y) }

                    // BROAD GHOST TYPE FILTER: Categorically rejects water/electrical chatter
                    val isGhostType = ghostTypeClassifier.isGhostType(
                        dwellMs = 50,
                        size = event.size,
                        pressure = event.pressure,
                        accelDelta = effectiveAccel,
                        hasImpulse = hasImpulse,
                        microTremorVar = 0f,
                        intervalMs = interval,
                        isStylus = hasOnlyStylus,
                        inGreenZone = inGreenZone,
                        fingerMargin = fingerMargin
                    )

                    if (isGhostType) {
                        prefs.totalBlockedGhosts = prefs.totalBlockedGhosts + 1
                        hapticFeedback.triggerAlert(isGhost = true)
                        return true // Swallow ghost touch down!
                    }

                    val isHumanTap = humanImpulseGate.isHumanTap(
                        event = event,
                        hasImpulse = hasImpulse,
                        effectiveAccel = effectiveAccel,
                        greenZones = aiCore.ghostProfile.greenZones,
                        humanProfile = aiCore.humanProfile,
                        margin = fingerMargin
                    )

                    if (isHumanTap) {
                        lastHumanUpTime = now
                        passThroughTemporarily()
                        return false // Allow DOWN to pass to underlying app cleanly
                    }

                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    isPointerDownOnScreen = true
                    lastHumanInteractionTime = event.eventTime
                    movePoints.add(Pair(event.x, event.y))
                    if (overlayManager.isTouchThrough) {
                        handler.removeCallbacks(reArmRunnable)
                        handler.postDelayed(reArmRunnable, 650L)
                        return false
                    }
                    val lateShockwave = impulseSensor.hasMechanicalImpulse(event.eventTime, windowMs = 100L)
                    val effectiveAccel = if (lateShockwave) max(impulseSensor.getLastAccelDelta(), 0.025f) else impulseSensor.getLastAccelDelta()

                    val isHumanMove = humanImpulseGate.isHumanTap(
                        event = event,
                        hasImpulse = lateShockwave,
                        effectiveAccel = effectiveAccel,
                        greenZones = aiCore.ghostProfile.greenZones,
                        humanProfile = aiCore.humanProfile,
                        margin = fingerMargin
                    )

                    if (isHumanMove) {
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

                    // ULTRA-FAST DWELL GATE (< 45ms)
                    if (dwell < 45 && !hasOnlyStylus) {
                        prefs.totalBlockedGhosts = prefs.totalBlockedGhosts + 1
                        hapticFeedback.triggerAlert(isGhost = true)
                        reArmShieldDirect()
                        return true // SWALLOW INSTANTLY!
                    }

                    val timeSinceHuman = if (lastHumanUpTime > 0L) pendingDownTime - lastHumanUpTime else -1L
                    val timeSinceStylus = if (lastStylusUpTime > 0L) pendingDownTime - lastStylusUpTime else -1L

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

                    val isDefiniteHuman = (hasImpulse && fingerMargin.sampleCount >= 3 && fingerMargin.isWithinHumanMargin(event.pressure, event.size, dwell, effectiveAccel)) ||
                            hasImpulse || inGreenZone || extendedVector.microTremorVariance >= 0.50f ||
                            (dwell >= 110 && extendedVector.microTremorVariance >= 0.25f && hasImpulse) ||
                            (aiCore.humanProfile.isValid && aiCore.humanProfile.matchesHuman(dwell, event.size, event.pressure, effectiveAccel)) ||
                            (!masterDecision.isGhost && masterDecision.confidence >= 0.85f && (hasImpulse || extendedVector.microTremorVariance >= 0.30f))

                    if (isDefiniteHuman) {
                        lastHumanUpTime = now
                        aiExecutor.execute {
                            try {
                                brainEngine.learnOnline(featureVector, isGhost = false)
                                masterClassifier.learnOnline(extendedVector, isGhost = false)
                            } catch (_: Exception) {}
                        }
                        if (!overlayManager.isTouchThrough) passThroughTemporarily()
                        return false
                    }

                    val isGhostType = ghostTypeClassifier.isGhostType(
                        dwellMs = dwell,
                        size = event.size,
                        pressure = event.pressure,
                        accelDelta = effectiveAccel,
                        hasImpulse = hasImpulse,
                        microTremorVar = extendedVector.microTremorVariance,
                        intervalMs = interval,
                        isStylus = hasOnlyStylus,
                        inGreenZone = inGreenZone,
                        fingerMargin = fingerMargin
                    )

                    if (isGhostType || masterDecision.isGhost) {
                        prefs.totalBlockedGhosts = prefs.totalBlockedGhosts + 1
                        leakSuppressionEngine.registerGhostTouch(event.x, event.y, interval, width, height)
                        ghostTypeClassifier.learnGhostType(effectiveAccel, extendedVector.microTremorVariance, interval)

                        aiExecutor.execute {
                            try {
                                masterClassifier.learnOnline(extendedVector, isGhost = true)
                                brainEngine.learnOnline(featureVector, isGhost = true)
                                prefs.ghostTypeClassifierJson = ghostTypeClassifier.toJson()

                                if (masterClassifier.totalOnlineUpdates % 25L == 0L) {
                                    storageOrganizer.organizeAndCompact(masterClassifier)
                                    prefs.ghostBrainEngineJson = brainEngine.toJson()
                                }
                            } catch (_: Exception) {}
                        }

                        hapticFeedback.triggerAlert(isGhost = true)
                        isPointerDownOnScreen = false
                        reArmShieldDirect()
                        return true
                    }

                    val brainDecision = brainEngine.classify(featureVector, toolType, inGreenZone)

                    if (!brainDecision.isGhost) {
                        lastHumanUpTime = now
                        if (brainDecision.confidence >= 0.70f) {
                            aiExecutor.execute {
                                try {
                                    brainEngine.learnOnline(featureVector, isGhost = false)
                                    masterClassifier.learnOnline(extendedVector, isGhost = false)
                                } catch (_: Exception) {}
                            }
                        }
                        if (!overlayManager.isTouchThrough) passThroughTemporarily()
                        return false
                    }

                    prefs.totalBlockedGhosts = prefs.totalBlockedGhosts + 1
                    leakSuppressionEngine.registerGhostTouch(event.x, event.y, interval, width, height)
                    aiExecutor.execute {
                        try {
                            brainEngine.learnOnline(featureVector, isGhost = true)
                            masterClassifier.learnOnline(extendedVector, isGhost = true)

                            if (brainEngine.totalOnlineUpdates % 25L == 0L) {
                                storageOrganizer.organizeAndCompact(masterClassifier)
                                prefs.ghostBrainEngineJson = brainEngine.toJson()
                            }
                        } catch (_: Exception) {}
                    }

                    hapticFeedback.triggerAlert(isGhost = true)
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
