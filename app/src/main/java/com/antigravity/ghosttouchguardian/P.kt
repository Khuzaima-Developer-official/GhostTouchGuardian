package com.antigravity.ghosttouchguardian

import android.content.Context
import android.content.SharedPreferences

/** Thin SharedPreferences wrapper. Handles one-time migration from v3.x keys. */
class P(ctx: Context) {

    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("gtg_p", Context.MODE_PRIVATE)

    init { migrate(ctx) }

    var en: Boolean  // guardian enabled
        get() = sp.getBoolean(K.PK_EN, true)
        set(v) { sp.edit().putBoolean(K.PK_EN, v).apply() }

    var pen: Boolean // S-Pen exclusive mode
        get() = sp.getBoolean(K.PK_PEN, false)
        set(v) { sp.edit().putBoolean(K.PK_PEN, v).apply() }

    var blk: Long    // lifetime blocked count
        get() = sp.getLong(K.PK_BLK, 0L)
        set(v) { sp.edit().putLong(K.PK_BLK, v).apply() }

    var zns: String  // safe zones JSON array
        get() = sp.getString(K.PK_ZNS, "[]") ?: "[]"
        set(v) { sp.edit().putString(K.PK_ZNS, v).apply() }

    var stats: Boolean  // live stats overlay on/off
        get() = sp.getBoolean(K.PK_STS, false)
        set(v) { sp.edit().putBoolean(K.PK_STS, v).apply() }

    /** One-time migration: preserve lifetime block count from old prefs. */
    private fun migrate(ctx: Context) {
        if (sp.contains(K.PK_MIG)) return
        try {
            val old = ctx.getSharedPreferences("ghost_touch_guardian_prefs", Context.MODE_PRIVATE)
            val oldBlk = old.getLong("pref_blocked_ghosts", 0L)
            if (oldBlk > 0L) blk = oldBlk
            val oldPen = old.getBoolean("pref_spen_mode_active", false)
            if (oldPen) pen = true
        } catch (_: Exception) {}
        sp.edit().putBoolean(K.PK_MIG, true).apply()
    }
}
