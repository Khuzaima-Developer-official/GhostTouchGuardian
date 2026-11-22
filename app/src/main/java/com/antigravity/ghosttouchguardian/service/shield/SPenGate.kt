package com.antigravity.ghosttouchguardian.service.shield

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent

/**
 * SPenGate — Single-Task Component for S-Pen Wacom EMR Routing & Smooth Timing.
 *
 * Responsibilities:
 * - Accurately detects whether an event contains purely S-Pen stylus input across all pointers.
 * - Handles stylus hover enter, hover move, and hover exit.
 * - Enforces zero WindowManager mutations during active handwriting strokes.
 * - Maintains smooth 350ms debounce timing between letters so strokes never clip or freeze.
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
                // Smooth 350ms debounce: prevents WindowManager IPC stalls during hover movement
                handler.postDelayed(penExitRunnable, 350L)
                return false
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                if (!isPointerDownOnScreen) {
                    handler.removeCallbacks(penExitRunnable)
                    handler.postDelayed(penExitRunnable, 250L)
                }
                return false
            }
        }
        return false
    }

    /**
     * Called when stylus is actively touching down on screen.
     * Keeps pass-through solidly open with ZERO premature re-arm while pen is touching.
     */
    fun onStylusTouchDown() {
        handler.removeCallbacks(penExitRunnable)
        onEnablePassThrough()
    }

    /**
     * Called when stylus lifts up from the screen.
     * Clean 350ms window allows natural gaps between letters without mutating window flags.
     */
    fun onStylusTouchUp() {
        handler.removeCallbacks(penExitRunnable)
        handler.postDelayed(penExitRunnable, 350L)
    }

    fun cancelPendingReArm() {
        handler.removeCallbacks(penExitRunnable)
    }
}
