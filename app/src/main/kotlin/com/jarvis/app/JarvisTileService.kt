package com.jarvis.app

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class JarvisTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            label = "J.A.R.V.I.S."
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        OverlayOrbService.show(this)
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            updateTile()
        }
    }
}
