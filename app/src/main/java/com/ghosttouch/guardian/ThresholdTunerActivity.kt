package com.ghosttouch.guardian

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class ThresholdTunerActivity : AppCompatActivity() {
    
    private lateinit var p: P
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_threshold_tuner)
        
        p = P(this)
        
        updateDisplay()
        
        findViewById<Button>(R.id.btnResetThresholds).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset Thresholds?")
                .setMessage("Reset adaptive thresholds to defaults?")
                .setPositiveButton("Reset") { _, _ ->
                    Rec.aJG = K.JG
                    Rec.aJH = K.JH
                    p.aJG = K.JG
                    p.aJH = K.JH
                    updateDisplay()
                    Toast.makeText(this, "Thresholds reset to defaults", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        
        findViewById<Button>(R.id.btnClose).setOnClickListener {
            finish()
        }
    }
    
    private fun updateDisplay() {
        // Jitter thresholds
        findViewById<TextView>(R.id.tvJG).text = "JG (Ghost Max): %.2fpx (default: %.2fpx)".format(Rec.aJG, K.JG)
        findViewById<TextView>(R.id.tvJH).text = "JH (Human Min): %.2fpx (default: %.2fpx)".format(Rec.aJH, K.JH)
        findViewById<TextView>(R.id.tvAdaptiveJG).text = "Adaptive JG: %.2fpx".format(Rec.aJG)
        findViewById<TextView>(R.id.tvAdaptiveJH).text = "Adaptive JH: %.2fpx".format(Rec.aJH)
        
        // Echo window
        findViewById<TextView>(R.id.tvE0).text = "E0 (Echo Start): %dms".format(K.E0)
        findViewById<TextView>(R.id.tvE1).text = "E1 (Echo End): %dms".format(K.E1)
        
        // Accelerometer
        findViewById<TextView>(R.id.tvAT).text = "AT (Accel Threshold): %.3f m/s²".format(K.AT)
        
        // Geometry
        findViewById<TextView>(R.id.tvEG).text = "EG (Eccentricity Ghost): %.2f".format(K.EG)
        findViewById<TextView>(R.id.tvSH).text = "SH (Size Human): %.3f".format(K.SH)
        findViewById<TextView>(R.id.tvPH).text = "PH (Pressure Human): %.3f".format(K.PH)
        
        // Burst
        findViewById<TextView>(R.id.tvBI).text = "BI (Burst Interval): %dms".format(K.BI)
        findViewById<TextView>(R.id.tvBD).text = "BD (Burst Distance): %.1fpx".format(K.BD)
    }
}
