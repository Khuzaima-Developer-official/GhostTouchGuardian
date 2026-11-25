package com.ghosttouch.guardian

import org.junit.Test
import org.junit.Assert.*

class PressureSlopeTest {
    
    @Test
    fun `pSlp returns 0 for zero dwell`() {
        val ctx = Ctx(dw=0, pts=emptyList(), ecc=1f, acc=0f, iv=0L,
            px=0f, py=0f, prs=0.1f, p0=0.1f, pMx=0.1f, hpv=0f, 
            sz=0f, ori=0f, tsDn=0L, tsUp=0L, tsSty=-1L)
        assertEquals(0f, pSlp(ctx), 0.001f)
    }
    
    @Test
    fun `pSlp calculates gradient correctly`() {
        val ctx = Ctx(dw=100, pts=emptyList(), ecc=1f, acc=0f, iv=0L,
            px=0f, py=0f, prs=0.15f, p0=0.05f, pMx=0.15f, hpv=0f, 
            sz=0f, ori=0f, tsDn=0L, tsUp=0L, tsSty=-1L)
        val slope = pSlp(ctx)
        assertEquals(0.001f, slope, 0.0001f)
    }
}
