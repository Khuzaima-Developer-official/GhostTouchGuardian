package com.ghosttouch.guardian

import org.junit.Test
import org.junit.Assert.*

class FullDecideTest {
    
    private fun mockCtx(
        dw: Int = 100,
        acc: Float = 0.01f,
        sz: Float = 0.05f,
        prs: Float = 0.1f,
        ecc: Float = 1.5f,
        tsSty: Long = -1L
    ) = Ctx(
        dw = dw,
        pts = listOf(100f to 100f, 101f to 100f, 100f to 101f, 99f to 100f),
        ecc = ecc,
        acc = acc,
        iv = 10000L,
        px = 100f,
        py = 100f,
        prs = prs,
        p0 = prs,
        pMx = prs,
        hpv = 0f,
        sz = sz,
        ori = 0f,
        tsDn = 0L,
        tsUp = 100L,
        tsSty = tsSty
    )
    
    @Test
    fun `decide allows touch in safe zone`() {
        val ctx = mockCtx()
        val zones = listOf(Z(90f, 90f, 20f, 20f))
        
        val verdict = decide(ctx, zones)
        
        assertFalse(verdict.ghost)
        assertEquals("SafeZone", verdict.why)
    }
    
    @Test
    fun `decide allows touch with high acceleration`() {
        val ctx = mockCtx(acc = 0.020f) // Above K.AT
        
        val verdict = decide(ctx, emptyList())
        
        assertFalse(verdict.ghost)
        assertTrue(verdict.why.startsWith("Accel:"))
    }
    
    @Test
    fun `decide allows flesh contact`() {
        val ctx = mockCtx(sz = 0.05f, prs = 0.1f, ecc = 1.5f)
        
        val verdict = decide(ctx, emptyList())
        
        assertFalse(verdict.ghost)
        assertEquals("Flesh", verdict.why)
    }
    
    @Test
    fun `decide blocks echo touch`() {
        val ctx = mockCtx(tsSty = 200L, dw = 80, acc = 0.010f)
        
        val verdict = decide(ctx, emptyList())
        
        assertTrue(verdict.ghost)
        assertTrue(verdict.why.startsWith("Echo:"))
    }
    
    @Test
    fun `decide uses voting when ambiguous`() {
        val ctx = mockCtx(acc = 0.012f, sz = 0.025f, prs = 0.05f)
        
        val verdict = decide(ctx, emptyList())
        
        // Should use Tier 3 voting
        assertTrue(verdict.why.contains("Vote") || verdict.why.contains("Allow"))
    }
}
