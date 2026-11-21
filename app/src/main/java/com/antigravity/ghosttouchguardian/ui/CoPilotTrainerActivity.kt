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
    private val recentUnconfirmedVectors = mutableListOf<ExtendedTouchVector>()

    private var currentMode = MODE_INTERACTIVE

    companion object {
        const val MODE_AUTO_GHOST = 0
        const val MODE_INTERACTIVE = 1
        const val MODE_AUTO_FINGER = 2
    }

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
        setupModeSelectors()

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

        binding.btnThisFingerRestGhost.setOnClickListener {
            val thisVec = latestVector
            if (thisVec == null) {
                Toast.makeText(this, "Wait for a touch to occur first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 1. Mark this specific touch as REAL HUMAN FINGER
            lastHumanLiftTime = System.currentTimeMillis()
            masterClassifier.supervise(thisVec, actuallyGhost = false)

            // 2. Mark all other recent touches in history as GHOST
            var ghostCount = 0
            for (v in recentUnconfirmedVectors) {
                if (v !== thisVec) {
                    masterClassifier.supervise(v, actuallyGhost = true)
                    ghostCount++
                }
            }
            recentUnconfirmedVectors.clear()

            vibrateFeedback(isGhost = false)
            updateBadges()
            saveBrainAsync()

            binding.tvLiveVerdict.text = "🎯 LEARNED: THIS = FINGER, $ghostCount OTHERS = GHOST!"
            binding.tvLiveVerdict.setTextColor(Color.parseColor("#38BDF8"))
            Toast.makeText(
                this,
                "✅ Fast Triage: Current touch confirmed as FINGER, $ghostCount other glitches trained as GHOST!",
                Toast.LENGTH_LONG
            ).show()
        }

        binding.btnMarkAllRecentAsGhost.setOnClickListener {
            if (recentUnconfirmedVectors.isEmpty()) {
                val vec = latestVector
                if (vec != null) {
                    recentUnconfirmedVectors.add(vec)
                } else {
                    Toast.makeText(this, "No recent glitches to batch-mark!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            val count = recentUnconfirmedVectors.size
            for (v in recentUnconfirmedVectors) {
                masterClassifier.supervise(v, actuallyGhost = true)
            }
            recentUnconfirmedVectors.clear()
            vibrateFeedback(isGhost = true)
            updateBadges()
            saveBrainAsync()

            binding.tvLiveVerdict.text = "⚡ BATCH COMPLETED: $count GLITCHES MARKED AS GHOST!"
            binding.tvLiveVerdict.setTextColor(Color.parseColor("#EF4444"))
            Toast.makeText(this, "✅ Batch Saved: All $count glitches added to ghost memory!", Toast.LENGTH_LONG).show()
        }

        binding.btnUndoLastAction.setOnClickListener {
            val undone = masterClassifier.undoLastSupervision()
            if (undone) {
                vibrateFeedback(isGhost = false)
                updateBadges()
                saveBrainAsync()
                binding.tvLiveVerdict.text = "↩️ LAST ACTION UNDONE (Decision Boundary Restored)!"
                binding.tvLiveVerdict.setTextColor(Color.parseColor("#F59E0B"))
                Toast.makeText(this, "↩️ Reverted last teach action successfully!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No previous actions to undo!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupModeSelectors() {
        binding.btnModeAutoGhost.setOnClickListener {
            setFastMode(MODE_AUTO_GHOST)
        }
        binding.btnModeInteractive.setOnClickListener {
            setFastMode(MODE_INTERACTIVE)
        }
        binding.btnModeAutoFinger.setOnClickListener {
            setFastMode(MODE_AUTO_FINGER)
        }
        updateModeUI()
    }

    private fun setFastMode(mode: Int) {
        currentMode = mode
        updateModeUI()
        val msg = when (mode) {
            MODE_AUTO_GHOST -> "🔥 AUTO GHOST HARVEST: All incoming touches automatically marked as GHOST (Zero clicks needed)!"
            MODE_AUTO_FINGER -> "🟢 AUTO FINGER MODE: Type/tap naturally; all touches recorded as your finger!"
            else -> "✋ 1-BY-1 CO-PILOT: Interactive single touch review mode."
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun updateModeUI() {
        val activeBg = Color.parseColor("#2563EB")
        val ghostActiveBg = Color.parseColor("#EF4444")
        val fingerActiveBg = Color.parseColor("#22C55E")

        when (currentMode) {
            MODE_AUTO_GHOST -> {
                binding.btnModeAutoGhost.setBackgroundColor(ghostActiveBg)
                binding.btnModeAutoGhost.setTextColor(Color.WHITE)
                binding.btnModeInteractive.setBackgroundColor(Color.TRANSPARENT)
                binding.btnModeInteractive.setTextColor(Color.parseColor("#94A3B8"))
                binding.btnModeAutoFinger.setBackgroundColor(Color.TRANSPARENT)
                binding.btnModeAutoFinger.setTextColor(Color.parseColor("#94A3B8"))
            }
            MODE_INTERACTIVE -> {
                binding.btnModeAutoGhost.setBackgroundColor(Color.TRANSPARENT)
                binding.btnModeAutoGhost.setTextColor(Color.parseColor("#FF5252"))
                binding.btnModeInteractive.setBackgroundColor(activeBg)
                binding.btnModeInteractive.setTextColor(Color.WHITE)
                binding.btnModeAutoFinger.setBackgroundColor(Color.TRANSPARENT)
                binding.btnModeAutoFinger.setTextColor(Color.parseColor("#22C55E"))
            }
            MODE_AUTO_FINGER -> {
                binding.btnModeAutoGhost.setBackgroundColor(Color.TRANSPARENT)
                binding.btnModeAutoGhost.setTextColor(Color.parseColor("#94A3B8"))
                binding.btnModeInteractive.setBackgroundColor(Color.TRANSPARENT)
                binding.btnModeInteractive.setTextColor(Color.parseColor("#94A3B8"))
                binding.btnModeAutoFinger.setBackgroundColor(fingerActiveBg)
                binding.btnModeAutoFinger.setTextColor(Color.parseColor("#070A11"))
            }
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

        // Mode selector bar touches pass to buttons
        val modeBarBounds = binding.modeSelectorBar.let {
            val loc = IntArray(2); it.getLocationOnScreen(loc)
            Rect(loc[0], loc[1], loc[0] + it.width, loc[1] + it.height)
        }
        if (modeBarBounds.contains(event.rawX.toInt(), event.rawY.toInt())) {
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

                // Fast Batch Modes: Zero manual clicks needed!
                when (currentMode) {
                    MODE_AUTO_GHOST -> {
                        masterClassifier.supervise(vector, actuallyGhost = true)
                        vibrateFeedback(isGhost = true)
                        updateBadges()
                        saveBrainAsync()
                        binding.tvArenaHint.visibility = View.GONE
                        binding.tvLiveVerdict.text = "🔴 AUTO-LEARNED AS GHOST #${masterClassifier.getSupervisoryCount()} (${vector.dwell.toInt()}ms)"
                        binding.tvLiveVerdict.setTextColor(Color.parseColor("#EF4444"))
                        binding.decisionPanel.strokeColor = Color.parseColor("#EF4444")
                        binding.tvTouchDetails.text = "Water glitch auto-absorbed without clicking! Dwell: ${vector.dwell.toInt()}ms | Pos: (${downX.toInt()}, ${downY.toInt()})"
                    }
                    MODE_AUTO_FINGER -> {
                        lastHumanLiftTime = now
                        masterClassifier.supervise(vector, actuallyGhost = false)
                        vibrateFeedback(isGhost = false)
                        updateBadges()
                        saveBrainAsync()
                        binding.tvArenaHint.visibility = View.GONE
                        binding.tvLiveVerdict.text = "🟢 AUTO-LEARNED AS REAL FINGER (${vector.dwell.toInt()}ms)"
                        binding.tvLiveVerdict.setTextColor(Color.parseColor("#22C55E"))
                        binding.decisionPanel.strokeColor = Color.parseColor("#22C55E")
                        binding.tvTouchDetails.text = "Finger biometric auto-absorbed! Dwell: ${vector.dwell.toInt()}ms | Tremor: ${String.format("%.2f", vector.microTremorVariance)} px²"
                    }
                    else -> {
                        // MODE_INTERACTIVE: Queue in recent unconfirmed list for 1-tap or batch action
                        recentUnconfirmedVectors.add(vector)
                        if (recentUnconfirmedVectors.size > 30) recentUnconfirmedVectors.removeAt(0)
                        val decision = masterClassifier.classify(vector, event.getToolType(0))
                        binding.tvArenaHint.visibility = View.GONE
                        updateDecisionUI(decision, vector, downX, downY)
                    }
                }
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
            val refreshIntent = Intent(this, HotspotShieldService::class.java).apply {
                action = HotspotShieldService.ACTION_REFRESH_ZONES
            }
            androidx.core.content.ContextCompat.startForegroundService(this, refreshIntent)
        }
    }
}
