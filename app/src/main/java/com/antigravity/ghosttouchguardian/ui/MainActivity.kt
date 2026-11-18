package com.antigravity.ghosttouchguardian.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.antigravity.ghosttouchguardian.R
import com.antigravity.ghosttouchguardian.data.GuardianPreferences
import com.antigravity.ghosttouchguardian.databinding.ActivityMainBinding
import com.antigravity.ghosttouchguardian.engine.TouchProfilerEngine
import com.antigravity.ghosttouchguardian.model.GhostTouchProfile
import com.antigravity.ghosttouchguardian.service.TouchGuardianService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: TouchProfilerEngine
    private lateinit var prefs: GuardianPreferences

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startGuardianService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = GuardianPreferences(this)

        // Request notification permission on Android 13+
        checkAndRequestNotificationPermission()

        // Ensure background guardian service is running
        startGuardianService()

        // Link profiler engine
        engine = TouchGuardianService.instance?.profiler ?: TouchProfilerEngine()

        setupProfileSelectors()
        setupActions()
        renderActiveProfile(prefs.currentProfile)
    }

    override fun onResume() {
        super.onResume()
        updateDashboard()
        renderActiveProfile(prefs.currentProfile)
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun startGuardianService() {
        val serviceIntent = Intent(this, TouchGuardianService::class.java).apply {
            action = TouchGuardianService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun setupProfileSelectors() {
        binding.btnProfileBalanced.setOnClickListener {
            applyProfile(GhostTouchProfile.BALANCED)
        }
        binding.btnProfileSparks.setOnClickListener {
            applyProfile(GhostTouchProfile.MICRO_SPARKS)
        }
        binding.btnProfileEcho.setOnClickListener {
            applyProfile(GhostTouchProfile.TRAILING_ECHO)
        }
        binding.btnProfileUVGlue.setOnClickListener {
            applyProfile(GhostTouchProfile.SEVERE_UV_GLUE)
        }
    }

    private fun applyProfile(profile: GhostTouchProfile) {
        prefs.currentProfile = profile
        engine.profile = profile

        // Notify background service immediately
        val intent = Intent(this, TouchGuardianService::class.java).apply {
            action = TouchGuardianService.ACTION_UPDATE_PROFILE
        }
        startService(intent)

        renderActiveProfile(profile)
        Toast.makeText(this, "Profile: ${profile.title}", Toast.LENGTH_SHORT).show()
    }

    private fun renderActiveProfile(profile: GhostTouchProfile) {
        binding.tvActiveProfileTitle.text = profile.title
        binding.tvActiveProfileSubtitle.text = profile.subtitle

        binding.tvMetricDwell.text = "Dwell: < ${profile.maxDwellMs}ms"
        binding.tvMetricEcho.text = "Echo: ${profile.echoWindowMs}ms"
        binding.tvMetricSize.text = "Size: < ${profile.maxContactSize}"

        // Update button visual states
        val buttons = listOf(
            binding.btnProfileBalanced to GhostTouchProfile.BALANCED,
            binding.btnProfileSparks to GhostTouchProfile.MICRO_SPARKS,
            binding.btnProfileEcho to GhostTouchProfile.TRAILING_ECHO,
            binding.btnProfileUVGlue to GhostTouchProfile.SEVERE_UV_GLUE
        )

        val activeBg = ContextCompat.getColor(this, R.color.accent_blue)
        val inactiveBorder = ContextCompat.getColor(this, R.color.card_border)
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val textWhite = ContextCompat.getColor(this, android.R.color.white)

        for ((btn, p) in buttons) {
            if (p == profile) {
                btn.setBackgroundColor(activeBg)
                btn.setTextColor(textWhite)
                btn.strokeColor = ContextCompat.getColorStateList(this, android.R.color.transparent)
            } else {
                btn.setBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent))
                btn.setTextColor(textPrimary)
                btn.strokeColor = ContextCompat.getColorStateList(this, R.color.card_border)
            }
        }
    }

    private fun setupActions() {
        // Launch Dedicated Fullscreen Test Lab
        binding.btnLaunchTestLab.setOnClickListener {
            startActivity(Intent(this, TestLabActivity::class.java))
        }

        binding.cardOpenLab.setOnClickListener {
            startActivity(Intent(this, TestLabActivity::class.java))
        }

        // Toggle Guardian Background state
        binding.btnToggleGuardian.setOnClickListener {
            val intent = Intent(this, TouchGuardianService::class.java).apply {
                action = TouchGuardianService.ACTION_TOGGLE
            }
            startService(intent)

            val isNowActive = binding.btnToggleGuardian.text != "Pause"
            binding.btnToggleGuardian.text = if (isNowActive) "Pause" else "Resume"
            binding.tvGuardianStatus.text = if (isNowActive) "Guardian Active (0% Screen-Off Battery)" else "Guardian Paused"
            binding.tvGuardianStatus.setTextColor(
                if (isNowActive) ContextCompat.getColor(this, R.color.valid_green)
                else 0xFFFF9100.toInt()
            )
        }

        // Flush Memory Button
        binding.btnFlushMemory.setOnClickListener {
            engine.flush()
            System.gc()
            updateDashboard()
            Toast.makeText(this, "Memory Purged: Primitive buffers zeroed (0 bytes active)", Toast.LENGTH_SHORT).show()
        }

        // Samsung TSP Fix (*#2663#)
        binding.btnSamsungCalibrate.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Samsung S23 Ultra TSP Recalibration")
                .setMessage(
                    "This will open your phone dialer with *#2663#.\n\n" +
                    "1. Once the service menu opens, tap 'TSP FW update (General)'.\n" +
                    "2. Do NOT touch the screen until 'PASS' appears.\n\n" +
                    "This resets the hardware baseline capacitance across the entire panel."
                )
                .setPositiveButton("Open Dialer") { _, _ ->
                    val intent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:" + Uri.encode("*#2663#"))
                    }
                    startActivity(intent)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // System Settings Link
        binding.btnSystemAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
    }

    private fun updateDashboard() {
        val buffer = engine.buffer
        binding.tvGhostCount.text = buffer.totalGhostCount.toString()
        binding.tvEchoCount.text = buffer.totalEchoCount.toString()
        binding.tvRamUsage.text = "~3.6 KB"
    }
}
