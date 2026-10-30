package com.davacada.launcher

import android.app.KeyguardManager
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.WindowManager

/**
 * Watches notifications (with the user's notification access) and lights the screen edges
 * while the screen is off: orange for notifications, purple when a new song starts,
 * cyan around the incoming-call screen while the phone rings.
 */
class EdgeListenerService : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private var lastGlow = 0L
    private var lastTrack = ""
    private var callOverlay: EdgeGlowView? = null
    private var callKey: String? = null

    private fun enabled() = getSharedPreferences("launcher", Context.MODE_PRIVATE).getBoolean("edge", true)

    private fun screenOffOrLocked(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        val km = getSystemService(KeyguardManager::class.java)
        return !pm.isInteractive || km.isKeyguardLocked
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!enabled() || sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        when {
            // A ringing call carries a full-screen intent; once answered or declined it doesn't.
            n.category == Notification.CATEGORY_CALL -> if (n.fullScreenIntent != null) onCall(sbn) else if (sbn.key == callKey) stopCall()
            n.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true || n.category == Notification.CATEGORY_TRANSPORT -> onMedia(n)
            else -> onRegular(sbn, n)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == callKey) stopCall()
    }

    // ------------------------------------------------------------------ notifications

    private fun onRegular(sbn: StatusBarNotification, n: Notification) {
        if (sbn.isOngoing || (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if ((n.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0 && System.currentTimeMillis() - n.`when` > 5000) return
        val ranking = Ranking()
        if (currentRanking?.getRanking(sbn.key, ranking) == true && ranking.importance < android.app.NotificationManager.IMPORTANCE_DEFAULT) return
        if (!getSystemService(PowerManager::class.java).isInteractive) glow(EdgeGlowView.Kind.NOTIFICATION)
        else sendBroadcast(Intent(ACTION_COMPANION_NOTICE).setPackage(packageName)) // the companion looks up
    }

    private fun onMedia(n: Notification) {
        val token = if (Build.VERSION.SDK_INT >= 33)
            n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        else @Suppress("DEPRECATION") n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)
        var playing = true
        var track = (n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "") + "|" +
            (n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "")
        if (token != null) try {
            val c = MediaController(this, token)
            playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
            c.metadata?.let { md ->
                track = (md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "") + "|" +
                    (md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "")
            }
        } catch (e: Exception) { }
        if (!playing || track == "|" || track == lastTrack) return
        lastTrack = track
        if (!getSystemService(PowerManager::class.java).isInteractive) glow(EdgeGlowView.Kind.MUSIC)
    }

    private fun glow(kind: EdgeGlowView.Kind) {
        val now = System.currentTimeMillis()
        if (now - lastGlow < 3500) return
        if (!Settings.canDrawOverlays(this)) return // Android needs this to wake the screen into the glow
        lastGlow = now
        try {
            startActivity(Intent(this, EdgeGlowActivity::class.java)
                .putExtra(EdgeGlowActivity.EXTRA_KIND, kind.name)
                .putExtra(EdgeGlowActivity.EXTRA_REAL, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
        } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ calls

    /** A see-through cyan border on top of the phone app's incoming-call screen; taps go straight through. */
    private fun onCall(sbn: StatusBarNotification) {
        if (callOverlay != null || !Settings.canDrawOverlays(this)) return
        if (!screenOffOrLocked()) return
        callKey = sbn.key
        main.post {
            try {
                val wm = getSystemService(WindowManager::class.java)
                val view = EdgeGlowView(this, EdgeGlowView.Kind.CALL)
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    alpha = 0.8f // keeps the call screen's buttons tappable through the border
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                wm.addView(view, lp)
                callOverlay = view
                main.postDelayed({ stopCall() }, 60_000) // never longer than a minute
            } catch (e: Exception) {
                callOverlay = null
            }
        }
    }

    private fun stopCall() {
        callKey = null
        main.post {
            callOverlay?.let { v -> try { getSystemService(WindowManager::class.java).removeView(v) } catch (e: Exception) { } }
            callOverlay = null
        }
    }

    companion object {
        const val ACTION_COMPANION_NOTICE = "com.davacada.launcher.COMPANION_NOTICE"
    }

    override fun onListenerDisconnected() { stopCall(); super.onListenerDisconnected() }
}
