package com.antigravity.ghosttouchguardian

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/** Accelerometer ring-buffer. Query peak delta-magnitude across any time span. */
class Sen(ctx: Context) : SensorEventListener {

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val isLin = sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION

    private val SZ = 128
    private val ts = LongArray(SZ)
    private val mg = FloatArray(SZ)
    private var hd = 0
    private var hx=0f; private var hy=0f; private var hz=0f

    @Volatile var on = false

    fun start() { if(on||sensor==null) return; sm?.registerListener(this,sensor,SensorManager.SENSOR_DELAY_GAME); on=true }
    fun stop()  { if(!on) return; sm?.unregisterListener(this); on=false }

    override fun onSensorChanged(e: SensorEvent?) {
        if (e==null) return
        val dm: Float = if (isLin) {
            sqrt((e.values[0]*e.values[0]+e.values[1]*e.values[1]+e.values[2]*e.values[2]).toDouble()).toFloat()
        } else {
            val a=0.80f
            hx=a*hx+(1-a)*e.values[0]; hy=a*hy+(1-a)*e.values[1]; hz=a*hz+(1-a)*e.values[2]
            val hpx=e.values[0]-hx; val hpy=e.values[1]-hy; val hpz=e.values[2]-hz
            sqrt((hpx*hpx+hpy*hpy+hpz*hpz).toDouble()).toFloat()
        }
        synchronized(this) { ts[hd]=SystemClock.uptimeMillis(); mg[hd]=dm; hd=(hd+1)%SZ }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    /** Peak magnitude in window [t0-AW .. tsUp+AW]. Covers full gesture + sensor delay. */
    fun peak(t0: Long, tsUp: Long): Float {
        val lo = t0 - K.AW; val hi = tsUp + K.AW
        var mx = 0f
        synchronized(this) { for(i in 0 until SZ) if(ts[i] in lo..hi && mg[i]>mx) mx=mg[i] }
        return mx
    }

    /** Quick check for current-time window (used at ACTION_DOWN for burst/echo gates). */
    fun peakNow(t: Long): Float = peak(t, t)

    /** True if peak in window >= AT threshold. */
    fun imp(t0: Long, tsUp: Long) = peak(t0, tsUp) >= K.AT
}
