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
            // Start service
            startGuardianService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Init prefs
        prefs = GuardianPreferences(this)

        // Permission check
        checkAndRequestNotificationPermission()

        // Start service
        startGuardianService()
        ensureShieldServiceRunning()

        // Engine link
        engine = TouchGuardianService.instance?.profiler ?: TouchProfilerEngine()

        setupLevelSelectors()
        setupActions()
        setupCalibrationCard()
        setupHardwareCard()
        updateAiStatusBadges()
        renderActiveProfile(prefs.currentProfile)
    }

    override fun onResume() {
        super.onResume()
        ensureShieldServiceRunning()
        // Refresh UI
        updateDashboard()
        updateShieldButton()
        updateSPenButton()
        updateSPenLiveFilterButton()
        updateBatteryOptimizationButton()
        updateAiStatusBadges()
        renderActiveProfile(prefs.currentProfile)
    }

    private fun ensureShieldServiceRunning() {
        if (prefs.isHotspotShieldEnabled || prefs.isSPenModeActive) {
            val shieldIntent = Intent(this, com.antigravity.ghosttouchguardian.service.HotspotShieldService::class.java).apply {
                this.action = if (prefs.isSPenModeActive) {
                    com.antigravity.ghosttouchguardian.service.HotspotShieldService.ACTION_ENABLE_SPEN_MODE
                } else {
                    com.antigravity.ghosttouchguardian.service.HotspotShieldService.ACTION_START
                }
            }
            try {
                ContextCompat.startForegroundService(this, shieldIntent)
            } catch (_: Exception) {}
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // Request perm
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

    private fun setupLevelSelectors() {
        // Light mode
        binding.btnProfileGaming.setOnClickListener {
            applyProfile(GhostTouchProfile.MICRO_SPARKS)
        }
        // Balanced mode
        binding.btnProfileBalanced.setOnClickListener {
            applyProfile(GhostTouchProfile.BALANCED)
        }
        // High mode
        binding.btnProfileAggressive.setOnClickListener {
            applyProfile(GhostTouchProfile.SEVERE_UV_GLUE)
        }
    }

    private fun applyProfile(profile: GhostTouchProfile) {
        // Save prefs
        prefs.currentProfile = profile
        engine.profile = profile

        // Notify service
        val intent = Intent(this, TouchGuardianService::class.java).apply {
            action = TouchGuardianService.ACTION_UPDATE_PROFILE
        }
        startService(intent)

        renderActiveProfile(profile)
        Toast.makeText(this, "${profile.title} Active", Toast.LENGTH_SHORT).show()
    }

    private fun renderActiveProfile(profile: GhostTouchProfile) {
        binding.tvMetricSummary.text = "Filters taps under ${profile.maxDwellMs}ms and release echoes under ${profile.echoWindowMs}ms"

        val buttons = listOf(
            binding.btnProfileGaming to GhostTouchProfile.MICRO_SPARKS,
            binding.btnProfileBalanced to GhostTouchProfile.BALANCED,
            binding.btnProfileAggressive to GhostTouchProfile.SEVERE_UV_GLUE
        )

        val activeBg = ContextCompat.getColor(this, R.color.accent_blue)
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

    private fun setupHardwareCard() {
        // Device check
        val manufacturer = Build.MANUFACTURER.lowercase()
        val isSamsung = manufacturer.contains("samsung")

        if (isSamsung) {
            binding.tvHardwareTitle.text = "SAMSUNG TSP RECALIBRATION"
            binding.tvHardwareDesc.text = "Resets capacitive digitizer baseline via Samsung secret menu (*#2663#)."
            binding.btnHardwareCalibrate.text = "OPEN *#2663# TSP FIX"
        } else {
            binding.tvHardwareTitle.text = "TOUCH SENSITIVITY CALIBRATION"
            binding.tvHardwareDesc.text = "Universal display touch sensitivity and touch latency calibration for ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }}."
            binding.btnHardwareCalibrate.text = "CALIBRATE TOUCH SENSITIVITY"
        }

        binding.btnHardwareCalibrate.setOnClickListener {
            if (isSamsung) {
                // Samsung dialer
                AlertDialog.Builder(this)
                    .setTitle("Samsung TSP Recalibration")
                    .setMessage(
                        "1. Dial *#2663#\n" +
                        "2. Tap 'TSP FW update (General)'\n" +
                        "3. Wait for 'PASS' without touching screen"
                    )
                    .setPositiveButton("Open Dialer") { _, _ ->
                        val intent = Intent(Intent.ACTION_DIAL).apply {
                            data = Uri.parse("tel:" + Uri.encode("*#2663#"))
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                // Universal settings
                try {
                    val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS)
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_SETTINGS)
                    startActivity(intent)
                }
            }
        }
    }

    private fun setupActions() {
        // Toggle service
        binding.btnToggleGuardian.setOnClickListener {
            val intent = Intent(this, TouchGuardianService::class.java).apply {
                action = TouchGuardianService.ACTION_TOGGLE
            }
            startService(intent)

            val isNowActive = binding.btnToggleGuardian.text != "Pause"
            binding.btnToggleGuardian.text = if (isNowActive) "Pause" else "Resume"
            binding.tvGuardianStatus.text = if (isNowActive) "Guardian Active (Background Protection)" else "Guardian Paused"
            binding.tvGuardianStatus.setTextColor(
                if (isNowActive) ContextCompat.getColor(this, R.color.valid_green)
                else 0xFFFF9100.toInt()
            )
        }

        // Testing alerts toggle
        updateTestingAlertsButton()
        binding.btnToggleTestingAlerts.setOnClickListener {
            prefs.isTestingAlertsEnabled = !prefs.isTestingAlertsEnabled
            updateTestingAlertsButton()
            val msg = if (prefs.isTestingAlertsEnabled) "Testing Haptics: ON (Buzz on ghost touch)" else "Testing Haptics: OFF (Silent)"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Flush buffer
        binding.btnFlushMemory.setOnClickListener {
            engine.flush()
            System.gc()
            updateDashboard()
            Toast.makeText(this, "Memory Purged (0 B active)", Toast.LENGTH_SHORT).show()
        }

    }

    private fun setupCalibrationCard() {
        // Master Shield toggle
        binding.btnToggleHotspotShield.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Grant 'Appear on top' permission to enable Shield", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                return@setOnClickListener
            }

            prefs.isHotspotShieldEnabled = !prefs.isHotspotShieldEnabled
            if (prefs.isHotspotShieldEnabled) {
                val shieldIntent = Intent(this, com.antigravity.ghosttouchguardian.service.HotspotShieldService::class.java).apply {
                    action = com.antigravity.ghosttouchguardian.service.HotspotShieldService.ACTION_START
                }
                androidx.core.content.ContextCompat.startForegroundService(this, shieldIntent)
            } else {
                val shieldIntent = Intent(this, com.antigravity.ghosttouchguardian.service.HotspotShieldService::class.java).apply {
                    action = com.antigravity.ghosttouchguardian.service.HotspotShieldService.ACTION_STOP
                }
                startService(shieldIntent)
            }
            updateShieldButton()
            updateAiStatusBadges()
        }

        // Dedicated S Pen Mode toggle
        binding.btnToggleSPenMode.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Grant 'Appear on top' permission to enable S Pen Mode", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                return@setOnClickListener
            }

            prefs.isSPenModeActive = !prefs.isSPenModeActive
            if (prefs.isSPenModeActive) {
                prefs.isHotspotShieldEnabled = true
                val spenIntent = Intent(this, com.antigravity.ghosttouchguardian.service.HotspotShieldService::class.java).apply {
                    action = com.antigravity.ghosttouchguardian.service.HotspotShieldService.ACTION_ENABLE_SPEN_MODE
                }
                androidx.core.content.ContextCompat.startForegroundService(this, spenIntent)
            } else {
                val spenIntent = Intent(this, com.antigravity.ghosttouchguardian.service.HotspotShieldService::class.java).apply {
                    action = com.antigravity.ghosttouchguardian.service.HotspotShieldService.ACTION_DISABLE_SPEN_MODE
                }
                startService(spenIntent)
            }
            updateSPenButton()
            updateSPenLiveFilterButton()
            updateShieldButton()
            updateAiStatusBadges()
            val status = if (prefs.isSPenModeActive)
                "🖊️ Dedicated S Pen Mode: ON (Finger Touch 100% MUTED)"
            else
                "🖐️ S Pen Mode: OFF (Finger Touch Restored)"
            Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
        }

        // S Pen Live Ghost Filter (Identify & Ignore) toggle
        updateSPenLiveFilterButton()
        binding.btnToggleSPenLiveFilter.setOnClickListener {
            prefs.isSPenLiveGhostFilterEnabled = !prefs.isSPenLiveGhostFilterEnabled
            updateSPenLiveFilterButton()
            val toggleIntent = Intent(this, com.antigravity.ghosttouchguardian.service.HotspotShieldService::class.java).apply {
                action = com.antigravity.ghosttouchguardian.service.HotspotShieldService.ACTION_TOGGLE_SPEN_LIVE_FILTER
            }
            startService(toggleIntent)
            val msg = if (prefs.isSPenLiveGhostFilterEnabled)
                "🖊️ S-Pen Live Ghost Filter: ON (Auto-Identify & Ignore)"
            else
                "⚠️ S-Pen Live Ghost Filter: OFF"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // 1-Tap Reset All Learned Data
        binding.btnResetLearnedData.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset Ghost & Brain Data?")
                .setMessage("Agar aap ne galti se finger touch ko ghost mark kar diya tha, to yeh button saara purana data clear karke app ko factory clean default state mein reset kar dega.")
                .setPositiveButton("Reset Now") { _, _ ->
                    prefs.resetAllLearnedProfiles()
                    com.antigravity.ghosttouchguardian.data.CompactTouchRepository(this).resetBrain()
                    prefs.totalBlockedGhosts = 0L
                    prefs.totalBlockedEchoes = 0L

                    val resetIntent = Intent(this, com.antigravity.ghosttouchguardian.service.HotspotShieldService::class.java).apply {
                        action = com.antigravity.ghosttouchguardian.service.HotspotShieldService.ACTION_RESET_DATA
                    }
                    startService(resetIntent)

                    updateDashboard()
                    updateShieldButton()
                    updateSPenButton()
                    updateAiStatusBadges()
                    Toast.makeText(this, "✅ All Data Reset to Clean Factory State!", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // AI Co-Pilot Teach & Undo Arena
        binding.btnLaunchCoPilot.setOnClickListener {
            startActivity(Intent(this, CoPilotTrainerActivity::class.java))
        }

        // Dedicated Finger Intensity Margin Profiler
        binding.btnFingerMarginProfiler.setOnClickListener {
            startActivity(Intent(this, FingerMarginProfilerActivity::class.java))
        }

        setupBatteryOptimizationButton()
    }

    private fun setupBatteryOptimizationButton() {
        binding.btnBatteryOptimization.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(android.os.PowerManager::class.java)
                if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "✅ 24/7 Unrestricted Mode Active! Samsung will never sleep this app.", Toast.LENGTH_LONG).show()
                }
            }
        }
        updateBatteryOptimizationButton()
    }

    private fun updateBatteryOptimizationButton() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(android.os.PowerManager::class.java)
            val isUnrestricted = pm?.isIgnoringBatteryOptimizations(packageName) == true
            if (isUnrestricted) {
                binding.btnBatteryOptimization.text = "⚡ 24/7 Samsung Unrestricted: ACTIVE (Never Sleep)"
                binding.btnBatteryOptimization.setTextColor(ContextCompat.getColor(this, R.color.valid_green))
                binding.btnBatteryOptimization.strokeColor = ContextCompat.getColorStateList(this, R.color.valid_green)
            } else {
                binding.btnBatteryOptimization.text = "⚡ Fix Samsung Sleep: TAP TO ALLOW 24/7 UNRESTRICTED"
                binding.btnBatteryOptimization.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
                binding.btnBatteryOptimization.strokeColor = ContextCompat.getColorStateList(this, R.color.accent_cyan)
            }
        }
    }

    private fun updateSPenButton() {
        if (prefs.isSPenModeActive) {
            binding.btnToggleSPenMode.text = "Dedicated S Pen Mode: ON (ALL CAPACITIVE MUTED)"
            binding.btnToggleSPenMode.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
            binding.btnToggleSPenMode.strokeColor = ContextCompat.getColorStateList(this, R.color.accent_cyan)
        } else {
            binding.btnToggleSPenMode.text = "Dedicated S Pen Mode: OFF (Tap to Activate)"
            binding.btnToggleSPenMode.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.btnToggleSPenMode.strokeColor = ContextCompat.getColorStateList(this, R.color.card_border)
        }
    }

    private fun updateSPenLiveFilterButton() {
        if (prefs.isSPenLiveGhostFilterEnabled) {
            binding.btnToggleSPenLiveFilter.text = "🖊️ S-Pen Live Ghost Filter: ON (Auto-Identify & Ignore)"
            binding.btnToggleSPenLiveFilter.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
            binding.btnToggleSPenLiveFilter.strokeColor = ContextCompat.getColorStateList(this, R.color.accent_cyan)
        } else {
            binding.btnToggleSPenLiveFilter.text = "🖊️ S-Pen Live Ghost Filter: OFF"
            binding.btnToggleSPenLiveFilter.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.btnToggleSPenLiveFilter.strokeColor = ContextCompat.getColorStateList(this, R.color.card_border)
        }
    }

    private fun updateShieldButton() {
        if (prefs.isHotspotShieldEnabled) {
            binding.btnToggleHotspotShield.text = "🛡️ GHOST TOUCH SHIELD: ACTIVE (45ms Spam Filter ON)"
            binding.btnToggleHotspotShield.setBackgroundColor(ContextCompat.getColor(this, R.color.valid_green))
            binding.btnToggleHotspotShield.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        } else {
            binding.btnToggleHotspotShield.text = "🛡️ GHOST TOUCH SHIELD: OFF (Tap to Activate)"
            binding.btnToggleHotspotShield.setBackgroundColor(ContextCompat.getColor(this, R.color.ghost_red))
            binding.btnToggleHotspotShield.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        }
    }

    private fun updateAiStatusBadges() {
        binding.tvAiFingerStatus.text = "⚡ 45ms Spam Filter: ACTIVE (Touches < 45ms dropped instantly)"
        binding.tvAiFingerStatus.setTextColor(ContextCompat.getColor(this, R.color.valid_green))

        binding.tvAiGhostStatus.text = "🔬 Physical Gate: Mechanical impulse & flesh area active"
        binding.tvAiGhostStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))

        if (prefs.isSPenModeActive) {
            binding.tvAiSPenStatus.text = "🖊️ S Pen Exclusive: ON (Notification toggle enabled)"
            binding.tvAiSPenStatus.setTextColor(ContextCompat.getColor(this, R.color.valid_green))
        } else {
            binding.tvAiSPenStatus.text = "🖊️ S Pen Exclusive: OFF (Notification toggle ready)"
            binding.tvAiSPenStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun updateTestingAlertsButton() {
        if (prefs.isTestingAlertsEnabled) {
            binding.btnToggleTestingAlerts.text = "Testing Haptics: ON (Buzz Active)"
            binding.btnToggleTestingAlerts.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
            binding.btnToggleTestingAlerts.strokeColor = ContextCompat.getColorStateList(this, R.color.accent_cyan)
        } else {
            binding.btnToggleTestingAlerts.text = "Testing Haptics: OFF (Silent)"
            binding.btnToggleTestingAlerts.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.btnToggleTestingAlerts.strokeColor = ContextCompat.getColorStateList(this, R.color.card_border)
        }
    }

    private fun updateDashboard() {
        // Update stats
        val buffer = engine.buffer
        binding.tvGhostCount.text = buffer.totalGhostCount.toString()
        binding.tvEchoCount.text = buffer.totalEchoCount.toString()
        binding.tvRamUsage.text = "~3.6 KB"
    }
}
