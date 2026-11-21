package com.antigravity.ghosttouchguardian.service.shield

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * GhostHapticFeedback — Single-Task Component for Throttled Haptic Alerts.
 *
 * Responsibilities:
 * - Triggers subtle haptic feedback when a ghost touch or human tap is filtered.
 * - Strictly throttles vibrations (max once per 250ms) to prevent UI thread IPC stalls during rapid storms.
 */
class GhostHapticFeedback(private val context: Context) {

    private var lastHapticTime: Long = 0L

    fun triggerAlert(isGhost: Boolean) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastHapticTime < 250L) return // Throttled: prevents IPC freeze
        lastHapticTime = now

        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val duration = if (isGhost) 35L else 15L
                val effect = VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(if (isGhost) 35L else 15L)
            }
        } catch (_: Exception) {}
    }
}
