package com.ghosttouch.guardian

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Detection statistics tracker.
 * Records blocks by reason, false detection rates, and aggregates.
 */
object Stats {
    
    private val reasonCounts = mutableMapOf<String, Int>()
    
    @Volatile var totalBlocks = 0L
    @Volatile var totalAllows = 0L
    @Volatile var falsePositives = 0  // User reported: blocked but was human
    @Volatile var falseNegatives = 0  // User reported: allowed but was ghost
    
    private var prefs: SharedPreferences? = null
    
    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("gtg_stats", Context.MODE_PRIVATE)
        load()
    }
    
    fun recordBlock(reason: String) {
        totalBlocks++
        reasonCounts[reason] = (reasonCounts[reason] ?: 0) + 1
        save()
    }
    
    fun recordAllow(reason: String) {
        totalAllows++
        save()
    }
    
    fun recordFalsePositive() {
        falsePositives++
        save()
    }
    
    fun recordFalseNegative() {
        falseNegatives++
        save()
    }
    
    fun getTopReasons(limit: Int = 10): List<Pair<String, Int>> {
        return reasonCounts.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }
    }
    
    fun getFalseDetectionRate(): Float {
        val total = falsePositives + falseNegatives
        val totalDecisions = totalBlocks + totalAllows
        return if (totalDecisions > 0) total / totalDecisions.toFloat() else 0f
    }
    
    fun getAccuracyRate(): Float {
        return 1.0f - getFalseDetectionRate()
    }
    
    fun reset() {
        totalBlocks = 0L
        totalAllows = 0L
        falsePositives = 0
        falseNegatives = 0
        reasonCounts.clear()
        save()
    }
    
    private fun save() {
        prefs?.edit()?.apply {
            putLong("totalBlocks", totalBlocks)
            putLong("totalAllows", totalAllows)
            putInt("falsePositives", falsePositives)
            putInt("falseNegatives", falseNegatives)
            
            // Save reason counts as JSON
            val json = JSONObject()
            reasonCounts.forEach { (reason, count) ->
                json.put(reason, count)
            }
            putString("reasonCounts", json.toString())
            
            apply()
        }
    }
    
    private fun load() {
        prefs?.let { p ->
            totalBlocks = p.getLong("totalBlocks", 0L)
            totalAllows = p.getLong("totalAllows", 0L)
            falsePositives = p.getInt("falsePositives", 0)
            falseNegatives = p.getInt("falseNegatives", 0)
            
            // Load reason counts from JSON
            val json = p.getString("reasonCounts", "{}")
            try {
                val obj = JSONObject(json ?: "{}")
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    reasonCounts[key] = obj.getInt(key)
                }
            } catch (e: Exception) {
                L.w(Tags.IO, "Failed to load reason counts", e)
            }
        }
    }
}
