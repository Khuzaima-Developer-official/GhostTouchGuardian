package com.antigravity.ghosttouchguardian

import android.view.MotionEvent
import kotlin.math.sqrt

// ─── Touch context: everything extracted once per gesture ─────────────────
data class Ctx(
    val dw:   Int,                        // dwell ms (DOWN→UP)
    val pts:  List<Pair<Float,Float>>,    // all (x,y) samples collected during gesture
    val ecc:  Float,                      // touchMajor/touchMinor at UP
    val acc:  Float,                      // peak accel in [downTime-AW .. upTime+AW]
    val iv:   Long,                       // ms since previous gesture ended
    val px:   Float,                      // x at UP
    val py:   Float,                      // y at UP
    val prs:  Float,                      // pressure at UP
    val p0:   Float,                      // pressure at DOWN
    val pMx:  Float,                      // max pressure during gesture
    val hpv:  Float,                      // pressure variance across UP-batch history
    val sz:   Float,                      // contact size at UP
    val ori:  Float,                      // AXIS_ORIENTATION at UP
    val tsDn: Long,                       // DOWN eventTime
    val tsUp: Long,                       // UP eventTime
    val tsSty:Long                        // ms since last stylus UP (-1 if never)
)

// ─── Cross-gesture state (single instance, main thread only) ─────────────
object St {
    var lt   = 0L   // DOWN time of previous gesture
    var lx   = 0f   // x of previous gesture
    var ly   = 0f   // y of previous gesture
    var lUp  = 0L   // UP time of last confirmed-human gesture
    var lSty = 0L   // UP time of last stylus gesture (0 = never)

    // Rolling orientation ring: detects ITO-axis ghost clustering
    private val OR = FloatArray(16)
    private var oh = 0
    private var on_ = 0   // count of valid entries
    fun pushOri(v: Float) { OR[oh]=v; oh=(oh+1)%16; if(on_<16) on_++ }
    fun oriVar(): Float {
        if (on_ < 5) return 999f          // not enough data
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
    }

    fun up(e: MotionEvent, sen: Sen): Ctx {
        if (e.pressure > pMx) pMx = e.pressure
        for (h in 0 until e.historySize)
            pts.add(e.getHistoricalX(0,h) to e.getHistoricalY(0,h))
        pts.add(e.x to e.y)

        // pressure variance across UP batch
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
            // window spans DOWN→UP plus sensor-delay margin each side
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
// Ghost pooling < 2.5px; human tremor > 3.5px (autonomic 8-12Hz)
fun sj(pts: List<Pair<Float,Float>>): Float {
    if (pts.size < 4) return -1f
    var sx=0f; var sy=0f
    for (p in pts) { sx+=p.first; sy+=p.second }
    val mx=sx/pts.size; val my=sy/pts.size
    var ss=0f
    for (p in pts) { val dx=p.first-mx; val dy=p.second-my; ss+=dx*dx+dy*dy }
    return sqrt(ss/pts.size)
}

// ─── Pressure slope: flesh ramps gradually, water is instantaneous ────────
fun pSlp(c: Ctx): Float = if (c.dw > 0) (c.pMx - c.p0) / c.dw else 0f

// ─── Verdict + scores for stats overlay ──────────────────────────────────
data class Vrd(val ghost: Boolean, val h: Int, val g: Int, val j: Float, val why: String)

// ─── Main detection function ──────────────────────────────────────────────
fun decide(c: Ctx, zones: List<Z>): Vrd {

    // ── TIER 1: Unconditional ALLOW ───────────────────────────────────────
    // Any single definitive human signal ends evaluation immediately.

    if (zones.any { it.has(c.px, c.py) })
        return Vrd(false, 9, 0, -1f, "SafeZone")

    // Strong physical impact (Newtonian mass on glass)
    if (c.acc >= K.AT)
        return Vrd(false, 9, 0, -1f, "Accel:${c.acc}")

    // Flesh geometry: round contact AND minimum size AND pressure together
    // Require ALL THREE — water can satisfy one or two individually
    if (c.sz >= K.SH && c.prs >= K.PH && c.ecc < 1.9f)
        return Vrd(false, 9, 0, -1f, "Flesh:sz=${c.sz} prs=${c.prs} ecc=${c.ecc}")

    // Gradual pressure ramp — physiologically impossible to fake with water
    if (pSlp(c) >= K.PST && c.dw >= K.DL)
        return Vrd(false, 9, 0, -1f, "PSlp:${pSlp(c)}")

    // ── TIER 2: Unconditional BLOCK ───────────────────────────────────────
    // Hardware-signature ghosts: no ambiguity possible.

    // S-Pen electrostatic echo through moisture
    if (c.tsSty in K.E0..K.E1 && c.dw < 150 && c.acc < K.AT)
        return Vrd(true, 0, 7, -1f, "Echo:${c.tsSty}ms")

    // Burst at same location (moisture controller glitch)
    if (c.iv in 1L..(K.BI-1L)) {
        val dx=c.px-St.lx; val dy=c.py-St.ly
        if (sqrt(dx*dx+dy*dy) < K.BD)
            return Vrd(true, 0, 7, -1f, "Burst:${c.iv}ms")
    }

    // ── TIER 3: Multi-signal voting ───────────────────────────────────────
    // Human: need 2+ independent signals (harder to fake)
    // Ghost: need 3+ independent signals with ZERO human signals

    var h = 0
    var g = 0
    val j = sj(c.pts)

    // Human votes — each is an independent physical property
    if (c.acc  >= K.AT * 0.60f) h++                        // sub-threshold impact
    if (c.sz   >= K.SH * 0.75f && c.prs >= K.PH*0.70f) h++ // partial flesh contact
    if (c.ecc  <  1.85f)        h++                        // round = fingertip pad
    if (c.hpv  >  0.0003f)      h++                        // pressure variability
    if (j      >= K.JH)         h++                        // biological tremor
    if (c.prs  >= K.PH)         h++                        // plausible pressure alone

    // Ghost votes — each is a physical absence of life signal
    if (c.acc  < K.AT * 0.40f)              g++  // truly zero momentum
    if (c.ecc  > K.EG)                      g++  // elongated ITO electrode track
    if (c.sz   < K.SH * 0.55f)             g++  // sub-flesh contact area
    if (c.hpv  < 0.00008f && c.dw >= K.DL) g++  // perfectly flat pressure
    if (j in 0f..K.JG  && c.dw >= K.DL)   g++  // zero tremor = static capacitance
    if (c.prs  < 0.040f)                   g++  // sub-flesh pressure
    val ov = St.oriVar()
    if (ov < 0.06f && ov >= 0f)            g++  // ITO orientation locked

    // Decision rules:
    // Allow if 2+ human signals (even without full Tier-1 certainty)
    if (h >= 2) return Vrd(false, h, g, j, "hVote:$h")

    // Block if 3+ ghost signals AND zero human signals
    if (g >= 3 && h == 0) return Vrd(true, h, g, j, "gVote:$g")

    // Ambiguous (1 human signal, or g<3): bias toward human safety
    return Vrd(false, h, g, j, "Ambiguous→Allow")
}
