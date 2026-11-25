package com.ghosttouch.guardian

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

class ZoneEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    var zones = mutableListOf<Z>()
    var onZoneAdded: ((Z) -> Unit)? = null
    
    private var isDrawing = false
    private var drawingZone: RectF? = null
    private var downX = 0f
    private var downY = 0f
    
    private val zonePaint = Paint().apply {
        color = 0x4000FF00.toInt()  // Semi-transparent green
        style = Paint.Style.FILL
    }
    
    private val borderPaint = Paint().apply {
        color = 0xFF00FF00.toInt()  // Solid green
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    
    private val textPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 32f
        isAntiAlias = true
    }
    
    fun startDrawing() {
        isDrawing = true
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw existing zones
        zones.forEachIndexed { index, zone ->
            val rect = RectF(zone.x, zone.y, zone.x + zone.w, zone.y + zone.h)
            canvas.drawRect(rect, zonePaint)
            canvas.drawRect(rect, borderPaint)
            
            // Draw zone number
            canvas.drawText("#" + (index + 1),
                zone.x + 8f,
                zone.y + 36f,
                textPaint
            )
        }
        
        // Draw zone being created
        drawingZone?.let {
            canvas.drawRect(it, zonePaint)
            canvas.drawRect(it, borderPaint)
        }
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isDrawing) return false
        
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                drawingZone = RectF(downX, downY, downX, downY)
            }
            
            MotionEvent.ACTION_MOVE -> {
                drawingZone?.let {
                    it.left = min(downX, event.x)
                    it.top = min(downY, event.y)
                    it.right = max(downX, event.x)
                    it.bottom = max(downY, event.y)
                    invalidate()
                }
            }
            
            MotionEvent.ACTION_UP -> {
                drawingZone?.let {
                    val w = abs(it.right - it.left)
                    val h = abs(it.bottom - it.top)
                    
                    // Minimum size 50x50px
                    if (w > 50 && h > 50) {
                        val zone = Z(
                            x = it.left,
                            y = it.top,
                            w = w,
                            h = h
                        )
                        onZoneAdded?.invoke(zone)
                    }
                    
                    drawingZone = null
                    isDrawing = false
                    invalidate()
                }
            }
        }
        
        return true
    }
}
