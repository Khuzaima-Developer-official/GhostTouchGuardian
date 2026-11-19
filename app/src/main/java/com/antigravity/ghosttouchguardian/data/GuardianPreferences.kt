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

    companion object {
        private const val PREF_NAME = "ghost_touch_guardian_prefs"
        private const val KEY_PROFILE_ID = "pref_profile_id"
        private const val KEY_GUARDIAN_ENABLED = "pref_guardian_enabled"
        private const val KEY_BLOCKED_GHOSTS = "pref_blocked_ghosts"
        private const val KEY_BLOCKED_ECHOES = "pref_blocked_echoes"
        private const val KEY_TESTING_ALERTS_ENABLED = "pref_testing_alerts_enabled"
    }
}
