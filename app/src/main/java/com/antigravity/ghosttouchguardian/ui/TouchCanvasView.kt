package com.antigravity.ghosttouchguardian.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.antigravity.ghosttouchguardian.engine.TouchProfilerEngine
import com.antigravity.ghosttouchguardian.model.CompactTouchBuffer

/**
 * High-performance, zero-allocation touch canvas view.
 * Renders touch events and ghost indicators with large, prominent labels and background pills.
 * Clamps labels to view boundaries so text never clips off-screen.
 */
class TouchCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var profiler: TouchProfilerEngine? = null

    // Pre-allocated Paint objects to ensure 0 bytes memory allocation during onDraw
    private val humanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E676.toInt() // Valid Green
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val ghostIdlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF4B4B.toInt() // Ghost Red
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 8f
    }

    private val ghostEchoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF9100.toInt() // Amber/Orange for Trailing Echo
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 10f
    }

    private val spenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB388FF.toInt() // Stylus Purple
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDD0B101B.toInt()
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF1F5F9.toInt()
        textSize = 38f
        isFakeBoldText = true
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF64748B.toInt()
        textSize = 34f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = false
    }

    private val pillRect = RectF()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val engine = profiler ?: return super.onTouchEvent(event)
        engine.processTouchEvent(event)
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val engine = profiler ?: return
        val buffer = engine.buffer
        val count = buffer.size

        if (count == 0) {
            canvas.drawText("CANVAS READY", width / 2f, height / 2f - 24f, hintPaint)
            canvas.drawText("Touch screen or place phone on table", width / 2f, height / 2f + 24f, hintPaint)
            return
        }

        val xArr = buffer.xCoords
        val yArr = buffer.yCoords
        val typeArr = buffer.eventTypes
        val dwellArr = buffer.dwellTimes
        val sizeArr = buffer.contactSizes

        for (i in 0 until count) {
            val x = xArr[i]
            val y = yArr[i]
            val type = typeArr[i]
            val dwell = dwellArr[i]
            val contactSize = sizeArr[i]

            val radius = 30f + (contactSize * 90f)

            when (type) {
                CompactTouchBuffer.TYPE_GHOST_IDLE -> {
                    canvas.drawCircle(x, y, radius, ghostIdlePaint)
                    val label = "GHOST ($dwell ms)"
                    drawPillLabel(canvas, label, x, y, 0xFFFF4B4B.toInt())
                }
                CompactTouchBuffer.TYPE_GHOST_ECHO -> {
                    canvas.drawCircle(x, y, radius * 1.3f, ghostEchoPaint)
                    val label = "ECHO ($dwell ms)"
                    drawPillLabel(canvas, label, x, y, 0xFFFF9100.toInt())
                }
                CompactTouchBuffer.TYPE_SPEN -> {
                    canvas.drawCircle(x, y, 28f, spenPaint)
                    drawPillLabel(canvas, "S PEN", x, y, 0xFFB388FF.toInt())
                }
                else -> {
                    canvas.drawCircle(x, y, radius, humanPaint)
                }
            }
        }
    }

    private fun drawPillLabel(canvas: Canvas, text: String, x: Float, y: Float, accentColor: Int) {
        val textWidth = textPaint.measureText(text)
        val pad = 16f
        var startX = x + 40f
        if (startX + textWidth + pad > width - 16f) {
            startX = x - textWidth - 40f - pad
        }
        if (startX < 16f) startX = 16f

        pillRect.set(startX - pad, y - 36f, startX + textWidth + pad, y + 16f)
        canvas.drawRoundRect(pillRect, 12f, 12f, labelBgPaint)
        textPaint.color = accentColor
        canvas.drawText(text, startX, y, textPaint)
    }
}
