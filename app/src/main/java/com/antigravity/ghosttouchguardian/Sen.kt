package com.antigravity.ghosttouchguardian

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Ring-buffer accelerometer. Query peak delta-magnitude in any time window.
 * Uses linear accel sensor if available, falls back to raw accel + high-pass.
 */
class Sen(ctx: Context) : SensorEventListener {

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val isLinear = sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION

    // ring buffer — 128 slots at SENSOR_DELAY_GAME (~20ms) covers ~2.5 seconds
    private val sz = 128
    private val ts = LongArray(sz)   // uptimeMillis timestamps
    private val mg = FloatArray(sz)  // delta magnitude m/s²
    private var hd = 0               // ring head

    // high-pass state for raw accel fallback
    private var hx = 0f; private var hy = 0f; private var hz = 0f

    @Volatile var on = false

    fun start() {
        if (on || sensor == null) return
        sm?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        on = true
    }

    fun stop() {
        if (!on) return
        sm?.unregisterListener(this)
        on = false
    }

    override fun onSensorChanged(e: SensorEvent?) {
        if (e == null) return
        val dm: Float
        if (isLinear) {
            dm = sqrt((e.values[0]*e.values[0] + e.values[1]*e.values[1] + e.values[2]*e.values[2]).toDouble()).toFloat()
        } else {
            val a = 0.80f
            hx = a*hx + (1-a)*e.values[0]; hy = a*hy + (1-a)*e.values[1]; hz = a*hz + (1-a)*e.values[2]
            val hpx = e.values[0]-hx; val hpy = e.values[1]-hy; val hpz = e.values[2]-hz
            dm = sqrt((hpx*hpx + hpy*hpy + hpz*hpz).toDouble()).toFloat()
        }
        synchronized(this) {
            ts[hd] = SystemClock.uptimeMillis()
            mg[hd] = dm
            hd = (hd + 1) % sz
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    /** Peak magnitude in window [touchMs - AW .. touchMs + AW]. */
    fun peak(touchMs: Long): Float {
        val lo = touchMs - K.AW; val hi = touchMs + K.AW
        var mx = 0f
        synchronized(this) {
            for (i in 0 until sz) if (ts[i] in lo..hi && mg[i] > mx) mx = mg[i]
        }
        return mx
    }

    /** True if any sample in window exceeds AT threshold. */
    fun imp(touchMs: Long) = peak(touchMs) >= K.AT
}
