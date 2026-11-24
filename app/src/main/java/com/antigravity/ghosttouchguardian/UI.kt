package com.antigravity.ghosttouchguardian

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.antigravity.ghosttouchguardian.databinding.ActivityMainBinding

class UI : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var p: P

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        p = P(this)
        ensureOverlay()
        startSvc(K.A_START)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val svc = Svc.inst
        val blk   = svc?.p?.blk   ?: p.blk
        val en    = svc?.p?.en    ?: p.en
        val pen   = svc?.p?.pen   ?: p.pen
        val stats = svc?.p?.stats ?: p.stats

        b.tvStatus.text = when {
            pen -> "🖊 S-Pen exclusive — finger touch muted  (${blk} blocked)"
            en  -> "✅ Active — ${blk} ghosts blocked"
            else -> "⏸ Paused"
        }

        b.btnToggle.text  = if (en)    "Pause Guardian"      else "Resume Guardian"
        b.btnPen.text     = if (pen)   "S-Pen Mode: ON"      else "S-Pen Mode: OFF"
        b.btnStats.text   = if (stats) "Stats Overlay: ON"   else "Stats Overlay: OFF"
        b.btnOverlay.text = if (Settings.canDrawOverlays(this))
            "✅ Overlay permission granted" else "⚠ Grant 'Appear on top'"
        b.btnBattery.text = if (battOk())
            "⚡ Battery unrestricted: ON" else "⚡ Allow 24/7 unrestricted"

        b.btnToggle.setOnClickListener  { startSvc(K.A_TOGGLE); refresh() }
        b.btnPen.setOnClickListener     { startSvc(K.A_PEN);    refresh() }
        b.btnStats.setOnClickListener   { startSvc(K.A_STATS);  refresh() }
        b.btnOverlay.setOnClickListener { ensureOverlay() }
        b.btnBattery.setOnClickListener { requestBatt() }
    }

    private fun startSvc(action: String) =
        ContextCompat.startForegroundService(this,
            Intent(this, Svc::class.java).apply { this.action = action })

    private fun ensureOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)) {
            Toast.makeText(this,
                "Grant 'Appear on top' for ghost blocking", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }
    }

    private fun battOk(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return getSystemService(android.os.PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(packageName) == true
    }

    private fun requestBatt() {
        if (!battOk())
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        else Toast.makeText(this, "✅ Already unrestricted", Toast.LENGTH_SHORT).show()
    }
}
