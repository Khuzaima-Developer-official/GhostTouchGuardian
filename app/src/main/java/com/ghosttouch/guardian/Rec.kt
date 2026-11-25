package com.ghosttouch.guardian

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Logging imports
import com.ghosttouch.guardian.L
import com.ghosttouch.guardian.Tags

// Recorder: CSV test logger + adaptive thresholds + error correction
object Rec {

    // Last decision for "Wrong?" feedback
    @Volatile var lastV: Vrd? = null
    @Volatile var lastC: Ctx? = null

    // Adaptive thresholds (loaded from prefs, adjusted by feedback)
    @Volatile var aJG = K.JG   // adaptive jitter ghost max
    @Volatile var aJH = K.JH   // adaptive jitter human min

    private var w: FileWriter? = null
    private var cnt = 0

    // Load adaptive thresholds from prefs
    fun load(p: P) {
        aJG = p.aJG
        aJH = p.aJH
    }

    // Open CSV log file (app external dir, no permission needed)
    fun open(ctx: Context) {
        try {
            val dir = ctx.getExternalFilesDir(null)
            dir?.mkdirs()
            val f = File(dir, "GTG_log.csv")
            val isNew = !f.exists()
            w = FileWriter(f, true)
            if (isNew) w?.write("time,vrd,h,g,jit,acc,dw,sz,prs,ecc,why\n")
        } catch(e: Exception) {
            L.w(Tags.IO, "Failed to open CSV log file", e)
        }
    }

    // Record a decision to CSV + store as last for error feedback
    fun rec(v: Vrd, c: Ctx?) {
        lastV = v
        lastC = c
        val jit = c?.let { sj(it.pts) } ?: -1f
        val dw = c?.dw ?: 0
        val sz = c?.sz ?: 0f
        val prs = c?.prs ?: 0f
        val ecc = c?.ecc ?: 0f
        val acc = c?.acc ?: 0f
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        try {
            w?.let { fw ->
                fw.write("$ts,${if(v.ghost) "BLK" else "ALW"},${v.h},${v.g},")
                fw.write("${"%.2f".format(jit)},${"%.3f".format(acc)},$dw,")
                fw.write("${"%.3f".format(sz)},${"%.3f".format(prs)},${"%.1f".format(ecc)},")
                fw.write("${v.why}\n")
                cnt++
                if (cnt >= 5) { fw.flush(); cnt = 0 }
            }
        } catch(e: Exception) {
            L.w(Tags.IO, "Failed to write CSV record", e)
        }
    }

    // User reports last decision was wrong — adjust thresholds + log error
    fun err(p: P): String {
        val v = lastV ?: return "No recent touch"
        try {
            w?.write("ERROR,${if(v.ghost) "FALSE_BLK" else "FALSE_ALW"},${v.h},${v.g},,${v.why}\n")
            w?.flush()
        } catch(e: Exception) {
            L.w(Tags.IO, "Failed to write error feedback to CSV", e)
        }

        // Update Stats object for dashboard
        if (v.ghost) {
            Stats.recordFalsePositive()
            // False positive: was human, we blocked → make more lenient
            aJG = (aJG * 1.05f).coerceAtMost(K.JG * 1.30f)
            aJH = (aJH * 0.97f).coerceAtLeast(K.JH * 0.80f)
            // Ensure gap: human threshold must stay above ghost
            if (aJH <= aJG) aJH = aJG + 0.5f
        } else {
            Stats.recordFalseNegative()
            // False negative: was ghost, we allowed → make more strict
            aJG = (aJG * 0.95f).coerceAtLeast(K.JG * 0.70f)
            aJH = (aJH * 1.03f).coerceAtMost(K.JH * 1.20f)
            if (aJH <= aJG) aJG = aJH - 0.5f
        }
        p.aJG = aJG
        p.aJH = aJH
        return if (v.ghost) "Marked as false block — thresholds relaxed" 
               else "Marked as false allow — thresholds tightened"
    }

    fun close() {
        try { w?.flush(); w?.close() } catch(e: Exception) {
            L.w(Tags.IO, "Failed to close CSV file", e)
        }
        w = null
    }
}