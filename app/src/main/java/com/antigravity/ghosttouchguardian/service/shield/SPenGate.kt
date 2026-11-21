package com.antigravity.ghosttouchguardian.service.shield

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent

/**
 * SPenGate — Single-Task Component for S-Pen Wacom EMR Routing & Timing.
 *
 * Responsibilities:
 * - Accurately detects whether an event contains purely S-Pen stylus input across all pointers.
 * - Handles stylus hover enter, hover move, and hover exit.
 * - Maintains ultra-tight debounce timing (35ms - 45ms) so the shield locks down immediately when pen lifts.
 */
class SPenGate(
    private val onEnablePassThrough: () -> Unit,
    private val onReArmShield: () -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())

    private val penExitRunnable = Runnable {
        onReArmShield()
    }

    /**
     * Checks if all active pointers in the MotionEvent are stylus.
     */
    fun hasOnlyStylus(event: MotionEvent): Boolean {
        for (i in 0 until event.pointerCount) {
            if (event.getToolType(i) != MotionEvent.TOOL_TYPE_STYLUS) {
                return false
            }
        }
        return true
    }

    /**
     * Handles S-Pen hover events near the glass.
     */
    fun handleHover(event: MotionEvent, isPointerDownOnScreen: Boolean): Boolean {
        if (!hasOnlyStylus(event)) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE -> {
                handler.removeCallbacks(penExitRunnable)
                onEnablePassThrough()
                handler.postDelayed(penExitRunnable, 45L)
                return false
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                if (!isPointerDownOnScreen) {
                    handler.removeCallbacks(penExitRunnable)
                    handler.postDelayed(penExitRunnable, 35L)
                }
                return false
            }
        }
        return false
    }

    /**
     * Called when stylus is actively touching down on screen.
     */
    fun onStylusTouchDown() {
        handler.removeCallbacks(penExitRunnable)
        onEnablePassThrough()
        handler.postDelayed(penExitRunnable, 45L)
    }

    /**
     * Called when stylus lifts up from the screen.
     */
    fun onStylusTouchUp() {
        handler.removeCallbacks(penExitRunnable)
        handler.postDelayed(penExitRunnable, 35L)
    }

    fun cancelPendingReArm() {
        handler.removeCallbacks(penExitRunnable)
    }
}
