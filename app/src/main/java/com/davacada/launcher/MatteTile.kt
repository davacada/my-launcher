package com.davacada.launcher

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/** Quick settings tile: matte screen on/off. */
class MatteTile : TileService() {
    private val prefs get() = getSharedPreferences("launcher", Context.MODE_PRIVATE)

    override fun onStartListening() { update() }

    override fun onClick() {
        if (!MatteService.isEnabled(this)) {
            Toast.makeText(this, "Turn on \"My Launcher: matte screen\" in Accessibility first", Toast.LENGTH_LONG).show()
            val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, i, android.app.PendingIntent.FLAG_IMMUTABLE))
            } else @Suppress("DEPRECATION") startActivityAndCollapse(i)
            return
        }
        prefs.edit().putBoolean("matte", !prefs.getBoolean("matte", false)).apply()
        update()
    }

    private fun update() {
        val t = qsTile ?: return
        val on = prefs.getBoolean("matte", false) && MatteService.isEnabled(this)
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Matte screen"
        t.subtitle = if (on) listOf("", "Light", "Medium", "Strong")[prefs.getInt("matteStrength", 2).coerceIn(1, 3)] else "Off"
        t.updateTile()
    }
}
