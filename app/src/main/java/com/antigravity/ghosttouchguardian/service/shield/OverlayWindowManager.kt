package com.antigravity.ghosttouchguardian.service.shield

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * OverlayWindowManager — Single-Task Component for Window Management.
 *
 * Responsibilities:
 * - Creates and manages TYPE_APPLICATION_OVERLAY parameters.
 * - Enforces 100% full panel edge-to-edge coverage across display cutouts (camera hole).
 * - Toggles touchability flags (FLAG_NOT_TOUCHABLE) safely with atomic IPC calls.
 */
class OverlayWindowManager(
    private val context: Context,
    private val windowManager: WindowManager
) {

    var overlayView: View? = null
        private set
    var overlayParams: WindowManager.LayoutParams? = null
        private set
    var isTouchThrough: Boolean = false
        private set

    fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
    }

    fun attachOverlay(view: View) {
        if (overlayView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        try {
            windowManager.addView(view, params)
            overlayView = view
            overlayParams = params
            isTouchThrough = false
        } catch (_: Exception) {}
    }

    fun setTouchThrough(enable: Boolean) {
        if (isTouchThrough == enable) return
        isTouchThrough = enable
        overlayParams?.let { p ->
            if (enable) {
                p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            try {
                overlayView?.let { windowManager.updateViewLayout(it, p) }
            } catch (_: Exception) {}
        }
    }

    fun removeOverlay() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
        overlayParams = null
        isTouchThrough = false
    }
}
