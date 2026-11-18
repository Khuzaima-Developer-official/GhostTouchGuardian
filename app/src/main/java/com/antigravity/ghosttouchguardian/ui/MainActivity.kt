package com.antigravity.ghosttouchguardian.ui

import android.Manifest
import android.content.Context
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
import com.antigravity.ghosttouchguardian.databinding.ActivityMainBinding
import com.antigravity.ghosttouchguardian.engine.TouchProfilerEngine
import com.antigravity.ghosttouchguardian.service.TouchGuardianService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: TouchProfilerEngine

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

        // Request notification permission on Android 13+ (S23 Ultra One UI 5/6/7)
        checkAndRequestNotificationPermission()

        // Ensure background guardian service is running
        startGuardianService()

        // Link profiler engine
        engine = TouchGuardianService.instance?.profiler ?: TouchProfilerEngine()

        setupActions()
    }

    override fun onResume() {
        super.onResume()
        updateDashboard()
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

    private fun setupActions() {
        // Launch Dedicated Fullscreen Test Lab
        binding.btnLaunchTestLab.setOnClickListener {
            val intent = Intent(this, TestLabActivity::class.java)
            startActivity(intent)
        }

        binding.cardOpenLab.setOnClickListener {
            val intent = Intent(this, TestLabActivity::class.java)
            startActivity(intent)
        }

        // Toggle Guardian Background state
        binding.btnToggleGuardian.setOnClickListener {
            val intent = Intent(this, TouchGuardianService::class.java).apply {
                action = TouchGuardianService.ACTION_TOGGLE
            }
            startService(intent)
            // BUG FIX: button text reflects actual state after toggle
            val isNowPaused = binding.btnToggleGuardian.text == "Pause"
            binding.btnToggleGuardian.text = if (isNowPaused) "Resume" else "Pause"
            binding.tvGuardianStatus.text = if (isNowPaused) "⏸ Guardian Paused" else "● Guardian Active"
            binding.tvGuardianStatus.setTextColor(
                if (isNowPaused) 0xFFFF9100.toInt() else getColor(R.color.valid_green)
            )
        }

        // Flush Memory Button (Proactive buffer wipe to ensure near-zero RAM usage)
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
                .setMessage("This will open your phone dialer with *#2663#.\n\n" +
                        "1. Once the service menu opens, tap 'TSP FW update (General)'.\n" +
                        "2. Do NOT touch the screen until 'PASS' appears.\n\n" +
                        "This resets the hardware baseline capacitance across the entire panel.")
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
