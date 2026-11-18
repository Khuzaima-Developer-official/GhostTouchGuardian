package com.antigravity.ghosttouchguardian.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.antigravity.ghosttouchguardian.R
import com.antigravity.ghosttouchguardian.data.GuardianPreferences
import com.antigravity.ghosttouchguardian.databinding.ActivityTestLabBinding
import com.antigravity.ghosttouchguardian.engine.TouchProfilerEngine
import com.antigravity.ghosttouchguardian.model.GhostTouchProfile
import com.antigravity.ghosttouchguardian.service.TouchGuardianService

/**
 * Dedicated, Immersive Fullscreen Ghost Touch Diagnostic Lab.
 * Supports:
 * 1. Mode 1: Hands-Free (Ambient Electrical Noise)
 * 2. Mode 2: Finger Echo (Trailing Post-Release Clicks)
 * 3. Mode 3: Calibration Wizard (Measures user's personal touch signature to prevent false positives)
 * Screen is completely LOCKED against ghost clicks with HumanExitSliderView.
 */
class TestLabActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTestLabBinding
    private lateinit var engine: TouchProfilerEngine
    private lateinit var prefs: GuardianPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTestLabBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = GuardianPreferences(this)

        // 1. Lock Screen into Immersive Sticky Mode
        lockImmersiveMode()

        // 2. Intercept Hardware / Gesture Back button
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(
                    this@TestLabActivity,
                    "Screen Locked: Slide the bottom bar to exit",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })

        // 3. Link Profiler Engine
        engine = TouchGuardianService.instance?.profiler ?: TouchProfilerEngine()
        binding.labTouchCanvasView.profiler = engine

        setupModeSwitcher()
        setupHumanExitSlider()
        setupCallbacks()
        updateStats()
    }

    private fun lockImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            lockImmersiveMode()
        }
    }

    private fun setupModeSwitcher() {
        val activeBg = ContextCompat.getColor(this, R.color.accent_blue)
        val inactiveBg = ContextCompat.getColor(this, android.R.color.transparent)
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val textWhite = ContextCompat.getColor(this, android.R.color.white)

        // Mode 1: Hands-Free
        binding.btnLabIdleMode.setOnClickListener {
            engine.isIdleMonitoringActive = true
            engine.isInteractiveEchoMonitoringActive = false
            engine.isCalibrationActive = false

            binding.btnLabIdleMode.setBackgroundColor(activeBg)
            binding.btnLabIdleMode.setTextColor(textWhite)

            binding.btnLabEchoMode.setBackgroundColor(inactiveBg)
            binding.btnLabEchoMode.setTextColor(textPrimary)

            binding.btnLabCalibrateMode.setBackgroundColor(inactiveBg)
            binding.btnLabCalibrateMode.setTextColor(textPrimary)

            binding.tvLabInstructions.text = "Hands-Off Test: Place phone on table. Any touch is auto-flagged as Ghost."
            binding.tvLabInstructions.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
        }

        // Mode 2: Finger Echo
        binding.btnLabEchoMode.setOnClickListener {
            engine.isIdleMonitoringActive = false
            engine.isInteractiveEchoMonitoringActive = true
            engine.isCalibrationActive = false

            binding.btnLabEchoMode.setBackgroundColor(activeBg)
            binding.btnLabEchoMode.setTextColor(textWhite)

            binding.btnLabIdleMode.setBackgroundColor(inactiveBg)
            binding.btnLabIdleMode.setTextColor(textPrimary)

            binding.btnLabCalibrateMode.setBackgroundColor(inactiveBg)
            binding.btnLabCalibrateMode.setTextColor(textPrimary)

            binding.tvLabInstructions.text = "Echo Test: Tap and swipe with finger. App measures trailing release echoes."
            binding.tvLabInstructions.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
        }

        // Mode 3: Calibrate Screen
        binding.btnLabCalibrateMode.setOnClickListener {
            engine.isIdleMonitoringActive = false
            engine.isInteractiveEchoMonitoringActive = false
            engine.isCalibrationActive = true
            engine.calibrationSamplesCount = 0
            engine.calibrationTotalDwell = 0L
            engine.calibrationTotalSize = 0f

            binding.btnLabCalibrateMode.setBackgroundColor(activeBg)
            binding.btnLabCalibrateMode.setTextColor(textWhite)

            binding.btnLabIdleMode.setBackgroundColor(inactiveBg)
            binding.btnLabIdleMode.setTextColor(textPrimary)

            binding.btnLabEchoMode.setBackgroundColor(inactiveBg)
            binding.btnLabEchoMode.setTextColor(textPrimary)

            binding.tvLabInstructions.text = "Calibration: Tap screen 5 times naturally to calibrate to your finger."
            binding.tvLabInstructions.setTextColor(ContextCompat.getColor(this, R.color.valid_green))
        }

        binding.btnFlushLab.setOnClickListener {
            engine.flush()
            updateStats()
            binding.labTouchCanvasView.invalidate()
            Toast.makeText(this, "Canvas Cleared & Buffer Flushed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupHumanExitSlider() {
        binding.humanExitSlider.onHumanVerifiedExit = {
            Toast.makeText(this, "Human Verified! Exiting Lab...", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun setupCallbacks() {
        engine.onAnomalyDetected = { type, x, y, dwell ->
            runOnUiThread {
                updateStats()
                binding.labTouchCanvasView.invalidate()
            }
        }

        engine.onCalibrationSample = { sampleIndex, dwell, size ->
            runOnUiThread {
                if (sampleIndex < 5) {
                    binding.tvLabInstructions.text = "Calibration: Sample $sampleIndex/5 recorded (${dwell}ms). Tap again."
                } else {
                    val customProfile = engine.finishCalibration()
                    prefs.currentProfile = customProfile
                    prefs.customDwellMs = engine.ghostMaxDwellMs
                    prefs.customSizeThreshold = engine.ghostMaxSize

                    // Update background service
                    val intent = Intent(this, TouchGuardianService::class.java).apply {
                        action = TouchGuardianService.ACTION_UPDATE_PROFILE
                    }
                    startService(intent)

                    binding.tvLabInstructions.text = "Calibration Complete! Safe threshold set (< ${engine.ghostMaxDwellMs}ms). Real taps will never be blocked."
                    binding.tvLabInstructions.setTextColor(ContextCompat.getColor(this, R.color.valid_green))
                    Toast.makeText(this, "Screen Calibrated to Your Finger!", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateStats() {
        val buffer = engine.buffer
        binding.tvLabGhostCount.text = buffer.totalGhostCount.toString()
        binding.tvLabEchoCount.text = buffer.totalEchoCount.toString()
    }
}
