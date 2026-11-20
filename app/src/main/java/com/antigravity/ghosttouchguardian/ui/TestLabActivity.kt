package com.antigravity.ghosttouchguardian.ui

import android.content.Intent
import android.content.res.ColorStateList
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
import com.antigravity.ghosttouchguardian.engine.GhostCalibrationEngine
import com.antigravity.ghosttouchguardian.engine.TouchProfilerEngine
import com.antigravity.ghosttouchguardian.model.GhostFingerprint
import com.antigravity.ghosttouchguardian.sensor.MechanicalImpulseSensor
import com.antigravity.ghosttouchguardian.service.TouchGuardianService

/**
 * Test Lab Activity — 3 Testing Modes (all temporary, for analysis only):
 *
 * MODE 1 — Ghost Touch Detect:
 *   Every touch = ghost. Records fingerprint factors. Use this in GhostIdentifyActivity.
 *   Here it shows with ghost markers (red) but doesn't block.
 *
 * MODE 2 — Ghost & Finger Touch (DIAGNOSTIC):
 *   Shows both ghost (red) and human (green) classified by 12-factor engine.
 *   The default diagnostic mode.
 *
 * MODE 3 — Ghost Touch Block:
 *   Actively tests ghost blocking using calibrated fingerprint.
 *   Ghost touches = consumed (not passed to canvas). Real finger = shown green.
 *
 * Exit: SLIDE ONLY (no back button).
 */
class TestLabActivity : AppCompatActivity() {

    private enum class LabMode { GHOST_DETECT, DIAGNOSTIC, GHOST_BLOCK }

    private lateinit var binding: ActivityTestLabBinding
    private lateinit var engine: TouchProfilerEngine
    private lateinit var prefs: GuardianPreferences
    private var impulseSensor: MechanicalImpulseSensor? = null
    private var currentMode = LabMode.DIAGNOSTIC

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTestLabBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = GuardianPreferences(this)
        impulseSensor = MechanicalImpulseSensor(this).also { it.start() }

        // Pause background shield during Test Lab so touches reach canvas directly
        startService(Intent(this, com.antigravity.ghosttouchguardian.service.HotspotShieldService::class.java).apply {
            action = com.antigravity.ghosttouchguardian.service.HotspotShieldService.ACTION_PAUSE
        })

        lockImmersiveMode()

        // Block back button
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(this@TestLabActivity, "Slide to exit →", Toast.LENGTH_SHORT).show()
            }
        })

        engine = TouchGuardianService.instance?.profiler ?: TouchProfilerEngine()
        engine.impulseSensor = impulseSensor

        // Load calibrated zones
        val zones = GhostCalibrationEngine.parseZonesFromJson(prefs.savedHotspotsJson)
        engine.calibratedHotspots = zones
        engine.calibratedAvgPeriodMs = prefs.calibratedAvgPeriodMs
        engine.calibratedVariancePercent = prefs.calibratedVariancePercent

        // Load ghost fingerprint from Identify Mode
        engine.ghostFingerprint = GhostFingerprint.fromJson(prefs.ghostFingerprintJson)

        // Load GhostMind AI Profiles
        engine.ghostMindCore.humanProfile = com.antigravity.ghosttouchguardian.model.HumanFingerProfile.fromJson(prefs.humanFingerProfileJson)
        engine.ghostMindCore.ghostProfile = com.antigravity.ghosttouchguardian.model.GhostGlitchProfile.fromJson(prefs.ghostGlitchProfileJson)
        engine.ghostMindCore.isSPenModeActive = prefs.isSPenModeActive

        binding.labTouchCanvasView.profiler = engine
        binding.labTouchCanvasView.hotspots = zones

        setupModeButtons()
        setupClearButton()
        setupHumanExitSlider()
        setupCallbacks()
        applyMode(LabMode.DIAGNOSTIC)
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
        if (hasFocus) lockImmersiveMode()
    }

    private fun setupModeButtons() {
        // MODE 1: Ghost Detect (treat all as ghost — for visual identification)
        binding.btnModeGhostDetect.setOnClickListener {
            applyMode(LabMode.GHOST_DETECT)
        }

        // MODE 2: Diagnostic (show both ghost + human)
        binding.btnModeDiagnostic.setOnClickListener {
            applyMode(LabMode.DIAGNOSTIC)
        }

        // MODE 3: Ghost Block (actively block ghosts matching fingerprint)
        binding.btnModeGhostBlock.setOnClickListener {
            if (!engine.ghostFingerprint.isValid && prefs.savedHotspotsJson.isBlank()) {
                Toast.makeText(this, "⚠ No ghost fingerprint loaded. Run Ghost Identify first!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            applyMode(LabMode.GHOST_BLOCK)
        }
    }

    private fun applyMode(mode: LabMode) {
        currentMode = mode
        val cyan = ContextCompat.getColor(this, R.color.accent_cyan)
        val red = ContextCompat.getColor(this, R.color.ghost_red)
        val green = ContextCompat.getColor(this, R.color.valid_green)
        val inactive = ContextCompat.getColor(this, R.color.text_secondary)
        val border = ContextCompat.getColor(this, R.color.card_border)

        // Reset all buttons to inactive
        listOf(binding.btnModeGhostDetect, binding.btnModeDiagnostic, binding.btnModeGhostBlock).forEach { btn ->
            btn.setTextColor(inactive)
            btn.strokeColor = ColorStateList.valueOf(border)
            btn.strokeWidth = 1
        }

        when (mode) {
            LabMode.GHOST_DETECT -> {
                engine.isGhostOnlyMode = true
                engine.isHandsFreeScanActive = false
                binding.btnModeGhostDetect.setTextColor(red)
                binding.btnModeGhostDetect.strokeColor = ColorStateList.valueOf(red)
                binding.btnModeGhostDetect.strokeWidth = 2
                binding.tvLabSubheader.text = "GHOST DETECT — All touches shown as ghost (red)"
                binding.tvLabSubheader.setTextColor(red)
                Toast.makeText(this, "Mode 1: Ghost Detect — every touch marked red", Toast.LENGTH_SHORT).show()
            }
            LabMode.DIAGNOSTIC -> {
                engine.isGhostOnlyMode = false
                engine.isHandsFreeScanActive = false
                binding.btnModeDiagnostic.setTextColor(cyan)
                binding.btnModeDiagnostic.strokeColor = ColorStateList.valueOf(cyan)
                binding.btnModeDiagnostic.strokeWidth = 2
                binding.tvLabSubheader.text = "DIAGNOSTIC — Ghost (red) & Human (green) classified"
                binding.tvLabSubheader.setTextColor(green)
                Toast.makeText(this, "Mode 2: Diagnostic — both ghost and finger shown", Toast.LENGTH_SHORT).show()
            }
            LabMode.GHOST_BLOCK -> {
                engine.isGhostOnlyMode = false
                engine.isHandsFreeScanActive = false
                binding.btnModeGhostBlock.setTextColor(green)
                binding.btnModeGhostBlock.strokeColor = ColorStateList.valueOf(green)
                binding.btnModeGhostBlock.strokeWidth = 2
                binding.tvLabSubheader.text = "GHOST BLOCK — Ghosts blocked by fingerprint engine"
                binding.tvLabSubheader.setTextColor(green)
                Toast.makeText(this, "Mode 3: Ghost Block — using fingerprint to block ghosts", Toast.LENGTH_SHORT).show()
            }
        }

        binding.labTouchCanvasView.invalidate()
    }

    private fun setupClearButton() {
        binding.btnClearCanvas.setOnClickListener {
            engine.flush()
            binding.labTouchCanvasView.invalidate()
            updateStats()
            Toast.makeText(this, "Canvas Cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupHumanExitSlider() {
        binding.humanExitSlider.onHumanVerifiedExit = {
            finish()
        }
    }

    private fun setupCallbacks() {
        engine.onAnomalyDetected = { _, _, _, _, _ ->
            runOnUiThread {
                updateStats()
                binding.labTouchCanvasView.invalidate()
            }
        }

        engine.onHumanIgnoredInGhostMode = { _, _, dwell ->
            runOnUiThread {
                binding.labTouchCanvasView.setIgnoredFingerTelemetry(dwell)
            }
        }
    }

    private fun updateStats() {
        val buffer = engine.buffer
        binding.tvLabGhostCount.text = buffer.totalGhostCount.toString()
        binding.tvLabEchoCount.text = buffer.totalEchoCount.toString()
        binding.tvLabHumanCount.text = buffer.totalHumanCount.toString()
    }

    override fun onDestroy() {
        super.onDestroy()
        impulseSensor?.stop()

        // Resume Hotspot Shield service upon exiting Test Lab
        if (prefs.isHotspotShieldEnabled) {
            startService(Intent(this, com.antigravity.ghosttouchguardian.service.HotspotShieldService::class.java).apply {
                action = com.antigravity.ghosttouchguardian.service.HotspotShieldService.ACTION_START
            })
        }
    }
}
