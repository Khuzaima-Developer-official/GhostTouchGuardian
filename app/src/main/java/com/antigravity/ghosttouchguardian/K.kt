package com.antigravity.ghosttouchguardian

/** All detection thresholds and action strings. 1-3 char names. */
object K {

    // --- jitter thresholds (pixels RMS) ---
    const val JG  = 2.8f   // jitter max → ghost pooling
    const val JH  = 3.5f   // jitter min → confirmed human

    // --- burst gate ---
    const val BI  = 50L    // ms: interval below this + same location = burst ghost
    const val BD  = 55f    // px: max distance to call "same location" for burst

    // --- S-Pen echo window ---
    const val E0  = 50L    // ms: echo window start (after stylus up)
    const val E1  = 500L   // ms: echo window end

    // --- accelerometer ---
    const val AT  = 0.016f // m/s²: min accel delta confirming physical impact
    const val AW  = 55L    // ms: half-window for impulse lookup

    // --- contact geometry ---
    const val EG  = 2.4f   // eccentricity above this → elongated ghost track
    const val SH  = 0.030f // size min for plausible flesh contact
    const val PH  = 0.07f  // pressure min for plausible flesh contact

    // --- pressure slope ---
    const val PST = 0.0008f // pressure/ms: ramp rate confirming flesh deformation

    // --- dwell gates ---
    const val DL  = 60     // ms: min dwell for jitter test to be meaningful

    // --- pass-through grace period ---
    const val PT  = 500L   // ms: pass-through stays open after human touch

    // --- score thresholds ---
    const val HT  = 2      // human score >= this → ALLOW (need 2 signals)
    const val GT  = 3      // ghost score >= this → BLOCK (3 ghost signals, 0 human)

    // --- service actions ---
    const val A_START  = "gtg.START"
    const val A_STOP   = "gtg.STOP"
    const val A_TOGGLE = "gtg.TOGGLE"
    const val A_PEN    = "gtg.PEN"
    const val A_STATS  = "gtg.STATS"

    // --- prefs keys ---
    const val PK_EN  = "en"
    const val PK_PEN = "pen"
    const val PK_BLK = "blk"
    const val PK_ZNS = "zns"
    const val PK_STS = "sts"
    const val PK_MIG = "mig"

    // --- notification ---
    const val CH_ID = "gtg_ch"
    const val N_ID  = 2401
}
