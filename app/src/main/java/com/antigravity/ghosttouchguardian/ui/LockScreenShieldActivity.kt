package com.antigravity.ghosttouchguardian.ui

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.antigravity.ghosttouchguardian.data.CompactTouchRepository
import com.antigravity.ghosttouchguardian.data.GuardianPreferences
import com.antigravity.ghosttouchguardian.engine.ai.ExtendedTouchVector
import com.antigravity.ghosttouchguardian.engine.ai.MasterTouchClassifier

/**
 * LockScreenShieldActivity — Active Lock Screen Guard for Samsung Galaxy S23 Ultra.
 *
 * Solves the Samsung One UI Keyguard restriction:
 * On Samsung devices, WindowManager hides TYPE_APPLICATION_OVERLAY windows when Keyguard is active.
 * This Activity leverages official Android `setShowWhenLocked(true)` and `setTurnScreenOn(true)`
 * to sit directly over the lock screen, intercepting all moisture shorts and water chatter
 * before they can wake or corrupt the lock screen PIN or emergency dialer.
 */
class LockScreenShieldActivity : AppCompatActivity() {

    private lateinit var prefs: GuardianPreferences
    private lateinit var repository: CompactTouchRepository
    private lateinit var masterClassifier: MasterTouchClassifier
    private val aiExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastTouchTime = 0L

    companion object {
        @Volatile
        var instance: LockScreenShieldActivity? = null
            private set

        fun dismissIfRunning() {
            instance?.let {
                it.finish()
                instance = null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        prefs = GuardianPreferences(this)
        repository = CompactTouchRepository(this)
        masterClassifier = repository.loadBrain()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        // Completely transparent full-screen touch listener
        val transparentView = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        setContentView(transparentView)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)
        val now = event.eventTime

        // -------------------------------------------------------------------------
        // TIER 0: S PEN EXCLUSIVE MODE ON LOCK SCREEN
        // -------------------------------------------------------------------------
        if (prefs.isSPenModeActive) {
            if (toolType == MotionEvent.TOOL_TYPE_STYLUS) {
                // S Pen is allowed to dismiss Keyguard via swipe
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val distY = kotlin.math.abs(event.y - downY)
                    val distX = kotlin.math.abs(event.x - downX)
                    if (distY > 150f || distX > 150f) {
                        requestUnlock()
                    }
                }
                return false
            } else {
                // ANY non-stylus touch on the lock screen is a 100% water glitch!
                val intervalMs = if (lastTouchTime > 0L) (now - lastTouchTime).coerceAtLeast(1L) else 25L
                lastTouchTime = now
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    downX = event.x
                    downY = event.y
                    downTime = now
                    prefs.totalBlockedGhosts = prefs.totalBlockedGhosts + 1
                    learnLockScreenGhost(event, 25, intervalMs)
                } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val dwell = (now - downTime).toInt().coerceIn(1, 5000)
                    learnLockScreenGhost(event, dwell, intervalMs)
                }
                return true // Completely swallowed: never touches lock screen PIN/emergency!
            }
        }

        // -------------------------------------------------------------------------
        // TIER 1: NORMAL MODE RAPID SPAM FILTER (< 45ms)
        // -------------------------------------------------------------------------
        val interval = if (lastTouchTime > 0L) (now - lastTouchTime).coerceAtLeast(0L) else Long.MAX_VALUE
        lastTouchTime = now
        if (interval in 0L..44L && toolType != MotionEvent.TOOL_TYPE_STYLUS) {
            prefs.totalBlockedGhosts = prefs.totalBlockedGhosts + 1
            return true // Drop high-frequency chatter immediately!
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = now
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dwell = (now - downTime).toInt()
                if (dwell < 45 && toolType != MotionEvent.TOOL_TYPE_STYLUS) {
                    prefs.totalBlockedGhosts = prefs.totalBlockedGhosts + 1
                    learnLockScreenGhost(event, dwell, interval)
                    return true // Swallow instant moisture click
                }

                // If genuine human swipe across lock screen, request dismiss keyguard
                val distY = kotlin.math.abs(event.y - downY)
                val distX = kotlin.math.abs(event.x - downX)
                if (distY > 160f || distX > 160f) {
                    requestUnlock()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun learnLockScreenGhost(event: MotionEvent, dwell: Int, intervalMs: Long) {
        val curPressure = event.pressure
        val curSize = event.size
        val curMajor = event.touchMajor.coerceAtLeast(1f)
        val curMinor = event.touchMinor.coerceAtLeast(1f)
        val curX = event.x
        val curY = event.y
        val curDownX = downX
        val curDownY = downY

        aiExecutor.execute {
            try {
                val dx = curX - curDownX
                val dy = curY - curDownY
                val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                val velocity = if (dwell > 0) dist / dwell.toFloat() else 0f
                val eccentricity = (curMajor / curMinor).coerceIn(1.0f, 10.0f)

                val extendedVector = ExtendedTouchVector(
                    dwell = dwell.toFloat(),
                    size = curSize.coerceIn(0f, 1f),
                    pressure = curPressure.coerceIn(0f, 1f),
                    accelDelta = 0.005f,
                    pressureGradient = 0f,
                    microTremorVariance = 0f,
                    eccentricity = eccentricity,
                    velocity = velocity.coerceIn(0f, 50f),
                    interval = intervalMs.toFloat().coerceAtLeast(0f),
                    timeSinceStimulus = 100f
                )
                masterClassifier.learnOnline(extendedVector, isGhost = true)
                if (masterClassifier.totalOnlineUpdates % 10L == 0L) {
                    repository.saveBrain(masterClassifier)
                }
            } catch (_: Exception) {}
        }
    }

    private fun requestUnlock() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            km?.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    finish()
                }
                override fun onDismissCancelled() {
                    finish()
                }
            })
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        try { aiExecutor.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }
}
