package com.antigravity.ghosttouchguardian

/** All detection thresholds and action strings. 1-3 char names. */
object K {

    // --- jitter thresholds (pixels RMS) ---
    const val JG  = 2.8f   // jitter max → ghost pooling
    const val JH  = 3.5f   // jitter min → confirmed human

    // --- burst gate ---
    const val BI  = 50L    // ms: interval below this + same location = burst
    const val BD  = 55f    // px: max distance for "same location"

    // --- S-Pen echo window ---
    const val E0  = 50L    // ms: echo window start
    const val E1  = 500L   // ms: echo window end

    // --- accelerometer ---
    const val AT  = 0.016f // m/s²: min accel confirming impact
    const val AW  = 55L    // ms: half-window for impulse lookup

    // --- contact geometry ---
    const val EG  = 2.4f   // eccentricity above → ghost track
    const val SH  = 0.030f // size min for flesh contact
    const val PH  = 0.07f  // pressure min for flesh contact

    // --- pressure slope ---
    const val PST = 0.0008f // pressure/ms: ramp rate

    // --- dwell gates ---
    const val DL  = 60     // ms: min dwell for jitter

    // --- pass-through grace ---
    const val PT  = 500L   // ms: pass-through stays open

    // --- score thresholds ---
    const val HT  = 2      // human score ≥ this → ALLOW
    const val GT  = 3      // ghost score ≥ this → BLOCK

    // --- service actions ---
    const val A_START  = "gtg.START"
    const val A_STOP   = "gtg.STOP"
    const val A_TOGGLE = "gtg.TOGGLE"
    const val A_PEN    = "gtg.PEN"
    const val A_STATS  = "gtg.STATS"
    const val A_WRONG  = "gtg.WRONG"    // report false detection

    // --- prefs keys ---
    const val PK_EN  = "en"
    const val PK_PEN = "pen"
    const val PK_BLK = "blk"
    const val PK_ZNS = "zns"
    const val PK_STS = "sts"
    const val PK_MIG = "mig"
    const val PK_AJG = "ajG"   // adaptive jitter ghost
    const val PK_AJH = "ajH"   // adaptive jitter human

    // --- notification ---
    const val CH_ID = "gtg_ch"
    const val N_ID  = 2401
}
