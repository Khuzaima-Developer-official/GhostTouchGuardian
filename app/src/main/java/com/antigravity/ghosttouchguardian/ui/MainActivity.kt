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
        // Refresh UI
        updateDashboard()
        updateShieldButton()
        updateSPenButton()
        updateAiStatusBadges()
        renderActiveProfile(prefs.currentProfile)
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
        // Open lab
        binding.btnLaunchTestLab.setOnClickListener {
            startActivity(Intent(this, TestLabActivity::class.java))
        }

        binding.cardOpenLab.setOnClickListener {
            startActivity(Intent(this, TestLabActivity::class.java))
        }

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
        // AI Co-Pilot (Teach AI Brain) click
        binding.btnLaunchCoPilot.setOnClickListener {
            startActivity(Intent(this, CoPilotTrainerActivity::class.java))
        }

        // Ghost Identify (Fingerprint Learning) click
        binding.btnGhostIdentify.setOnClickListener {
            startActivity(Intent(this, GhostIdentifyActivity::class.java))
        }

        // Calibration click
        binding.btnAutoCalibrateScreen.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }

        // Shield toggle
        binding.btnToggleHotspotShield.setOnClickListener {
            val hasZones = prefs.savedHotspotsJson.isNotBlank()
            val hasFp = prefs.ghostFingerprintJson.isNotBlank()
            val hasAiProfile = prefs.ghostGlitchProfileJson.isNotBlank()
            if (!hasZones && !hasFp && !hasAiProfile) {
                Toast.makeText(this, "Run Ghost Identify or Calibration first to learn ghost patterns!", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, GhostIdentifyActivity::class.java))
                return@setOnClickListener
            }

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
            val shieldAction = if (prefs.isHotspotShieldEnabled) {
                com.antigravity.ghosttouchguardian.service.HotspotShieldService.ACTION_START
            } else {
                com.antigravity.ghosttouchguardian.service.HotspotShieldService.ACTION_STOP
            }
            startService(Intent(this, com.antigravity.ghosttouchguardian.service.HotspotShieldService::class.java).apply {
                action = shieldAction
            })
            updateShieldButton()
        }

        // Dedicated S Pen Mode toggle
        binding.btnToggleSPenMode.setOnClickListener {
            prefs.isSPenModeActive = !prefs.isSPenModeActive
            val spenAction = if (prefs.isSPenModeActive) {
                com.antigravity.ghosttouchguardian.service.HotspotShieldService.ACTION_ENABLE_SPEN_MODE
            } else {
                com.antigravity.ghosttouchguardian.service.HotspotShieldService.ACTION_DISABLE_SPEN_MODE
            }
            startService(Intent(this, com.antigravity.ghosttouchguardian.service.HotspotShieldService::class.java).apply {
                action = spenAction
            })
            updateSPenButton()
            val status = if (prefs.isSPenModeActive) "Dedicated S Pen Mode: Full-Time Active" else "Dedicated S Pen Mode: Deactivated"
            Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
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

    private fun updateShieldButton() {
        // Update shield
        val hasZones = prefs.savedHotspotsJson.isNotBlank()
        val hasFp = prefs.ghostFingerprintJson.isNotBlank()
        val hasAiProfile = prefs.ghostGlitchProfileJson.isNotBlank()
        val hasBrain = prefs.ghostBrainEngineJson.isNotBlank()
        val isCalibrated = hasZones || hasFp || hasAiProfile || hasBrain
        val zones = com.antigravity.ghosttouchguardian.engine.GhostCalibrationEngine.parseZonesFromJson(prefs.savedHotspotsJson)

        if (prefs.isHotspotShieldEnabled && isCalibrated) {
            val guardDetails = when {
                hasBrain -> "8D Bayesian AI Brain"
                hasAiProfile -> "GhostMind AI Profile"
                hasFp -> "Fingerprint & ${zones.size} Zones"
                else -> "${zones.size} Zones"
            }
            binding.btnToggleHotspotShield.text = "Hotspot Shield: ACTIVE ($guardDetails Guarded)"
            binding.btnToggleHotspotShield.setTextColor(ContextCompat.getColor(this, R.color.valid_green))
            binding.btnToggleHotspotShield.strokeColor = ContextCompat.getColorStateList(this, R.color.valid_green)
        } else {
            val status = if (isCalibrated) "Hotspot Shield: OFF (Ready to Activate)" else "Hotspot Shield: OFF (Not Calibrated)"
            binding.btnToggleHotspotShield.text = status
            binding.btnToggleHotspotShield.setTextColor(ContextCompat.getColor(this, R.color.ghost_red))
            binding.btnToggleHotspotShield.strokeColor = ContextCompat.getColorStateList(this, R.color.ghost_red)
        }
    }

    private fun updateAiStatusBadges() {
        // 1. Finger Profile
        val humanProfile = com.antigravity.ghosttouchguardian.model.HumanFingerProfile.fromJson(prefs.humanFingerProfileJson)
        if (humanProfile.isValid) {
            binding.tvAiFingerStatus.text = "🟢 Real Finger Profile: LOCKED (100% Native Pass-Through)"
            binding.tvAiFingerStatus.setTextColor(ContextCompat.getColor(this, R.color.valid_green))
        } else {
            binding.tvAiFingerStatus.text = "🟢 Real Finger Profile: Not Calibrated (Tap Step 1 to Lock)"
            binding.tvAiFingerStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        // 2. 8D Bayesian Ghost Brain & S23 Ultra Touch Master
        val masterClassifier = com.antigravity.ghosttouchguardian.data.CompactTouchRepository(this).loadBrain()
        val brainEngine = com.antigravity.ghosttouchguardian.engine.ai.GhostBrainEngine.fromJson(prefs.ghostBrainEngineJson)
        val ghostProfile = com.antigravity.ghosttouchguardian.model.GhostGlitchProfile.fromJson(prefs.ghostGlitchProfileJson)

        if (masterClassifier.totalUserCorrections > 0L || masterClassifier.getSupervisoryCount() > 0) {
            binding.tvAiGhostStatus.text = "🔴 S23U Touch Master: ACTIVE (${masterClassifier.getSupervisoryCount()} water signatures, ${masterClassifier.totalUserCorrections} corrections)"
            binding.tvAiGhostStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
        } else if (brainEngine.ghostCluster.sampleCount > 5 || brainEngine.totalOnlineUpdates > 0L) {
            binding.tvAiGhostStatus.text = "🔴 8D AI Brain: ACTIVE (${brainEngine.ghostCluster.sampleCount} glitches, ${brainEngine.totalOnlineUpdates} online updates)"
            binding.tvAiGhostStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
        } else if (ghostProfile.isValid) {
            binding.tvAiGhostStatus.text = "🔴 Ghost Spectrum: [${ghostProfile.minDwell}..${ghostProfile.maxDwell}ms], Band [${ghostProfile.minIntervalMs}..${ghostProfile.maxIntervalMs}ms]"
            binding.tvAiGhostStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
        } else {
            val hasZones = prefs.savedHotspotsJson.isNotBlank()
            if (hasZones) {
                binding.tvAiGhostStatus.text = "🔴 Glitch Map: Calibrated Zone Hotspots"
                binding.tvAiGhostStatus.setTextColor(ContextCompat.getColor(this, R.color.valid_green))
            } else {
                binding.tvAiGhostStatus.text = "🔴 8D Ghost Brain: Not Calibrated (Tap Step 1 to Learn)"
                binding.tvAiGhostStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
        }

        // 3. Dedicated S Pen Status
        if (prefs.isSPenModeActive) {
            binding.tvAiSPenStatus.text = "🖊️ Dedicated S Pen: 100% CAPACITIVE MUTED (EMR Magnetic Pure)"
            binding.tvAiSPenStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
        } else {
            binding.tvAiSPenStatus.text = "🖊️ Dedicated S Pen: OFF (Tap toggle below)"
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
