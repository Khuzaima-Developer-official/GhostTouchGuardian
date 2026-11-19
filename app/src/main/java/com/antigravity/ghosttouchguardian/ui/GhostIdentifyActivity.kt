package com.antigravity.ghosttouchguardian.ui

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
 * Ghost Identify Activity — IDENTIFY MODE
 *
 * Every touch on this screen is treated as a ghost touch.
 * App records all 12-factor dimensions for each touch, builds
 * a statistical GhostFingerprint, and saves it for the blocking engine.
 *
 * Exit: SLIDE ONLY (no back button).
 * Scan time: User-configurable (10s to 120s).
 */
class GhostIdentifyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGhostIdentifyBinding
    private lateinit var prefs: GuardianPreferences
    private var impulseSensor: MechanicalImpulseSensor? = null

    private val recordedEntries = mutableListOf<GhostTouchEntry>()
    private var scanDurationMs: Long = 30_000L  // default 30s
    private var countDownTimer: CountDownTimer? = null
    private var scanActive = false
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

        lockImmersive()

        // Disable back button
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
        // SeekBar range: 10s – 120s in 5s steps → 0..22 steps
        binding.seekScanTime.max = 22
        binding.seekScanTime.progress = 4  // default 30s = step 4 (10 + 4*5)
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

        binding.btnStartScan.visibility = View.GONE
        binding.scanTimeSetup.visibility = View.GONE
        binding.scanActivePanel.visibility = View.VISIBLE
        binding.resultPanel.visibility = View.GONE

        val totalSec = (scanDurationMs / 1000L).toInt()
        binding.tvIdentifyCountdown.text = totalSec.toString()

        countDownTimer = object : CountDownTimer(scanDurationMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000L).toInt()
                binding.tvIdentifyCountdown.text = sec.toString()
                binding.tvCapturedSamples.text = "${recordedEntries.size} Ghost Samples Recorded"
            }
            override fun onFinish() {
                binding.tvIdentifyCountdown.text = "0"
                finishScan()
            }
        }.start()

        Toast.makeText(this, "IDENTIFY SCAN STARTED — Do NOT touch unless it's a ghost touch!", Toast.LENGTH_LONG).show()
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) return super.dispatchTouchEvent(event)

        // Let the slider handle its own touches
        val sliderBounds = binding.humanExitSlider.let {
            val loc = IntArray(2); it.getLocationOnScreen(loc)
            android.graphics.Rect(loc[0], loc[1], loc[0] + it.width, loc[1] + it.height)
        }
        if (sliderBounds.contains(event.rawX.toInt(), event.rawY.toInt())) {
            return super.dispatchTouchEvent(event)
        }

        // Let start button handle its own touches
        if (!scanActive) return super.dispatchTouchEvent(event)

        // Record ghost touch data
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

                // Displacement from last ghost touch
                val intervalFromPrev = if (lastTouchTime > 0L) now - lastTouchTime else 0L
                val dispFromPrev = if (lastTouchX >= 0f) {
                    val dx = downX - lastTouchX; val dy = downY - lastTouchY
                    sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                } else 0f

                // Accelerometer delta
                val accelDelta = impulseSensor?.getLastAccelDelta() ?: 0f

                // Build factor notes
                val notes = buildString {
                    if (intervalFromPrev in 2L..25L && dispFromPrev < 30f) append("echo,")
                    if (dwell < 50) append("spark,")
                    if (accelDelta < 0.05f) append("noShockwave,")
                    if (event.size < 0.05f) append("zeroSize,")
                    if (event.pressure < 0.05f) append("zeroPressure,")
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
                    factorNotes = notes.trimEnd(',')
                )

                recordedEntries.add(entry)
                lastTouchTime = now
                lastTouchX = downX
                lastTouchY = downY

                runOnUiThread {
                    binding.tvCapturedSamples.text = "${recordedEntries.size} Ghost Samples Recorded"
                    binding.tvLastFactor.text = "Last: dwell=${dwell}ms size=${String.format("%.3f", event.size)} accel=${String.format("%.4f", accelDelta)} [$notes]"
                }
            }
        }

        return true  // Consume — don't pass to underlying UI
    }

    private fun finishScan() {
        scanActive = false

        val fingerprint = GhostFingerprint.fromEntries(recordedEntries)

        binding.scanActivePanel.visibility = View.GONE
        binding.resultPanel.visibility = View.VISIBLE

        if (fingerprint.isValid) {
            binding.tvResultSummary.text = buildString {
                append("✅ ${recordedEntries.size} ghost samples analyzed\n")
                append("📍 Ghost zone center: (${fingerprint.avgX.toInt()}, ${fingerprint.avgY.toInt()})\n")
                append("⏱ Avg dwell: ${fingerprint.avgDwell.toInt()}ms ± ${fingerprint.dwellStdDev.toInt()}ms\n")
                append("🔄 Avg period: ${if (fingerprint.avgPeriodMs > 0) "${fingerprint.avgPeriodMs}ms" else "irregular"}\n")
                append("📐 Avg size: ${String.format("%.4f", fingerprint.avgSize)}\n")
                append("⚡ Avg accel: ${String.format("%.4f", fingerprint.avgAccelDelta)} (${if (fingerprint.avgAccelDelta < 0.08f) "No Shockwave ✓" else "Has Shockwave!"})")
            }
            binding.btnSaveFingerprint.visibility = View.VISIBLE
            binding.btnDiscardScan.text = "Discard & Rescan"
        } else {
            binding.tvResultSummary.text = "⚠️ Not enough samples (${recordedEntries.size}). Need at least 3 ghost touches to build fingerprint. Try again."
            binding.btnSaveFingerprint.visibility = View.GONE
        }

        binding.btnSaveFingerprint.setOnClickListener {
            prefs.ghostFingerprintJson = GhostFingerprint.toJson(fingerprint)
            prefs.isGhostFingerprintEnabled = true
            Toast.makeText(this, "✅ Ghost Fingerprint Saved! Background shield will now use it.", Toast.LENGTH_LONG).show()
            finish()
        }

        binding.btnDiscardScan.setOnClickListener {
            // Reset for new scan
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
    }
}
