package com.antigravity.ghosttouchguardian.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Boot check
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, TouchGuardianService::class.java).apply {
                action = TouchGuardianService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Foreground start
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                // Background start
                context.startService(serviceIntent)
            }
        }
    }
}
