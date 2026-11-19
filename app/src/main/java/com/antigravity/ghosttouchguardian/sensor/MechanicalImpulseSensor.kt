package com.antigravity.ghosttouchguardian.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

class MechanicalImpulseSensor(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val isLinear = sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION

    // Ring buffer
    private val bufferSize = 64
    private val timestamps = LongArray(bufferSize)
    private val impulseMagnitudes = FloatArray(bufferSize)
    private var head = 0

    // High-pass filter
    private var lastAccX = 0f
    private var lastAccY = 0f
    private var lastAccZ = 0f
    private var lastMag = 0f

    @Volatile
    var isRunning: Boolean = false
        private set

    fun start() {
        // Start sensor
        if (isRunning || sensor == null) return
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        isRunning = true
    }

    fun stop() {
        // Stop sensor
        if (!isRunning) return
        sensorManager?.unregisterListener(this)
        isRunning = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        // Handle change
        if (event == null) return

        val rawX = event.values[0]
        val rawY = event.values[1]
        val rawZ = event.values[2]

        val deltaMag: Float
        if (isLinear) {
            val mag = sqrt((rawX * rawX + rawY * rawY + rawZ * rawZ).toDouble()).toFloat()
            deltaMag = abs(mag - lastMag)
            lastMag = mag
        } else {
            // High pass
            val alpha = 0.8f
            lastAccX = alpha * lastAccX + (1 - alpha) * rawX
            lastAccY = alpha * lastAccY + (1 - alpha) * rawY
            lastAccZ = alpha * lastAccZ + (1 - alpha) * rawZ

            val hpX = rawX - lastAccX
            val hpY = rawY - lastAccY
            val hpZ = rawZ - lastAccZ
            deltaMag = sqrt((hpX * hpX + hpY * hpY + hpZ * hpZ).toDouble()).toFloat()
        }

        // Store sample
        val nowMs = SystemClock.uptimeMillis()
        synchronized(this) {
            timestamps[head] = nowMs
            impulseMagnitudes[head] = deltaMag
            head = (head + 1) % bufferSize
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Accuracy change
    }

    /** Returns the most recent accelerometer delta magnitude (useful for identifying ghost touches). */
    fun getLastAccelDelta(): Float {
        synchronized(this) {
            var maxVal = 0f
            val now = SystemClock.uptimeMillis()
            for (i in 0 until bufferSize) {
                if (timestamps[i] in (now - 100L)..now) {
                    if (impulseMagnitudes[i] > maxVal) maxVal = impulseMagnitudes[i]
                }
            }
            return maxVal
        }
    }

    fun hasMechanicalImpulse(touchTimeMs: Long, windowMs: Long = 30L): Boolean {
        // Check impulse
        val minTime = touchTimeMs - windowMs
        val maxTime = touchTimeMs + windowMs

        synchronized(this) {
            for (i in 0 until bufferSize) {
                val t = timestamps[i]
                if (t in minTime..maxTime) {
                    if (impulseMagnitudes[i] >= THRESHOLD_IMPULSE) {
                        return true
                    }
                }
            }
        }
        return false
    }

    companion object {
        private const val THRESHOLD_IMPULSE = 0.045f
    }
}
