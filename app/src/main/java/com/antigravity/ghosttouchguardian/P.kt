package com.antigravity.ghosttouchguardian

import android.content.Context
import android.content.SharedPreferences

/** Thin SharedPreferences wrapper. Handles migration from v3.x. */
class P(ctx: Context) {

    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("gtg_p", Context.MODE_PRIVATE)

    init { migrate(ctx) }

    var en: Boolean
        get() = sp.getBoolean(K.PK_EN, true)
        set(v) { sp.edit().putBoolean(K.PK_EN, v).apply() }

    var pen: Boolean
        get() = sp.getBoolean(K.PK_PEN, false)
        set(v) { sp.edit().putBoolean(K.PK_PEN, v).apply() }

    var blk: Long
        get() = sp.getLong(K.PK_BLK, 0L)
        set(v) { sp.edit().putLong(K.PK_BLK, v).apply() }

    var zns: String
        get() = sp.getString(K.PK_ZNS, "[]") ?: "[]"
        set(v) { sp.edit().putString(K.PK_ZNS, v).apply() }

    var stats: Boolean
        get() = sp.getBoolean(K.PK_STS, false)
        set(v) { sp.edit().putBoolean(K.PK_STS, v).apply() }

    // Adaptive thresholds (persisted, loaded by Rec)
    var aJG: Float
        get() = sp.getFloat(K.PK_AJG, K.JG)
        set(v) { sp.edit().putFloat(K.PK_AJG, v).apply() }

    var aJH: Float
        get() = sp.getFloat(K.PK_AJH, K.JH)
        set(v) { sp.edit().putFloat(K.PK_AJH, v).apply() }

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
