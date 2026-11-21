package com.antigravity.ghosttouchguardian.ui

import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.antigravity.ghosttouchguardian.R
import com.antigravity.ghosttouchguardian.data.CompactTouchRepository
import com.antigravity.ghosttouchguardian.data.GuardianPreferences
import com.antigravity.ghosttouchguardian.engine.ai.ExtendedTouchVector
import com.antigravity.ghosttouchguardian.engine.ai.GhostBrainEngine
import com.antigravity.ghosttouchguardian.engine.ai.MasterTouchClassifier
import com.antigravity.ghosttouchguardian.model.FingerIntensityMargin
import com.antigravity.ghosttouchguardian.model.TouchFeatureVector
import com.antigravity.ghosttouchguardian.sensor.MechanicalImpulseSensor
import com.google.android.material.button.MaterialButton
import kotlin.math.max

/**
 * FingerMarginProfilerActivity — Dedicated Dynamic Intensity Measurement Arena.
 *
 * Allows the user to calibrate their finger intensity across all levels (light, normal, firm)
 * while strictly blocking and identifying background water ghost touches.
 */
class FingerMarginProfilerActivity : AppCompatActivity() {

    private lateinit var prefs: GuardianPreferences
    private lateinit var repository: CompactTouchRepository
    private lateinit var impulseSensor: MechanicalImpulseSensor
    private lateinit var masterClassifier: MasterTouchClassifier
    private lateinit var brainEngine: GhostBrainEngine

    private var margin = FingerIntensityMargin()
    private var blockedGhostsInSession = 0

    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var downPressure = 0f
    private val movePoints = mutableListOf<Pair<Float, Float>>()

    private lateinit var tvIntensityBadge: TextView
    private lateinit var tvLivePressure: TextView
    private lateinit var tvLiveSize: TextView
    private lateinit var tvLiveDwell: TextView
    private lateinit var tvLiveImpulse: TextView
    private lateinit var tvMarginSummary: TextView
    private lateinit var tvGhostShieldCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_finger_margin_profiler)

        prefs = GuardianPreferences(this)
        repository = CompactTouchRepository(this)
        masterClassifier = repository.loadBrain()
        brainEngine = GhostBrainEngine.fromJson(prefs.ghostBrainEngineJson)

        margin = FingerIntensityMargin.fromJson(prefs.fingerIntensityMarginJson)

        impulseSensor = MechanicalImpulseSensor(this).apply { start() }

        tvIntensityBadge = findViewById(R.id.tvIntensityBadge)
        tvLivePressure = findViewById(R.id.tvLivePressure)
        tvLiveSize = findViewById(R.id.tvLiveSize)
        tvLiveDwell = findViewById(R.id.tvLiveDwell)
        tvLiveImpulse = findViewById(R.id.tvLiveImpulse)
        tvMarginSummary = findViewById(R.id.tvMarginSummary)
        tvGhostShieldCount = findViewById(R.id.tvGhostShieldCount)

        updateMarginSummaryUI()

        findViewById<MaterialButton>(R.id.btnCloseProfiler).setOnClickListener {
            finish()
        }

        findViewById<MaterialButton>(R.id.btnResetMargin).setOnClickListener {
            margin = FingerIntensityMargin()
            prefs.fingerIntensityMarginJson = ""
            updateMarginSummaryUI()
            tvIntensityBadge.text = "MARGIN RESET"
            tvIntensityBadge.setBackgroundColor(Color.parseColor("#78909C"))
            Toast.makeText(this, "Finger margin reset to default", Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.btnSaveMargin).setOnClickListener {
            if (margin.sampleCount < 3) {
                Toast.makeText(this, "Please record at least 3 taps across varied intensities first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.fingerIntensityMarginJson = margin.toJson()
            repository.saveBrain(masterClassifier)
            prefs.ghostBrainEngineJson = brainEngine.toJson()
            Toast.makeText(this, "✅ Margin envelope saved! Ghost filter personalized.", Toast.LENGTH_LONG).show()
            finish()
        }

        val tapSurface = findViewById<android.view.View>(R.id.viewTapSurface)
        tapSurface.setOnTouchListener { _, event ->
            handleProfilerTouch(event)
            true
        }
    }

    private fun handleProfilerTouch(event: MotionEvent) {
        val now = event.eventTime
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = now
                downX = event.x
                downY = event.y
                downPressure = event.pressure
                movePoints.clear()
                movePoints.add(Pair(event.x, event.y))
            }
            MotionEvent.ACTION_MOVE -> {
                movePoints.add(Pair(event.x, event.y))
            }
            MotionEvent.ACTION_UP -> {
                val dwell = (now - downTime).toInt().coerceAtLeast(1)
                val accelDelta = impulseSensor.getLastAccelDelta()
                val hasImpulse = impulseSensor.hasMechanicalImpulse(downTime, windowMs = 120L)
                val effectiveAccel = if (hasImpulse) max(accelDelta, 0.025f) else accelDelta

                // Compute biological micro-tremor
                var tremorVar = 0f
                if (movePoints.size >= 3) {
                    val meanX = movePoints.map { it.first }.average().toFloat()
                    val meanY = movePoints.map { it.second }.average().toFloat()
                    val sqDiffSum = movePoints.fold(0f) { acc, p ->
                        val px = p.first - meanX
                        val py = p.second - meanY
                        acc + (px * px + py * py)
                    }
                    tremorVar = (sqDiffSum / movePoints.size.toFloat()).coerceIn(0f, 25f)
                }

                // -------------------------------------------------------------
                // PHYSICAL SEPARATION: REAL HUMAN VS WATER GHOST
                // -------------------------------------------------------------
                if (hasImpulse || effectiveAccel >= 0.020f || tremorVar >= 0.45f) {
                    // VERIFIED HUMAN FINGER TOUCH ACROSS INTENSITY SPECTRUM!
                    margin.recordSample(
                        pressure = event.pressure,
                        size = event.size,
                        dwellMs = dwell,
                        impulseG = effectiveAccel,
                        tremorVar = tremorVar
                    )

                    // Determine intensity category
                    val intensityLabel: String
                    val badgeColor: Int
                    when {
                        event.pressure < 0.20f || effectiveAccel < 0.028f -> {
                            intensityLabel = "🪶 LIGHT / FEATHER TOUCH"
                            badgeColor = Color.parseColor("#42A5F5") // Blue
                        }
                        event.pressure > 0.55f || effectiveAccel >= 0.075f -> {
                            intensityLabel = "💪 FIRM / HEAVY PRESS"
                            badgeColor = Color.parseColor("#AB47BC") // Purple
                        }
                        else -> {
                            intensityLabel = "🖐️ NORMAL HUMAN TAP"
                            badgeColor = Color.parseColor("#00E676") // Green
                        }
                    }

                    tvIntensityBadge.text = intensityLabel
                    tvIntensityBadge.setBackgroundColor(badgeColor)

                    tvLivePressure.text = String.format("%.2f", event.pressure)
                    tvLiveSize.text = String.format("%.3f", event.size)
                    tvLiveDwell.text = "$dwell ms"
                    tvLiveImpulse.text = String.format("%.3f G", effectiveAccel)

                    updateMarginSummaryUI()

                    // Train AI models with human sample
                    val featureVector = TouchFeatureVector(
                        dwell = dwell.toFloat(),
                        size = event.size,
                        pressure = event.pressure,
                        accelDelta = effectiveAccel,
                        interval = 250f,
                        velocity = 0.05f,
                        eccentricity = 1.2f,
                        timeSinceStimulus = 1000f
                    )
                    val extendedVector = ExtendedTouchVector(
                        dwell = dwell.toFloat(),
                        size = event.size,
                        pressure = event.pressure,
                        accelDelta = effectiveAccel,
                        pressureGradient = 0.003f,
                        microTremorVariance = tremorVar,
                        eccentricity = 1.2f,
                        velocity = 0.05f,
                        interval = 250f,
                        timeSinceStimulus = 1000f
                    )
                    brainEngine.learnOnline(featureVector, isGhost = false)
                    masterClassifier.learnOnline(extendedVector, isGhost = false)
                } else {
                    // ZERO PHYSICAL IMPULSE: BACKGROUND GHOST GLITCH INTERCEPTED & BLOCKED!
                    blockedGhostsInSession++
                    tvGhostShieldCount.text = "🛡️ $blockedGhostsInSession Ghosts Blocked"

                    tvIntensityBadge.text = "👻 GHOST GLITCH BLOCKED!"
                    tvIntensityBadge.setBackgroundColor(Color.parseColor("#FF1744")) // Red

                    tvLivePressure.text = String.format("%.2f", event.pressure)
                    tvLiveSize.text = String.format("%.3f", event.size)
                    tvLiveDwell.text = "$dwell ms"
                    tvLiveImpulse.text = "0.000 G"

                    // Feed ghost touch to AI brain
                    val featureVector = TouchFeatureVector(
                        dwell = dwell.toFloat(),
                        size = event.size,
                        pressure = event.pressure,
                        accelDelta = 0.002f,
                        interval = 25f,
                        velocity = 0f,
                        eccentricity = 2.0f,
                        timeSinceStimulus = 100f
                    )
                    val extendedVector = ExtendedTouchVector(
                        dwell = dwell.toFloat(),
                        size = event.size,
                        pressure = event.pressure,
                        accelDelta = 0.002f,
                        pressureGradient = 0f,
                        microTremorVariance = 0f,
                        eccentricity = 2.0f,
                        velocity = 0f,
                        interval = 25f,
                        timeSinceStimulus = 100f
                    )
                    brainEngine.learnOnline(featureVector, isGhost = true)
                    masterClassifier.learnOnline(extendedVector, isGhost = true)
                }
            }
        }
    }

    private fun updateMarginSummaryUI() {
        if (margin.sampleCount == 0) {
            tvMarginSummary.text = "No samples recorded yet. Tap the arena above with light, normal, and firm pressure."
            return
        }

        tvMarginSummary.text = buildString {
            append("• Calibrated Samples: ${margin.sampleCount}\n")
            append("• Pressure Range: ${String.format("%.2f", margin.minPressure)} - ${String.format("%.2f", margin.maxPressure)}\n")
            append("• Contact Size: ${String.format("%.3f", margin.minSize)} - ${String.format("%.3f", margin.maxSize)}\n")
            append("• Dwell Duration: ${margin.minDwellMs} ms - ${margin.maxDwellMs} ms\n")
            append("• Chassis Impulse: ${String.format("%.3f", margin.minImpulseG)} G - ${String.format("%.3f", margin.maxImpulseG)} G")
        }
    }

    override fun onDestroy() {
        impulseSensor.stop()
        super.onDestroy()
    }
}
