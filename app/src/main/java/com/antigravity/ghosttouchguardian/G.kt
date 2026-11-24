package com.antigravity.ghosttouchguardian

import android.view.MotionEvent
import kotlin.math.sqrt

// ─── Touch context: extracted once per gesture ──────────────────────────
data class Ctx(
    val dw:   Int,                        // dwell ms (DOWN→UP)
    val pts:  List<Pair<Float,Float>>,    // all (x,y) samples
    val ecc:  Float,                      // touchMajor/touchMinor at UP
    val acc:  Float,                      // peak accel [downTime-AW .. upTime+AW]
    val iv:   Long,                       // ms since previous gesture
    val px:   Float,                      // x at UP
    val py:   Float,                      // y at UP
    val prs:  Float,                      // pressure at UP
    val p0:   Float,                      // pressure at DOWN
    val pMx:  Float,                      // max pressure during gesture
    val hpv:  Float,                      // pressure variance UP-batch
    val sz:   Float,                      // contact size at UP
    val ori:  Float,                      // AXIS_ORIENTATION at UP
    val tsDn: Long,                       // DOWN eventTime
    val tsUp: Long,                       // UP eventTime
    val tsSty:Long                        // ms since last stylus UP (-1=never)
)

// ─── Cross-gesture state (main thread only) ──────────────────────────────
object St {
    var lt   = 0L   // DOWN time of previous gesture
    var lx   = 0f   // x of previous gesture
    var ly   = 0f   // y of previous gesture
    var lUp  = 0L   // UP time of last confirmed-human gesture
    var lSty = 0L   // UP time of last stylus gesture (0 = never)

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

// ─── Accumulates samples for one gesture ─────────────────────────────────
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
        if (e.pressure > pMx) pMx = e.pressure
        // Cap memory: keep last 200 points
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

// ─── Spatial jitter: RMS of positions from their mean ────────────────────
fun sj(pts: List<Pair<Float,Float>>): Float {
    if (pts.size < 4) return -1f
    var sx=0f; var sy=0f
    for (p in pts) { sx+=p.first; sy+=p.second }
    val mx=sx/pts.size; val my=sy/pts.size
    var ss=0f
    for (p in pts) { val dx=p.first-mx; val dy=p.second-my; ss+=dx*dx+dy*dy }
    return sqrt(ss/pts.size)
}

// ─── Pressure slope: flesh ramps, water spikes ───────────────────────────
fun pSlp(c: Ctx): Float = if (c.dw > 0) (c.pMx - c.p0) / c.dw else 0f

// ─── Verdict + scores for stats overlay ─────────────────────────────────
data class Vrd(val ghost: Boolean, val h: Int, val g: Int, val j: Float, val why: String)

// ─── QUICK DECIDE: runs at ACTION_DOWN with instant signals only ────────
// Returns: true = block (ghost), false = pass through (human/unknown)
// Uses only signals available at DOWN time: position, tool, interval, accel, size, pressure, ecc
fun qDecide(
    e: MotionEvent, sen: Sen, zones: List<Z>, penMode: Boolean
): Vrd {

    // Stylus → always allow
    if (e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS)
        return Vrd(false, 9, 0, -1f, "Stylus")

    // S-Pen exclusive → block all capacitive
    if (penMode)
        return Vrd(true, 0, 9, -1f, "PenMode")

    // Safe zone → always allow
    if (zones.any { it.has(e.x, e.y) })
        return Vrd(false, 9, 0, -1f, "SafeZone")

    // Burst: rapid repeat at same location
    if (St.lt > 0L && (e.eventTime - St.lt) in 1L..(K.BI-1L)) {
        val dx=e.x-St.lx; val dy=e.y-St.ly
        if (sqrt(dx*dx+dy*dy) < K.BD)
            return Vrd(true, 0, 7, -1f, "Burst:${e.eventTime-St.lt}ms")
    }

    // S-Pen echo: electrostatic discharge through moisture
    val tsSty = if (St.lSty > 0L) e.eventTime - St.lSty else -1L
    if (tsSty in K.E0..K.E1 && sen.peakNow(e.eventTime) < K.AT)
        return Vrd(true, 0, 7, -1f, "Echo:${tsSty}ms")

    // Accel spike present at DOWN → strong human signal
    val acc = sen.peakNow(e.eventTime)
    if (acc >= K.AT)
        return Vrd(false, 9, 0, -1f, "Accel:$acc")

    // Flesh geometry at DOWN: round + adequate size + pressure
    val tmj = e.touchMajor.coerceAtLeast(1f)
    val tmn = e.touchMinor.coerceAtLeast(1f)
    val ecc = (tmj/tmn).coerceIn(1f, 10f)
    if (e.size >= K.SH && e.pressure >= K.PH && ecc < 1.9f)
        return Vrd(false, 9, 0, -1f, "Flesh@DOWN")

    // Sub-flesh: tiny contact + zero pressure + elongated = ghost spike
    if (e.size < K.SH * 0.50f && e.pressure < 0.040f && ecc > K.EG)
        return Vrd(true, 0, 6, -1f, "Spike@DOWN:sz=${e.size}")

    // Default: pass through (human safety bias — accept some ghost leakage)
    return Vrd(false, 1, 1, -1f, "PassThrough")
}

// ─── FULL DECIDE: runs at ACTION_UP for blocked touches (logging only) ───
fun decide(c: Ctx, zones: List<Z>): Vrd {

    // TIER 1: Unconditional ALLOW
    if (zones.any { it.has(c.px, c.py) })
        return Vrd(false, 9, 0, -1f, "SafeZone")
    if (c.acc >= K.AT)
        return Vrd(false, 9, 0, -1f, "Accel:${c.acc}")
    if (c.sz >= K.SH && c.prs >= K.PH && c.ecc < 1.9f)
        return Vrd(false, 9, 0, -1f, "Flesh")
    if (pSlp(c) >= K.PST && c.dw >= K.DL)
        return Vrd(false, 9, 0, -1f, "PSlp:${pSlp(c)}")

    // TIER 2: Unconditional BLOCK
    if (c.tsSty in K.E0..K.E1 && c.dw < 150 && c.acc < K.AT)
        return Vrd(true, 0, 7, -1f, "Echo:${c.tsSty}ms")
    if (c.iv in 1L..(K.BI-1L)) {
        val dx=c.px-St.lx; val dy=c.py-St.ly
        if (sqrt(dx*dx+dy*dy) < K.BD)
            return Vrd(true, 0, 7, -1f, "Burst:${c.iv}ms")
    }

    // TIER 3: Multi-signal voting
    var h = 0; var g = 0; val j = sj(c.pts)

    if (c.acc  >= K.AT * 0.60f) h++
    if (c.sz   >= K.SH * 0.75f && c.prs >= K.PH*0.70f) h++
    if (c.ecc  <  1.85f)        h++
    if (c.hpv  >  0.0003f)      h++
    if (j      >= K.JH)         h++
    if (c.prs  >= K.PH)         h++

    if (c.acc  < K.AT * 0.40f)              g++
    if (c.ecc  > K.EG)                      g++
    if (c.sz   < K.SH * 0.55f)             g++
    if (c.hpv  < 0.00008f && c.dw >= K.DL) g++
    if (j in 0f..K.JG  && c.dw >= K.DL)   g++
    if (c.prs  < 0.040f)                   g++
    val ov = St.oriVar()
    if (ov < 0.06f && ov >= 0f)            g++

    if (h >= 2) return Vrd(false, h, g, j, "hVote:$h")
    if (g >= 3 && h == 0) return Vrd(true, h, g, j, "gVote:$g")
    return Vrd(false, h, g, j, "Ambiguous→Allow")
}
