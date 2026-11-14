package com.davacada.letra

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * While a take is recording, this keeps Letra allowed to use the microphone even if the screen locks or you
 * switch to another app. It shows a "Recording" notification; only Stop in Letra ends the take.
 */
class RecService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Recording", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, LetraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.i_mic)
            .setContentTitle("Recording")
            .setContentText(intent?.getStringExtra("title") ?: "Letra")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        try { startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) } catch (e: Exception) { stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val CH = "recording"
        fun start(ctx: Context, title: String) {
            try { ctx.startForegroundService(Intent(ctx, RecService::class.java).putExtra("title", title)) } catch (e: Exception) { }
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, RecService::class.java)) }
    }
}
