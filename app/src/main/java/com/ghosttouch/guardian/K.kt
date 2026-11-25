package com.ghosttouch.guardian

/** 
 * Detection thresholds and system constants.
 * 
 * Variable naming: 1-3 character abbreviations for compact code.
 * All values empirically tuned on Galaxy Note 20 Ultra, 2024-11.
 */
object K {

    // --- Spatial Jitter Thresholds (pixels RMS) ---
    // Ghost touches (water pooling, EMR interference) stay stationary with low jitter.
    // Human touches have natural hand tremor causing position variance.
    const val JG  = 2.8f   // Max jitter for ghost classification (below this = pooled/static)
    const val JH  = 3.5f   // Min jitter for human classification (above this = tremor detected)

    // --- Burst Gate ---
    // Rapid repeated touches at same location indicate interference echo or water bridge.
    const val BI  = 50L    // ms: interval threshold (touches closer than this trigger burst check)
    const val BD  = 55f    // px: max distance for "same location" (Euclidean distance)

    // --- S-Pen Electromagnetic Echo Window ---
    // Samsung S Pen uses Wacom EMR (Electromagnetic Resonance) technology. After stylus
    // lift-off, the EM field decays over ~50-500ms, causing phantom capacitive touches
    // as the digitizer stabilizes. These "echo" touches have low acceleration (no physical impact).
    const val E0  = 50L    // ms: echo window start (ignore immediate touches, they're usually intentional)
    const val E1  = 500L   // ms: echo window end (beyond this, treat as independent touch)

    // --- Accelerometer Thresholds ---
    // Physical hand/finger impacts create measurable acceleration. Stationary interference
    // (water, EMR) has near-zero acceleration.
    const val AT  = 0.016f // m/s²: min acceleration magnitude confirming physical impact
    const val AW  = 55L    // ms: half-window for peak detection (covers gesture + sensor delay)

    // --- Contact Geometry Thresholds ---
    // Flesh contacts are large, round, with moderate pressure. Ghost touches are small,
    // elongated, with extreme pressure (too high or too low).
    const val EG  = 2.4f   // Eccentricity (touchMajor/touchMinor): above this = elongated track (ghost)
    const val SH  = 0.030f // Size: min value for flesh contact (normalized 0-1 scale)
    const val PH  = 0.07f  // Pressure: min value for flesh contact (normalized 0-1 scale)

    // --- Pressure Slope Threshold ---
    // Flesh contacts have gradual pressure ramp (finger pressing down). Water/EMR have
    // instant pressure spike (no ramp-up time).
    const val PST = 0.0008f // pressure/ms: min ramp rate indicating flesh (0.07 over ~100ms)

    // --- Dwell Time Threshold ---
    // Very brief touches (<60ms) are often interference spikes. Intentional touches dwell longer.
    const val DL  = 60     // ms: min dwell time for jitter calculation (need 4+ samples)

    // --- Pass-Through Grace Period ---
    // Once a touch is allowed, the overlay becomes pass-through for a short duration to let
    // the gesture complete (e.g., swipe, drag). Prevents interrupting mid-gesture.
    const val PT  = 500L   // ms: how long pass-through stays open after allowing a touch

    // --- Voting Thresholds (Tier 3 Multi-Signal) ---
    // Used when touch doesn't match Tier 1 (unconditional allow) or Tier 2 (unconditional block).
    const val HT  = 2      // Human signals ≥ this → ALLOW
    const val GT  = 3      // Ghost signals ≥ this (with 0 human) → BLOCK

    // --- Service Actions (Intent action strings) ---
    const val A_START  = "gtg.START"   // Start service
    const val A_STOP   = "gtg.STOP"    // Stop service
    const val A_TOGGLE = "gtg.TOGGLE"  // Toggle guardian on/off
    const val A_PEN    = "gtg.PEN"     // Toggle S-Pen exclusive mode
    const val A_STATS  = "gtg.STATS"   // Toggle stats overlay
    const val A_WRONG  = "gtg.WRONG"   // Report false detection (adaptive learning trigger)

    // --- SharedPreferences Keys ---
    const val PK_EN  = "en"   // Guardian enabled (boolean)
    const val PK_PEN = "pen"  // S-Pen mode active (boolean)
    const val PK_BLK = "blk"  // Total blocks count (long)
    const val PK_ZNS = "zns"  // Safe zones JSON (string)
    const val PK_STS = "sts"  // Stats overlay visible (boolean)
    const val PK_MIG = "mig"  // Migration done flag (boolean)
    const val PK_AJG = "ajG"  // Adaptive jitter ghost threshold (float)
    const val PK_AJH = "ajH"  // Adaptive jitter human threshold (float)

    // --- Notification IDs ---
    const val CH_ID = "gtg_ch"  // Notification channel ID
    const val N_ID  = 2401      // Notification ID
}
