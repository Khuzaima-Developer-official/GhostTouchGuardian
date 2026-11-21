package com.antigravity.ghosttouchguardian

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Intent
import androidx.core.content.ContextCompat
import com.antigravity.ghosttouchguardian.data.GuardianPreferences
import com.antigravity.ghosttouchguardian.service.HotspotShieldService
import com.antigravity.ghosttouchguardian.service.TouchGuardianService

class GuardianApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val prefs = GuardianPreferences(this)

        // 1. Core Guardian foreground service
        if (prefs.isGuardianEnabled) {
            val guardianIntent = Intent(this, TouchGuardianService::class.java).apply {
                this.action = TouchGuardianService.ACTION_START
            }
            try {
                ContextCompat.startForegroundService(this, guardianIntent)
            } catch (_: Exception) {}
        }

        // 2. Active Touch Shield / S Pen Exclusive overlay service
        // Auto-starts the moment the app process is spawned, even before any activity opens!
        if (prefs.isHotspotShieldEnabled || prefs.isSPenModeActive) {
            val shieldIntent = Intent(this, HotspotShieldService::class.java).apply {
                this.action = if (prefs.isSPenModeActive) {
                    HotspotShieldService.ACTION_ENABLE_SPEN_MODE
                } else {
                    HotspotShieldService.ACTION_START
                }
            }
            try {
                ContextCompat.startForegroundService(this, shieldIntent)
            } catch (_: Exception) {}
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            // Trim memory
            TouchGuardianService.instance?.let { service ->
                service.profiler.flush()
            }
        }
    }
}
