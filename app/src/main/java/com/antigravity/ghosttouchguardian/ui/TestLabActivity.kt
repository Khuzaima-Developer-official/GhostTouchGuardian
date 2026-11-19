package com.antigravity.ghosttouchguardian.ui

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
import com.antigravity.ghosttouchguardian.engine.TouchProfilerEngine
import com.antigravity.ghosttouchguardian.service.TouchGuardianService

class TestLabActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTestLabBinding
    private lateinit var engine: TouchProfilerEngine
    private lateinit var prefs: GuardianPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTestLabBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = GuardianPreferences(this)

        lockImmersiveMode()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(this@TestLabActivity, "Slide bottom bar to exit", Toast.LENGTH_SHORT).show()
            }
        })

        engine = TouchGuardianService.instance?.profiler ?: TouchProfilerEngine()
        binding.labTouchCanvasView.profiler = engine

        setupModeSelectors()
        setupClearButton()
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

    private fun setupModeSelectors() {
        renderModeButtons(engine.isGhostOnlyMode)

        binding.btnModeDiagnostic.setOnClickListener {
            engine.isGhostOnlyMode = false
            renderModeButtons(false)
            binding.labTouchCanvasView.invalidate()
            Toast.makeText(this, "Diagnostic: All Touches Shown", Toast.LENGTH_SHORT).show()
        }

        binding.btnModeGhostOnly.setOnClickListener {
            engine.isGhostOnlyMode = true
            renderModeButtons(true)
            binding.labTouchCanvasView.invalidate()
            Toast.makeText(this, "Ghost-Only: Real Fingers Ignored", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderModeButtons(isGhostOnly: Boolean) {
        val activeCyan = ContextCompat.getColor(this, R.color.accent_cyan)
        val inactiveText = ContextCompat.getColor(this, R.color.text_secondary)
        val activeGhostRed = ContextCompat.getColor(this, R.color.ghost_red)
        val borderDark = ContextCompat.getColor(this, R.color.card_border)

        if (isGhostOnly) {
            // Ghost-only active
            binding.btnModeGhostOnly.setTextColor(activeGhostRed)
            binding.btnModeGhostOnly.strokeColor = ColorStateList.valueOf(activeGhostRed)
            binding.btnModeGhostOnly.strokeWidth = 2

            binding.btnModeDiagnostic.setTextColor(inactiveText)
            binding.btnModeDiagnostic.strokeColor = ColorStateList.valueOf(borderDark)
            binding.btnModeDiagnostic.strokeWidth = 1

            binding.tvLabSubheader.text = "GHOST-ONLY FILTER (Fingers Blocked / Ignored)"
            binding.tvLabSubheader.setTextColor(activeGhostRed)
        } else {
            // Diagnostic active
            binding.btnModeDiagnostic.setTextColor(activeCyan)
            binding.btnModeDiagnostic.strokeColor = ColorStateList.valueOf(activeCyan)
            binding.btnModeDiagnostic.strokeWidth = 2

            binding.btnModeGhostOnly.setTextColor(inactiveText)
            binding.btnModeGhostOnly.strokeColor = ColorStateList.valueOf(borderDark)
            binding.btnModeGhostOnly.strokeWidth = 1

            binding.tvLabSubheader.text = "DIAGNOSTIC MODE (All Taps Tested)"
            binding.tvLabSubheader.setTextColor(ContextCompat.getColor(this, R.color.valid_green))
        }
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
}
