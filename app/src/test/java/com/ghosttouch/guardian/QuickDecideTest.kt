package com.ghosttouch.guardian

import android.view.MotionEvent
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito.*
import org.mockito.kotlin.whenever

class QuickDecideTest {
    
    @Test
    fun `qDecide allows stylus tool type`() {
        val event = mock(MotionEvent::class.java)
        whenever(event.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_STYLUS)
        
        val sen = mock(Sen::class.java)
        val verdict = qDecide(event, sen, emptyList(), false)
        
        assertFalse(verdict.ghost)
        assertEquals("Stylus", verdict.why)
    }
    
    @Test
    fun `qDecide blocks in pen mode`() {
        val event = mock(MotionEvent::class.java)
        whenever(event.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_FINGER)
        
        val sen = mock(Sen::class.java)
        val verdict = qDecide(event, sen, emptyList(), true)
        
        assertTrue(verdict.ghost)
        assertEquals("PenMode", verdict.why)
    }
    
    @Test
    fun `qDecide allows touch in safe zone`() {
        val event = mock(MotionEvent::class.java)
        whenever(event.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_FINGER)
        whenever(event.x).thenReturn(125f)
        whenever(event.y).thenReturn(125f)
        
        val sen = mock(Sen::class.java)
        val zones = listOf(Z(100f, 100f, 50f, 50f))
        
        val verdict = qDecide(event, sen, zones, false)
        
        assertFalse(verdict.ghost)
        assertEquals("SafeZone", verdict.why)
    }
    
    @Test
    fun `qDecide allows touch with acceleration`() {
        val event = mock(MotionEvent::class.java)
        whenever(event.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_FINGER)
        whenever(event.eventTime).thenReturn(1000L)
        whenever(event.x).thenReturn(100f)
        whenever(event.y).thenReturn(100f)
        
        val sen = mock(Sen::class.java)
        whenever(sen.peakNow(1000L)).thenReturn(0.020f) // Above K.AT (0.016)
        
        val verdict = qDecide(event, sen, emptyList(), false)
        
        assertFalse(verdict.ghost)
        assertTrue(verdict.why.startsWith("Accel:"))
    }
}
