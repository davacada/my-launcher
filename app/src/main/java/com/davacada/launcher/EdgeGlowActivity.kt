package com.davacada.launcher

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Wakes the screen into pure black with only the glowing border. On an OLED screen the black
 * pixels stay off, so it looks like just the edges light up. Closes itself when the glow ends,
 * on a tap, or if the screen is turned off.
 */
class EdgeGlowActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.attributes = window.attributes.apply {
            screenBrightness = 0.35f
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)

        val kind = runCatching { EdgeGlowView.Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: "") }.getOrDefault(EdgeGlowView.Kind.NOTIFICATION)
        // Real notifications keep breathing until you tap the screen or press power; previews breathe once.
        val real = intent.getBooleanExtra(EXTRA_REAL, false)
        val glow = EdgeGlowView(this, kind, loop = real).apply { onFinished = { afterGlow() } }
        if (real) window.decorView.postDelayed({ afterGlow() }, 30 * 60 * 1000L) // safety stop after 30 minutes
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(glow, FrameLayout.LayoutParams(-1, -1))
        })
    }

    /**
     * When the glow ends: switch the screen straight off if that's allowed; otherwise stay pure black at
     * the lowest brightness (looks off on an OLED screen) and let the normal screen timeout turn it off.
     */
    private fun afterGlow() {
        if (!intent.getBooleanExtra(EXTRA_REAL, false)) { finish(); return } // previews from the drawer just close
        if (isFinishing) return
        if (EdgeOffService.screenOff()) { finish(); return }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) finish()
        return true
    }

    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_REAL = "real"
    }
}
