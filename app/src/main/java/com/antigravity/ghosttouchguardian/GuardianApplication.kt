package com.antigravity.ghosttouchguardian

import android.app.Application
import android.content.ComponentCallbacks2
import com.antigravity.ghosttouchguardian.service.TouchGuardianService

class GuardianApplication : Application() {

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
