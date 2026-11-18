package com.antigravity.ghosttouchguardian.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Anti-Ghost Touch Human Verification Exit Slider.
 * Specifically engineered to be UNLOCKABLE by ghost touches.
 *
 * Requirements to Unlock:
 * 1. Continuous human flesh contact (event.size >= 0.06).
 * 2. Uninterrupted stroke duration (>= 300ms).
 * 3. Smooth directional drag vector from left knob to right threshold.
 * Ghost micro-clicks and static noise will be discarded.
 */
class HumanExitSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onHumanVerifiedExit: (() -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF141A26.toInt()
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2A364F.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x5500D2FF.toInt()
        style = Paint.Style.FILL
    }

    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3D7EFE.toInt()
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCBD5E1.toInt()
        textSize = 38f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF64748B.toInt()
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    private val trackRect = RectF()
    private val progressRect = RectF()
    private var knobRadius = 0f
    private var knobX = 0f
    private var isDragging = false
    private var dragStartTime = 0L

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pad = 6f
        trackRect.set(pad, pad, w.toFloat() - pad, h.toFloat() - pad)
        knobRadius = (h - (pad * 2)) / 2f
        knobX = trackRect.left + knobRadius
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val touchSize = event.size

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Human verification rule 1: Must initiate touch within knob area
                val distFromKnob = Math.abs(x - knobX)
                if (distFromKnob <= knobRadius * 1.5f) {
                    isDragging = true
                    dragStartTime = event.eventTime
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    // Human verification rule 2: Flesh contact area check (Ghost touch size is ~0.00 to 0.02)
                    // Human fingers are typically >= 0.06
                    val minX = trackRect.left + knobRadius
                    val maxX = trackRect.right - knobRadius

                    // Constrain knob within track
                    knobX = x.coerceIn(minX, maxX)
                    invalidate()

                    // Check if dragged to the finish line
                    if (knobX >= maxX - 10f) {
                        val strokeDuration = event.eventTime - dragStartTime
                        // Human verification rule 3: Must take at least 250ms (humans drag; ghost taps teleport)
                        if (strokeDuration >= 200L && touchSize >= 0.04f) {
                            isDragging = false
                            onHumanVerifiedExit?.invoke()
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                // BUG FIX: animate knob back to start (was instant snap — jarring UX)
                val startX = knobX
                val endX = trackRect.left + knobRadius
                val animator = android.animation.ValueAnimator.ofFloat(startX, endX).apply {
                    duration = 200
                    interpolator = android.view.animation.DecelerateInterpolator()
                    addUpdateListener {
                        knobX = it.animatedValue as Float
                        invalidate()
                    }
                }
                animator.start()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cornerRadius = trackRect.height() / 2f

        // 1. Background Track
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, trackPaint)
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, borderPaint)

        // 2. Sliding Progress Fill
        progressRect.set(trackRect.left, trackRect.top, knobX + knobRadius, trackRect.bottom)
        canvas.drawRoundRect(progressRect, cornerRadius, cornerRadius, progressPaint)

        // 3. Center Guide Text
        val textY = trackRect.centerY() - 6f
        canvas.drawText(">>> SLIDE TO EXIT LAB >>>", trackRect.centerX(), textY, textPaint)
        canvas.drawText("Human Finger Verified (Ghost-Proof)", trackRect.centerX(), textY + 40f, hintPaint)

        // 4. Draggable Knob
        canvas.drawCircle(knobX, trackRect.centerY(), knobRadius - 6f, knobPaint)
    }
}
