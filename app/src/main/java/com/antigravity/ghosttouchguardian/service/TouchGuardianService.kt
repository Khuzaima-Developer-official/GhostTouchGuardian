package com.antigravity.ghosttouchguardian.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.antigravity.ghosttouchguardian.R
import com.antigravity.ghosttouchguardian.data.GuardianPreferences
import com.antigravity.ghosttouchguardian.engine.TemporalFilter
import com.antigravity.ghosttouchguardian.engine.TouchProfilerEngine
import com.antigravity.ghosttouchguardian.ui.MainActivity

class TouchGuardianService : Service() {

    val temporalFilter = TemporalFilter()
    val profiler = TouchProfilerEngine()
    private lateinit var prefs: GuardianPreferences

    private var screenReceiver: ScreenStateReceiver? = null
    private var isScreenActive: Boolean = true
    private var blockedAnomalyCount: Int = 0
    private var lastAnomalySummary: String = "No anomalies"

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = GuardianPreferences(this)

        // Load profile
        val profile = prefs.currentProfile
        profiler.profile = profile
        temporalFilter.applyProfile(profile)
        temporalFilter.isEnabled = prefs.isGuardianEnabled

        // Create channel
        createNotificationChannel()

        val notification = buildNotification(temporalFilter.isEnabled)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Hook profiler anomaly callback
        profiler.onAnomalyDetected = { type, x, y, dwell, reason ->
            handleAnomalyDetected(type, x, y, dwell, reason)
        }

        // Screen listener
        screenReceiver = ScreenStateReceiver(
            onScreenOn = {
                isScreenActive = true
                temporalFilter.isEnabled = prefs.isGuardianEnabled
            },
            onScreenOff = {
                isScreenActive = false
                temporalFilter.isEnabled = false
                profiler.flush()
                temporalFilter.flush()
            }
        )

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> {
                temporalFilter.isEnabled = !temporalFilter.isEnabled
                prefs.isGuardianEnabled = temporalFilter.isEnabled
                val notification = buildNotification(temporalFilter.isEnabled)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
            }
            ACTION_UPDATE_PROFILE -> {
                val profile = prefs.currentProfile
                profiler.profile = profile
                temporalFilter.applyProfile(profile)
                val notification = buildNotification(temporalFilter.isEnabled)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
            }
            ACTION_FLUSH -> {
                profiler.flush()
                temporalFilter.flush()
                blockedAnomalyCount = 0
                lastAnomalySummary = "Cleared"
                val notification = buildNotification(temporalFilter.isEnabled)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    fun handleAnomalyDetected(type: Byte, x: Float, y: Float, dwell: Int, reason: String) {
        blockedAnomalyCount++
        prefs.totalBlockedGhosts = prefs.totalBlockedGhosts + 1
        lastAnomalySummary = "$reason at (${x.toInt()}, ${y.toInt()})"

        // Only trigger haptic buzz & alert notification if Testing Mode is active
        if (prefs.isTestingAlertsEnabled) {
            triggerHapticAlert()
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(temporalFilter.isEnabled))
        }
    }

    private fun triggerHapticAlert() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(45L)
            }
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW || level >= TRIM_MEMORY_BACKGROUND) {
            profiler.flush()
            temporalFilter.flush()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        screenReceiver?.let { unregisterReceiver(it) }
        screenReceiver = null
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(isActive: Boolean): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingLaunch = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val toggleIntent = Intent(this, TouchGuardianService::class.java).apply {
            action = ACTION_TOGGLE
        }
        val pendingToggle = PendingIntent.getService(
            this, 1, toggleIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val profileTitle = profiler.profile.title
        val statusText = if (isActive) {
            if (blockedAnomalyCount > 0) {
                "Active • $blockedAnomalyCount Caught ($lastAnomalySummary)"
            } else {
                "$profileTitle Active • Live Touch Protection"
            }
        } else {
            getString(R.string.status_paused)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setContentIntent(pendingLaunch)
            .addAction(
                R.drawable.ic_shield,
                if (isActive) "Pause" else "Resume",
                pendingToggle
            )
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "ghost_touch_guardian_channel"
        const val NOTIFICATION_ID = 2301

        const val ACTION_START = "com.antigravity.guardian.START"
        const val ACTION_STOP = "com.antigravity.guardian.STOP"
        const val ACTION_TOGGLE = "com.antigravity.guardian.TOGGLE"
        const val ACTION_FLUSH = "com.antigravity.guardian.FLUSH"
        const val ACTION_UPDATE_PROFILE = "com.antigravity.guardian.UPDATE_PROFILE"

        @Volatile
        var instance: TouchGuardianService? = null
            private set
    }
}
