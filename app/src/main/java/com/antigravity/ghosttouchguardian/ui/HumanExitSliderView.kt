package com.antigravity.ghosttouchguardian.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

class HumanExitSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onHumanVerifiedExit: (() -> Unit)? = null

    // Track paint
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF141A26.toInt()
        style = Paint.Style.FILL
    }

    // Border paint
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2A364F.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    // Progress paint
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x5500D2FF.toInt()
        style = Paint.Style.FILL
    }

    // Knob paint
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3D7EFE.toInt()
        style = Paint.Style.FILL
    }

    // Text paint
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCBD5E1.toInt()
        textSize = 34f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // Hint paint
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF64748B.toInt()
        textSize = 26f
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
        val touchSize = event.size

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Knob check
                val distFromKnob = Math.abs(x - knobX)
                if (distFromKnob <= knobRadius * 1.5f) {
                    isDragging = true
                    dragStartTime = event.eventTime
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val minX = trackRect.left + knobRadius
                    val maxX = trackRect.right - knobRadius

                    // Bound knob
                    knobX = x.coerceIn(minX, maxX)
                    invalidate()

                    // Threshold check
                    if (knobX >= maxX - 10f) {
                        val strokeDuration = event.eventTime - dragStartTime
                        // Human check
                        if (strokeDuration >= 200L && touchSize >= 0.04f) {
                            isDragging = false
                            onHumanVerifiedExit?.invoke()
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                // Smooth reset
                val startX = knobX
                val endX = trackRect.left + knobRadius
                val animator = ValueAnimator.ofFloat(startX, endX).apply {
                    duration = 200
                    interpolator = DecelerateInterpolator()
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

        // Draw track
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, trackPaint)
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, borderPaint)

        // Draw progress
        progressRect.set(trackRect.left, trackRect.top, knobX + knobRadius, trackRect.bottom)
        canvas.drawRoundRect(progressRect, cornerRadius, cornerRadius, progressPaint)

        // Draw text
        val textY = trackRect.centerY() - 4f
        canvas.drawText(">>> SLIDE TO EXIT >>>", trackRect.centerX(), textY, textPaint)
        canvas.drawText("Human Finger Verified", trackRect.centerX(), textY + 34f, hintPaint)

        // Draw knob
        canvas.drawCircle(knobX, trackRect.centerY(), knobRadius - 6f, knobPaint)
    }
}
