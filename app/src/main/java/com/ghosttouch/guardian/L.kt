package com.ghosttouch.guardian

import android.util.Log

/** Centralized logging utility with component tags. */
object L {
    private const val TAG = "GTG"
    
    fun d(component: String, msg: String) {
        Log.d("$TAG-$component", msg)
    }
    
    fun i(component: String, msg: String) {
        Log.i("$TAG-$component", msg)
    }
    
    fun w(component: String, msg: String, e: Throwable? = null) {
        if (e != null) {
            Log.w("$TAG-$component", msg, e)
        } else {
            Log.w("$TAG-$component", msg)
        }
    }
    
    fun e(component: String, msg: String, e: Throwable? = null) {
        if (e != null) {
            Log.e("$TAG-$component", msg, e)
        } else {
            Log.e("$TAG-$component", msg)
        }
    }
}

/** Component tags for logging. */
object Tags {
    const val UI = "UI"
    const val SERVICE = "Service"
    const val DETECTION = "Detection"
    const val SENSOR = "Sensor"
    const val OVERLAY = "Overlay"
    const val IO = "IO"
    const val PREFS = "Prefs"
}