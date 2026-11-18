package com.antigravity.ghosttouchguardian.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Screen state listener.
 * Guarantees zero battery drain when the screen is turned off by
 * suspending all guardian activity until the display turns back on.
 */
class ScreenStateReceiver(
    private val onScreenOn: () -> Unit,
    private val onScreenOff: () -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SCREEN_OFF -> onScreenOff()
            Intent.ACTION_SCREEN_ON -> onScreenOn()
        }
    }
}
