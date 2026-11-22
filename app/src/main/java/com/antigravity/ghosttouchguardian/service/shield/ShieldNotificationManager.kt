package com.antigravity.ghosttouchguardian.service.shield

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.antigravity.ghosttouchguardian.R
import com.antigravity.ghosttouchguardian.data.GuardianPreferences
import com.antigravity.ghosttouchguardian.service.HotspotShieldService
import com.antigravity.ghosttouchguardian.ui.MainActivity

/**
 * ShieldNotificationManager — Single-Task Component for Foreground Notifications.
 *
 * Responsibilities:
 * - Creates and manages NotificationChannel.
 * - Builds persistent un-swipeable foreground service notification.
 * - Displays active mode (S-Pen Exclusive vs 45ms Ghost Shield) and blocked count.
 */
class ShieldNotificationManager(
    private val context: Context,
    private val prefs: GuardianPreferences
) {

    companion object {
        const val CHANNEL_ID = "ghost_touch_shield_channel"
        const val NOTIFICATION_ID = 2302
    }

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ghost Touch Active Shield",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "24/7 Persistent Touch Interception & S Pen Gate"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    fun buildNotification(): Notification {
        val launchIntent = Intent(context, MainActivity::class.java)
        val pendingLaunch = PendingIntent.getActivity(
            context, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(context, HotspotShieldService::class.java).apply {
            action = HotspotShieldService.ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            context, 10, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title: String
        val text: String
        val spenButtonText: String
        val spenPendingIntent: PendingIntent
        val blocked = prefs.totalBlockedGhosts

        if (prefs.isSPenModeActive) {
            title = "🖊️ S Pen Mode: ON"
            val liveFilterStatus = if (prefs.isSPenLiveGhostFilterEnabled) "Live Ignore: ON" else "Live Ignore: OFF"
            text = "$blocked Glitches Blocked • $liveFilterStatus"
            val disableSPenIntent = Intent(context, HotspotShieldService::class.java).apply {
                action = HotspotShieldService.ACTION_DISABLE_SPEN_MODE
            }
            spenButtonText = "🖐️ Switch to Finger Mode"
            spenPendingIntent = PendingIntent.getService(
                context, 11, disableSPenIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            title = "🛡️ Ghost Touch Shield: ON"
            text = "Smooth Touch Active • $blocked Glitches Swallowed"
            val enableSPenIntent = Intent(context, HotspotShieldService::class.java).apply {
                action = HotspotShieldService.ACTION_ENABLE_SPEN_MODE
            }
            spenButtonText = "🖊️ Switch to S-Pen Only"
            spenPendingIntent = PendingIntent.getService(
                context, 12, enableSPenIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pendingLaunch)
            .addAction(R.drawable.ic_shield, spenButtonText, spenPendingIntent)
            .addAction(R.drawable.ic_shield, "🛑 Turn Off Shield", pendingStop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    fun updateNotification() {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {}
    }
}
