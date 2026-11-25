package com.ghosttouch.guardian

import android.view.MotionEvent
import kotlin.math.sqrt
import com.ghosttouch.guardian.L
import com.ghosttouch.guardian.Tags

// Touch context: extracted once per gesture
data class Ctx(
    val dw:   Int,
    val pts:  List<Pair<Float,Float>>,
    val ecc:  Float,
    val acc:  Float,
    val iv:   Long,
    val px:   Float,
    val py:   Float,
    val prs:  Float,
    val p0:   Float,
    val pMx:  Float,
    val hpv:  Float,
    val sz:   Float,
    val ori:  Float,
    val tsDn: Long,
    val tsUp: Long,
    val tsSty:Long
)

// Cross-gesture state (main thread only)
object St {
    var lt   = 0L
    var lx   = 0f
    var ly   = 0f
    var lUp  = 0L
    var lSty = 0L

    private val OR = FloatArray(16)
    private var oh = 0
    private var on_ = 0
    fun pushOri(v: Float) { OR[oh]=v; oh=(oh+1)%16; if(on_<16) on_++ }
    fun oriVar(): Float {
        if (on_ < 5) return 999f
        var mu = 0f; for (i in 0 until on_) mu += OR[i]; mu /= on_
        var v  = 0f; for (i in 0 until on_) { val d=OR[i]-mu; v+=d*d }
        return v / on_
    }
    fun reset() { lt=0L; lx=0f; ly=0f; lUp=0L; lSty=0L; oh=0; on_=0 }
}

// Accumulates samples for one gesture
class CtxBuilder {
    private var t0  = 0L
    private var p0  = 0f
    private var pMx = 0f
    private val pts = mutableListOf<Pair<Float,Float>>()

    fun down(e: MotionEvent) {
        t0=e.eventTime; p0=e.pressure; pMx=e.pressure
        pts.clear(); pts.add(e.x to e.y)
    }

    fun move(e: MotionEvent) {
        for (h in 0 until e.historySize)
            pts.add(e.getHistoricalX(0,h) to e.getHistoricalY(0,h))
        pts.add(e.x to e.y)
        St.pushOri(e.getAxisValue(MotionEvent.AXIS_ORIENTATION))
        if (e.pressure > pMx) pMx = e.pressure
        if (pts.size > 200) {
            val extra = pts.size - 200
            repeat(extra) { pts.removeAt(0) }
        }
    }

    fun up(e: MotionEvent, sen: Sen): Ctx {
        if (e.pressure > pMx) pMx = e.pressure
        for (h in 0 until e.historySize)
            pts.add(e.getHistoricalX(0,h) to e.getHistoricalY(0,h))
        pts.add(e.x to e.y)

        val hpv = if (e.historySize >= 3) {
            val ps = FloatArray(e.historySize+1) { i ->
                if (i < e.historySize) e.getHistoricalPressure(0,i) else e.pressure
            }
            val mu = ps.sum() / ps.size
            ps.fold(0f) { a,v -> val d=v-mu; a+d*d } / ps.size
        } else 0f

        val tmj = e.touchMajor.coerceAtLeast(1f)
        val tmn = e.touchMinor.coerceAtLeast(1f)

        return Ctx(
            dw   = (e.eventTime - t0).toInt().coerceIn(1, 10_000),
            pts  = pts.toList(),
            ecc  = (tmj/tmn).coerceIn(1f, 10f),
            acc  = sen.peak(t0, e.eventTime),
            iv   = if (St.lt > 0L) e.eventTime - St.lt else Long.MAX_VALUE,
            px   = e.x,  py = e.y,
            prs  = e.pressure, p0 = p0, pMx = pMx,
            hpv  = hpv,
            sz   = e.size,
            ori  = e.getAxisValue(MotionEvent.AXIS_ORIENTATION),
            tsDn = t0, tsUp = e.eventTime,
            tsSty= if (St.lSty > 0L) e.eventTime - St.lSty else -1L
        )
    }
}

// Spatial jitter: RMS of positions from their mean
fun sj(pts: List<Pair<Float,Float>>): Float {
    if (pts.size < 4) return -1f
    var sx=0f; var sy=0f
    for (p in pts) { sx+=p.first; sy+=p.second }
    val mx=sx/pts.size; val my=sy/pts.size
    var ss=0f
    for (p in pts) { val dx=p.first-mx; val dy=p.second-my; ss+=dx*dx+dy*dy }
    return sqrt(ss/pts.size)
}

// Pressure slope: flesh ramps, water spikes
fun pSlp(c: Ctx): Float = if (c.dw > 0) (c.pMx - c.p0) / c.dw else 0f

// Verdict + scores
data class Vrd(val ghost: Boolean, val h: Int, val g: Int, val j: Float, val why: String)

// QUICK DECIDE: runs at ACTION_DOWN with instant signals only
fun qDecide(
    e: MotionEvent, sen: Sen, zones: List<Z>, penMode: Boolean
): Vrd {
    if (e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS)
        return Vrd(false, 9, 0, -1f, "Stylus")
    if (penMode)
        return Vrd(true, 0, 9, -1f, "PenMode")
    if (zones.any { it.has(e.x, e.y) })
        return Vrd(false, 9, 0, -1f, "SafeZone")

    if (St.lt > 0L && (e.eventTime - St.lt) in 1L..(K.BI-1L)) {
        val dx=e.x-St.lx; val dy=e.y-St.ly
        if (sqrt(dx*dx+dy*dy) < K.BD)
            return Vrd(true, 0, 7, -1f, "Burst:${e.eventTime-St.lt}ms")
    }

    // v4.2.0: Smart echo window - only check if stylus recently used
    val tsSty = if (St.lSty > 0L) e.eventTime - St.lSty else -1L
    val echoWindow = StylusMonitor.getEchoWindow(e.eventTime)
    if (echoWindow != null && tsSty in echoWindow && sen.peakNow(e.eventTime) < K.AT)
        return Vrd(true, 0, 7, -1f, "Echo:${tsSty}ms")

    // v4.2.0: Multi-window impact analysis
    val impact = sen.analyzeImpact(e.eventTime, e.eventTime)
    if (impact.confidence >= 0.70f)
        return Vrd(false, 9, 0, -1f, "Impact:conf=${"%.2f".format(impact.confidence)},pk=${"%.3f".format(impact.peak)}")

    val tmj = e.touchMajor.coerceAtLeast(1f)
    val tmn = e.touchMinor.coerceAtLeast(1f)
    val ecc = (tmj/tmn).coerceIn(1f, 10f)
    if (e.size >= K.SH && e.pressure >= K.PH && ecc < 1.9f)
        return Vrd(false, 9, 0, -1f, "Flesh@DOWN")

    if (e.size < K.SH * 0.50f && e.pressure < 0.040f && ecc > K.EG)
        return Vrd(true, 0, 6, -1f, "Spike@DOWN:sz=${e.size}")

    return Vrd(false, 1, 1, -1f, "PassThrough")
}

// FULL DECIDE: runs at ACTION_UP for blocked touches (logging + learning)
// Uses adaptive thresholds (Rec.aJG, Rec.aJH) instead of static K.JG, K.JH
fun decide(c: Ctx, zones: List<Z>): Vrd {

    // TIER 1: Unconditional ALLOW
    if (zones.any { it.has(c.px, c.py) })
        return Vrd(false, 9, 0, -1f, "SafeZone")
    // v4.2.0: Multi-window impact analysis (NOTE: sen not available here, use c.acc as before for now)
    if (c.acc >= K.AT)
        return Vrd(false, 9, 0, -1f, "Accel:${c.acc}")
    if (c.sz >= K.SH && c.prs >= K.PH && c.ecc < 1.9f)
        return Vrd(false, 9, 0, -1f, "Flesh")
    if (pSlp(c) >= K.PST && c.dw >= K.DL)
        return Vrd(false, 9, 0, -1f, "PSlp:${pSlp(c)}")

    // TIER 2: Unconditional BLOCK
    // v4.2.0: Smart echo window - only check if stylus recently used
    val echoWindow = StylusMonitor.getEchoWindow(c.tsUp)
    if (echoWindow != null && c.tsSty in echoWindow && c.dw < 150 && c.acc < K.AT)
        return Vrd(true, 0, 7, -1f, "Echo:${c.tsSty}ms")
    if (c.iv in 1L..(K.BI-1L)) {
        val dx=c.px-St.lx; val dy=c.py-St.ly
        if (sqrt(dx*dx+dy*dy) < K.BD)
            return Vrd(true, 0, 7, -1f, "Burst:${c.iv}ms")
    }

    // TIER 3: Multi-signal voting with ADAPTIVE thresholds
    var h = 0; var g = 0; val j = sj(c.pts)

    if (c.acc  >= K.AT * 0.60f) h++
    if (c.sz   >= K.SH * 0.75f && c.prs >= K.PH*0.70f) h++
    if (c.ecc  <  1.85f)        h++
    if (c.hpv  >  0.0003f)      h++
    if (j      >= Rec.aJH)      h++    // ADAPTIVE: was K.JH
    if (c.prs  >= K.PH)         h++

    if (c.acc  < K.AT * 0.40f)              g++
    if (c.ecc  > K.EG)                      g++
    if (c.sz   < K.SH * 0.55f)             g++
    if (c.hpv  < 0.00008f && c.dw >= K.DL) g++
    if (j in 0f..Rec.aJG  && c.dw >= K.DL) g++    // ADAPTIVE: was K.JG
    if (c.prs  < 0.040f)                   g++
    val ov = St.oriVar()
    if (ov < 0.06f && ov >= 0f)            g++

    if (h >= 2) return Vrd(false, h, g, j, "hVote:$h")
    if (g >= 3 && h == 0) return Vrd(true, h, g, j, "gVote:$g")
    return Vrd(false, h, g, j, "Ambiguous→Allow")
}


