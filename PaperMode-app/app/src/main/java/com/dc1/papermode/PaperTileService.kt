package com.dc1.papermode

import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: one tap toggles the full reading preset —
 * grayscale on + 30fps + warm filter. Tap again to revert.
 */
class PaperTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let {
            it.state = if (DisplayController.isGrayscale(this)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val turningOn = !DisplayController.isGrayscale(this)
        runCatching { DisplayController.setGrayscale(this, turningOn) }
        if (DisplayController.canWriteSystem(this)) {
            DisplayController.setRefresh(this, if (turningOn) 30f else 0f)
        }
        if (Settings.canDrawOverlays(this)) {
            val i = Intent(this, OverlayService::class.java)
            if (turningOn) {
                startService(i.setAction(OverlayService.ACTION_WARMTH).putExtra(OverlayService.EXTRA_LEVEL, 25))
            } else {
                startService(i.setAction(OverlayService.ACTION_STOP))
            }
        }
        qsTile?.let {
            it.state = if (turningOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.updateTile()
        }
    }
}
