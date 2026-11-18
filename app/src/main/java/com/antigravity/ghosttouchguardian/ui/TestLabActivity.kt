package com.antigravity.ghosttouchguardian.ui

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.antigravity.ghosttouchguardian.R
import com.antigravity.ghosttouchguardian.databinding.ActivityTestLabBinding
import com.antigravity.ghosttouchguardian.engine.TouchProfilerEngine
import com.antigravity.ghosttouchguardian.service.TouchGuardianService

/**
 * Dedicated, Immersive Fullscreen Ghost Touch Diagnostic Lab.
 * Screen is completely LOCKED against accidental ghost touch exits:
 * 1. System bars hidden (Sticky Immersive Mode).
 * 2. Back button / gesture intercepted.
 * 3. Exit strictly gated by HumanExitSliderView (Ghost-proof continuous vector swipe).
 */
class TestLabActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTestLabBinding
    private lateinit var engine: TouchProfilerEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTestLabBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. Lock Screen into Immersive Sticky Mode (Ghost clicks cannot pull down notification/nav)
        lockImmersiveMode()

        // 2. Intercept Hardware / Gesture Back button
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(
                    this@TestLabActivity,
                    "🔒 Screen Locked: Slide the bottom bar to exit",
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
        binding.btnLabIdleMode.setOnClickListener {
            engine.isIdleMonitoringActive = true
            engine.isInteractiveEchoMonitoringActive = false

            binding.btnLabIdleMode.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_blue))
            binding.btnLabEchoMode.setBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent))
            binding.btnLabEchoMode.setTextColor(ContextCompat.getColor(this, R.color.text_primary))

            binding.tvLabInstructions.text = "[Hands-Off Test] Phone placed on table. Any touch is auto-flagged as Ghost."
            binding.tvLabInstructions.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
        }

        binding.btnLabEchoMode.setOnClickListener {
            engine.isIdleMonitoringActive = false
            engine.isInteractiveEchoMonitoringActive = true

            binding.btnLabEchoMode.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_blue))
            binding.btnLabIdleMode.setBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent))
            binding.tvLabInstructions.text = "[Echo Test] Tap and swipe with finger. App measures trailing touches after release."
            binding.tvLabInstructions.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
        }

        binding.btnFlushLab.setOnClickListener {
            engine.flush()
            updateStats()
            binding.labTouchCanvasView.invalidate()
            Toast.makeText(this, "Canvas Cleared & Buffer Flushed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupHumanExitSlider() {
        // Human-Only Exit gate
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
    }

    private fun updateStats() {
        val buffer = engine.buffer
        binding.tvLabGhostCount.text = buffer.totalGhostCount.toString()
        binding.tvLabEchoCount.text = buffer.totalEchoCount.toString()
    }
}
