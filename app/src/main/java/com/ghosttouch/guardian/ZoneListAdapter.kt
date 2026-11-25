package com.ghosttouch.guardian

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ZoneListAdapter(
    private val zones: List<Z>,
    private val onDeleteClick: (Int) -> Unit
) : RecyclerView.Adapter<ZoneListAdapter.ZoneViewHolder>() {
    
    class ZoneViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvZoneInfo: TextView = view.findViewById(R.id.tvZoneInfo)
        val btnDelete: Button = view.findViewById(R.id.btnDelete)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ZoneViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_zone, parent, false)
        return ZoneViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ZoneViewHolder, position: Int) {
        val zone = zones[position]
        holder.tvZoneInfo.text = String.format("Zone #%d: (%.0f, %.0f) %.0fx%.0fpx", position + 1, zone.x, zone.y, zone.w, zone.h)
        holder.btnDelete.setOnClickListener {
            onDeleteClick(position)
        }
    }
    
    override fun getItemCount() = zones.size
}
