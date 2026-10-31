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
import android.hardware.camera2.CameraDevice
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

class CameraActivity : Activity(), EngineListener, TextureView.SurfaceTextureListener, BmdRemote.Listener {

    private lateinit var engine: CamEngine
    private lateinit var remote: BmdRemote
    private lateinit var remoteButton: TextView
    private var remoteDevices: List<android.bluetooth.BluetoothDevice> = emptyList()
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
        remote = sharedRemote?.also { it.listener = this } ?: BmdRemote(applicationContext, this).also { sharedRemote = it }
        loadSettings()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        immersive()
        main.removeCallbacks(analyser)
        main.postDelayed(analyser, 300)
        if (!hasPermissions()) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        engine.start()
        if (texture.isAvailable) openCamera()
    }

    // Reads a small copy of the live picture ~10 times a second for the histogram, overlays and auto exposure.
    private var analysis = Analysis()
    private val analyser = object : Runnable {
        override fun run() {
            if (texture.isAvailable) {
                try {
                    texture.getBitmap(analysis.bitmap)
                    val overlayPixels = analysis.run(engine.overlays)
                    hist.setData(analysis.hist)
                    overlay.setFrame(overlayPixels, analysis.w, analysis.h)
                    engine.feedLuma(analysis.mean)
                } catch (e: Exception) { }
            }
            main.postDelayed(this, 100)
        }
    }

    override fun onDestroy() {
        // Turning the phone rebuilds the screen; keep the Bluetooth link to the camera through that.
        if (!isChangingConfigurations) { remote.disconnect(); sharedRemote = null }
        super.onDestroy()
    }

    override fun onPause() {
        remote.stopScan()
        main.removeCallbacks(analyser)
        saveSettings()
        engine.stop()
        main.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            if (hasBtPermissions()) startRemoteScan() else say("Pro Camera needs Bluetooth access to talk to the camera.")
            return
        }
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
        if (lenses.isEmpty()) {
            lenses = Caps.lenses(this)
            val wanted = prefs.getString("lensLabel", "")
            lensIdx = lenses.indexOfFirst { it.label == wanted && it.available }.takeIf { it >= 0 } ?: lenses.indexOfFirst { it.isMain }.coerceAtLeast(0)
        }
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
        } else {
            // Upright: the picture arrives already turned; fit 9:16 inside the view without stretching.
            val s2 = min(vw / ph, vh / pw)
            m.setScale(ph * s2 / vw, pw * s2 / vh, cx, cy)
            if (rotation == Surface.ROTATION_180) m.postRotate(180f, cx, cy)
            texture.setTransform(m)
            val dw2 = ph * s2; val dh2 = pw * s2
            overlay.previewRect = RectF(cx - dw2 / 2, cy - dh2 / 2, cx + dw2 / 2, cy + dh2 / 2)
            return
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

    private var portraitUi = false

    private fun buildUi(): View {
        portraitUi = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val portrait = portraitUi
        analysis = if (portrait) Analysis(90, 160) else Analysis(160, 90)
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
        resCell = Cell("RES") { if (remote.connected) showRemoteMenu() else if (engine.settings.video) showResPanel() else showFormatPanel() }
        fpsCell = Cell("FPS") { showFpsPanel() }
        shutterCell = Cell("SHUTTER") { showShutterPanel() }
        isoCell = Cell("ISO") { showIsoPanel() }
        wbCell = Cell("WB") { showWbPanel() }
        // Upright: settings fill the first row; timecode, remaining and remote go on a second row.
        val topWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = glass() }
        val top2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), dp(4))
        }
        if (portrait) { top.background = null; topWrap.addView(top, LinearLayout.LayoutParams(-1, dp(52))); topWrap.addView(top2, LinearLayout.LayoutParams(-1, dp(40))) }
        val row2 = if (portrait) top2 else top
        listOf(resCell, fpsCell, shutterCell, isoCell, wbCell).forEach {
            if (portrait) { it.setPadding(dp(4), 0, dp(4), 0); top.addView(it, LinearLayout.LayoutParams(0, dp(44), 1f)) }
            else top.addView(it, LinearLayout.LayoutParams(-2, dp(44)))
        }
        val tcBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        recDot = View(this).apply { background = pill(0xFFFF3B30.toInt(), dpf(5f)); visibility = View.INVISIBLE }
        tcBox.addView(recDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) })
        timecode = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); setTypeface(Typeface.DEFAULT_BOLD)
            fontFeatureSettings = "tnum"; letterSpacing = 0.04f; setTextColor(Color.WHITE); text = "00:00:00:00"
        }
        tcBox.addView(timecode)
        row2.addView(tcBox, LinearLayout.LayoutParams(0, dp(if (portrait) 36 else 44), 1f))
        remainingCell = Cell("REMAINING", null).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        remainingCell.labelView.gravity = Gravity.END; remainingCell.valueView.gravity = Gravity.END
        row2.addView(remainingCell, LinearLayout.LayoutParams(-2, dp(if (portrait) 36 else 44)))
        if (portrait) root.addView(topWrap, FrameLayout.LayoutParams(-1, dp(92), Gravity.TOP))
        else root.addView(top, FrameLayout.LayoutParams(-1, dp(52), Gravity.TOP))

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
        root.addView(left, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = dp(if (portrait) 102 else 62); leftMargin = dp(10) })

        // histogram + meter
        hist = HistogramView(this).apply { background = glass(0x80, dpf(12f)); setPadding(dp(6), dp(6), dp(6), dp(6)) }
        root.addView(hist, FrameLayout.LayoutParams(dp(150), dp(60), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(if (portrait) 10 else 72); bottomMargin = dp(if (portrait) 78 else 12)
        })
        val meterBox = FrameLayout(this).apply { background = glass(0x80, dpf(12f)); setPadding(0, dp(8), 0, dp(8)) }
        meter = AudioMeterView(this)
        meterBox.addView(meter, FrameLayout.LayoutParams(-1, -1))
        root.addView(meterBox, FrameLayout.LayoutParams(dp(34), dp(60), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(if (portrait) 168 else 232); bottomMargin = dp(if (portrait) 78 else 12)
        })

        // focus bar
        val focusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = glass(0x80, dpf(26f)); setPadding(dp(6), 0, dp(12), 0)
        }
        afButton = TextView(this).apply {
            gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (remote.connected) { remote.autoFocus(); say("Autofocus on the camera."); return@setOnClickListener }
                engine.settings.af = !engine.settings.af; engine.update(); renderAll()
            }
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
                    if (remote.connected) { remote.setFocus(p / 1000f); return }
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
        root.addView(focusRow, FrameLayout.LayoutParams(-1, dp(52), Gravity.BOTTOM).apply {
            if (portrait) { leftMargin = dp(10); rightMargin = dp(104); bottomMargin = dp(16) }
            else { leftMargin = dp(280); rightMargin = dp(110); bottomMargin = dp(16) }
        })

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
        remoteButton = TextView(this).apply {
            gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setTypeface(Typeface.DEFAULT_BOLD)
            letterSpacing = 0.06f; setPadding(dp(10), 0, dp(10), 0)
            contentDescription = "Blackmagic camera remote"
            setOnClickListener { showRemoteMenu() }
        }
        // Remote lives at the top right of the top bar, after REMAINING.
        row2.addView(remoteButton, LinearLayout.LayoutParams(-2, dp(30)).apply { marginStart = dp(10); marginEnd = dp(4) })
        if (!portrait) right.addView(modeButton, LinearLayout.LayoutParams(-2, dp(30)).apply { bottomMargin = dp(8) })
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { setColor(0xFF2C3440.toInt()); cornerRadius = dpf(10f); setStroke(dp(2), 0xB3FFFFFF.toInt()) }
            clipToOutline = true
            contentDescription = "Open last capture"
            setOnClickListener { openLast() }
        }
        if (!portrait) {
            right.addView(thumb, LinearLayout.LayoutParams(dp(40), dp(40)))
            root.addView(right, FrameLayout.LayoutParams(dp(98), -1, Gravity.END).apply { topMargin = dp(52) })
        } else {
            // Upright: everything you press while shooting sits in one column at the bottom right, under your thumb:
            // focal lengths, photo/video, record, last capture.
            (lensColumn.parent as? ViewGroup)?.removeView(lensColumn)
            (recordButton.parent as? ViewGroup)?.removeView(recordButton)
            lensColumn.orientation = LinearLayout.VERTICAL
            val thumbRail = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                background = glass(0x73, dpf(40f)); setPadding(dp(8), dp(12), dp(8), dp(12))
            }
            thumbRail.addView(lensColumn, LinearLayout.LayoutParams(-2, -2))
            thumbRail.addView(modeButton, LinearLayout.LayoutParams(-2, dp(30)).apply { topMargin = dp(10) })
            thumbRail.addView(recordButton, LinearLayout.LayoutParams(dp(74), dp(74)).apply { topMargin = dp(12) })
            thumbRail.addView(thumb, LinearLayout.LayoutParams(dp(42), dp(42)).apply { topMargin = dp(12) })
            root.addView(thumbRail, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(8); bottomMargin = dp(16)
            })
        }

        // little message strip
        toast = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = glass(0xD0, dpf(14f)); setPadding(dp(14), dp(10), dp(14), dp(10)); visibility = View.GONE
            maxWidth = dp(420)
        }
        root.addView(toast, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(if (portrait) 104 else 64) })

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
            fpsCell.set(if (s.slowMo) "${s.fps}→24" else s.fps.toString() + if (engine.currentPlan()?.highSpeed == true) " S" else "")
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
        renderRemote()
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
        lensColumn.addView(TextView(this).apply {
            text = "MM"; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            letterSpacing = 0.08f; setTextColor(0xFFB9C2CF.toInt())
        }, LinearLayout.LayoutParams(dp(50), -2).apply {
            bottomMargin = dp(3)
        })
        lenses.forEachIndexed { i, l ->
            val on = i == lensIdx
            lensColumn.addView(TextView(this).apply {
                text = l.label; gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(if (on) 0xFF0D1117.toInt() else Color.WHITE)
                background = pill(if (on) Color.WHITE else 0x1FFFFFFF, dpf(14f))
                alpha = if (l.available) 1f else 0.3f
                contentDescription = "${l.label} millimetre lens"
                setOnClickListener { pickLens(i) }
            }, LinearLayout.LayoutParams(dp(50), dp(28)).apply {
                bottomMargin = dp(4)
            })
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
        if (remote.connected) { remote.toggleRecord(); return }
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
        val target = lenses.getOrNull(i) ?: return
        if (i == lensIdx) return
        if (!target.available) { say(target.reason); return }
        // Zooming on the same camera is fine mid-recording; switching to another camera isn't.
        val sameCamera = lenses.getOrNull(lensIdx)?.id == target.id
        if (!sameCamera && busy()) return
        lensIdx = i
        renderLenses()
        if (target.digital && engine.settings.video && (engine.settings.res?.size?.width ?: 0) >= 3840)
            say("${target.mm} mm is a crop of the sensor, so ${engine.settings.res?.label} will be a little softer than at wider focal lengths.")
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

    // ------------------------------------------------------------------ Blackmagic remote

    private fun hasBtPermissions(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 31)
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        else checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun showRemoteMenu() {
        if (!remote.available) { say("This phone has no Bluetooth."); return }
        if (!hasBtPermissions()) {
            val perms = if (android.os.Build.VERSION.SDK_INT >= 31)
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            requestPermissions(perms, 2)
            return
        }
        if (remote.connected) { connectedRemotePanel(); return }
        startRemoteScan()
    }

    private fun startRemoteScan() {
        if (!remote.enabled) { say("Turn Bluetooth on, then tap REMOTE again."); return }
        remoteDevices = emptyList()
        remote.scan()
        devicePanel()
    }

    private fun devicePanel() {
        val opts = remoteDevices.map { d ->
            @Suppress("MissingPermission") val name = d.name ?: d.address
            Opt(name.takeLast(6), false) { remote.connect(d) }
        }
        showPanel(remoteButton, "BLACKMAGIC REMOTE", opts,
            if (opts.isEmpty()) "Looking for your camera… On the Pocket 4K, turn Bluetooth on in Setup › Bluetooth, and keep it close."
            else "Tap your camera. The first time, your phone asks for the 6-digit code shown on the camera's screen.",
            columns = 2)
    }

    private fun connectedRemotePanel() {
        val stops = listOf(1.8f, 2.8f, 4f, 5.6f, 8f, 11f, 16f)
        val opts = ArrayList<Opt>()
        stops.forEach { f -> opts.add(Opt("f/" + (if (f % 1f == 0f) f.toInt().toString() else f.toString()), kotlin.math.abs(remote.fStop - f) < 0.15f) { remote.setFStop(f) }) }
        opts.add(Opt("AF", false) { remote.autoFocus() })
        opts.add(Opt("OFF", false) { remote.disconnect(); say("Back to the phone's camera.") })
        showPanel(remoteButton, "${remote.cameraName.uppercase()} · IRIS", opts,
            "Record, ISO, shutter, white balance, frame rate and focus now control the camera. OFF switches back to the phone's camera.")
    }

    private fun remoteIsoPanel() {
        val isos = listOf(100, 200, 400, 640, 800, 1250, 1600, 3200, 6400, 12800, 25600)
        showPanel(isoCell, "ISO · CAMERA", isos.map { v -> Opt(v.toString(), remote.iso == v) { remote.setIso(v) } },
            "The Pocket 4K has two native ISOs, 400 and 3200, where it's cleanest.")
    }

    private fun remoteShutterPanel() {
        val angles = listOf(45f, 90f, 172.8f, 180f, 270f, 360f)
        showPanel(shutterCell, "SHUTTER · CAMERA", angles.map { a ->
            Opt(if (a % 1f == 0f) "${a.toInt()}°" else "$a°", kotlin.math.abs(remote.shutterAngle - a) < 0.5f) { remote.setShutterAngle(a) }
        }, "172.8° avoids flicker from 50 Hz lights at 24 fps. 180° gives natural motion blur.", columns = 3)
    }

    private fun remoteWbPanel() {
        val ks = listOf(0, 3200, 4000, 4500, 5600, 6500, 7500)
        showPanel(wbCell, "WHITE BALANCE · CAMERA", ks.map { k ->
            Opt(if (k == 0) "AUTO" else "${k}K", k != 0 && remote.whiteBalance == k) { if (k == 0) remote.autoWhiteBalance() else remote.setWhiteBalance(k, remote.tint) }
        }, "AUTO sets the white balance once from what the camera sees.", columns = 4)
    }

    private fun remoteFpsPanel() {
        val rates = listOf(24, 25, 30, 50, 60)
        showPanel(fpsCell, "FRAME RATE · CAMERA", rates.map { f ->
            Opt(f.toString(), remote.fps == f) {
                if (!remote.setFps(f)) say("Change the frame rate once on the camera so the app learns its resolution, then try again.")
            }
        }, "Keeps the camera's current resolution and codec.")
    }

    private fun renderRemote() {
        val on = remote.connected
        remoteButton.text = when { on -> "POCKET"; remote.connecting -> "…"; else -> "REMOTE" }
        remoteButton.setTextColor(if (on) 0xFF0D1117.toInt() else Color.WHITE)
        remoteButton.background = pill(if (on) 0xFF33E07A.toInt() else 0x1FFFFFFF, dpf(13f))
        if (!on) return
        resCell.labelView.text = "CAMERA"; resCell.set(remote.cameraName.take(10))
        fpsCell.visibility = View.VISIBLE
        fpsCell.set(if (remote.fps > 0) remote.fps.toString() else "—")
        shutterCell.set(if (remote.shutterAngle > 0) (if (remote.shutterAngle % 1f == 0f) "${remote.shutterAngle.toInt()}°" else "${remote.shutterAngle}°") else "—")
        isoCell.set(if (remote.iso > 0) remote.iso.toString() else "—")
        wbCell.set(if (remote.whiteBalance > 0) "${remote.whiteBalance}K" else "—")
        remainingCell.labelView.text = "LINK"; remainingCell.set("BLUETOOTH")
        if (remote.timecode.isNotEmpty()) timecode.text = remote.timecode
        timecode.setTextColor(if (remote.recording) 0xFFFF6B61.toInt() else Color.WHITE)
        recDot.visibility = if (remote.recording) View.VISIBLE else View.INVISIBLE
        recordButton.photo = false
        recordButton.recording = remote.recording
        recordButton.contentDescription = if (remote.recording) "Stop recording on the camera" else "Start recording on the camera"
        afButton.text = "AF"
        focusText.text = "camera"
    }

    override fun onRemoteChanged() {
        if (!remote.connected) resCell.labelView.text = "RES"
        renderAll()
    }

    override fun onRemoteFound(devices: List<android.bluetooth.BluetoothDevice>) {
        remoteDevices = devices
        if (panel != null && !remote.connected && !remote.connecting) devicePanel()
    }

    override fun onRemoteMessage(msg: String) {
        say(msg)
        if (remote.connected) closePanel()
    }

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
        if (!note.isNullOrEmpty()) {
            val long = note.length > 300
            val tv = TextView(this).apply {
                text = note; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(0xFFC9D1DC.toInt()); setLineSpacing(0f, 1.25f)
                maxWidth = dp(if (long) 460 else 320)
                setTextIsSelectable(long)
            }
            if (long) {
                // Long text (capabilities + diagnostics) scrolls inside the card.
                val sv = android.widget.ScrollView(this).apply { addView(tv) }
                card.addView(sv, LinearLayout.LayoutParams(dp(460), (root.height - dp(110)).coerceAtLeast(dp(160))).apply { topMargin = dp(10) })
            } else card.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        }

        val loc = IntArray(2).also { anchor.getLocationInWindow(it) }
        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        val lp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = (loc[0] - rootLoc[0]).coerceIn(dp(8), (root.width - dp(if ((note?.length ?: 0) > 300) 500 else 360)).coerceAtLeast(dp(8)))
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
        if (remote.connected) { remoteFpsPanel(); return }
        if (busy()) return
        val c = engine.caps ?: return
        val s = engine.settings
        val r = s.res ?: return
        val opts = Caps.FPS.map { f ->
            val plan = c.plan(r.size, f)
            Opt(if (plan?.highSpeed == true) "$f S" else f.toString(), f == s.fps && !s.slowMo, plan != null) {
                s.fps = f; s.slowMo = false; engine.reconfigure()
            }
        }.toMutableList()
        // Slow motion: shot at a high speed, saved to play at 24 fps (no sound). Uses this resolution if it can,
        // otherwise 1080p, otherwise 720p.
        listOf(120, 240, 480, 960).forEach { f ->
            val res = listOf(r) + c.resolutions.filter { it.label == "1080p" } + c.resolutions.filter { it.label == "720p" }
            val pick = res.firstOrNull { c.plan(it.size, f) != null }
            opts.add(Opt("SLO $f", s.slowMo && s.fps == f, pick != null) {
                s.res = pick; s.fps = f; s.slowMo = true; engine.reconfigure()
            })
        }
        val hs = Caps.FPS.filter { c.plan(r.size, it)?.highSpeed == true }
        val note = StringBuilder("Speeds your phone can't record at ${r.label} are greyed out.")
        note.append(" SLO speeds save the clip to play at 24 fps without sound: SLO 120 is 5x slower, 240 is 10x, 480 is 20x, 960 is 40x. They switch to 1080p or 720p if this resolution can't go that fast.")
        if (hs.isNotEmpty()) note.append(" \"S\" speeds use slow-motion mode, where ISO, shutter and white balance are automatic.")
        if (c.plan(r.size, s.fps)?.aeLocked == false) note.append(" At ${s.fps} fps the app runs exposure itself; set ISO or shutter for full control.")
        showPanel(fpsCell, "FRAME RATE · ${r.label}", opts, note.toString())
    }

    private fun showShutterPanel() {
        if (remote.connected) { remoteShutterPanel(); return }
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
        if (remote.connected) { remoteIsoPanel(); return }
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
        if (remote.connected) { remoteWbPanel(); return }
        val s = engine.settings
        if (highSpeedNow) { say("White balance is automatic in slow-motion mode."); return }
        val steps = (WhiteBalance.MAX - WhiteBalance.MIN) / WhiteBalance.STEP
        val start = if (s.wb == 0) WhiteBalance.NEUTRAL else s.wb
        fun k(i: Int) = WhiteBalance.MIN + i * WhiteBalance.STEP
        showPanel(wbCell, "WHITE BALANCE", listOf(
            Opt("AUTO", s.wb == 0) { s.wb = 0; engine.update() },
            Opt("4000K", s.wb == WhiteBalance.NEUTRAL) { s.wb = WhiteBalance.NEUTRAL; engine.update() },
        ), "4000K is neutral daylight. Slide left for a cooler picture (or to correct warm indoor light), right for warmer.",
            slider = Triple(steps, (start - WhiteBalance.MIN) / WhiteBalance.STEP, { i: Int -> if (k(i) == WhiteBalance.NEUTRAL) "4000K ☀" else "${k(i)}K" }),
            onSlide = { i -> s.wb = k(i); engine.update() }, columns = 2)
    }

    private fun showInfoPanel() {
        val c = engine.caps ?: Caps(lenses.getOrNull(lensIdx)?.chars ?: run {
            showPanel(lensColumn, "DIAGNOSTICS", emptyList(), engine.debugLog().ifEmpty { "Nothing logged yet." }); return
        })
        val l = lenses.getOrNull(lensIdx)
        showPanel(lensColumn, "WHAT THIS LENS CAN DO" + (l?.let { " · ${it.label}" } ?: ""), emptyList(),
            Caps.nativeSummary + "\n" + c.describe() + "\n\nDIAGNOSTICS (safe mode ${engine.safeLevel})\n" + engine.debugLog())
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
        // Not used any more: the analyser above reads the on-screen picture instead.
    }

    override fun onRecording(recording: Boolean) {
        recordButton.recording = recording
        recDot.visibility = if (recording) View.VISIBLE else View.INVISIBLE
        timecode.setTextColor(if (recording) 0xFFFF6B61.toInt() else Color.WHITE)
        if (recording) { recStart = System.nanoTime(); main.post(ticker) } else { main.removeCallbacks(ticker); meter.level = 0f }
        // Don't let the screen turn mid-clip: that would restart the camera.
        requestedOrientation = if (recording) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
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

    /** A lens refused to open: drop back to the main camera and explain in plain words. */
    override fun onOpenFailed(lens: Lens, error: Int) {
        val mainIdx = lenses.indexOfFirst { it.isMain }
        val reason = when (error) {
            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE, CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ->
                "The camera is busy in another app. Close it and try again."
            CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "The camera is turned off by a phone setting or policy."
            else -> null
        }
        val mainId = lenses.getOrNull(mainIdx)?.id
        if (reason == null && mainId != null && lens.id != mainId) {
            // This lens isn't open to other apps on this phone: remove its button and use the main camera.
            // Mark every button that relied on that camera as unavailable.
            lenses = lenses.map { if (it.id == lens.id && it.available) Lens(it.id, it.chars, it.mm, it.ratio, available = false, main = it.main,
                reason = "${it.mm} mm needs a lens this phone doesn't open to other apps.") else it }
            lensIdx = lenses.indexOfFirst { it.isMain }.coerceAtLeast(0)
            say("${lens.label} mm needs a lens this phone doesn't open to other apps, so you're on ${lenses.getOrNull(lensIdx)?.label ?: ""} mm.")
            openCamera()
            return
        }
        say(reason ?: "The camera couldn't be opened (code $error). Tap (i) to see what happened.")
    }

    // ------------------------------------------------------------------ settings

    private fun loadSettings() {
        val s = engine.settings
        s.video = prefs.getBoolean("video", true)
        s.fps = prefs.getInt("fps", 25)
        s.shutterAngle = prefs.getInt("angle", 180).takeIf { it > 0 }
        s.iso = prefs.getInt("iso", -1).takeIf { it > 0 }
        s.photoShutterNs = prefs.getLong("photoShutter", -1L).takeIf { it > 0 }
        s.wb = prefs.getInt("wb", 0).let { if (it == 0) 0 else it.coerceIn(WhiteBalance.MIN, WhiteBalance.MAX) }
        s.slowMo = prefs.getBoolean("slowMo", false)
        s.af = prefs.getBoolean("af", true)
        s.focus = prefs.getFloat("focus", 1f)
        s.stab = prefs.getBoolean("stab", true)
        s.raw = prefs.getBoolean("raw", false)
        val resLabel = prefs.getString("res", "4K")
        s.res = Caps.CANDIDATES.firstOrNull { it.first == resLabel }?.let { Res(it.first, it.second) }
        engine.overlays.zebras = prefs.getBoolean("zebras", false)
        engine.overlays.peaking = prefs.getBoolean("peaking", false)
        engine.overlays.falseColor = prefs.getBoolean("falseColor", false)
    }

    private fun saveSettings() {
        val s = engine.settings
        prefs.edit()
            .putBoolean("video", s.video).putInt("fps", s.fps)
            .putInt("angle", s.shutterAngle ?: -1).putInt("iso", s.iso ?: -1)
            .putLong("photoShutter", s.photoShutterNs ?: -1L).putInt("wb", s.wb).putBoolean("slowMo", s.slowMo)
            .putBoolean("af", s.af).putFloat("focus", s.focus).putBoolean("stab", s.stab).putBoolean("raw", s.raw)
            .putString("res", s.res?.label ?: "4K")
            .putBoolean("zebras", engine.overlays.zebras).putBoolean("peaking", engine.overlays.peaking)
            .putBoolean("falseColor", engine.overlays.falseColor).putBoolean("grid", overlay.grid)
            .putString("lensLabel", lenses.getOrNull(lensIdx)?.label ?: "")
            .apply()
    }

    companion object {
        private var sharedRemote: BmdRemote? = null
        val ISO_STOPS = listOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800)
    }
}
