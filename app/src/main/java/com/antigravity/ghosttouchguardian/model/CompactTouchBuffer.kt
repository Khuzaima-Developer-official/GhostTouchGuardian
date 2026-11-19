package com.antigravity.ghosttouchguardian.model

import java.util.Arrays

class CompactTouchBuffer(val capacity: Int = 128) {

    // Primitive arrays
    val xCoords = FloatArray(capacity)
    val yCoords = FloatArray(capacity)
    val timestamps = LongArray(capacity)
    val dwellTimes = IntArray(capacity)
    val contactSizes = FloatArray(capacity)
    val pressures = FloatArray(capacity)
    val eventTypes = ByteArray(capacity)

    private var head = 0
    var size = 0
        private set

    var totalGhostCount = 0L
        private set
    var totalHumanCount = 0L
        private set
    var totalEchoCount = 0L
        private set

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
        // In-place insert
        xCoords[head] = x
        yCoords[head] = y
        timestamps[head] = timestamp
        dwellTimes[head] = dwell
        contactSizes[head] = sizeVal
        pressures[head] = pressureVal
        eventTypes[head] = type

        // Ring wrap
        head = (head + 1) % capacity
        if (size < capacity) {
            size++
        }

        // Increment stats
        when (type) {
            TYPE_GHOST_IDLE, TYPE_GHOST_TELEPORT, TYPE_STATIC_LOCK, TYPE_GRID_COLINEAR, TYPE_GHOST_HOTSPOT -> totalGhostCount++
            TYPE_GHOST_ECHO -> {
                totalGhostCount++
                totalEchoCount++
            }
            TYPE_HUMAN_FINGER, TYPE_HUMAN_FAST_TAP -> totalHumanCount++
        }
    }

    @Synchronized
    fun flush() {
        // Zero arrays
        Arrays.fill(xCoords, 0f)
        Arrays.fill(yCoords, 0f)
        Arrays.fill(timestamps, 0L)
        Arrays.fill(dwellTimes, 0)
        Arrays.fill(contactSizes, 0f)
        Arrays.fill(pressures, 0f)
        Arrays.fill(eventTypes, 0.toByte())

        // Reset state
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
        const val TYPE_HUMAN_FAST_TAP: Byte = 4
        const val TYPE_GHOST_TELEPORT: Byte = 5
        const val TYPE_STATIC_LOCK: Byte = 6
        const val TYPE_GRID_COLINEAR: Byte = 7
        const val TYPE_GHOST_HOTSPOT: Byte = 8
    }
}
