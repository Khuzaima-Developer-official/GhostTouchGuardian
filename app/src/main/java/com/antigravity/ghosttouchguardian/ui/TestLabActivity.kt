package com.antigravity.ghosttouchguardian.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

        // Init prefs
        prefs = GuardianPreferences(this)

        // Lock screen
        lockImmersiveMode()

        // Intercept back
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(this@TestLabActivity, "Slide bottom bar to exit", Toast.LENGTH_SHORT).show()
            }
        })

        // Engine link
        engine = TouchGuardianService.instance?.profiler ?: TouchProfilerEngine()
        binding.labTouchCanvasView.profiler = engine

        // Unified mode
        engine.isIdleMonitoringActive = true
        engine.isInteractiveEchoMonitoringActive = true

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
            // Re-lock
            lockImmersiveMode()
        }
    }

    private fun setupHumanExitSlider() {
        // Human slider
        binding.humanExitSlider.onHumanVerifiedExit = {
            finish()
        }
    }

    private fun setupCallbacks() {
        // Update UI
        engine.onAnomalyDetected = { _, _, _, _ ->
            runOnUiThread {
                updateStats()
                binding.labTouchCanvasView.invalidate()
            }
        }
    }

    private fun updateStats() {
        // Refresh counts
        val buffer = engine.buffer
        binding.tvLabGhostCount.text = buffer.totalGhostCount.toString()
        binding.tvLabEchoCount.text = buffer.totalEchoCount.toString()
    }
}
