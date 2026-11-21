package com.antigravity.ghosttouchguardian.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings Tile in Android Notification Bar for 1-Tap Toggle.
 */
class GuardianTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, TouchGuardianService::class.java).apply {
            action = if (TouchGuardianService.instance != null) {
                TouchGuardianService.ACTION_TOGGLE
            } else {
                TouchGuardianService.ACTION_START
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val service = TouchGuardianService.instance
        val isActive = service != null

        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isActive) "Guard Active" else "Guard Off"
        tile.updateTile()
    }
}
