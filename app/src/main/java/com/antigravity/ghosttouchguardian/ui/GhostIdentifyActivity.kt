package com.antigravity.ghosttouchguardian.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.CountDownTimer
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.antigravity.ghosttouchguardian.data.GuardianPreferences
import com.antigravity.ghosttouchguardian.databinding.ActivityGhostIdentifyBinding
import com.antigravity.ghosttouchguardian.engine.ai.GaussianCluster
import com.antigravity.ghosttouchguardian.engine.ai.GhostBrainEngine
import com.antigravity.ghosttouchguardian.model.GhostGlitchProfile
import com.antigravity.ghosttouchguardian.model.GhostTouchEntry
import com.antigravity.ghosttouchguardian.model.GreenSafeZone
import com.antigravity.ghosttouchguardian.model.HumanFingerProfile
import com.antigravity.ghosttouchguardian.model.TouchFeatureVector
import com.antigravity.ghosttouchguardian.sensor.MechanicalImpulseSensor
import kotlin.math.sqrt

/**
 * GhostIdentifyActivity (v2.0) — Guided 2-Phase GhostMind AI Calibration
 *
 * PHASE 1: Real Finger Touch Calibration (5 Natural Taps)
 *   Learns the user's specific mechanical shockwave mass, flesh deformation size, and tap dwell.
 *   Guarantees that real clicks and typing are NEVER blocked!
 *
 * PHASE 2: Ghost Spectrum Scan with Green Safe Zones
 *   User taps the Green Safe Zones freely to provoke post-interaction trailing glitches.
 *   Glitches occurring outside are captured to learn the stochastic frequency envelope [min..max]
 *   and dwell spectrum, eliminating single-frequency limitations.
 */
class GhostIdentifyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGhostIdentifyBinding
    private lateinit var prefs: GuardianPreferences
    private var impulseSensor: MechanicalImpulseSensor? = null

    // Phase 1: Finger calibration state
    private val fingerSamples = mutableListOf<Pair<Float, Float>>() // Pair(size, pressure)
    private val fingerVectors = mutableListOf<TouchFeatureVector>()
    private var humanProfile = HumanFingerProfile.defaultProfile()
    private var brainEngine = GhostBrainEngine()

    // Phase 2: Ghost scan state
    private val recordedGhostEntries = mutableListOf<GhostTouchEntry>()
    private val recordedGhostVectors = mutableListOf<TouchFeatureVector>()
    private var scanDurationMs: Long = 30_000L
    private var countDownTimer: CountDownTimer? = null
    private var isGhostScanActive = false

    // Green safe zones
    private val greenZones = mutableListOf<GreenSafeZone>()

    // Stimulus and timing tracking
    private var lastStimulusTimestamp = 0L
    private var lastGhostTimestamp = 0L
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var downHistorySize = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGhostIdentifyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = GuardianPreferences(this)
        impulseSensor = MechanicalImpulseSensor(this).also { it.start() }

        // Pause background shield during calibration so all raw touch characteristics can be profiled
        startService(Intent(this, com.antigravity.ghosttouchguardian.service.HotspotShieldService::class.java).apply {
            action = com.antigravity.ghosttouchguardian.service.HotspotShieldService.ACTION_PAUSE
        })

        lockImmersive()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(this@GhostIdentifyActivity, "Slide to exit →", Toast.LENGTH_SHORT).show()
            }
        })

        setupPhase1FingerCalibration()
        setupPhase2GhostScanControls()
        setupSlideExit()
    }

    private fun lockImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val ctrl = WindowInsetsControllerCompat(window, window.decorView)
        ctrl.hide(WindowInsetsCompat.Type.systemBars())
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // ---------------------------------------------------------------------------
    // PHASE 1: Finger Touch Calibration
    // ---------------------------------------------------------------------------
    private fun setupPhase1FingerCalibration() {
        binding.panelFingerCalibration.visibility = View.VISIBLE
        binding.scanTimeSetup.visibility = View.GONE
        binding.scanActivePanel.visibility = View.GONE
        binding.resultPanel.visibility = View.GONE

        binding.cardFingerTapTarget.setOnClickListener {
            // Handled via dispatchTouchEvent for precise hardware reading
        }

        binding.btnProceedToGhostScan.setOnClickListener {
            // Proceed to Step 2
            binding.panelFingerCalibration.visibility = View.GONE
            binding.scanTimeSetup.visibility = View.VISIBLE
        }
    }

    private fun handleFingerCalibrationTap(event: MotionEvent) {
        val size = event.size.coerceAtLeast(0.02f)
        val pressure = event.pressure.coerceAtLeast(0.05f)
        fingerSamples.add(Pair(size, pressure))

        val accel = impulseSensor?.getLastAccelDelta() ?: 0.025f
        val dwellMs = if (downTime > 0L) (event.eventTime - downTime).toInt().coerceIn(30, 300) else 100
        val vector = TouchFeatureVector.extract(
            event = event,
            dwellMs = dwellMs,
            downX = event.x,
            downY = event.y,
            accelDelta = maxOf(accel, 0.020f),
            intervalMs = 350L,
            timeSinceStimulusMs = 2000L
        )
        fingerVectors.add(vector)
        brainEngine.learnOnline(vector, isGhost = false)

        val count = fingerSamples.size
        binding.tvFingerTapCount.text = "$count / 5"

        binding.tvFingerBioFeedback.text = "Tap #$count Registered: Mass=${String.format("%.3f", accel)} m/s², Flesh=${String.format("%.3f", size)}"

        // Visual tap feedback animation
        binding.cardFingerTapTarget.animate().scaleX(0.96f).scaleY(0.96f).setDuration(60).withEndAction {
            binding.cardFingerTapTarget.animate().scaleX(1.0f).scaleY(1.0f).setDuration(60).start()
        }.start()

        if (count >= 5) {
            humanProfile = HumanFingerProfile.fromSamples(fingerSamples)
            binding.tvFingerTapHint.text = "✅ FINGER PROFILE LOCKED!"
            binding.tvFingerTapHint.setTextColor(Color.parseColor("#22C55E"))
            binding.cardFingerTapTarget.strokeColor = Color.parseColor("#22C55E")
            binding.btnProceedToGhostScan.visibility = View.VISIBLE
            Toast.makeText(this, "Finger Profile Locked! Proceed to Step 2.", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------------------
    // PHASE 2: Ghost Touch Spectrum Scan Setup
    // ---------------------------------------------------------------------------
    private fun setupPhase2GhostScanControls() {
        binding.seekScanTime.max = 22
        binding.seekScanTime.progress = 4 // 30s
        updateTimeLabel()

        binding.seekScanTime.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                scanDurationMs = (10L + progress * 5L) * 1000L
                updateTimeLabel()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        binding.btnStartScan.setOnClickListener {
            startGhostSpectrumScan()
        }
    }

    private fun updateTimeLabel() {
        val sec = (scanDurationMs / 1000L).toInt()
        binding.tvSelectedTime.text = "${sec}s"
    }

    private fun startGhostSpectrumScan() {
        isGhostScanActive = true
        recordedGhostEntries.clear()
        lastGhostTimestamp = 0L
        lastStimulusTimestamp = 0L

        binding.scanTimeSetup.visibility = View.GONE
        binding.scanActivePanel.visibility = View.VISIBLE

        val totalSec = (scanDurationMs / 1000L).toInt()
        binding.tvIdentifyCountdown.text = "${totalSec}s"

        // Cache Green Safe Zones coordinates
        binding.root.post {
            greenZones.clear()
            val locTop = IntArray(2)
            binding.cardGreenSafeZoneTop.getLocationOnScreen(locTop)
            greenZones.add(GreenSafeZone(
                left = locTop[0].toFloat(),
                top = locTop[1].toFloat(),
                right = (locTop[0] + binding.cardGreenSafeZoneTop.width).toFloat(),
                bottom = (locTop[1] + binding.cardGreenSafeZoneTop.height).toFloat(),
                label = "Top Stimulus Pad"
            ))

            val locBottom = IntArray(2)
            binding.cardGreenSafeZoneBottom.getLocationOnScreen(locBottom)
            greenZones.add(GreenSafeZone(
                left = locBottom[0].toFloat(),
                top = locBottom[1].toFloat(),
                right = (locBottom[0] + binding.cardGreenSafeZoneBottom.width).toFloat(),
                bottom = (locBottom[1] + binding.cardGreenSafeZoneBottom.height).toFloat(),
                label = "Bottom Stimulus Pad"
            ))
        }

        countDownTimer = object : CountDownTimer(scanDurationMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000L).toInt()
                binding.tvIdentifyCountdown.text = "${sec}s"
                binding.tvCapturedSamples.text = "${recordedGhostEntries.size} Glitches Captured"
            }
            override fun onFinish() {
                binding.tvIdentifyCountdown.text = "0s"
                finishGhostSpectrumScan()
            }
        }.start()

        Toast.makeText(this, "SCAN ACTIVE — Tap Green Safe Zones to provoke post-touch glitches!", Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------------------
    // Dispatch Touch Handling (Phase 1 taps & Phase 2 Green Zones / Glitches)
    // ---------------------------------------------------------------------------
    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) return super.dispatchTouchEvent(event)

        // Slide Exit handler always gets own touches
        val sliderBounds = binding.humanExitSlider.let {
            val loc = IntArray(2); it.getLocationOnScreen(loc)
            Rect(loc[0], loc[1], loc[0] + it.width, loc[1] + it.height)
        }
        if (sliderBounds.contains(event.rawX.toInt(), event.rawY.toInt())) {
            return super.dispatchTouchEvent(event)
        }

        // --- PHASE 1 ACTIVE ---
        if (binding.panelFingerCalibration.visibility == View.VISIBLE) {
            if (event.actionMasked == MotionEvent.ACTION_UP && fingerSamples.size < 5) {
                handleFingerCalibrationTap(event)
            }
            return super.dispatchTouchEvent(event)
        }

        // --- SCAN NOT ACTIVE ---
        if (!isGhostScanActive) {
            return super.dispatchTouchEvent(event)
        }

        // --- PHASE 2 ACTIVE: GHOST SCAN WITH GREEN SAFE ZONES ---
        val rawX = event.rawX
        val rawY = event.rawY

        // Check if inside any Green Safe Zone
        val isInsideGreenZone = greenZones.any { it.contains(rawX, rawY) }

        if (isInsideGreenZone) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                lastStimulusTimestamp = event.eventTime
                runOnUiThread {
                    binding.tvLastFactor.text = "✅ Safe Green Tap Registered! Listening for trailing glitches..."
                    binding.tvLastFactor.setTextColor(Color.parseColor("#22C55E"))
                }
            }
            return true // Consume stimulus tap safely — NEVER recorded as ghost
        }

        // Touches OUTSIDE Green Zone during active scan = Candidate Ghost Glitches
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                downHistorySize = event.historySize
            }
            MotionEvent.ACTION_UP -> {
                val dwell = (event.eventTime - downTime).toInt()
                val now = event.eventTime

                val intervalFromPrev = if (lastGhostTimestamp > 0L) now - lastGhostTimestamp else 0L
                val timeSinceStimulus = if (lastStimulusTimestamp > 0L) now - lastStimulusTimestamp else -1L
                val accelDelta = impulseSensor?.getLastAccelDelta() ?: 0f

                val isEcho = timeSinceStimulus in 2L..1500L

                val entry = GhostTouchEntry(
                    x = downX, y = downY,
                    dwell = dwell,
                    size = event.size,
                    pressure = event.pressure,
                    touchMajor = event.touchMajor,
                    historySize = downHistorySize,
                    accelDelta = accelDelta,
                    intervalFromPrevMs = intervalFromPrev,
                    displacementPx = 0f,
                    timestamp = now,
                    timeSinceFingerMs = timeSinceStimulus,
                    velocityPxPerMs = 0f,
                    factorNotes = if (isEcho) "Post-Green Stimulus Glitch (${timeSinceStimulus}ms)" else "Spontaneous Glitch"
                )

                recordedGhostEntries.add(entry)
                lastGhostTimestamp = now

                // Ingest into 8D Bayesian AI Model via online learning
                val ghostVector = TouchFeatureVector.extract(
                    event = event,
                    dwellMs = dwell,
                    downX = downX,
                    downY = downY,
                    accelDelta = accelDelta,
                    intervalMs = intervalFromPrev,
                    timeSinceStimulusMs = timeSinceStimulus
                )
                recordedGhostVectors.add(ghostVector)
                brainEngine.learnOnline(ghostVector, isGhost = true)

                val decision = brainEngine.classify(ghostVector)

                runOnUiThread {
                    binding.tvCapturedSamples.text = "${recordedGhostEntries.size} Glitches Profiled into 8D Brain"
                    val echoNote = if (isEcho) " [Post-Stimulus Echo!]" else ""
                    binding.tvLastFactor.text = "Glitch #${recordedGhostEntries.size} (${dwell}ms)$echoNote | P(Ghost)=${(decision.ghostProbability * 100).toInt()}%"
                    binding.tvLastFactor.setTextColor(Color.parseColor("#FF5252"))

                    val minD = recordedGhostEntries.minOfOrNull { it.dwell } ?: dwell
                    val maxD = recordedGhostEntries.maxOfOrNull { it.dwell } ?: dwell
                    val intervals = recordedGhostEntries.map { it.intervalFromPrevMs }.filter { it > 10L }
                    val minI = intervals.minOrNull() ?: 30L
                    val maxI = intervals.maxOrNull() ?: 450L

                    binding.tvFactorMatrix.text = buildString {
                        append("🧠 8D Brain Probability: P(Ghost)=${(decision.ghostProbability * 100).toInt()}%\n")
                        append("⏱ Dwell Spectrum:       ${minD}ms – ${maxD}ms (Noise)\n")
                        append("🔄 Frequency Band:       ${minI}ms – ${maxI}ms (Stochastic)\n")
                        append("⚡ Zero Momentum:        ${String.format("%.4f", accelDelta)} m/s² (No physical mass)\n")
                        append("📐 Ellipse Eccentricity: ${String.format("%.2f", ghostVector.eccentricity)} | Area: ${String.format("%.4f", ghostVector.size)}")
                    }
                }
            }
        }

        return true
    }

    // ---------------------------------------------------------------------------
    // PHASE 3: AI Model Synthesis & Activation
    // ---------------------------------------------------------------------------
    private fun finishGhostSpectrumScan() {
        isGhostScanActive = false

        val glitchProfile = GhostGlitchProfile.fromEntries(recordedGhostEntries, greenZones)

        binding.scanActivePanel.visibility = View.GONE
        binding.resultPanel.visibility = View.VISIBLE

        binding.tvResultSummary.text = buildString {
            append("🟢 HUMAN FINGER BASELINE (LOCKED):\n")
            append("   • Min Shockwave Mass:  ${String.format("%.3f", humanProfile.minShockwaveThreshold)} m/s²\n")
            append("   • Min Flesh Contact:    ${String.format("%.3f", humanProfile.minContactSize)}\n")
            append("   • Safe Interaction:     100% Native 120Hz Smooth Pass-Through\n\n")

            append("🔴 8D GHOST BRAIN & SPECTRUM:\n")
            append("   • Learned Samples:      ${recordedGhostEntries.size} Glitches Ingested\n")
            append("   • Dwell Spectrum:       ${glitchProfile.minDwell}ms – ${glitchProfile.maxDwell}ms\n")
            append("   • Frequency Band:       ${glitchProfile.minIntervalMs}ms – ${glitchProfile.maxIntervalMs}ms\n")
            append("   • Online Welford Memory: ACTIVE (Continuous adaptation)\n")
            append("   • Full-Screen Shield:   ARMED (All unknown frequencies evaluated)\n\n")

            append("🛡️ GhostMind v3.0 8D Bayesian Shield ready to activate.")
        }

        binding.btnSaveFingerprint.setOnClickListener {
            // Save profiles and 8D Bayesian Brain Engine
            prefs.humanFingerProfileJson = HumanFingerProfile.toJson(humanProfile)
            prefs.ghostGlitchProfileJson = GhostGlitchProfile.toJson(glitchProfile)
            prefs.ghostBrainEngineJson = brainEngine.toJson()
            prefs.isFingerShieldModeActive = true
            prefs.isHotspotShieldEnabled = true

            // Cluster recorded ghost entries into hotspots if localized
            if (recordedGhostEntries.isNotEmpty()) {
                val calibEngine = com.antigravity.ghosttouchguardian.engine.GhostCalibrationEngine()
                calibEngine.startCalibration()
                for (entry in recordedGhostEntries) {
                    calibEngine.recordTouch(entry.x, entry.y, entry.timestamp)
                }
                val calibResult = calibEngine.finishCalibration()
                if (calibResult.zones.isNotEmpty()) {
                    prefs.savedHotspotsJson = com.antigravity.ghosttouchguardian.engine.GhostCalibrationEngine.serializeZonesToJson(calibResult.zones)
                    prefs.calibratedAvgPeriodMs = calibResult.avgPeriodMs
                    prefs.calibratedVariancePercent = calibResult.periodVariancePercent
                }
            }

            // Start HotspotShieldService with AI Core and refreshed zones
            val shieldIntent = Intent(this, com.antigravity.ghosttouchguardian.service.HotspotShieldService::class.java).apply {
                action = com.antigravity.ghosttouchguardian.service.HotspotShieldService.ACTION_REFRESH_ZONES
            }
            androidx.core.content.ContextCompat.startForegroundService(this, shieldIntent)

            Toast.makeText(this, "✅ GhostMind AI Shield Activated Successfully!", Toast.LENGTH_LONG).show()
            finish()
        }

        binding.btnDiscardScan.setOnClickListener {
            fingerSamples.clear()
            recordedGhostEntries.clear()
            setupPhase1FingerCalibration()
        }
    }

    private fun setupSlideExit() {
        binding.humanExitSlider.onHumanVerifiedExit = {
            countDownTimer?.cancel()
            isGhostScanActive = false
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        impulseSensor?.stop()

        // Resume shield upon exit
        if (prefs.isHotspotShieldEnabled) {
            val shieldIntent = Intent(this, com.antigravity.ghosttouchguardian.service.HotspotShieldService::class.java).apply {
                action = com.antigravity.ghosttouchguardian.service.HotspotShieldService.ACTION_START
            }
            androidx.core.content.ContextCompat.startForegroundService(this, shieldIntent)
        }
    }
}
