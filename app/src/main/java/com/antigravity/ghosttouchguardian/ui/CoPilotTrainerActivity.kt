package com.antigravity.ghosttouchguardian.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.antigravity.ghosttouchguardian.data.CompactTouchRepository
import com.antigravity.ghosttouchguardian.data.GuardianPreferences
import com.antigravity.ghosttouchguardian.databinding.ActivityCoPilotTrainerBinding
import com.antigravity.ghosttouchguardian.engine.ai.ExtendedTouchVector
import com.antigravity.ghosttouchguardian.engine.ai.MasterTouchClassifier
import com.antigravity.ghosttouchguardian.sensor.MechanicalImpulseSensor
import com.antigravity.ghosttouchguardian.service.HotspotShieldService
import kotlinx.coroutines.launch

/**
 * CoPilotTrainerActivity — Interactive Human-in-the-Loop Teaching Arena.
 *
 * Implements the user's vision:
 * "Ghost touch mode mein jo touch ho raha hai uss jagah pe mark karna aur ye bhi bataana
 *  tumhe ye ghost touch lag raha hai ya finger touch phir main bataunga ye ghost touch hai...
 *  iska half brain main banunga jo mistake ko dekhta hai."
 */
class CoPilotTrainerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCoPilotTrainerBinding
    private lateinit var repository: CompactTouchRepository
    private lateinit var prefs: GuardianPreferences
    private var impulseSensor: MechanicalImpulseSensor? = null

    private lateinit var masterClassifier: MasterTouchClassifier

    // Active touch tracking
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var downPressure = 0f
    private var lastAnyTouchTime = 0L
    private var lastHumanLiftTime = 0L
    private val movePoints = mutableListOf<Pair<Float, Float>>()

    private var latestVector: ExtendedTouchVector? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCoPilotTrainerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = GuardianPreferences(this)
        repository = CompactTouchRepository(this)
        masterClassifier = repository.loadBrain()
        impulseSensor = MechanicalImpulseSensor(this).also { it.start() }

        // Pause shield service during interactive training
        startService(Intent(this, HotspotShieldService::class.java).apply {
            action = HotspotShieldService.ACTION_PAUSE
        })

        lockImmersive()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(this@CoPilotTrainerActivity, "Slide bottom bar to exit →", Toast.LENGTH_SHORT).show()
            }
        })

        setupButtons()
        setupSlideExit()
        updateBadges()
    }

    private fun lockImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val ctrl = WindowInsetsControllerCompat(window, window.decorView)
        ctrl.hide(WindowInsetsCompat.Type.systemBars())
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupButtons() {
        binding.btnMarkAsGhost.setOnClickListener {
            val vec = latestVector
            if (vec == null) {
                Toast.makeText(this, "Wait for a touch to occur first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            masterClassifier.supervise(vec, actuallyGhost = true)
            vibrateFeedback(isGhost = true)
            updateBadges()
            saveBrainAsync()

            binding.tvLiveVerdict.text = "⚠️ CORRECTED: MARKED AS GHOST!"
            binding.tvLiveVerdict.setTextColor(Color.parseColor("#EF4444"))
            Toast.makeText(this, "✅ Learned! This water pattern marked as GHOST.", Toast.LENGTH_SHORT).show()
        }

        binding.btnMarkAsFinger.setOnClickListener {
            val vec = latestVector
            if (vec == null) {
                Toast.makeText(this, "Wait for a touch to occur first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lastHumanLiftTime = System.currentTimeMillis()
            masterClassifier.supervise(vec, actuallyGhost = false)
            vibrateFeedback(isGhost = false)
            updateBadges()
            saveBrainAsync()

            binding.tvLiveVerdict.text = "✅ CONFIRMED: REAL FINGER TOUCH!"
            binding.tvLiveVerdict.setTextColor(Color.parseColor("#22C55E"))
            Toast.makeText(this, "✅ Learned! This pattern confirmed as HUMAN FINGER.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateBadges() {
        binding.tvUserCorrectionCount.text = masterClassifier.totalUserCorrections.toString()
        binding.tvWaterMemoryCount.text = "${masterClassifier.getSupervisoryCount()} Signatures"
        binding.tvBrainState.text = if (masterClassifier.getSupervisoryCount() > 0) "ARMED" else "LEARNING"
        binding.tvBrainState.setTextColor(
            if (masterClassifier.getSupervisoryCount() > 0) Color.parseColor("#22C55E") else Color.parseColor("#00E5FF")
        )
    }

    private fun saveBrainAsync() {
        lifecycleScope.launch {
            repository.saveBrainAsync(masterClassifier)
            prefs.ghostBrainEngineJson = masterClassifier.toJson()
        }
    }

    private fun vibrateFeedback(isGhost: Boolean) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (isGhost) {
                    VibrationEffect.createWaveform(longArrayOf(0, 50, 40, 50), -1)
                } else {
                    VibrationEffect.createOneShot(40L, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(if (isGhost) 100L else 40L)
            }
        } catch (_: Exception) {}
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) return super.dispatchTouchEvent(event)

        // Slide Exit handler always gets own touches
        val sliderBounds = binding.humanExitSlider.let {
            val loc = IntArray(2); it.getLocationOnScreen(loc)
            Rect(loc[0], loc[1], loc[0] + it.width, loc[1] + it.height)
        }
        if (sliderBounds.contains(event.rawX.toInt(), event.rawY.toInt())) {
            return super.dispatchTouchEvent(event)
        }

        // Decision panel button touches pass to buttons
        val decisionBounds = binding.decisionPanel.let {
            val loc = IntArray(2); it.getLocationOnScreen(loc)
            Rect(loc[0], loc[1], loc[0] + it.width, loc[1] + it.height)
        }
        if (decisionBounds.contains(event.rawX.toInt(), event.rawY.toInt())) {
            return super.dispatchTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                downPressure = event.pressure
                movePoints.clear()
                movePoints.add(Pair(event.x, event.y))

                // Place animated visual ring directly where the touch hit the glass
                showTouchRing(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                movePoints.add(Pair(event.x, event.y))
            }
            MotionEvent.ACTION_UP -> {
                val now = event.eventTime
                val dwell = (now - downTime).toInt().coerceAtLeast(4)
                val interval = if (lastAnyTouchTime > 0L) downTime - lastAnyTouchTime else dwell.toLong()
                val timeSinceHuman = if (lastHumanLiftTime > 0L) downTime - lastHumanLiftTime else -1L
                val accelDelta = impulseSensor?.getLastAccelDelta() ?: 0f

                lastAnyTouchTime = now

                val vector = ExtendedTouchVector.extract(
                    event = event,
                    dwellMs = dwell,
                    downX = downX,
                    downY = downY,
                    downPressure = downPressure,
                    accelDelta = accelDelta,
                    intervalMs = interval,
                    timeSinceStimulusMs = timeSinceHuman,
                    movePoints = movePoints
                )
                latestVector = vector

                // Evaluate with Master AI Brain
                val decision = masterClassifier.classify(vector, event.getToolType(0))

                binding.tvArenaHint.visibility = View.GONE
                updateDecisionUI(decision, vector, downX, downY)
            }
        }

        return true
    }

    private fun showTouchRing(x: Float, y: Float) {
        binding.touchRingView.x = (x - binding.touchRingView.width / 2f).coerceAtLeast(0f)
        binding.touchRingView.y = (y - binding.touchRingView.height / 2f).coerceAtLeast(0f)
        binding.touchRingView.visibility = View.VISIBLE
        binding.touchRingView.scaleX = 0.4f
        binding.touchRingView.scaleY = 0.4f
        binding.touchRingView.alpha = 1.0f

        binding.touchRingView.animate()
            .scaleX(1.4f)
            .scaleY(1.4f)
            .alpha(0.0f)
            .setDuration(450)
            .withEndAction {
                binding.touchRingView.visibility = View.GONE
            }
            .start()
    }

    private fun updateDecisionUI(
        decision: MasterTouchClassifier.MasterDecision,
        vector: ExtendedTouchVector,
        x: Float,
        y: Float
    ) {
        val prob = decision.ghostProbability
        if (decision.isGhost) {
            binding.tvLiveVerdict.text = "🔴 AI GUESS: GHOST (${(prob * 100).toInt()}%)"
            binding.tvLiveVerdict.setTextColor(Color.parseColor("#EF4444"))
            binding.decisionPanel.strokeColor = Color.parseColor("#EF4444")
        } else {
            binding.tvLiveVerdict.text = "🟢 AI GUESS: FINGER (${((1.0f - prob) * 100).toInt()}%)"
            binding.tvLiveVerdict.setTextColor(Color.parseColor("#22C55E"))
            binding.decisionPanel.strokeColor = Color.parseColor("#22C55E")
        }

        binding.tvTouchDetails.text = buildString {
            append("📍 Pos: (${x.toInt()}, ${y.toInt()})  |  ⏱ Dwell: ${vector.dwell.toInt()}ms\n")
            append("📐 Area Size: ${String.format("%.3f", vector.size)}  |  ⚡ Momentum: ${String.format("%.3f", vector.accelDelta)} m/s²\n")
            append("〰️ Micro-Tremor: ${String.format("%.2f", vector.microTremorVariance)} px²  |  Factor: ${decision.dominantFactor}")
        }
    }

    private fun setupSlideExit() {
        binding.humanExitSlider.onHumanVerifiedExit = {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        impulseSensor?.stop()

        // Resume background shield service upon exit
        if (prefs.isHotspotShieldEnabled) {
            startService(Intent(this, HotspotShieldService::class.java).apply {
                action = HotspotShieldService.ACTION_REFRESH_ZONES
            })
            startService(Intent(this, HotspotShieldService::class.java).apply {
                action = HotspotShieldService.ACTION_START
            })
        }
    }
}
