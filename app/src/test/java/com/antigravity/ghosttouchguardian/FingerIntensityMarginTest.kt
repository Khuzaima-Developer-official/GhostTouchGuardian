package com.antigravity.ghosttouchguardian

import com.antigravity.ghosttouchguardian.model.FingerIntensityMargin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerIntensityMarginTest {

    @Test
    fun testInitialDefaults() {
        val margin = FingerIntensityMargin()
        assertEquals(0, margin.sampleCount)
        assertFalse(margin.isWithinHumanMargin(0.5f, 0.1f, 100, 0.05f))
    }

    @Test
    fun testRecordSamplesExpandsBoundaries() {
        val margin = FingerIntensityMargin()
        // Feather tap
        margin.recordSample(pressure = 0.12f, size = 0.035f, dwellMs = 60, impulseG = 0.018f, tremorVar = 0.42f)
        // Normal tap
        margin.recordSample(pressure = 0.35f, size = 0.080f, dwellMs = 120, impulseG = 0.045f, tremorVar = 0.65f)
        // Firm tap
        margin.recordSample(pressure = 0.70f, size = 0.180f, dwellMs = 280, impulseG = 0.110f, tremorVar = 0.95f)

        assertEquals(3, margin.sampleCount)
        assertEquals(0.12f, margin.minPressure, 0.001f)
        assertEquals(0.70f, margin.maxPressure, 0.001f)
        assertEquals(0.035f, margin.minSize, 0.001f)
        assertEquals(0.180f, margin.maxSize, 0.001f)
        assertEquals(60, margin.minDwellMs)
        assertEquals(280, margin.maxDwellMs)

        // Valid touch within envelope with shockwave
        assertTrue(margin.isWithinHumanMargin(pressure = 0.40f, size = 0.090f, dwellMs = 150, impulseG = 0.050f))

        // Water droplet: Zero impulse shockwave (0.002g) must be REJECTED despite valid pressure!
        assertFalse(margin.isWithinHumanMargin(pressure = 0.40f, size = 0.090f, dwellMs = 150, impulseG = 0.002f))
    }

    @Test
    fun testJsonSerializationRoundtrip() {
        val original = FingerIntensityMargin(
            minPressure = 0.15f,
            maxPressure = 0.85f,
            minSize = 0.040f,
            maxSize = 0.250f,
            minDwellMs = 55,
            maxDwellMs = 450,
            minImpulseG = 0.022f,
            maxImpulseG = 0.150f,
            minMicroTremorVariance = 0.40f,
            sampleCount = 5
        )

        val json = original.toJson()
        val restored = FingerIntensityMargin.fromJson(json)

        assertEquals(original.minPressure, restored.minPressure, 0.001f)
        assertEquals(original.maxPressure, restored.maxPressure, 0.001f)
        assertEquals(original.minSize, restored.minSize, 0.001f)
        assertEquals(original.maxSize, restored.maxSize, 0.001f)
        assertEquals(original.minDwellMs, restored.minDwellMs)
        assertEquals(original.maxDwellMs, restored.maxDwellMs)
        assertEquals(original.sampleCount, restored.sampleCount)
    }
}
