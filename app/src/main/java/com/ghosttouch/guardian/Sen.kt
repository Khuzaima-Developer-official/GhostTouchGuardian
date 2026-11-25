package com.ghosttouch.guardian

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/** Impact analysis result with confidence scoring. */
data class ImpactAnalysis(
    val peak: Float,
    val hasSteadyBaseline: Boolean,
    val hasSharpSpike: Boolean,
    val confidence: Float
)

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

    fun start() {
        if(on||sensor==null) return
        L.i(Tags.SENSOR, "Starting accelerometer (type=${sensor.type})")
        sm?.registerListener(this,sensor,SensorManager.SENSOR_DELAY_GAME)
        on=true
    }
    
    fun stop() {
        if(!on) return
        L.i(Tags.SENSOR, "Stopping accelerometer")
        sm?.unregisterListener(this)
        on=false
    }

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
    
    /** Multi-window analysis with confidence scoring (v4.2.0). */
    fun analyzeImpact(t0: Long, tsUp: Long): ImpactAnalysis {
        val gestureWindow = t0 - K.AW .. tsUp + K.AW
        val preWindow = t0 - 150L .. t0 - 10L
        val duringWindow = t0 .. tsUp
        val postWindow = tsUp .. tsUp + 100L
        
        val peakPre = peakInWindow(preWindow)
        val peakDuring = peakInWindow(duringWindow)
        val peakPost = peakInWindow(postWindow)
        val peakOverall = maxOf(peakPre, peakDuring, peakPost)
        
        val steadyBaseline = peakPre < 0.008f
        val sharpSpike = peakDuring > peakPre * 2.0f && peakDuring > 0.012f
        
        val confidence = when {
            peakOverall >= K.AT && sharpSpike -> 0.95f
            peakOverall >= K.AT -> 0.80f
            steadyBaseline && peakDuring > 0.012f -> 0.70f
            peakOverall > 0.010f -> 0.50f
            else -> 0.0f
        }
        
        return ImpactAnalysis(peakOverall, steadyBaseline, sharpSpike, confidence)
    }
    
    private fun peakInWindow(range: LongRange): Float {
        var mx = 0f
        synchronized(this) {
            for (i in 0 until SZ) {
                if (ts[i] in range && mg[i] > mx) mx = mg[i]
            }
        }
        return mx
    }
}
