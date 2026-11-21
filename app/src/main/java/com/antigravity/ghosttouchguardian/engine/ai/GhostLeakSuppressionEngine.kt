package com.antigravity.ghosttouchguardian.engine.ai

import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot

/**
 * GhostLeakSuppressionEngine — Active AI Mind & Real-Time Ghost Leak Choke.
 *
 * Implements:
 * 1. Autonomous Resonance Tracking: Computes rolling ghost frequency spectrum (Hz) and inter-arrival delta.
 * 2. Active Leak Interception (Choke Engine): Detects anomalous touch chatter during open pass-through
 *    and immediately clamps the shield shut, engaging Emergency Storm Suppression.
 * 3. Spatial Moisture Hotspot Grid: Identifies 8x8 screen cells with high moisture glitch density and
 *    autonomously tightens suppression thresholds over those coordinates.
 */
class GhostLeakSuppressionEngine {

    // 8x8 Spatial Moisture Grid (64 cells across screen)
    private val gridRows = 8
    private val gridCols = 8
    private val moistureDensity = Array(gridRows) { IntArray(gridCols) }
    private var lastMoistureDecayTime = System.currentTimeMillis()

    // Resonant Frequency Spectrum Tracking
    private val recentIntervals = mutableListOf<Long>()
    var resonantFrequencyHz: Float = 0f
        private set
    var rollingAvgIntervalMs: Long = 35L
        private set

    // Emergency Storm Suppression State
    @Volatile var isStormSuppressionActive: Boolean = false
        private set
    private var stormReleaseTime: Long = 0L

    // Rolling touch window for real-time leak detection (last 5 touches)
    data class RecentTouch(
        val x: Float,
        val y: Float,
        val time: Long,
        val interval: Long,
        val accelDelta: Float,
        val hasImpulse: Boolean
    )
    private val touchWindow = mutableListOf<RecentTouch>()

    /**
     * Registers a confirmed ghost touch event to update the active frequency spectrum
     * and spatial moisture hotspot map.
     */
    fun registerGhostTouch(x: Float, y: Float, intervalMs: Long, screenWidth: Int = 1080, screenHeight: Int = 2340) {
        decayMoistureMapIfNeeded()

        // 1. Update Resonant Frequency Spectrum
        if (intervalMs in 1L..300L) {
            recentIntervals.add(intervalMs)
            if (recentIntervals.size > 20) recentIntervals.removeAt(0)
            rollingAvgIntervalMs = (recentIntervals.average()).toLong().coerceAtLeast(1L)
            resonantFrequencyHz = 1000f / rollingAvgIntervalMs.toFloat()

            // Autonomous Storm Clamp: If rolling average interval drops below 30ms with 5+ bursts, clamp shut!
            if (recentIntervals.size >= 5 && rollingAvgIntervalMs < 30L) {
                triggerStormChoke(System.currentTimeMillis(), 1000L)
            }
        }

        // 2. Update Spatial Moisture Grid
        val col = ((x / screenWidth.coerceAtLeast(1)) * gridCols).toInt().coerceIn(0, gridCols - 1)
        val row = ((y / screenHeight.coerceAtLeast(1)) * gridRows).toInt().coerceIn(0, gridRows - 1)
        moistureDensity[row][col] = (moistureDensity[row][col] + 1).coerceAtMost(50)
    }

    /**
     * Checks if coordinates fall within an actively detected moisture hotspot.
     */
    fun isInMoistureHotspot(x: Float, y: Float, screenWidth: Int = 1080, screenHeight: Int = 2340): Boolean {
        val col = ((x / screenWidth.coerceAtLeast(1)) * gridCols).toInt().coerceIn(0, gridCols - 1)
        val row = ((y / screenHeight.coerceAtLeast(1)) * gridRows).toInt().coerceIn(0, gridRows - 1)
        return moistureDensity[row][col] >= 3
    }

    /**
     * Real-time Active Leak Detection & Choke Assessment:
     * Evaluates whether an incoming touch during pass-through is actually an escaping ghost leak!
     *
     * Returns true if a leak is detected and suppression must be triggered immediately!
     */
    fun evaluateLeak(
        event: MotionEvent,
        now: Long,
        intervalMs: Long,
        accelDelta: Float,
        hasImpulse: Boolean,
        screenWidth: Int = 1080,
        screenHeight: Int = 2340
    ): Boolean {
        // If storm suppression is currently locked, continue choking
        if (isStormSuppressionActive) {
            if (now < stormReleaseTime) {
                return true // Active clamp!
            } else {
                isStormSuppressionActive = false
            }
        }

        val inHotspot = isInMoistureHotspot(event.x, event.y, screenWidth, screenHeight)

        // Add to recent window
        touchWindow.add(RecentTouch(event.x, event.y, now, intervalMs, accelDelta, hasImpulse))
        if (touchWindow.size > 5) touchWindow.removeAt(0)

        // Leak Criterion 1: Zero physical momentum in an active moisture hotspot
        if (!hasImpulse && inHotspot && accelDelta < 0.015f) {
            triggerStormChoke(now, 600L)
            return true
        }

        // Leak Criterion 2: Resonant frequency match (water oscillation harmonic) without momentum
        if (!hasImpulse && resonantFrequencyHz > 5f) {
            val freq = if (intervalMs > 0) 1000f / intervalMs else 0f
            if (abs(freq - resonantFrequencyHz) < 12f && accelDelta < 0.015f) {
                triggerStormChoke(now, 700L)
                return true
            }
        }

        // Leak Criterion 3: Rapid cluster without impulse (e.g., 2 touches within < 50ms)
        if (touchWindow.size >= 2) {
            val prev = touchWindow[touchWindow.size - 2]
            val gap = now - prev.time
            val dist = hypot(event.x - prev.x, event.y - prev.y)
            if (gap in 1L..55L && !hasImpulse && !prev.hasImpulse && dist < 120f) {
                triggerStormChoke(now, 800L)
                return true
            }
        }

        return false
    }

    private fun triggerStormChoke(now: Long, durationMs: Long) {
        isStormSuppressionActive = true
        stormReleaseTime = now + durationMs
    }

    fun releaseStorm() {
        isStormSuppressionActive = false
        stormReleaseTime = 0L
    }

    private fun decayMoistureMapIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastMoistureDecayTime > 15000L) { // Decay every 15s
            lastMoistureDecayTime = now
            for (r in 0 until gridRows) {
                for (c in 0 until gridCols) {
                    if (moistureDensity[r][c] > 0) {
                        moistureDensity[r][c]--
                    }
                }
            }
        }
    }
}
