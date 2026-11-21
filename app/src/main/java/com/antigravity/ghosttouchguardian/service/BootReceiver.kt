package com.antigravity.ghosttouchguardian.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.antigravity.ghosttouchguardian.data.GuardianPreferences

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_PRESENT,
            "android.intent.action.QUICKBOOT_POWERON"
        )

        if (action in validActions) {
            val prefs = GuardianPreferences(context)

            // 1. Start core Guardian Service
            if (prefs.isGuardianEnabled) {
                val serviceIntent = Intent(context, TouchGuardianService::class.java).apply {
                    this.action = TouchGuardianService.ACTION_START
                }
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (_: Exception) {}
            }

            // 2. Auto-revive Hotspot Shield / S Pen Exclusive overlay if enabled
            if (prefs.isHotspotShieldEnabled || prefs.isSPenModeActive) {
                val shieldIntent = Intent(context, HotspotShieldService::class.java).apply {
                    this.action = if (prefs.isSPenModeActive) {
                        HotspotShieldService.ACTION_ENABLE_SPEN_MODE
                    } else {
                        HotspotShieldService.ACTION_START
                    }
                }
                try {
                    ContextCompat.startForegroundService(context, shieldIntent)
                } catch (_: Exception) {}
            }
        }
    }
}
