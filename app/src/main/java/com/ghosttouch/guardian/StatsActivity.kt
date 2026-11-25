package com.ghosttouch.guardian

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class StatsActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)
        
        refreshStats()
        
        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            refreshStats()
        }
        
        findViewById<Button>(R.id.btnResetStats).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset Statistics?")
                .setMessage("Clear all detection statistics? This cannot be undone.")
                .setPositiveButton("Reset") { _, _ ->
                    Stats.reset()
                    refreshStats()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        
        findViewById<Button>(R.id.btnClose).setOnClickListener {
            finish()
        }
    }
    
    private fun refreshStats() {
        findViewById<TextView>(R.id.tvTotalBlocks).text = 
            "Total Blocks: " + Stats.totalBlocks
        findViewById<TextView>(R.id.tvTotalAllows).text =
            "Total Allows: " + Stats.totalAllows
        findViewById<TextView>(R.id.tvFalsePositives).text =
            "False Positives: " + Stats.falsePositives
        findViewById<TextView>(R.id.tvFalseNegatives).text =
            "False Negatives: " + Stats.falseNegatives
        findViewById<TextView>(R.id.tvAccuracy).text =
            "Accuracy: %.1f%%".format(Stats.getAccuracyRate() * 100)
        
        val reasons = Stats.getTopReasons(10)
        val adapter = ReasonAdapter(reasons)
        findViewById<RecyclerView>(R.id.reasonList).apply {
            layoutManager = LinearLayoutManager(this@StatsActivity)
            this.adapter = adapter
        }
    }
}

class ReasonAdapter(private val reasons: List<Pair<String, Int>>) :
    RecyclerView.Adapter<ReasonAdapter.ViewHolder>() {
    
    class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
    
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val tv = TextView(parent.context).apply {
            textSize = 14f
            setTextColor(0xFFF1F5F9.toInt())
            setPadding(32, 24, 32, 24)
        }
        return ViewHolder(tv)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (reason, count) = reasons[position]
        holder.textView.text = String.format("#%d. %s: %d times", position + 1, reason, count)
    }
    
    override fun getItemCount() = reasons.size
}
