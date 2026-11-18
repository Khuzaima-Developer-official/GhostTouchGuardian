package com.antigravity.ghosttouchguardian.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * Deep Accessibility Integration for Samsung Galaxy devices.
 * Integrates directly with Phone Settings -> Accessibility.
 */
class TouchGuardianAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Ensure background guardian service is running
        val serviceIntent = Intent(this, TouchGuardianService::class.java).apply {
            action = TouchGuardianService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Ultra-lean event handling: do not retain event objects in heap to keep RAM footprint near zero
    }

    override fun onInterrupt() {
        // Accessibility service interrupted by system
    }
}
