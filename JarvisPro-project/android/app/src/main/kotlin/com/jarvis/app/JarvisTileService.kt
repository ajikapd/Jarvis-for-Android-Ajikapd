package com.jarvis.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.jarvis.app.ui.JarvisActivity

/** Плитка в шторке открывает Jarvis и начинает слушать, как слово активации. */
class JarvisTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            label = "J.A.R.V.I.S."
            state = if (VoiceController.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        val intent = Intent(this, JarvisActivity::class.java).setAction(Intent.ACTION_ASSIST)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
