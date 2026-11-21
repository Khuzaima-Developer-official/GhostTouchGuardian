package com.antigravity.ghosttouchguardian.service

import android.app.Service
import android.content.Context
import android.content.Intent
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
            if (isPointerDownOnScreen || timeSinceHuman < 1200L) {
                handler.postDelayed(this, 800L)
            } else {
                reArmShieldDirect()
            }
        }
    }

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
            }
            ACTION_DISABLE_SPEN_MODE -> {
                prefs.isSPenModeActive = false
                aiCore.isSPenModeActive = false
                brainEngine.isSPenModeActive = false
                masterClassifier.isSPenModeActive = false
                reArmShieldDirect()
                overlayView?.postInvalidate()
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
        brainEngine = GhostBrainEngine.fromJson(prefs.ghostBrainEngineJson).apply {
            isSPenModeActive = prefs.isSPenModeActive
        }
        masterClassifier = repository.loadBrain().apply {
            isSPenModeActive = prefs.isSPenModeActive
        }
    }

    private fun attachOverlay() {
        if (overlayView != null) return

        // In S Pen mode, the shield MUST cover the full screen unconditionally
        val isFullScreen = prefs.isSPenModeActive || activeZones.isEmpty()

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
        // Keep pass-through open while S Pen is active; re-arm 400ms after pen stops
        handler.postDelayed(penExitRunnable, 400L)
    }

    private fun scheduleReArmAfterStylus() {
        handler.removeCallbacks(penExitRunnable)
        // Fast 250ms snap shut when pen leaves hover range
        handler.postDelayed(penExitRunnable, 250L)
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
        handler.postDelayed(reArmRunnable, 1200L) // Tight 1.2s typing grace period
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
        removeOverlay()
        impulseSensor.stop()
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

            // 1. Draw localized zone tints if in normal zone-restricted mode
            if (!isTouchThrough && !isFullScreen) {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), boxPaint)
            }

            // 2. Draw Floating HUD Pill if S Pen Mode is ACTIVE
            if (prefs.isSPenModeActive) {
                val now = android.os.SystemClock.uptimeMillis()
                val pillWidth = 430f
                val pillHeight = 90f
                val pillLeft = (width - pillWidth - 32f).coerceAtLeast(16f)
                val pillTop = 100f
                val pillRect = RectF(pillLeft, pillTop, pillLeft + pillWidth, pillTop + pillHeight)

                val isRecentlyBlocked = (now - lastBlockedTime) < 1400L
                val isStylusHovering = (now - lastStylusHoverTime) < 800L

                when {
                    isRecentlyBlocked -> {
                        // Flash Red: Finger or Water Touch Blocked!
                        hudBgPaint.color = Color.parseColor("#E6EF4444")
                        hudBorderPaint.color = Color.parseColor("#FFFFFF")
                        hudTextPaint.color = Color.WHITE
                        hudSubTextPaint.color = Color.parseColor("#FEE2E2")

                        canvas.drawRoundRect(pillRect, 45f, 45f, hudBgPaint)
                        canvas.drawRoundRect(pillRect, 45f, 45f, hudBorderPaint)
                        canvas.drawText("🚫 FINGER MUTED", pillLeft + 30f, pillTop + 40f, hudTextPaint)
                        canvas.drawText("Capacitive Input Dropped (S Pen Pure)", pillLeft + 30f, pillTop + 70f, hudSubTextPaint)
                    }
                    isStylusHovering -> {
                        // Flash Cyan: Pen Active & Passing Natively
                        hudBgPaint.color = Color.parseColor("#E600E5FF")
                        hudBorderPaint.color = Color.parseColor("#FFFFFF")
                        hudTextPaint.color = Color.parseColor("#070A11")
                        hudSubTextPaint.color = Color.parseColor("#0F172A")

                        canvas.drawRoundRect(pillRect, 45f, 45f, hudBgPaint)
                        canvas.drawRoundRect(pillRect, 45f, 45f, hudBorderPaint)
                        canvas.drawText("🖊️ S PEN ACTIVE", pillLeft + 30f, pillTop + 40f, hudTextPaint)
                        canvas.drawText("EMR Magnetic Signal Flowing", pillLeft + 30f, pillTop + 70f, hudSubTextPaint)
                    }
                    else -> {
                        // Normal Armed State: Deep navy pill with cyan outline
                        hudBgPaint.color = Color.parseColor("#E60F172A")
                        hudBorderPaint.color = Color.parseColor("#00E5FF")
                        hudTextPaint.color = Color.parseColor("#00E5FF")
                        hudSubTextPaint.color = Color.parseColor("#94A3B8")

                        canvas.drawRoundRect(pillRect, 45f, 45f, hudBgPaint)
                        canvas.drawRoundRect(pillRect, 45f, 45f, hudBorderPaint)
                        canvas.drawText("🖊️ S PEN EXCLUSIVE", pillLeft + 30f, pillTop + 40f, hudTextPaint)
                        canvas.drawText("Fingers 100% Muted • EMR Armed", pillLeft + 30f, pillTop + 70f, hudSubTextPaint)
                    }
                }
            }
        }

        override fun onHoverEvent(event: MotionEvent): Boolean {
            if (prefs.isSPenModeActive) {
                val toolType = event.getToolType(0)
                if (toolType == MotionEvent.TOOL_TYPE_STYLUS) {
                    lastStylusHoverTime = android.os.SystemClock.uptimeMillis()
                    postInvalidate()
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

            // =========================================================================
            // TIER 0: DEDICATED S PEN MODE (100% CAPACITIVE TOUCH MUTING)
            // =========================================================================
            if (prefs.isSPenModeActive) {
                if (toolType == MotionEvent.TOOL_TYPE_STYLUS) {
                    // Stylus is physically touching down
                    lastStylusHoverTime = android.os.SystemClock.uptimeMillis()
                    lastStylusUpTime = event.eventTime
                    enableStylusPassThrough()
                    postInvalidate()
                    return false // Let stylus stroke flow through natively
                } else {
                    // 100% CAPACITIVE TOUCH MUTING:
                    // Any finger touch, water droplet, or parasitic capacitive short is
                    // COMPLETELY CONSUMED HERE. Nothing reaches the underlying app!
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        lastBlockedTime = android.os.SystemClock.uptimeMillis()
                        prefs.totalBlockedGhosts = prefs.totalBlockedGhosts + 1
                        triggerHapticAlert(isGhost = true)
                        postInvalidate()
                    }
                    return true // Swallowed immediately!
                }
            }

            // =========================================================================
            // TIER 1: NORMAL FINGER TOUCH & AI GHOST SHIELD MODE
            // =========================================================================
            if (isTouchThrough) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                    isPointerDownOnScreen = true
                    lastHumanInteractionTime = event.eventTime
                    handler.removeCallbacks(reArmRunnable)
                    handler.postDelayed(reArmRunnable, 1200L)
                } else if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    isPointerDownOnScreen = false
                    lastHumanInteractionTime = event.eventTime
                    handler.removeCallbacks(reArmRunnable)
                    handler.postDelayed(reArmRunnable, 1200L)
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
                    val now = event.eventTime
                    val interval = if (lastTouchTime > 0L) now - lastTouchTime else -1L
                    lastTouchTime = now

                    val accelDelta = impulseSensor.getLastAccelDelta()
                    val hasImpulse = impulseSensor.hasMechanicalImpulse(now, windowMs = 80L)
                    val effectiveAccel = if (hasImpulse) max(accelDelta, 0.025f) else accelDelta

                    val inGreenZone = aiCore.ghostProfile.greenZones.any { it.contains(event.x, event.y) }

                    // If physical shockwave, fleshy deformation, green safe zone, or human profile matches:
                    val isHumanTap = hasImpulse || event.size >= 0.035f || event.pressure >= 0.10f || inGreenZone ||
                            (aiCore.humanProfile.isValid && aiCore.humanProfile.matchesHuman(0, event.size, event.pressure, effectiveAccel))

                    if (isHumanTap) {
                        lastHumanUpTime = now
                        passThroughTemporarily()
                        return false // Allow DOWN to pass to underlying app cleanly
                    }

                    // Ghost candidate: Wait and evaluate at ACTION_UP where dwell is known
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    isPointerDownOnScreen = true
                    lastHumanInteractionTime = event.eventTime
                    movePoints.add(Pair(event.x, event.y))
                    if (isTouchThrough) {
                        handler.removeCallbacks(reArmRunnable)
                        handler.postDelayed(reArmRunnable, 1200L)
                        return false
                    }
                    val lateShockwave = impulseSensor.hasMechanicalImpulse(event.eventTime, windowMs = 100L)
                    if (lateShockwave || event.size >= 0.035f || event.pressure >= 0.10f) {
                        lastHumanUpTime = event.eventTime
                        passThroughTemporarily()
                        return false
                    }
                    return true // Swallow ghost drag
                }

                MotionEvent.ACTION_UP -> {
                    isPointerDownOnScreen = false
                    lastHumanInteractionTime = event.eventTime
                    val dwell = (event.eventTime - pendingDownTime).toInt()
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

                    val isDefiniteHuman = hasImpulse || event.size >= 0.035f || event.pressure >= 0.10f ||
                            dwell >= 110 || inGreenZone || extendedVector.microTremorVariance >= 0.60f ||
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

                        val cancelEvent = MotionEvent.obtain(event).also {
                            it.action = MotionEvent.ACTION_CANCEL
                        }
                        try {
                            super.onTouchEvent(cancelEvent)
                        } catch (_: Exception) {}
                        cancelEvent.recycle()
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
    }
}
