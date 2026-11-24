package com.antigravity.ghosttouchguardian

import org.json.JSONArray
import org.json.JSONObject

/** Safe-zone rectangle. Touch inside → always allow, bypass detection. */
data class Z(val x: Float, val y: Float, val w: Float, val h: Float) {
    fun has(px: Float, py: Float) = px in x..(x + w) && py in y..(y + h)
}

/** Serialize / deserialize a list of safe zones to JSON string. */
object ZStore {
    fun toJson(zs: List<Z>): String = JSONArray().also { arr ->
        zs.forEach { z ->
            arr.put(JSONObject().apply {
                put("x", z.x); put("y", z.y); put("w", z.w); put("h", z.h)
            })
        }
    }.toString()

    fun fromJson(s: String): List<Z> {
        val arr = try { JSONArray(s) } catch (_: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { i ->
            try {
                val o = arr.getJSONObject(i)
                Z(o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
                  o.getDouble("w").toFloat(), o.getDouble("h").toFloat())
            } catch (_: Exception) { null }
        }
    }
}
