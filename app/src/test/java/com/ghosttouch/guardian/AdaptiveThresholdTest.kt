package com.ghosttouch.guardian

import android.content.Context
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.mockito.Mockito.*

class AdaptiveThresholdTest {
    
    private lateinit var mockP: P
    
    @Before
    fun setup() {
        mockP = mock(P::class.java)
        Rec.aJG = K.JG
        Rec.aJH = K.JH
    }
    
    @Test
    fun `err increases aJG on false positive`() {
        val ghostVerdict = Vrd(ghost = true, h = 0, g = 3, j = 2.5f, why = "gVote")
        Rec.lastV = ghostVerdict
        
        val originalJG = Rec.aJG
        Rec.err(mockP)
        
        assertTrue("aJG should increase", Rec.aJG > originalJG)
    }
    
    @Test
    fun `err decreases aJH on false positive`() {
        val ghostVerdict = Vrd(ghost = true, h = 0, g = 3, j = 2.5f, why = "gVote")
        Rec.lastV = ghostVerdict
        
        val originalJH = Rec.aJH
        Rec.err(mockP)
        
        assertTrue("aJH should decrease", Rec.aJH < originalJH)
    }
    
    @Test
    fun `err decreases aJG on false negative`() {
        val humanVerdict = Vrd(ghost = false, h = 2, g = 0, j = 4.0f, why = "hVote")
        Rec.lastV = humanVerdict
        
        val originalJG = Rec.aJG
        Rec.err(mockP)
        
        assertTrue("aJG should decrease", Rec.aJG < originalJG)
    }
    
    @Test
    fun `err increases aJH on false negative`() {
        val humanVerdict = Vrd(ghost = false, h = 2, g = 0, j = 4.0f, why = "hVote")
        Rec.lastV = humanVerdict
        
        val originalJH = Rec.aJH
        Rec.err(mockP)
        
        assertTrue("aJH should increase", Rec.aJH > originalJH)
    }
    
    @Test
    fun `err maintains gap between aJH and aJG`() {
        val ghostVerdict = Vrd(ghost = true, h = 0, g = 3, j = 2.5f, why = "gVote")
        Rec.lastV = ghostVerdict
        
        Rec.err(mockP)
        
        assertTrue("aJH must be > aJG", Rec.aJH > Rec.aJG)
    }
    
    @Test
    fun `err respects upper bound`() {
        Rec.aJG = K.JG * 1.25f
        val ghostVerdict = Vrd(ghost = true, h = 0, g = 3, j = 2.5f, why = "gVote")
        Rec.lastV = ghostVerdict
        
        Rec.err(mockP)
        
        assertTrue("aJG should not exceed 1.3x base", Rec.aJG <= K.JG * 1.30f)
    }
    
    @Test
    fun `err respects lower bound`() {
        Rec.aJG = K.JG * 0.75f
        val humanVerdict = Vrd(ghost = false, h = 2, g = 0, j = 4.0f, why = "hVote")
        Rec.lastV = humanVerdict
        
        Rec.err(mockP)
        
        assertTrue("aJG should not go below 0.7x base", Rec.aJG >= K.JG * 0.70f)
    }
}
