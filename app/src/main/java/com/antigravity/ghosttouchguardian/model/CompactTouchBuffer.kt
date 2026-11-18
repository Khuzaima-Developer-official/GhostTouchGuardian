package com.antigravity.ghosttouchguardian.model

import java.util.Arrays

/**
 * Ultra-compact, zero-allocation circular ring buffer.
 * Memory footprint: ~3.6 Kilobytes total (stored in flat primitive arrays).
 * Avoids any heap allocation or object boxing in the touch loop to eliminate GC pauses.
 */
class CompactTouchBuffer(val capacity: Int = 128) {

    // Primitive flat arrays (Zero object wrapper overhead)
    val xCoords = FloatArray(capacity)
    val yCoords = FloatArray(capacity)
    val timestamps = LongArray(capacity)
    val dwellTimes = IntArray(capacity)      // in milliseconds
    val contactSizes = FloatArray(capacity)  // normalized 0.0 - 1.0
    val pressures = FloatArray(capacity)     // normalized 0.0 - 1.0
    val eventTypes = ByteArray(capacity)    // 0: Human, 1: Ghost Idle, 2: Ghost Echo, 3: S-Pen

    private var head = 0
    var size = 0
        private set

    var totalGhostCount = 0L
        private set
    var totalHumanCount = 0L
        private set
    var totalEchoCount = 0L
        private set

    /**
     * Inserts a record using O(1) in-place overwrite.
     * Absolutely ZERO memory allocation occurs here.
     */
    @Synchronized
    fun push(
        x: Float,
        y: Float,
        timestamp: Long,
        dwell: Int,
        sizeVal: Float,
        pressureVal: Float,
        type: Byte
    ) {
        xCoords[head] = x
        yCoords[head] = y
        timestamps[head] = timestamp
        dwellTimes[head] = dwell
        contactSizes[head] = sizeVal
        pressures[head] = pressureVal
        eventTypes[head] = type

        head = (head + 1) % capacity
        if (size < capacity) {
            size++
        }

        when (type) {
            TYPE_GHOST_IDLE -> totalGhostCount++
            TYPE_GHOST_ECHO -> {
                totalGhostCount++
                totalEchoCount++
            }
            TYPE_HUMAN_FINGER -> totalHumanCount++
        }
    }

    /**
     * Immediate memory flush.
     * Overwrites arrays to zero and resets all counters.
     */
    @Synchronized
    fun flush() {
        Arrays.fill(xCoords, 0f)
        Arrays.fill(yCoords, 0f)
        Arrays.fill(timestamps, 0L)
        Arrays.fill(dwellTimes, 0)
        Arrays.fill(contactSizes, 0f)
        Arrays.fill(pressures, 0f)
        Arrays.fill(eventTypes, 0.toByte())

        head = 0
        size = 0
        totalGhostCount = 0L
        totalHumanCount = 0L
        totalEchoCount = 0L
    }

    companion object {
        const val TYPE_HUMAN_FINGER: Byte = 0
        const val TYPE_GHOST_IDLE: Byte = 1
        const val TYPE_GHOST_ECHO: Byte = 2
        const val TYPE_SPEN: Byte = 3
    }
}
