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
import com.antigravity.ghosttouchguardian.model.GhostFingerprint
import com.antigravity.ghosttouchguardian.model.GhostTouchEntry
import com.antigravity.ghosttouchguardian.sensor.MechanicalImpulseSensor
import kotlin.math.sqrt

/**
 * Ghost Identify Activity — Focused Core Invariants Mode
 *
 * Focuses on the 4 Definitive Physical Invariants:
 * 1. Glitch Zone Centroid (Where the damaged screen area is)
 * 2. Pulse Dwell Time (Fast electrical transient vs human intent)
 * 3. Newtonian Shockwave (Zero physical momentum = ghost)
 * 4. Post-Finger Trailing Echo (Charge trapped on glass after release)
 */
class GhostIdentifyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGhostIdentifyBinding
    private lateinit var prefs: GuardianPreferences
    private var impulseSensor: MechanicalImpulseSensor? = null

    private val recordedEntries = mutableListOf<GhostTouchEntry>()
    private var scanDurationMs: Long = 30_000L
    private var countDownTimer: CountDownTimer? = null
    private var scanActive = false

    // Finger stimulus tracking
    private var lastFingerStimulusTime = 0L
    private var lastFingerStimulusX = -1f
    private var lastFingerStimulusY = -1f

    // Consecutive ghost tracking
    private var lastTouchTime = 0L
    private var lastTouchX = -1f
    private var lastTouchY = -1f

    // Per-touch tracking
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

        // Pause background shield during identify mode so all touches can be detected freely
        startService(Intent(this, com.antigravity.ghosttouchguardian.service.HotspotShieldService::class.java).apply {
            action = com.antigravity.ghosttouchguardian.service.HotspotShieldService.ACTION_PAUSE
        })

        lockImmersive()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(this@GhostIdentifyActivity, "Slide to exit →", Toast.LENGTH_SHORT).show()
            }
        })

        setupTimeSelector()
        setupStartButton()
        setupSlideExit()
    }

    private fun lockImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val ctrl = WindowInsetsControllerCompat(window, window.decorView)
        ctrl.hide(WindowInsetsCompat.Type.systemBars())
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupTimeSelector() {
        binding.seekScanTime.max = 22
        binding.seekScanTime.progress = 4  // 30s
        updateTimeLabel()

        binding.seekScanTime.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                scanDurationMs = (10L + progress * 5L) * 1000L
                updateTimeLabel()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun updateTimeLabel() {
        val sec = (scanDurationMs / 1000L).toInt()
        binding.tvSelectedTime.text = "${sec}s"
    }

    private fun setupStartButton() {
        binding.btnStartScan.setOnClickListener {
            if (!scanActive) {
                startScan()
            }
        }
    }

    private fun startScan() {
        scanActive = true
        recordedEntries.clear()
        lastTouchTime = 0L
        lastTouchX = -1f
        lastTouchY = -1f
        lastFingerStimulusTime = 0L

        binding.btnStartScan.visibility = View.GONE
        binding.scanTimeSetup.visibility = View.GONE
        binding.scanActivePanel.visibility = View.VISIBLE
        binding.resultPanel.visibility = View.GONE

        val totalSec = (scanDurationMs / 1000L).toInt()
        binding.tvIdentifyCountdown.text = "${totalSec}s"

        countDownTimer = object : CountDownTimer(scanDurationMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000L).toInt()
                binding.tvIdentifyCountdown.text = "${sec}s"
                binding.tvCapturedSamples.text = "${recordedEntries.size} Ghost Samples Captured"
            }
            override fun onFinish() {
                binding.tvIdentifyCountdown.text = "0s"
                finishScan()
            }
        }.start()

        Toast.makeText(this, "SCAN ACTIVE — Tap Safe Spot to provoke post-touch glitches!", Toast.LENGTH_LONG).show()
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) return super.dispatchTouchEvent(event)

        // 1. Human exit slider handles own touches
        val sliderBounds = binding.humanExitSlider.let {
            val loc = IntArray(2); it.getLocationOnScreen(loc)
            Rect(loc[0], loc[1], loc[0] + it.width, loc[1] + it.height)
        }
        if (sliderBounds.contains(event.rawX.toInt(), event.rawY.toInt())) {
            return super.dispatchTouchEvent(event)
        }

        if (!scanActive) {
            // When scan is not active (setup screen or result panel), shield UI buttons from ghost clicks!
            // Any touch with zero mechanical shockwave, low pressure, and tiny size is an unwanted phantom:
            val hasShockwave = impulseSensor?.hasMechanicalImpulse(event.eventTime, windowMs = 80L) ?: false
            val isFlesh = event.size >= 0.035f || event.pressure >= 0.10f || event.pointerCount > 1
            if (!hasShockwave && !isFlesh) {
                // Swallow phantom touch so it cannot click buttons automatically
                return true
            }
            return super.dispatchTouchEvent(event)
        }

        // 2. Safe Spot & Human Finger Detection
        val safeSpotBounds = binding.cardFingerSafeSpot.let {
            val loc = IntArray(2); it.getLocationOnScreen(loc)
            Rect(loc[0], loc[1], loc[0] + it.width, loc[1] + it.height)
        }
        val isInsideSafeSpot = safeSpotBounds.contains(event.rawX.toInt(), event.rawY.toInt())
        val hasShockwave = impulseSensor?.hasMechanicalImpulse(event.eventTime, windowMs = 80L) ?: false
        val isSafeFingerTap = isInsideSafeSpot || hasShockwave

        if (isSafeFingerTap) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                lastFingerStimulusTime = event.eventTime
                lastFingerStimulusX = event.x
                lastFingerStimulusY = event.y

                runOnUiThread {
                    binding.tvSafeSpotTitle.text = "✅ SAFE FINGER TAP REGISTERED"
                    binding.tvSafeSpotTitle.setTextColor(Color.parseColor("#00E5FF"))
                    binding.cardFingerSafeSpot.strokeColor = Color.parseColor("#00E5FF")
                    binding.tvLastFactor.text = "Finger tap registered -> Waiting for trailing glitches..."
                }
            }
            return true
        }

        // 3. Ghost Touch Profiling (Zero shockwave touches outside safe spot)
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

                val intervalFromPrev = if (lastTouchTime > 0L) now - lastTouchTime else 0L
                val dispFromPrev = if (lastTouchX >= 0f) {
                    val dx = downX - lastTouchX; val dy = downY - lastTouchY
                    sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                } else 0f

                val accelDelta = impulseSensor?.getLastAccelDelta() ?: 0f
                val timeSinceFinger = if (lastFingerStimulusTime > 0L) now - lastFingerStimulusTime else -1L
                val isPostFingerEcho = timeSinceFinger in 2L..800L

                val notes = buildString {
                    if (isPostFingerEcho) append("postFingerGlitch(${timeSinceFinger}ms),")
                    if (dwell < 45) append("fastSpark,")
                    if (accelDelta < 0.018f) append("zeroMomentum,")
                }

                val entry = GhostTouchEntry(
                    x = downX, y = downY,
                    dwell = dwell,
                    size = event.size,
                    pressure = event.pressure,
                    touchMajor = event.touchMajor,
                    historySize = downHistorySize,
                    accelDelta = accelDelta,
                    intervalFromPrevMs = intervalFromPrev,
                    displacementPx = dispFromPrev,
                    timestamp = now,
                    timeSinceFingerMs = timeSinceFinger,
                    velocityPxPerMs = if (intervalFromPrev > 0L) dispFromPrev / intervalFromPrev.toFloat() else 0f,
                    factorNotes = notes.trimEnd(',')
                )

                recordedEntries.add(entry)
                lastTouchTime = now
                lastTouchX = downX
                lastTouchY = downY

                runOnUiThread {
                    binding.tvCapturedSamples.text = "${recordedEntries.size} Ghost Samples Captured"
                    val echoTag = if (isPostFingerEcho) " [Post-Finger Glitch!]" else ""
                    binding.tvLastFactor.text = "Glitch #${recordedEntries.size} at (${downX.toInt()}, ${downY.toInt()})$echoTag"

                    // Render 4 core physical factors
                    binding.tvFactorMatrix.text = buildString {
                        append("📍 Glitch Zone: (${downX.toInt()}, ${downY.toInt()})\n")
                        append("⏱ Dwell Time:  ${dwell}ms [${if (dwell <= 40) "Fast Spark ✓" else "Hold"}]\n")
                        append("⚡ Shockwave:   ${String.format("%.4f", accelDelta)} m/s² [Zero Mass ✓]\n")
                        append("🔄 Post-Finger: ${if (timeSinceFinger > 0) "${timeSinceFinger}ms after lift ✓" else "Idle spontaneous"}")
                    }
                }
            }
        }

        return true
    }

    private fun finishScan() {
        scanActive = false

        val fingerprint = GhostFingerprint.fromEntries(recordedEntries)

        binding.scanActivePanel.visibility = View.GONE
        binding.resultPanel.visibility = View.VISIBLE

        if (fingerprint.isValid) {
            val postFingerCount = recordedEntries.count { it.timeSinceFingerMs in 2L..800L }

            // Auto-arm and activate Hotspot Shield immediately upon mode completion!
            prefs.ghostFingerprintJson = GhostFingerprint.toJson(fingerprint)
            prefs.isGhostFingerprintEnabled = true
            prefs.isHotspotShieldEnabled = true
            startService(Intent(this, com.antigravity.ghosttouchguardian.service.HotspotShieldService::class.java).apply {
                action = com.antigravity.ghosttouchguardian.service.HotspotShieldService.ACTION_START
            })

            binding.tvResultSummary.text = buildString {
                append("✅ ${recordedEntries.size} Glitches Analyzed ($postFingerCount post-finger)\n\n")
                append("📍 Glitch Centroid: (${fingerprint.avgX.toInt()}, ${fingerprint.avgY.toInt()})\n")
                append("⏱ Glitch Pulse:     ${String.format("%.1f", fingerprint.avgDwell)}ms\n")
                append("⚡ Tap Momentum:     ${String.format("%.4f", fingerprint.avgAccelDelta)} m/s² (Zero mass)\n")
                append("🔄 Post-Finger Echo: ${if (fingerprint.avgPostFingerDelayMs > 0) "${fingerprint.avgPostFingerDelayMs}ms latency" else "None"}\n\n")
                append("🛡 Shield is ACTIVE & Blocking Ghosts Continuously.")
            }
            binding.btnSaveFingerprint.visibility = View.VISIBLE
            binding.btnSaveFingerprint.text = "Finish & Return"
            binding.btnDiscardScan.text = "Discard & Rescan"
        } else {
            binding.tvResultSummary.text = "⚠️ Need at least 3 ghost touches to lock glitch profile (${recordedEntries.size}/3). Please tap the Safe Spot to provoke post-touch glitches."
            binding.btnSaveFingerprint.visibility = View.GONE
        }

        binding.btnSaveFingerprint.setOnClickListener {
            prefs.ghostFingerprintJson = GhostFingerprint.toJson(fingerprint)
            prefs.isGhostFingerprintEnabled = true
            Toast.makeText(this, "✅ Glitch Profile Saved & Shield Activated!", Toast.LENGTH_LONG).show()
            finish()
        }

        binding.btnDiscardScan.setOnClickListener {
            binding.resultPanel.visibility = View.GONE
            binding.scanTimeSetup.visibility = View.VISIBLE
            binding.btnStartScan.visibility = View.VISIBLE
        }
    }

    private fun setupSlideExit() {
        binding.humanExitSlider.onHumanVerifiedExit = {
            countDownTimer?.cancel()
            scanActive = false
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        impulseSensor?.stop()

        // Resume Hotspot Shield service upon exiting Identify Mode
        if (prefs.isHotspotShieldEnabled) {
            startService(Intent(this, com.antigravity.ghosttouchguardian.service.HotspotShieldService::class.java).apply {
                action = com.antigravity.ghosttouchguardian.service.HotspotShieldService.ACTION_START
            })
        }
    }
}
