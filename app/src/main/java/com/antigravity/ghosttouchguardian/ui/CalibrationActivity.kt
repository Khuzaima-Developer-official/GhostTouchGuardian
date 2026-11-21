package com.antigravity.ghosttouchguardian.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
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
import com.antigravity.ghosttouchguardian.databinding.ActivityCalibrationBinding
import com.antigravity.ghosttouchguardian.engine.CalibrationResult
import com.antigravity.ghosttouchguardian.engine.GhostCalibrationEngine
import com.antigravity.ghosttouchguardian.service.HotspotShieldService

/**
 * Auto-Calibration Wizard for Hotspot Zone mapping.
 * User puts phone down (no touch), app records natural ghost patterns.
 *
 * Customizable scan duration (10s – 120s via slider).
 * Exit: SLIDE ONLY (no back button).
 */
class CalibrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibrationBinding
    private lateinit var prefs: GuardianPreferences
    private val calibrationEngine = GhostCalibrationEngine()

    private var countDownTimer: CountDownTimer? = null
    private var lastResult: CalibrationResult? = null
    private var recordedCount = 0
    private var scanDurationMs: Long = 15_000L  // default 15s

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = GuardianPreferences(this)
        lockImmersiveMode()

        // Pause background shield during calibration mode so all phantom touches can be mapped
        startService(Intent(this, HotspotShieldService::class.java).apply {
            action = HotspotShieldService.ACTION_PAUSE
        })

        // Disable back button — exit only via slide
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(this@CalibrationActivity, "Slide to exit →", Toast.LENGTH_SHORT).show()
            }
        })

        setupTimeSelector()
        setupStartButton()
        setupSlideExit()
    }

    private fun lockImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupTimeSelector() {
        // 0..22 → 10s to 120s in steps of 5
        binding.seekScanTime.max = 22
        binding.seekScanTime.progress = 1  // default 15s
        scanDurationMs = 15_000L
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
        binding.btnStartCalibration.setOnClickListener {
            binding.scanTimeSetup.visibility = View.GONE
            binding.btnStartCalibration.visibility = View.GONE
            binding.btnSaveAndActivate.visibility = View.GONE
            binding.tvCalibrationResult.visibility = View.GONE
            startCalibrationScan()
        }
    }

    private fun startCalibrationScan() {
        calibrationEngine.startCalibration()
        recordedCount = 0

        val totalSec = (scanDurationMs / 1000L).toInt()
        binding.tvCountdownTimer.text = totalSec.toString()
        binding.tvCalibrationSubtitle.text = "⚠️ Do NOT touch the screen for ${totalSec}s. Let phone naturally map its ghost patterns."

        countDownTimer = object : CountDownTimer(scanDurationMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000L).toInt()
                binding.tvCountdownTimer.text = sec.toString()
                binding.tvCapturedCount.text = "$recordedCount Ghost Touches Captured"
            }
            override fun onFinish() {
                binding.tvCountdownTimer.text = "0"
                finishScan()
            }
        }.start()
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        // Let the slider handle its own touches
        if (event != null) {
            val sliderBounds = binding.humanExitSlider.let {
                val loc = IntArray(2); it.getLocationOnScreen(loc)
                android.graphics.Rect(loc[0], loc[1], loc[0] + it.width, loc[1] + it.height)
            }
            if (sliderBounds.contains(event.rawX.toInt(), event.rawY.toInt())) {
                return super.dispatchTouchEvent(event)
            }
        }

        if (event != null && calibrationEngine.isCalibrating) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                calibrationEngine.recordTouch(event.x, event.y, event.eventTime)
                recordedCount++
                binding.tvCapturedCount.text = "$recordedCount Ghost Touches Captured"
            }
            return true  // Consume
        }

        // When scan is complete, shield UI buttons against ghost touches
        if (event != null && lastResult != null) {
            val isFlesh = event.size >= 0.035f || event.pressure >= 0.10f || event.pointerCount > 1
            val inGlitchZone = lastResult?.zones?.any { it.contains(event.x, event.y) } == true
            if (!isFlesh && (inGlitchZone || event.actionMasked == MotionEvent.ACTION_DOWN)) {
                // Block ghost touch from auto-clicking buttons!
                return true
            }
        }

        // Allow setup UI touches (SeekBar, Start button)
        return super.dispatchTouchEvent(event)
    }

    private fun finishScan() {
        val result = calibrationEngine.finishCalibration()
        lastResult = result

        // Auto-arm and activate immediately upon scan completion
        if (result.zones.isNotEmpty()) {
            val json = GhostCalibrationEngine.serializeZonesToJson(result.zones)
            prefs.savedHotspotsJson = json
            prefs.calibratedAvgPeriodMs = result.avgPeriodMs
            prefs.calibratedVariancePercent = result.periodVariancePercent
            prefs.isHotspotShieldEnabled = true

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                startService(Intent(this, HotspotShieldService::class.java).apply {
                    action = HotspotShieldService.ACTION_START
                })
            }
        }

        binding.tvCountdownTimer.visibility = View.GONE
        binding.tvCalibrationSubtitle.text = "Scan complete! ${result.zones.size} ghost zone(s) mapped."

        val zoneCount = result.zones.size
        val tapCount = result.totalGhostCount
        val periodText = if (result.avgPeriodMs > 0) " • Period: ${result.avgPeriodMs}ms" else ""

        binding.tvCalibrationResult.visibility = View.VISIBLE
        binding.tvCalibrationResult.text = "$zoneCount Ghost Zones Mapped ($tapCount phantom taps$periodText)\n🛡 Shield is ACTIVE & Blocking."

        binding.btnSaveAndActivate.visibility = View.VISIBLE
        binding.btnSaveAndActivate.text = "Finish & Return"
        binding.btnSaveAndActivate.setOnClickListener {
            saveAndActivate(result)
        }
    }

    private fun saveAndActivate(result: CalibrationResult) {
        val json = GhostCalibrationEngine.serializeZonesToJson(result.zones)
        prefs.savedHotspotsJson = json
        prefs.calibratedAvgPeriodMs = result.avgPeriodMs
        prefs.calibratedVariancePercent = result.periodVariancePercent
        prefs.isHotspotShieldEnabled = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Enable 'Appear on top' permission to activate Shield", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } else {
            val startIntent = Intent(this, HotspotShieldService::class.java).apply {
                action = HotspotShieldService.ACTION_START
            }
            androidx.core.content.ContextCompat.startForegroundService(this, startIntent)
            Toast.makeText(this, "✅ Hotspot Shield Activated!", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun setupSlideExit() {
        binding.humanExitSlider.onHumanVerifiedExit = {
            countDownTimer?.cancel()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()

        // Resume Hotspot Shield service upon exiting Calibration Mode
        if (prefs.isHotspotShieldEnabled) {
            val startIntent = Intent(this, HotspotShieldService::class.java).apply {
                action = HotspotShieldService.ACTION_START
            }
            androidx.core.content.ContextCompat.startForegroundService(this, startIntent)
        }
    }
}
