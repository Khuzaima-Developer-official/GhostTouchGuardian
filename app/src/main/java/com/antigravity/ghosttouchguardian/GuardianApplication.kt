package com.antigravity.ghosttouchguardian

import android.app.Application
import android.content.ComponentCallbacks2
import com.antigravity.ghosttouchguardian.service.TouchGuardianService

/**
 * Global Application container with proactive memory trimming.
 */
class GuardianApplication : Application() {

    override fun onCreate() {
        super.onCreate()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            // Aggressively flush any retained buffers in memory
            TouchGuardianService.instance?.let { service ->
                service.profiler.flush()
            }
        }
    }
}
