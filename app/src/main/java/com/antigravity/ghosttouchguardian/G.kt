package com.antigravity.ghosttouchguardian

import android.view.MotionEvent
import kotlin.math.sqrt

// ─── Touch context: everything computed once from one gesture ─────────────
data class Ctx(
    val dw:  Int,            // dwell ms
    val hs:  Int,            // historySize (batched samples in this event)
    val pts: List<Pair<Float,Float>>, // ACTION_MOVE positions (x,y)
    val ecc: Float,          // touchMajor/touchMinor (eccentricity)
    val acc: Float,          // peak accel delta in ±AW window
    val iv:  Long,           // interval since previous touch ms
    val px:  Float,          // last x
    val py:  Float,          // last y
    val prs: Float,          // pressure at UP
    val p0:  Float,          // pressure at DOWN
    val pMx: Float,          // max pressure seen during gesture
    val hpv: Float,          // historical pressure variance (batch samples)
    val sz:  Float,          // contact size at UP
    val tmj: Float,          // touchMajor at UP
    val tmn: Float,          // touchMinor at UP
    val ori: Float,          // orientation axis value
    val tsDn: Long,          // eventTime of DOWN
    val tsSty: Long          // time since last stylus up (ms), -1 if never
)

// ─── Shared state across gestures ────────────────────────────────────────
object St {
    @Volatile var lt  = 0L   // last touch DOWN time
    @Volatile var lx  = 0f   // last touch x
    @Volatile var ly  = 0f   // last touch y
    @Volatile var lUp = 0L   // last confirmed human UP time
    @Volatile var lSty= 0L   // last stylus UP time (0 = never)

    // rolling orientation variance: ghost orientations cluster tightly
    private val oriRing = FloatArray(16); private var oriHd = 0; var oriN = 0
    fun pushOri(v: Float) {
        oriRing[oriHd] = v; oriHd = (oriHd+1)%16; if (oriN<16) oriN++
    }
    fun oriVar(): Float {
        if (oriN < 4) return 999f   // not enough data yet → unknown
        val n  = oriN.toFloat()
        var mu = 0f
        for (i in 0 until oriN) mu += oriRing[i]
        mu /= n
        var v  = 0f
        for (i in 0 until oriN) { val d = oriRing[i]-mu; v += d*d }
        return v / n
    }
}

// ─── Build Ctx from live MotionEvent stream ───────────────────────────────
class CtxBuilder {
    private var t0  = 0L;  private var x0 = 0f;  private var y0 = 0f
    private var p0  = 0f;  private var pMx = 0f
    private val pts = mutableListOf<Pair<Float,Float>>()

    fun down(e: MotionEvent) {
        t0=e.eventTime; x0=e.x; y0=e.y; p0=e.pressure; pMx=e.pressure
        pts.clear(); pts.add(x0 to y0)
    }

    fun move(e: MotionEvent) {
        // consume all batched samples
        for (h in 0 until e.historySize) pts.add(e.getHistoricalX(0,h) to e.getHistoricalY(0,h))
        pts.add(e.x to e.y)
        if (e.pressure > pMx) pMx = e.pressure
    }

    fun up(e: MotionEvent, sen: Sen): Ctx {
        if (e.pressure > pMx) pMx = e.pressure

        // historical pressure variance from this UP batch
        val hpv: Float = if (e.historySize >= 3) {
            val ps = FloatArray(e.historySize+1) { i ->
                if (i < e.historySize) e.getHistoricalPressure(0,i) else e.pressure
            }
            val mu = ps.sum() / ps.size
            ps.fold(0f) { a,v -> val d=v-mu; a+d*d } / ps.size
        } else 0f

        val tmj = e.touchMajor.coerceAtLeast(1f)
        val tmn = e.touchMinor.coerceAtLeast(1f)
        val tsSty = if (St.lSty > 0L) e.eventTime - St.lSty else -1L

        return Ctx(
            dw   = (e.eventTime - t0).toInt().coerceIn(1, 10_000),
            hs   = e.historySize + pts.size,
            pts  = pts.toList(),
            ecc  = (tmj / tmn).coerceIn(1f, 10f),
            acc  = sen.peak(t0),              // window centred on DOWN time
            iv   = if (St.lt > 0L) e.eventTime - St.lt else Long.MAX_VALUE,
            px   = e.x, py = e.y,
            prs  = e.pressure, p0 = p0, pMx = pMx,
            hpv  = hpv,
            sz   = e.size,
            tmj  = tmj, tmn = tmn,
            ori  = e.getAxisValue(MotionEvent.AXIS_ORIENTATION),
            tsDn = t0,
            tsSty= tsSty
        )
    }
}

// ─── Spatial jitter: RMS deviation from mean position ────────────────────
fun sj(pts: List<Pair<Float,Float>>): Float {
    if (pts.size < 3) return -1f   // -1 = insufficient data
    var sx = 0f; var sy = 0f
    for (p in pts) { sx += p.first; sy += p.second }
    val mx = sx / pts.size; val my = sy / pts.size
    var ss = 0f
    for (p in pts) { val dx=p.first-mx; val dy=p.second-my; ss+=dx*dx+dy*dy }
    return sqrt(ss / pts.size)
}

// ─── Pressure slope: rate of pressure build-up (flesh ramps, water spikes)
fun pSlp(c: Ctx): Float = if (c.dw > 0) (c.pMx - c.p0) / c.dw else 0f

// ─── Core decision: returns true = ghost, false = human ──────────────────
fun isG(c: Ctx, zones: List<Z>): Boolean = isGScored(c, zones).first

// ─── Scored version: returns (isGhost, Stat) for logging and stats overlay
fun isGScored(c: Ctx, zones: List<Z>): Pair<Boolean, Stat> {

    // ── TIER 1: Absolute ALLOW ────────────────────────────────────────────
    if (zones.any { it.has(c.px, c.py) })
        return false to Stat(9, 0, false, -1f, c.acc, c.dw, 0)
    if (c.acc >= K.AT)
        return false to Stat(9, 0, false, -1f, c.acc, c.dw, 0)
    if (c.sz >= K.SH && c.prs >= K.PH && c.ecc < K.EG)
        return false to Stat(9, 0, false, -1f, c.acc, c.dw, 0)
    if (pSlp(c) >= K.PST && c.dw >= K.DL)
        return false to Stat(9, 0, false, -1f, c.acc, c.dw, 0)

    // ── TIER 2: Absolute BLOCK ────────────────────────────────────────────
    if (c.iv in 1L..(K.BI - 1L)) {
        val dx = c.px - St.lx; val dy = c.py - St.ly
        if (sqrt(dx * dx + dy * dy) < K.BD)
            return true to Stat(0, 6, true, -1f, c.acc, c.dw, 0)
    }
    if (c.tsSty in K.E0..K.E1 && c.dw < 120 && c.acc < K.AT)
        return true to Stat(0, 5, true, -1f, c.acc, c.dw, 0)

    // ── TIER 3: Evidence voting ───────────────────────────────────────────
    var hScore = 0
    if (c.acc  >= K.AT  * 0.75f)              hScore++
    if (c.sz   >= K.SH  * 0.80f)              hScore++
    if (c.prs  >= K.PH)                        hScore++
    if (c.ecc  <  2.0f)                        hScore++
    if (c.hpv  >  0.0004f)                     hScore++
    val j = sj(c.pts)
    if (j      >= K.JH)                        hScore++

    if (hScore >= K.HT)
        return false to Stat(hScore, 0, false, j, c.acc, c.dw, 0)

    var gScore = 0
    if (c.acc  < K.AT   * 0.50f)              gScore++
    if (c.ecc  > K.EG)                         gScore++
    if (c.sz   < K.SH   * 0.60f)              gScore++
    if (c.hpv  < 0.0001f && c.dw >= K.DL)    gScore++
    if (j in 0f..K.JG   && c.dw >= K.DL)     gScore++
    if (c.hs   <= 1      && c.dw < K.DL)      gScore++

    val ov = St.oriVar()
    if (ov < 0.08f && ov >= 0f)               gScore++

    val isGhost = gScore >= K.GT
    return isGhost to Stat(hScore, gScore, isGhost, j, c.acc, c.dw, 0)
}
