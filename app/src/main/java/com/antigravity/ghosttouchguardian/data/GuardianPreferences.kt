package com.antigravity.ghosttouchguardian.data

import android.content.Context
import android.content.SharedPreferences
import com.antigravity.ghosttouchguardian.model.GhostTouchProfile

class GuardianPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var currentProfile: GhostTouchProfile
        get() {
            // Read profile
            val id = prefs.getString(KEY_PROFILE_ID, GhostTouchProfile.BALANCED.id) ?: GhostTouchProfile.BALANCED.id
            return GhostTouchProfile.fromId(id)
        }
        set(value) {
            // Save profile
            prefs.edit().putString(KEY_PROFILE_ID, value.id).apply()
        }

    var isGuardianEnabled: Boolean
        get() = prefs.getBoolean(KEY_GUARDIAN_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_GUARDIAN_ENABLED, value).apply()

    var totalBlockedGhosts: Long
        get() = prefs.getLong(KEY_BLOCKED_GHOSTS, 0L)
        set(value) = prefs.edit().putLong(KEY_BLOCKED_GHOSTS, value).apply()

    var totalBlockedEchoes: Long
        get() = prefs.getLong(KEY_BLOCKED_ECHOES, 0L)
        set(value) = prefs.edit().putLong(KEY_BLOCKED_ECHOES, value).apply()

    var isTestingAlertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TESTING_ALERTS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_TESTING_ALERTS_ENABLED, value).apply()

    var savedHotspotsJson: String
        get() = prefs.getString(KEY_SAVED_HOTSPOTS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SAVED_HOTSPOTS, value).apply()

    var isHotspotShieldEnabled: Boolean
        get() = prefs.getBoolean(KEY_HOTSPOT_SHIELD_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_HOTSPOT_SHIELD_ENABLED, value).apply()

    var calibratedAvgPeriodMs: Long
        get() = prefs.getLong(KEY_CALIBRATED_AVG_PERIOD, 0L)
        set(value) = prefs.edit().putLong(KEY_CALIBRATED_AVG_PERIOD, value).apply()

    var calibratedVariancePercent: Float
        get() = prefs.getFloat(KEY_CALIBRATED_VARIANCE, 100f)
        set(value) = prefs.edit().putFloat(KEY_CALIBRATED_VARIANCE, value).apply()

    // Ghost Fingerprint (from Identify Mode)
    var ghostFingerprintJson: String
        get() = prefs.getString(KEY_GHOST_FINGERPRINT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GHOST_FINGERPRINT, value).apply()

    var isGhostFingerprintEnabled: Boolean
        get() = prefs.getBoolean(KEY_FINGERPRINT_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_FINGERPRINT_ENABLED, value).apply()

    // v2.0 GhostMind AI Profiles
    var humanFingerProfileJson: String
        get() = prefs.getString(KEY_HUMAN_FINGER_PROFILE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HUMAN_FINGER_PROFILE, value).apply()

    var ghostGlitchProfileJson: String
        get() = prefs.getString(KEY_GHOST_GLITCH_PROFILE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GHOST_GLITCH_PROFILE, value).apply()

    // v3.0 8D Probabilistic Bayesian AI Brain Engine
    var ghostBrainEngineJson: String
        get() = prefs.getString(KEY_GHOST_BRAIN_ENGINE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GHOST_BRAIN_ENGINE, value).apply()

    var isSPenModeActive: Boolean
        get() = prefs.getBoolean(KEY_SPEN_MODE_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_SPEN_MODE_ACTIVE, value).apply()

    var isFingerShieldModeActive: Boolean
        get() = prefs.getBoolean(KEY_FINGER_SHIELD_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_FINGER_SHIELD_MODE, value).apply()

    companion object {
        private const val PREF_NAME = "ghost_touch_guardian_prefs"
        private const val KEY_PROFILE_ID = "pref_profile_id"
        private const val KEY_GUARDIAN_ENABLED = "pref_guardian_enabled"
        private const val KEY_BLOCKED_GHOSTS = "pref_blocked_ghosts"
        private const val KEY_BLOCKED_ECHOES = "pref_blocked_echoes"
        private const val KEY_TESTING_ALERTS_ENABLED = "pref_testing_alerts_enabled"
        private const val KEY_SAVED_HOTSPOTS = "pref_saved_hotspots"
        private const val KEY_HOTSPOT_SHIELD_ENABLED = "pref_hotspot_shield_enabled"
        private const val KEY_CALIBRATED_AVG_PERIOD = "pref_calibrated_avg_period"
        private const val KEY_CALIBRATED_VARIANCE = "pref_calibrated_variance"
        private const val KEY_GHOST_FINGERPRINT = "pref_ghost_fingerprint"
        private const val KEY_FINGERPRINT_ENABLED = "pref_fingerprint_enabled"
        private const val KEY_HUMAN_FINGER_PROFILE = "pref_human_finger_profile"
        private const val KEY_GHOST_GLITCH_PROFILE = "pref_ghost_glitch_profile"
        private const val KEY_GHOST_BRAIN_ENGINE = "pref_ghost_brain_engine"
        private const val KEY_SPEN_MODE_ACTIVE = "pref_spen_mode_active"
        private const val KEY_FINGER_SHIELD_MODE = "pref_finger_shield_mode"
    }
}
