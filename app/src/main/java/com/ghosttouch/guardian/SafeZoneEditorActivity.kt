package com.ghosttouch.guardian

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class SafeZoneEditorActivity : AppCompatActivity() {
    
    private lateinit var zoneEditorView: ZoneEditorView
    private lateinit var zoneListAdapter: ZoneListAdapter
    private lateinit var p: P
    private val zones = mutableListOf<Z>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_safe_zone_editor)
        
        p = P(this)
        
        // Load existing zones
        zones.clear()
        zones.addAll(ZStore.fromJson(p.zns))
        
        // Setup zone editor view
        zoneEditorView = findViewById(R.id.zoneEditorView)
        zoneEditorView.zones = zones
        zoneEditorView.onZoneAdded = { zone ->
            zones.add(zone)
            zoneListAdapter.notifyDataSetChanged()
            saveZones()
            Toast.makeText(this, "Zone added", Toast.LENGTH_SHORT).show()
        }
        
        // Setup zone list
        val recyclerView = findViewById<RecyclerView>(R.id.zoneList)
        zoneListAdapter = ZoneListAdapter(zones) { position ->
            // Delete zone
            AlertDialog.Builder(this)
                .setTitle("Delete Zone?")
                .setMessage("Remove zone #" + (position + 1) + "?")
                .setPositiveButton("Delete") { _, _ ->
                    zones.removeAt(position)
                    zoneListAdapter.notifyDataSetChanged()
                    zoneEditorView.invalidate()
                    saveZones()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = zoneListAdapter
        
        // Add zone button
        findViewById<Button>(R.id.btnAddZone).setOnClickListener {
            zoneEditorView.startDrawing()
            Toast.makeText(this, "Drag to create zone", Toast.LENGTH_SHORT).show()
        }
        
        // Clear all button
        findViewById<Button>(R.id.btnClearAll).setOnClickListener {
            if (zones.isEmpty()) return@setOnClickListener
            
            AlertDialog.Builder(this)
                .setTitle("Clear All Zones?")
                .setMessage("Remove all " + zones.size + " zones?")
                .setPositiveButton("Clear") { _, _ ->
                    zones.clear()
                    zoneListAdapter.notifyDataSetChanged()
                    zoneEditorView.invalidate()
                    saveZones()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        
        // Done button
        findViewById<Button>(R.id.btnDone).setOnClickListener {
            finish()
        }
    }
    
    private fun saveZones() {
        p.zns = ZStore.toJson(zones)
        Svc.inst?.reloadZones()  // notify service to reload cache
        L.i(Tags.UI, "Saved " + zones.size + " safe zones")
    }
    
    override fun onPause() {
        super.onPause()
        saveZones()
    }
}
