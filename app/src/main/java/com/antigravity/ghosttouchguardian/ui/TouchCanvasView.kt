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

class TouchCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var profiler: TouchProfilerEngine? = null

    // Valid green
    private val humanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E676.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    // Ghost red
    private val ghostIdlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF4B4B.toInt()
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 8f
    }

    // Echo amber
    private val ghostEchoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF9100.toInt()
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 10f
    }

    // Stylus purple
    private val spenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB388FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    // Label bg
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEE0B101B.toInt()
        style = Paint.Style.FILL
    }

    // Label text
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF1F5F9.toInt()
        textSize = 34f
        isFakeBoldText = true
    }

    // Ready hint
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF64748B.toInt()
        textSize = 32f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = false
    }

    // Mode title paint
    private val modeTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF38BDF8.toInt()
        textSize = 38f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // Ignored hint paint
    private val ignoredHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E676.toInt()
        textSize = 28f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val pillRect = RectF()
    private var lastIgnoredDwell: Int = -1
    private var lastIgnoredTime: Long = 0L

    init {
        // Init view
    }

    fun setIgnoredFingerTelemetry(dwell: Int) {
        lastIgnoredDwell = dwell
        lastIgnoredTime = System.currentTimeMillis()
        invalidate()
    }

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
        val isGhostOnly = engine.isGhostOnlyMode

        // Mode banner at top
        if (isGhostOnly) {
            canvas.drawText("GHOST-ONLY FILTER ACTIVE", width / 2f, 60f, modeTitlePaint)
            val isRecentlyIgnored = (System.currentTimeMillis() - lastIgnoredTime) < 2000L
            if (isRecentlyIgnored && lastIgnoredDwell > 0) {
                canvas.drawText("Finger Tap Ignored (${lastIgnoredDwell}ms) • Only Ghost Taps Allowed", width / 2f, 105f, ignoredHintPaint)
            } else {
                canvas.drawText("Finger taps are filtered out • Waiting for real ghost touch...", width / 2f, 105f, hintPaint)
            }
        }

        if (count == 0) {
            if (isGhostOnly) {
                canvas.drawText("FILTER ENGAGED", width / 2f, height / 2f - 20f, hintPaint)
                canvas.drawText("Tap anywhere: Real fingers will NOT trigger", width / 2f, height / 2f + 24f, hintPaint)
                canvas.drawText("Only actual ghost touches will appear in RED", width / 2f, height / 2f + 68f, hintPaint)
            } else {
                canvas.drawText("DIAGNOSTIC CANVAS READY", width / 2f, height / 2f - 20f, hintPaint)
                canvas.drawText("Tap screen to test click latency & physical profile", width / 2f, height / 2f + 24f, hintPaint)
            }
            return
        }

        // Draw recorded events
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
            val radius = 28f + (contactSize * 85f)

            when (type) {
                CompactTouchBuffer.TYPE_GHOST_IDLE -> {
                    canvas.drawCircle(x, y, radius, ghostIdlePaint)
                    drawPillLabel(canvas, "GHOST ($dwell ms)", x, y, 0xFFFF4B4B.toInt())
                }
                CompactTouchBuffer.TYPE_GHOST_ECHO -> {
                    canvas.drawCircle(x, y, radius * 1.3f, ghostEchoPaint)
                    drawPillLabel(canvas, "ECHO ($dwell ms)", x, y, 0xFFFF9100.toInt())
                }
                CompactTouchBuffer.TYPE_GHOST_TELEPORT -> {
                    canvas.drawCircle(x, y, radius * 1.4f, ghostIdlePaint)
                    drawPillLabel(canvas, "EMI TELEPORT ($dwell ms)", x, y, 0xFFFF1744.toInt())
                }
                CompactTouchBuffer.TYPE_SPEN -> {
                    canvas.drawCircle(x, y, 26f, spenPaint)
                    drawPillLabel(canvas, "S PEN", x, y, 0xFFB388FF.toInt())
                }
                CompactTouchBuffer.TYPE_HUMAN_FAST_TAP -> {
                    canvas.drawCircle(x, y, radius, humanPaint)
                    drawPillLabel(canvas, "HUMAN CLICK ($dwell ms)", x, y, 0xFF00E676.toInt())
                }
                else -> {
                    canvas.drawCircle(x, y, radius, humanPaint)
                    drawPillLabel(canvas, "HUMAN ($dwell ms)", x, y, 0xFF00E676.toInt())
                }
            }
        }
    }

    private fun drawPillLabel(canvas: Canvas, text: String, x: Float, y: Float, accentColor: Int) {
        val textWidth = textPaint.measureText(text)
        val pad = 14f
        var startX = x + 36f
        if (startX + textWidth + pad > width - 16f) {
            startX = x - textWidth - 36f - pad
        }
        if (startX < 16f) startX = 16f

        pillRect.set(startX - pad, y - 32f, startX + textWidth + pad, y + 14f)
        canvas.drawRoundRect(pillRect, 10f, 10f, labelBgPaint)
        textPaint.color = accentColor
        canvas.drawText(text, startX, y, textPaint)
    }
}
