package com.antigravity.ghosttouchguardian

import com.antigravity.ghosttouchguardian.model.CompactTouchBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CompactTouchBufferTest {

    private lateinit var buffer: CompactTouchBuffer

    @Before
    fun setUp() {
        buffer = CompactTouchBuffer(capacity = 64)
    }

    @Test
    fun testPushAndCapacityWrapping() {
        // Push 200 events (more than 3x the capacity of 64)
        for (i in 0 until 200) {
            buffer.push(
                x = i.toFloat(),
                y = (i * 2).toFloat(),
                timestamp = 1000L + i,
                dwell = 10,
                sizeVal = 0.05f,
                pressureVal = 0.5f,
                type = CompactTouchBuffer.TYPE_GHOST_IDLE
            )
        }

        // Buffer size must not exceed capacity
        assertEquals(64, buffer.size)

        // Total ghost count must track all 200 events
        assertEquals(200L, buffer.totalGhostCount)
    }

    @Test
    fun testFlushResetsAllMemory() {
        for (i in 0 until 50) {
            buffer.push(
                x = 100f, y = 200f, timestamp = 5000L,
                dwell = 50, sizeVal = 0.2f, pressureVal = 0.8f,
                type = CompactTouchBuffer.TYPE_HUMAN_FINGER
            )
        }

        assertEquals(50, buffer.size)
        assertEquals(50L, buffer.totalHumanCount)

        // Flush memory
        buffer.flush()

        // Size and counters must be completely reset
        assertEquals(0, buffer.size)
        assertEquals(0L, buffer.totalHumanCount)
        assertEquals(0L, buffer.totalGhostCount)
        assertEquals(0L, buffer.totalEchoCount)

        // Check that primitive array values are zeroed out
        for (i in 0 until buffer.capacity) {
            assertEquals(0f, buffer.xCoords[i], 0.0001f)
            assertEquals(0f, buffer.yCoords[i], 0.0001f)
            assertEquals(0L, buffer.timestamps[i])
            assertEquals(0, buffer.dwellTimes[i])
        }
    }

    @Test
    fun testEchoAccounting() {
        buffer.push(10f, 10f, 100L, 10, 0.02f, 0.1f, CompactTouchBuffer.TYPE_GHOST_ECHO)
        buffer.push(20f, 20f, 200L, 80, 0.30f, 0.6f, CompactTouchBuffer.TYPE_HUMAN_FINGER)

        assertEquals(1L, buffer.totalEchoCount)
        assertEquals(1L, buffer.totalGhostCount)
        assertEquals(1L, buffer.totalHumanCount)
    }
}
