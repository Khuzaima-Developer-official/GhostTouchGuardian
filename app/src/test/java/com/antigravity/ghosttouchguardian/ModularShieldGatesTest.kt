package com.antigravity.ghosttouchguardian

import com.antigravity.ghosttouchguardian.engine.ai.GhostLeakSuppressionEngine
import com.antigravity.ghosttouchguardian.engine.ai.GhostTypeClassifier
import com.antigravity.ghosttouchguardian.model.FingerIntensityMargin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModularShieldGatesTest {

    @Test
    fun testLeakSuppressionHotspotGrid() {
        val engine = GhostLeakSuppressionEngine()
        val screenW = 1080
        val screenH = 2400

        // Initially no hotspots
        assertFalse(engine.isInMoistureHotspot(500f, 1000f, screenW, screenH))

        // Register 5 moisture touches in the same cell (e.g. at 500f, 1000f)
        for (i in 1..5) {
            engine.registerGhostTouch(500f, 1000f, 30L, screenW, screenH)
        }

        // Cell should now be recognized as a moisture hotspot
        assertTrue(engine.isInMoistureHotspot(500f, 1000f, screenW, screenH))
        // Unrelated screen area should NOT be a hotspot
        assertFalse(engine.isInMoistureHotspot(100f, 100f, screenW, screenH))
    }

    @Test
    fun testStormSuppressionTrigger() {
        val engine = GhostLeakSuppressionEngine()
        val screenW = 1080
        val screenH = 2400

        assertFalse(engine.isStormSuppressionActive)

        // Burst of rapid touches (<30ms) triggers storm suppression autonomously
        for (i in 1..8) {
            engine.registerGhostTouch(300f, 600f, 15L, screenW, screenH)
        }

        assertTrue(engine.isStormSuppressionActive)
        engine.releaseStorm()
        assertFalse(engine.isStormSuppressionActive)
    }

    @Test
    fun testGhostTypeClassificationZeroMomentumTremorless() {
        val classifier = GhostTypeClassifier()
        val emptyMargin = FingerIntensityMargin()

        // Water droplet: Zero chassis momentum (0.002g) + zero micro-tremor (0.05 px²)
        val isGhost = classifier.isGhostType(
            dwellMs = 80,
            size = 0.05f,
            pressure = 0.25f, // Water can have conductive voltage/pressure!
            accelDelta = 0.002f, // But zero physical shockwave!
            hasImpulse = false,
            microTremorVar = 0.05f, // And zero biological micro-tremor!
            intervalMs = 70L,
            isStylus = false,
            inGreenZone = false,
            fingerMargin = emptyMargin
        )

        // MUST be classified as Ghost Type!
        assertTrue("Water droplet with zero impulse and zero tremor must be blocked as Ghost Type!", isGhost)
    }

    @Test
    fun testGhostTypeClassificationHumanWithMomentum() {
        val classifier = GhostTypeClassifier()
        val emptyMargin = FingerIntensityMargin()

        // Human finger tap: Physical chassis momentum (0.045g) with natural micro-tremor (0.55 px²)
        val isGhost = classifier.isGhostType(
            dwellMs = 120,
            size = 0.08f,
            pressure = 0.40f,
            accelDelta = 0.045f,
            hasImpulse = true,
            microTremorVar = 0.55f,
            intervalMs = 300L,
            isStylus = false,
            inGreenZone = false,
            fingerMargin = emptyMargin
        )

        // Must NOT be blocked!
        assertFalse("Real human tap with physical momentum and micro-tremor must pass!", isGhost)
    }

    @Test
    fun testGhostTypeSubHumanBurstBlocked() {
        val classifier = GhostTypeClassifier()
        val emptyMargin = FingerIntensityMargin()

        // Sub-human interval (15ms burst): physically impossible for humans
        val isGhost = classifier.isGhostType(
            dwellMs = 10,
            size = 0.08f,
            pressure = 0.50f,
            accelDelta = 0.005f,
            hasImpulse = false,
            microTremorVar = 0.0f,
            intervalMs = 15L,
            isStylus = false,
            inGreenZone = false,
            fingerMargin = emptyMargin
        )

        assertTrue("Sub-45ms touch burst must be blocked as Ghost Type!", isGhost)
    }
}
