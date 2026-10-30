package com.davacada.camera

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

class CameraActivity : Activity(), EngineListener, TextureView.SurfaceTextureListener {

    private lateinit var engine: CamEngine
    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())

    private var lenses: List<Lens> = emptyList()
    private var lensIdx = 0

    // views
    private lateinit var root: FrameLayout
    private lateinit var texture: TextureView
    private lateinit var overlay: OverlayView
    private lateinit var resCell: Cell
    private lateinit var fpsCell: Cell
    private lateinit var shutterCell: Cell
    private lateinit var isoCell: Cell
    private lateinit var wbCell: Cell
    private lateinit var timecode: TextView
    private lateinit var recDot: View
    private lateinit var remainingCell: Cell
    private lateinit var hist: HistogramView
    private lateinit var meter: AudioMeterView
    private lateinit var afButton: TextView
    private lateinit var focusBar: SeekBar
    private lateinit var focusText: TextView
    private lateinit var lensColumn: LinearLayout
    private lateinit var recordButton: RecordButton
    private lateinit var modeButton: TextView
    private lateinit var thumb: ImageView
    private lateinit var toast: TextView
    private val toggles = HashMap<String, ImageView>()
    private var panel: View? = null

    private var recStart = 0L
    private var lastUri: Uri? = null
    private var meterIso = 0
    private var meterExp = 0L
    private var highSpeedNow = false

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("camera", MODE_PRIVATE)
        engine = CamEngine(this, this)
        loadSettings()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        immersive()
        if (!hasPermissions()) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        engine.start()
        if (texture.isAvailable) openCamera()
    }

    override fun onPause() {
        saveSettings()
        engine.stop()
        main.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            engine.start()
            if (texture.isAvailable) openCamera()
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                say("Microphone access is off, so videos won't have sound until you allow it in Settings.")
        } else {
            say("Pro Camera needs camera access. Allow it in Settings › Apps › Pro Camera › Permissions.")
        }
    }

    private fun hasPermissions() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    private fun openCamera() {
        if (lenses.isEmpty()) lenses = Caps.lenses(this)
        if (lenses.isEmpty()) { say("No usable back camera was found."); return }
        lensIdx = lensIdx.coerceIn(0, lenses.size - 1)
        @Suppress("DEPRECATION")
        engine.displayRotation = windowManager.defaultDisplay.rotation
        engine.open(lenses[lensIdx], texture.surfaceTexture ?: return)
        renderLenses()
    }

    // ------------------------------------------------------------------ texture

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { if (hasPermissions()) { engine.start(); openCamera() } }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = updateTransform()
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    /** Fits the camera picture (16:9) inside the screen without cropping, and lines the overlay up with it. */
    private fun updateTransform() {
        val vw = texture.width.toFloat(); val vh = texture.height.toFloat()
        if (vw == 0f || vh == 0f) return
        val buf: Size = engine.previewBufferSize()
        val pw = buf.width.toFloat(); val ph = buf.height.toFloat()
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        val m = Matrix()
        val viewRect = RectF(0f, 0f, vw, vh)
        val cx = viewRect.centerX(); val cy = viewRect.centerY()
        val scale = min(vw / pw, vh / ph)
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            val bufferRect = RectF(0f, 0f, ph, pw)
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
            m.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            m.postScale(scale, scale, cx, cy)
            m.postRotate(90f * (rotation - 2), cx, cy)
        } else if (rotation == Surface.ROTATION_180) {
            m.postRotate(180f, cx, cy)
        }
        texture.setTransform(m)
        val dw = pw * scale; val dh = ph * scale
        overlay.previewRect = RectF(cx - dw / 2, cy - dh / 2, cx + dw / 2, cy + dh / 2)
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    private fun glass(alpha: Int = 0x8C, radius: Float = 0f) = GradientDrawable().apply {
        setColor(Color.argb(alpha, 10, 14, 20)); cornerRadius = radius
    }

    private fun pill(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

    /** A label-over-value block in the top bar. */
    inner class Cell(label: String, onClick: (() -> Unit)?) : LinearLayout(this@CameraActivity) {
        val labelView = TextView(context)
        val valueView = TextView(context)
        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            labelView.text = label
            labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            labelView.letterSpacing = 0.08f
            labelView.setTextColor(0xFFB9C2CF.toInt())
            valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            valueView.setTypeface(Typeface.DEFAULT_BOLD)
            valueView.fontFeatureSettings = "tnum"
            valueView.setTextColor(Color.WHITE)
            addView(labelView); addView(valueView)
            if (onClick != null) {
                isClickable = true
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), pill(0x22FFFFFF, dpf(10f)))
                    addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
                }
                setOnClickListener { onClick() }
            }
        }
        fun set(v: String, enabled: Boolean = true) { valueView.text = v; alpha = if (enabled) 1f else 0.4f }
    }

    private fun buildUi(): View {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        texture = TextureView(this).also { it.surfaceTextureListener = this }
        root.addView(texture, FrameLayout.LayoutParams(-1, -1))
        overlay = OverlayView(this).also { it.grid = prefs.getBoolean("grid", true) }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        // top bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass()
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        resCell = Cell("RES") { if (engine.settings.video) showResPanel() else showFormatPanel() }
        fpsCell = Cell("FPS") { showFpsPanel() }
        shutterCell = Cell("SHUTTER") { showShutterPanel() }
        isoCell = Cell("ISO") { showIsoPanel() }
        wbCell = Cell("WB") { showWbPanel() }
        listOf(resCell, fpsCell, shutterCell, isoCell, wbCell).forEach { top.addView(it, LinearLayout.LayoutParams(-2, dp(44))) }
        val tcBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        recDot = View(this).apply { background = pill(0xFFFF3B30.toInt(), dpf(5f)); visibility = View.INVISIBLE }
        tcBox.addView(recDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) })
        timecode = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); setTypeface(Typeface.DEFAULT_BOLD)
            fontFeatureSettings = "tnum"; letterSpacing = 0.04f; setTextColor(Color.WHITE); text = "00:00:00:00"
        }
        tcBox.addView(timecode)
        top.addView(tcBox, LinearLayout.LayoutParams(0, dp(44), 1f))
        remainingCell = Cell("REMAINING", null).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        remainingCell.labelView.gravity = Gravity.END; remainingCell.valueView.gravity = Gravity.END
        top.addView(remainingCell, LinearLayout.LayoutParams(-2, dp(44)))
        root.addView(top, FrameLayout.LayoutParams(-1, dp(52), Gravity.TOP))

        // left rail: overlay toggles + info
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(0x73, dpf(28f))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        listOf(
            Triple("peaking", R.drawable.ic_peaking, "Focus peaking"),
            Triple("zebras", R.drawable.ic_zebras, "Zebras"),
            Triple("falseColor", R.drawable.ic_falsecolor, "False colour"),
            Triple("grid", R.drawable.ic_grid, "Grid"),
            Triple("stab", R.drawable.ic_stab, "Stabilisation")
        ).forEach { (key, icon, desc) ->
            val b = ImageView(this).apply {
                setImageResource(icon); contentDescription = desc
                setPadding(dp(11), dp(11), dp(11), dp(11))
                setOnClickListener { flipToggle(key) }
            }
            toggles[key] = b
            left.addView(b, LinearLayout.LayoutParams(dp(44), dp(44)).apply { bottomMargin = dp(4) })
        }
        left.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_info); contentDescription = "What this camera can do"
            setPadding(dp(11), dp(11), dp(11), dp(11)); alpha = 0.8f
            setOnClickListener { showInfoPanel() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(left, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = dp(62); leftMargin = dp(10) })

        // histogram + meter
        hist = HistogramView(this).apply { background = glass(0x80, dpf(12f)); setPadding(dp(6), dp(6), dp(6), dp(6)) }
        root.addView(hist, FrameLayout.LayoutParams(dp(150), dp(60), Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(72); bottomMargin = dp(12) })
        val meterBox = FrameLayout(this).apply { background = glass(0x80, dpf(12f)); setPadding(0, dp(8), 0, dp(8)) }
        meter = AudioMeterView(this)
        meterBox.addView(meter, FrameLayout.LayoutParams(-1, -1))
        root.addView(meterBox, FrameLayout.LayoutParams(dp(34), dp(60), Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(232); bottomMargin = dp(12) })

        // focus bar
        val focusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = glass(0x80, dpf(26f)); setPadding(dp(6), 0, dp(12), 0)
        }
        afButton = TextView(this).apply {
            gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE); setOnClickListener { engine.settings.af = !engine.settings.af; engine.update(); renderAll() }
        }
        focusRow.addView(afButton, LinearLayout.LayoutParams(dp(52), dp(40)))
        focusRow.addView(TextView(this).apply {
            text = "FOCUS"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); letterSpacing = 0.08f; setTextColor(0xFFB9C2CF.toInt())
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12); rightMargin = dp(4) })
        focusBar = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    engine.settings.focus = p / 1000f; engine.settings.af = false
                    engine.update(); renderFocus()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { renderAll() }
            })
        }
        focusRow.addView(focusBar, LinearLayout.LayoutParams(0, -2, 1f))
        focusText = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(Color.WHITE); gravity = Gravity.END }
        focusRow.addView(focusText, LinearLayout.LayoutParams(dp(48), -2))
        root.addView(focusRow, FrameLayout.LayoutParams(-1, dp(52), Gravity.BOTTOM).apply { leftMargin = dp(280); rightMargin = dp(110); bottomMargin = dp(16) })

        // right rail
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = glass(0x73); setPadding(0, dp(12), 0, dp(12))
        }
        lensColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        right.addView(lensColumn, LinearLayout.LayoutParams(-2, -2))
        right.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        recordButton = RecordButton(this).apply {
            contentDescription = "Record"
            setOnClickListener { shutter() }
        }
        right.addView(recordButton, LinearLayout.LayoutParams(dp(72), dp(72)))
        right.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        modeButton = TextView(this).apply {
            gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTypeface(Typeface.DEFAULT_BOLD)
            letterSpacing = 0.06f; setTextColor(Color.WHITE); background = pill(0x1FFFFFFF, dpf(15f))
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { switchMode() }
        }
        right.addView(modeButton, LinearLayout.LayoutParams(-2, dp(30)).apply { bottomMargin = dp(8) })
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { setColor(0xFF2C3440.toInt()); cornerRadius = dpf(10f); setStroke(dp(2), 0xB3FFFFFF.toInt()) }
            clipToOutline = true
            contentDescription = "Open last capture"
            setOnClickListener { openLast() }
        }
        right.addView(thumb, LinearLayout.LayoutParams(dp(40), dp(40)))
        root.addView(right, FrameLayout.LayoutParams(dp(98), -1, Gravity.END).apply { topMargin = dp(52) })

        // little message strip
        toast = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = glass(0xD0, dpf(14f)); setPadding(dp(14), dp(10), dp(14), dp(10)); visibility = View.GONE
            maxWidth = dp(420)
        }
        root.addView(toast, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(64) })

        renderAll()
        return root
    }

    // ------------------------------------------------------------------ rendering

    private fun renderAll() {
        val s = engine.settings
        val c = engine.caps
        if (s.video) {
            resCell.set(s.res?.label ?: "—")
            fpsCell.visibility = View.VISIBLE
            fpsCell.set(s.fps.toString() + if (engine.currentPlan()?.highSpeed == true) " S" else "")
            val angle = s.shutterAngle
            val den = if (angle != null) (s.fps * 360f / angle).roundToInt() else 0
            shutterCell.set(if (angle != null && !highSpeedNow) "$angle° · 1/$den" else "A " + expText(meterExp), c?.manualSensor != false && !highSpeedNow)
        } else {
            resCell.set(if (s.raw && c?.raw == true) "RAW+JPG" else "JPG")
            fpsCell.visibility = View.GONE
            shutterCell.set(s.photoShutterNs?.let { expText(it) } ?: ("A " + expText(meterExp)), c?.manualSensor != false)
        }
        isoCell.set(s.iso?.toString() ?: ("A " + (if (meterIso > 0) meterIso.toString() else "")), c?.manualSensor != false && !highSpeedNow)
        wbCell.set(if (s.wb == 0) "AUTO" else "${s.wb}K", !highSpeedNow)
        renderRemaining()
        renderFocus()
        val on = 0xE6FFFFFF.toInt()
        toggles.forEach { (key, v) ->
            val active = when (key) {
                "peaking" -> engine.overlays.peaking
                "zebras" -> engine.overlays.zebras
                "falseColor" -> engine.overlays.falseColor
                "grid" -> overlay.grid
                else -> s.stab
            }
            v.background = if (active) pill(on, dpf(22f)) else null
            v.setColorFilter(if (active) 0xFF0D1117.toInt() else Color.WHITE)
        }
        recordButton.photo = !s.video
        recordButton.recording = engine.recording
        recordButton.contentDescription = if (!s.video) "Take photo" else if (engine.recording) "Stop recording" else "Start recording"
        modeButton.text = if (s.video) "VIDEO" else "PHOTO"
    }

    private fun renderFocus() {
        val s = engine.settings
        afButton.text = if (s.af) "AF" else "MF"
        afButton.background = pill(if (s.af) 0x38FFFFFF else 0xFFFF9F0A.toInt(), dpf(20f))
        if (!focusBar.isPressed) focusBar.progress = (s.focus * 1000).toInt()
        val minFocus = engine.caps?.minFocus ?: 0f
        focusText.text = when {
            s.af -> "auto"
            minFocus <= 0f -> "fixed"
            s.focus > 0.98f -> "∞"
            else -> {
                val diopters = minFocus * (1f - s.focus)
                val m = 1f / diopters
                if (m < 1f) "${(m * 100).roundToInt()} cm" else String.format("%.1f m", m)
            }
        }
    }

    private fun renderRemaining() {
        val s = engine.settings
        val free = try { StatFs(Environment.getExternalStorageDirectory().path).availableBytes } catch (e: Exception) { 0L }
        if (s.video) {
            val r = s.res ?: return
            val bytesPerSec = Encoders.bitrate(r.size, s.fps) / 8L + 40_000L
            val secs = free / bytesPerSec
            remainingCell.set(if (secs >= 3600) "${secs / 3600} h ${(secs % 3600) / 60}" else "${secs / 60} min")
        } else {
            remainingCell.set("${free / 12_000_000L}")
            remainingCell.labelView.text = "PHOTOS LEFT"
            return
        }
        remainingCell.labelView.text = "REMAINING"
    }

    private fun renderLenses() {
        lensColumn.removeAllViews()
        lenses.forEachIndexed { i, l ->
            val on = i == lensIdx
            lensColumn.addView(TextView(this).apply {
                text = l.label; gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(if (on) 0xFF0D1117.toInt() else Color.WHITE)
                background = pill(if (on) Color.WHITE else 0x1FFFFFFF, dpf(16f))
                contentDescription = "Lens ${l.label}"
                setOnClickListener { pickLens(i) }
            }, LinearLayout.LayoutParams(dp(50), dp(32)).apply { bottomMargin = dp(6) })
        }
    }

    private fun expText(ns: Long): String {
        if (ns <= 0) return ""
        return if (ns >= 500_000_000L) String.format("%.1f\"", ns / 1e9) else "1/" + (1e9 / ns).roundToInt()
    }

    // ------------------------------------------------------------------ actions

    private fun busy(): Boolean {
        if (engine.recording) { say("Stop recording first."); return true }
        return false
    }

    private fun shutter() {
        val s = engine.settings
        if (!s.video) {
            engine.capturePhoto()
            root.foreground = ColorDrawable(0x66FFFFFF)
            main.postDelayed({ root.foreground = null }, 90)
            return
        }
        if (engine.recording) engine.stopRecording() else engine.startRecording()
    }

    private fun switchMode() {
        if (busy()) return
        engine.settings.video = !engine.settings.video
        engine.reconfigure()
        renderAll()
    }

    private fun pickLens(i: Int) {
        if (busy() || i == lensIdx) return
        lensIdx = i
        renderLenses()
        openCamera()
    }

    private fun flipToggle(key: String) {
        val o = engine.overlays
        when (key) {
            "peaking" -> o.peaking = !o.peaking
            "zebras" -> o.zebras = !o.zebras
            "falseColor" -> o.falseColor = !o.falseColor
            "grid" -> overlay.grid = !overlay.grid
            "stab" -> { engine.settings.stab = !engine.settings.stab; engine.update() }
        }
        if (!o.peaking && !o.zebras && !o.falseColor) overlay.setFrame(null, 0, 0)
        if ((o.peaking || o.zebras || o.falseColor) && highSpeedNow) say("Overlays aren't available while recording slow motion.")
        renderAll()
    }

    private fun openLast() {
        val uri = lastUri ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { say("No app could open it.") }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event?.repeatCount == 0) shutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun say(msg: String) {
        toast.text = msg
        toast.visibility = View.VISIBLE
        main.removeCallbacks(hideToast)
        main.postDelayed(hideToast, 3500)
    }
    private val hideToast = Runnable { toast.visibility = View.GONE }

    // ------------------------------------------------------------------ panels

    private class Opt(val label: String, val selected: Boolean, val enabled: Boolean = true, val action: () -> Unit)

    private fun closePanel() { panel?.let { root.removeView(it) }; panel = null }

    private fun showPanel(anchor: View, title: String, options: List<Opt>, note: String?,
                          slider: Triple<Int, Int, (Int) -> String>? = null, onSlide: ((Int) -> Unit)? = null, columns: Int = 4) {
        closePanel()
        val scrim = FrameLayout(this).apply { isClickable = true; setOnClickListener { closePanel() } }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xD10E141E.toInt()); cornerRadius = dpf(18f); setStroke(dp(1), 0x29FFFFFF) }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
        }
        card.addView(TextView(this).apply {
            text = title; setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); letterSpacing = 0.08f; setTextColor(0xFFB9C2CF.toInt())
        })
        if (options.isNotEmpty()) {
            val grid = GridLayout(this).apply { columnCount = columns }
            options.forEach { o ->
                grid.addView(TextView(this).apply {
                    text = o.label; gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(if (o.selected) 0xFF0D1117.toInt() else Color.WHITE)
                    background = pill(if (o.selected) Color.WHITE else 0x14FFFFFF, dpf(12f))
                    alpha = if (o.enabled) 1f else 0.3f
                    setOnClickListener {
                        if (!o.enabled) { say("Your phone can't record that with the current settings."); return@setOnClickListener }
                        o.action(); closePanel(); renderAll()
                    }
                }, GridLayout.LayoutParams().apply {
                    width = dp(66); height = dp(40); setMargins(dp(3), dp(3), dp(3), dp(3))
                })
            }
            card.addView(grid, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        }
        if (slider != null && onSlide != null) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val value = TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTypeface(Typeface.DEFAULT_BOLD); setTextColor(Color.WHITE)
                gravity = Gravity.END; text = slider.third(slider.second)
            }
            val sb = SeekBar(this).apply {
                max = slider.first; progress = slider.second
                progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        value.text = slider.third(p); onSlide(p); renderAll()
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            }
            row.addView(sb, LinearLayout.LayoutParams(dp(220), -2))
            row.addView(value, LinearLayout.LayoutParams(dp(64), -2))
            card.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        }
        if (!note.isNullOrEmpty()) card.addView(TextView(this).apply {
            text = note; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(0xFFC9D1DC.toInt()); setLineSpacing(0f, 1.25f)
            maxWidth = dp(320)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })

        val loc = IntArray(2).also { anchor.getLocationInWindow(it) }
        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        val lp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = (loc[0] - rootLoc[0]).coerceIn(dp(8), (root.width - dp(360)).coerceAtLeast(dp(8)))
            topMargin = dp(58)
        }
        scrim.addView(card, lp)
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        panel = scrim
    }

    private fun showResPanel() {
        if (busy()) return
        val c = engine.caps ?: return
        val s = engine.settings
        showPanel(resCell, "RESOLUTION", c.resolutions.map { r ->
            Opt(r.label, r.size == s.res?.size) {
                s.res = r; engine.pickDefaults(); engine.reconfigure()
            }
        }, "8K and 4K record as H.265 where the phone allows it, otherwise H.264. Only resolutions this lens can record are listed.")
    }

    private fun showFormatPanel() {
        val c = engine.caps ?: return
        val s = engine.settings
        val opts = mutableListOf(Opt("JPG", !s.raw) { s.raw = false; engine.reconfigure() })
        opts.add(Opt("RAW", s.raw, c.raw) { s.raw = true; engine.reconfigure() })
        showPanel(resCell, "PHOTO FORMAT", opts,
            if (c.raw) "RAW saves an uncompressed DNG next to the JPG, for editing in Lightroom and similar apps." else "This lens doesn't give apps RAW files.", columns = 2)
    }

    private fun showFpsPanel() {
        if (busy()) return
        val c = engine.caps ?: return
        val s = engine.settings
        val r = s.res ?: return
        val opts = Caps.FPS.map { f ->
            val plan = c.plan(r.size, f)
            Opt(if (plan?.highSpeed == true) "$f S" else f.toString(), f == s.fps, plan != null) {
                s.fps = f; engine.reconfigure()
            }
        }
        val hs = Caps.FPS.filter { c.plan(r.size, it)?.highSpeed == true }
        val note = StringBuilder("Speeds your phone can't record at ${r.label} are greyed out.")
        if (hs.isNotEmpty()) note.append(" \"S\" speeds use slow-motion mode, where ISO, shutter and white balance are automatic.")
        if (c.plan(r.size, s.fps)?.aeLocked == false) note.append(" At ${s.fps} fps the app runs exposure itself; set ISO or shutter for full control.")
        showPanel(fpsCell, "FRAME RATE · ${r.label}", opts, note.toString())
    }

    private fun showShutterPanel() {
        val c = engine.caps ?: return
        val s = engine.settings
        if (highSpeedNow) { say("Shutter is automatic in slow-motion mode."); return }
        if (!c.manualSensor) { say("This lens doesn't allow manual shutter control."); return }
        if (s.video) {
            val angles = listOf(45, 90, 180, 270, 360)
            val opts = mutableListOf(Opt("AUTO", s.shutterAngle == null) { s.shutterAngle = null; engine.update() })
            angles.forEach { a -> opts.add(Opt("$a°", s.shutterAngle == a) { s.shutterAngle = a; engine.update() }) }
            showPanel(shutterCell, "SHUTTER ANGLE", opts,
                "Shutter angle follows the frame rate: at ${s.fps} fps, 180° is 1/${s.fps * 2}. 180° gives natural motion blur.")
        } else {
            val speeds = listOf(1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L, 16_666_667L, 33_333_333L, 66_666_667L, 125_000_000L, 250_000_000L, 500_000_000L)
                .filter { it in c.expRange.lower..c.expRange.upper }
            val opts = mutableListOf(Opt("AUTO", s.photoShutterNs == null) { s.photoShutterNs = null; engine.update() })
            speeds.forEach { ns -> opts.add(Opt(expText(ns), s.photoShutterNs == ns) { s.photoShutterNs = ns; engine.update() }) }
            showPanel(shutterCell, "SHUTTER SPEED", opts, "Slower speeds let in more light; use a tripod below 1/30.")
        }
    }

    private fun showIsoPanel() {
        val c = engine.caps ?: return
        val s = engine.settings
        if (highSpeedNow) { say("ISO is automatic in slow-motion mode."); return }
        if (!c.manualSensor) { say("This lens doesn't allow manual ISO."); return }
        val stops = ISO_STOPS.filter { it in c.isoRange.lower..c.isoRange.upper }.ifEmpty { listOf(c.isoRange.lower, c.isoRange.upper) }
        val current = s.iso?.let { iso -> stops.indexOfFirst { it >= iso }.coerceAtLeast(0) } ?: stops.indexOfFirst { it >= meterIso }.coerceAtLeast(0)
        showPanel(isoCell, "ISO", listOf(Opt("AUTO", s.iso == null) { s.iso = null; engine.update() }),
            "Lower ISO gives a cleaner picture. Range on this lens: ${c.isoRange.lower}–${c.isoRange.upper}.",
            slider = Triple(stops.size - 1, current, { i: Int -> stops[i].toString() }),
            onSlide = { i -> s.iso = stops[i]; engine.update() })
    }

    private fun showWbPanel() {
        val s = engine.settings
        if (highSpeedNow) { say("White balance is automatic in slow-motion mode."); return }
        val presets = listOf(0, 3000, 4000, 5500, 6500, 7500)
        showPanel(wbCell, "WHITE BALANCE", presets.map { k ->
            Opt(if (k == 0) "AUTO" else "${k}K", s.wb == k) { s.wb = k; engine.update() }
        }, "3000K for indoor bulbs, 4000K for office light, 5500K for daylight, 6500K for clouds, 7500K for shade.", columns = 3)
    }

    private fun showInfoPanel() {
        val c = engine.caps ?: return
        val l = lenses.getOrNull(lensIdx)
        showPanel(lensColumn, "WHAT THIS LENS CAN DO" + (l?.let { " · ${it.label}" } ?: ""), emptyList(), c.describe())
        // place it on the left, next to the toggles
        (panel as? FrameLayout)?.getChildAt(0)?.let { card ->
            (card.layoutParams as FrameLayout.LayoutParams).leftMargin = dp(70)
            card.requestLayout()
        }
    }

    // ------------------------------------------------------------------ engine callbacks

    override fun onReady(highSpeed: Boolean, analysis: Boolean) {
        highSpeedNow = highSpeed
        updateTransform()
        if (!analysis) overlay.setFrame(null, 0, 0)
        renderAll()
    }

    override fun onMeter(iso: Int, exposureNs: Long) {
        meterIso = iso; meterExp = exposureNs
        val s = engine.settings
        if (s.iso == null) isoCell.set("A $iso", engine.caps?.manualSensor != false && !highSpeedNow)
        if ((s.video && s.shutterAngle == null) || (!s.video && s.photoShutterNs == null)) {
            shutterCell.set("A " + expText(exposureNs), engine.caps?.manualSensor != false && !highSpeedNow)
        }
    }

    override fun onAnalysis(hist: IntArray, overlay: IntArray?, w: Int, h: Int) {
        this.hist.setData(hist)
        this.overlay.setFrame(overlay, w, h)
    }

    override fun onRecording(recording: Boolean) {
        recordButton.recording = recording
        recDot.visibility = if (recording) View.VISIBLE else View.INVISIBLE
        timecode.setTextColor(if (recording) 0xFFFF6B61.toInt() else Color.WHITE)
        if (recording) { recStart = System.nanoTime(); main.post(ticker) } else { main.removeCallbacks(ticker); meter.level = 0f }
        renderAll()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!engine.recording) return
            val fps = engine.settings.fps
            val frames = ((System.nanoTime() - recStart) / (1_000_000_000L / fps))
            val ff = frames % fps; val totalS = frames / fps
            timecode.text = String.format("%02d:%02d:%02d:%02d", totalS / 3600, (totalS / 60) % 60, totalS % 60, ff)
            meter.level = engine.audioLevel()
            recDot.alpha = if ((totalS % 2L) == 0L) 1f else 0.3f
            main.postDelayed(this, 80)
        }
    }

    override fun onSaved(uri: Uri, video: Boolean) {
        lastUri = uri
        Thread {
            val bmp = try { contentResolver.loadThumbnail(uri, Size(160, 160), null) } catch (e: Exception) { null }
            if (bmp != null) main.post { thumb.setImageBitmap(bmp) }
        }.start()
        renderRemaining()
    }

    override fun onProblem(message: String) { say(message) }

    // ------------------------------------------------------------------ settings

    private fun loadSettings() {
        val s = engine.settings
        s.video = prefs.getBoolean("video", true)
        s.fps = prefs.getInt("fps", 25)
        s.shutterAngle = prefs.getInt("angle", 180).takeIf { it > 0 }
        s.iso = prefs.getInt("iso", -1).takeIf { it > 0 }
        s.photoShutterNs = prefs.getLong("photoShutter", -1L).takeIf { it > 0 }
        s.wb = prefs.getInt("wb", 0)
        s.af = prefs.getBoolean("af", true)
        s.focus = prefs.getFloat("focus", 1f)
        s.stab = prefs.getBoolean("stab", true)
        s.raw = prefs.getBoolean("raw", false)
        val resLabel = prefs.getString("res", "4K")
        s.res = Caps.CANDIDATES.firstOrNull { it.first == resLabel }?.let { Res(it.first, it.second) }
        engine.overlays.zebras = prefs.getBoolean("zebras", false)
        engine.overlays.peaking = prefs.getBoolean("peaking", false)
        engine.overlays.falseColor = prefs.getBoolean("falseColor", false)
        lensIdx = prefs.getInt("lens", 0)
    }

    private fun saveSettings() {
        val s = engine.settings
        prefs.edit()
            .putBoolean("video", s.video).putInt("fps", s.fps)
            .putInt("angle", s.shutterAngle ?: -1).putInt("iso", s.iso ?: -1)
            .putLong("photoShutter", s.photoShutterNs ?: -1L).putInt("wb", s.wb)
            .putBoolean("af", s.af).putFloat("focus", s.focus).putBoolean("stab", s.stab).putBoolean("raw", s.raw)
            .putString("res", s.res?.label ?: "4K")
            .putBoolean("zebras", engine.overlays.zebras).putBoolean("peaking", engine.overlays.peaking)
            .putBoolean("falseColor", engine.overlays.falseColor).putBoolean("grid", overlay.grid)
            .putInt("lens", lensIdx)
            .apply()
    }

    companion object {
        val ISO_STOPS = listOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800)
    }
}
