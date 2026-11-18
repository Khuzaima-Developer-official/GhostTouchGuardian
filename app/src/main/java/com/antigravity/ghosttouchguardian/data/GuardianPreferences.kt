package com.antigravity.ghosttouchguardian.data

import android.content.Context
import android.content.SharedPreferences
import com.antigravity.ghosttouchguardian.model.GhostTouchProfile

/**
 * Lightweight, zero-overhead SharedPreferences manager for Guardian settings.
 * Ensures protection parameters persist when app is closed, killed, or phone reboots.
 */
class GuardianPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var currentProfile: GhostTouchProfile
        get() {
            val id = prefs.getString(KEY_PROFILE_ID, GhostTouchProfile.BALANCED.id) ?: GhostTouchProfile.BALANCED.id
            return GhostTouchProfile.fromId(id)
        }
        set(value) {
            prefs.edit().putString(KEY_PROFILE_ID, value.id).apply()
        }

    var customDwellMs: Int
        get() = prefs.getInt(KEY_CUSTOM_DWELL, 35)
        set(value) = prefs.edit().putInt(KEY_CUSTOM_DWELL, value).apply()

    var customEchoMs: Long
        get() = prefs.getLong(KEY_CUSTOM_ECHO, 55L)
        set(value) = prefs.edit().putLong(KEY_CUSTOM_ECHO, value).apply()

    var customSizeThreshold: Float
        get() = prefs.getFloat(KEY_CUSTOM_SIZE, 0.045f)
        set(value) = prefs.edit().putFloat(KEY_CUSTOM_SIZE, value).apply()

    var isGuardianEnabled: Boolean
        get() = prefs.getBoolean(KEY_GUARDIAN_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_GUARDIAN_ENABLED, value).apply()

    var totalBlockedGhosts: Long
        get() = prefs.getLong(KEY_BLOCKED_GHOSTS, 0L)
        set(value) = prefs.edit().putLong(KEY_BLOCKED_GHOSTS, value).apply()

    var totalBlockedEchoes: Long
        get() = prefs.getLong(KEY_BLOCKED_ECHOES, 0L)
        set(value) = prefs.edit().putLong(KEY_BLOCKED_ECHOES, value).apply()

    fun incrementBlockedCount(isEcho: Boolean) {
        val currentGhosts = totalBlockedGhosts + 1
        val currentEchoes = if (isEcho) totalBlockedEchoes + 1 else totalBlockedEchoes
        prefs.edit()
            .putLong(KEY_BLOCKED_GHOSTS, currentGhosts)
            .putLong(KEY_BLOCKED_ECHOES, currentEchoes)
            .apply()
    }

    companion object {
        private const val PREF_NAME = "ghost_touch_guardian_prefs"
        private const val KEY_PROFILE_ID = "pref_profile_id"
        private const val KEY_CUSTOM_DWELL = "pref_custom_dwell"
        private const val KEY_CUSTOM_ECHO = "pref_custom_echo"
        private const val KEY_CUSTOM_SIZE = "pref_custom_size"
        private const val KEY_GUARDIAN_ENABLED = "pref_guardian_enabled"
        private const val KEY_BLOCKED_GHOSTS = "pref_blocked_ghosts"
        private const val KEY_BLOCKED_ECHOES = "pref_blocked_echoes"
    }
}
