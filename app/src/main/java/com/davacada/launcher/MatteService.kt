package com.davacada.launcher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.TileService
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.random.Random

/**
 * Matte screen: a see-through layer over everything (apps, status bar, notification shade, lock screen)
 * with fine paper grain, a little less contrast and shine, and optional warmth.
 * It doesn't read the screen, and every touch goes straight through.
 */
class MatteService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    private var view: MatteView? = null
    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private val camerasInUse = HashSet<String>()
    private var recording = false
    private var recordingCallback: java.util.function.Consumer<Int>? = null

    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(id: String) { camerasInUse.add(id); apply() }
        override fun onCameraAvailable(id: String) { camerasInUse.remove(id); apply() }
    }

    override fun onServiceConnected() {
        instance = this
        prefs = getSharedPreferences("launcher", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(this)
        runCatching { getSystemService(CameraManager::class.java).registerAvailabilityCallback(cameraCallback, main) }
        if (Build.VERSION.SDK_INT >= 35) runCatching {
            val wm = getSystemService(WindowManager::class.java)
            val cb = java.util.function.Consumer<Int> { state ->
                recording = state == WindowManager.SCREEN_RECORDING_STATE_VISIBLE
                main.post { apply() }
            }
            recording = wm.addScreenRecordingCallback(mainExecutor, cb) == WindowManager.SCREEN_RECORDING_STATE_VISIBLE
            recordingCallback = cb
        }
        apply()
        TileService.requestListeningState(this, ComponentName(this, MatteTile::class.java))
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        if (key != null && key.startsWith("matte")) apply()
    }

    /** Show, hide or restyle the layer. */
    fun apply() {
        val on = prefs.getBoolean("matte", false)
        val pausedForCamera = prefs.getBoolean("matteCameraPause", true) && camerasInUse.isNotEmpty()
        val pausedForRecording = prefs.getBoolean("matteRecordPause", true) && recording
        val show = on && !pausedForCamera && !pausedForRecording
        if (!show) { remove(); return }
        val strength = prefs.getInt("matteStrength", 2)
        val warmth = prefs.getInt("matteWarmth", 0)
        val v = view ?: MatteView(this).also { add(it) }
        v.set(strength, warmth)
    }

    private fun add(v: MatteView) {
        val wm = getSystemService(WindowManager::class.java)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "Matte screen"
        }
        runCatching { wm.addView(v, lp); view = v }
    }

    private fun remove() {
        view?.let { runCatching { getSystemService(WindowManager::class.java).removeViewImmediate(it) } }
        view = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(this) }
        runCatching { getSystemService(CameraManager::class.java).unregisterAvailabilityCallback(cameraCallback) }
        if (Build.VERSION.SDK_INT >= 35) recordingCallback?.let { cb -> runCatching { getSystemService(WindowManager::class.java).removeScreenRecordingCallback(cb) } }
        remove()
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: MatteService? = null

        fun isEnabled(ctx: Context): Boolean {
            val flat = ComponentName(ctx, MatteService::class.java).flattenToString()
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.equals(flat, ignoreCase = true) }
        }
    }
}

/** The layer itself: grain tile + a soft grey wash (less contrast and shine) + optional warmth. */
class MatteView(ctx: Context) : View(ctx) {
    private val grain = Paint()
    private val wash = Paint()
    private val warm = Paint()

    init {
        val n = 256
        val r = Random(11)
        val d = resources.displayMetrics.density
        // fine grain: tiny light and dark specks, about one screen pixel each
        val px = IntArray(n * n) {
            val v = r.nextFloat()
            when {
                v > 0.90f -> Color.argb(255, 0, 0, 0)
                v < 0.10f -> Color.argb(255, 255, 255, 255)
                else -> 0
            }
        }
        val bm = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        grain.shader = BitmapShader(bm, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        grain.isFilterBitmap = false
        if (d < 1f) grain.alpha = 0
    }

    /** strength 1..3, warmth 0..2 */
    fun set(strength: Int, warmth: Int) {
        grain.alpha = intArrayOf(0, 14, 22, 32)[strength.coerceIn(1, 3)]
        wash.color = Color.argb(intArrayOf(0, 10, 18, 28)[strength.coerceIn(1, 3)], 128, 124, 116)
        warm.color = Color.argb(intArrayOf(0, 14, 26)[warmth.coerceIn(0, 2)], 255, 170, 90)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), wash)
        if (warm.alpha > 0) c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), warm)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), grain)
    }
}
