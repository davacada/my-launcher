package com.davacada.roll

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

@UnstableApi
class RollActivity : Activity() {
    private lateinit var store: Projects
    private lateinit var root: FrameLayout
    private lateinit var screen: FrameLayout
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newFixedThreadPool(2)
    /** Runs [f] in the background, unless the screen is already gone (then there's nothing to update). */
    private fun work(f: () -> Unit) { if (!bg.isShutdown && !isDestroyed) try { bg.execute(f) } catch (e: java.util.concurrent.RejectedExecutionException) {} }
    private var insetTop = 0; private var insetBottom = 0

    private var project: Project? = null
    private var player: ExoPlayer? = null
    private var timeline: Timeline? = null
    private var timeText: TextView? = null
    private var playBtn: ImageView? = null
    private var lookTag: TextView? = null
    private var bottom: FrameLayout? = null
    private var panelOpen = false
    private var exporting: Render.Job? = null
    private val undo = ArrayList<String>(); private val redo = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Projects(this)
        window.setDecorFitsSystemWindows(false)
        root = FrameLayout(this).apply { background = grain() }
        screen = FrameLayout(this)
        root.addView(screen, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars())
            if (b.top != insetTop || b.bottom != insetBottom) { insetTop = b.top; insetBottom = b.bottom; screen.setPadding(0, insetTop, 0, insetBottom) }
            ins
        }
        setContentView(root)
        home()
        if (savedInstanceState == null) intent?.getStringExtra("selftest")?.let { selfTest() }
    }

    /**
     * A self-test for the build machine (adb shell am start ... --es selftest 1): builds a project from the
     * files in files/selftest, shows framing, a dissolve, a photo and a reversed clip, then exports.
     * Progress is written to the log under "ROLLTEST".
     */
    private fun selfTest() {
        fun log(m: String) = android.util.Log.i("ROLLTEST", m)
        if (intent?.getStringExtra("selftest") == "tour-land") requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        work {
            try {
                val dir = File(filesDir, "selftest")
                val vids = (dir.listFiles { f -> f.name.endsWith(".mp4") } ?: emptyArray()).sortedBy { it.name }
                val photo = dir.listFiles { f -> f.name.endsWith(".jpg") }?.firstOrNull()
                fun clip(f: File): Clip {
                    val mr = MediaMetadataRetriever(); mr.setDataSource(f.path)
                    val dur = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                    val w = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt(); val h = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
                    mr.release(); return Clip(Uri.fromFile(f).toString(), dur, 0, minOf(dur, 4000), w = w, h = h)
                }
                val p = Project(name = "Selftest"); p.aspect = "9:16"
                val a = clip(vids[0]).apply { fit = true; bg = "blur"; trans = "dissolve"; transMs = 1000 }
                val ph = Stills.stillClip(this, Stills.decodePhoto(this, Uri.fromFile(photo!!)), "photo", 3000).apply { trans = "zoom"; transMs = 800 }
                val b = clip(vids.getOrElse(1) { vids[0] }).apply { trans = "whip"; transMs = 800; rot = 8f; scale = 1.2f; speed = 1.5f; endMs = 5000 }
                log("reversing")
                val r = Stills.reverse(this, clip(vids[0]).apply { endMs = 3000 }) { } ?: error("reverse failed")
                p.clips.addAll(listOf(a, ph, b, r))
                p.texts.add(TextItem("Hello Roll", 300, 5200).apply { style = "box"; font = "anton"; y = 0.2f })
                p.grade.look = "vision500t"; p.grade.halation = 0.6f; p.grade.shSat = 0.5f
                vids.getOrNull(1)?.let { f -> p.audio.add(AudioItem(Uri.fromFile(f).toString(), "Test music", "music", 2000, 0, 4000, 5000).apply { fadeIn = 500; fadeOut = 1000 }) }
                p.texts.add(TextItem("a caption line here", 7000, 11000).apply { caption = true; style = "shadow"; y = 0.8f; size = 0.045f; words.addAll(listOf(0L, 800L, 1600L, 2400L)) })
                log("clips " + p.clips.joinToString { "${it.lengthMs}" })
                main.post {
                    store.save(p); open(p)
                    if (intent?.getStringExtra("selftest")?.startsWith("tour") == true) {
                        // a tour of the panels, for screenshots
                        val steps = listOf<Pair<String, () -> Unit>>(
                            "editor" to { seekTimeline(1500); timeline?.selected = 0; refresh() },
                            "frame" to { framePanel(0) },
                            "transition" to { closeClipPanel(false); transitionPanel(0) },
                            "text-style" to { closeClipPanel(false); seekTimeline(1500); textTab = "style"; textEditor(p.texts[0]) },
                            "text-font" to { val k = clipPanelBefore; textTab = "font"; textEditor(p.texts[0]); clipPanelBefore = k },
                            "text-motion" to { val k = clipPanelBefore; textTab = "motion"; textEditor(p.texts[0]); clipPanelBefore = k },
                            "audio" to { editingText = null; closeClipPanel(false); audioPanel() },
                            "captions" to { panelOpen = false; editBottom(); captionsPanel() },
                            "film" to { panelOpen = false; editBottom(); seekTimeline(5000); filmPanel(Looks.CINEMA) },
                            "adjust-split" to { closePanel(false); adjTab = "split"; adjustPanel() },
                            "adjust-effects" to { closePanel(false); adjTab = "effects"; adjustPanel() },
                            "aspect" to { closePanel(false); aspectMenu() },
                        ) + (if (intent?.getStringExtra("selftest") == "tour-land") listOf<Pair<String, () -> Unit>>(
                            "panel-left" to { closeSheet(); prefs().edit().putBoolean("panelLeft", true).apply(); relayout() },
                            "left-film" to { filmPanel(Looks.MOODS) },
                            "left-text" to { closePanel(false); seekTimeline(1500); textTab = "color"; textEditor(p.texts[0]) },
                            "left-timeline" to { editingText = null; closeClipPanel(false); seekTimeline(6500) },
                        ) else emptyList())
                        steps.forEachIndexed { k, (name, f) -> main.postDelayed({ try { f() } catch (e: Throwable) { log("step $name failed: $e") }; main.postDelayed({ log("shot $k $name") }, 2500) }, 5000L + k * 6000L) }
                        main.postDelayed({ closeSheet(); log("tour done") }, 5000L + steps.size * 6000L)
                        return@post
                    }
                    val starts = (0..3).map { p.startOf(it) }
                    val shots = listOf(1500L, starts[1] - 10, starts[1] + 300, starts[2] - 150, starts[2] + 1500, starts[3] + 800)
                    shots.forEachIndexed { k, t -> main.postDelayed({ seekTimeline(t); log("shot $k at $t") }, 4000L + k * 4000L) }
                    main.postDelayed({
                        log("export start (with music)"); openExport(); exFormat = "video"; exShort = 360; exFps = 25; startExport()
                        // then once more without the music, to compare
                        val again = object : Runnable { override fun run() {
                            if (exporting != null || exportView == null || exDissolve != null) { main.postDelayed(this, 1000); return }
                            if (p.audio.isEmpty()) return
                            p.audio.clear(); store.save(p); log("export start (no music)"); startExport()
                        } }
                        main.postDelayed(again, 5000)
                    }, 4000L + shots.size * 4000L)
                }
            } catch (e: Throwable) { log("failed: $e"); android.util.Log.e("ROLLTEST", "failed", e) }
        }
    }

    override fun onPause() { super.onPause(); player?.pause(); project?.let { store.save(it) }; if (project != null && ::glView.isInitialized) glView.onPause() }
    override fun onResume() { super.onResume(); if (project != null && ::glView.isInitialized) glView.onResume() }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); releasePlayer(); exporting?.cancel?.invoke(); bg.shutdownNow(); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            recorder != null -> toast("Tap the red button to stop recording")
            sheetView != null -> closeSheet()
            exportView != null -> { if (exporting == null) closeExport() else toast("Exporting · wait or tap Cancel") }
            panelOpen -> if (clipPanelBefore != null) { editingText = null; timeline?.selectedText = null; closeClipPanel(false) } else if (bottom?.findViewWithTag<View>("grade") != null || gradeBefore != null) closePanel(false) else { panelOpen = false; editBottom() }
            project != null -> { store.save(project!!); releasePlayer(); project = null; home() }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun prefs() = getSharedPreferences("roll", MODE_PRIVATE)

    /** Turned sideways (or back): rebuild the editor in the new shape, keeping the edit, the time and the undo history. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (project != null && exportView == null) relayout()
    }

    /** True while the editor is rebuilt around the same player and viewer (turning the phone, moving the panel). */
    private var reusing = false

    private fun relayout() {
        val p = project ?: return
        closeSheet(); editingText = null; framing = -1; clipPanelBefore = null; gradeBefore = null
        store.save(p)
        // keep the player and the picture; only the layout around them changes
        (glView.parent as? android.view.ViewGroup)?.removeView(glView)
        main.removeCallbacks(ticker); timeline?.release(); timeline = null
        val u = ArrayList(undo); val r = ArrayList(redo)
        val t = timelineTime()
        reusing = true
        try { open(p, keepHistory = true) } finally { reusing = false }
        undo.clear(); undo.addAll(u); redo.clear(); redo.addAll(r)
        timeline?.setTime(t); refreshTime(); applyGrade(); updateFrame()
        playBtn?.setImageResource(if (player?.isPlaying == true) R.drawable.i_pause else R.drawable.i_play)
        if (player?.isPlaying == true) main.post(ticker)
    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 9) { if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) recordVoice() else toast("Roll needs the microphone for voiceovers") }
    }

    // ================================================================ home

    private fun home() {
        screen.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(40)) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(14), dp(10), dp(6)) }
        head.addView(ImageView(this).apply { setImageResource(R.drawable.ic_reel); setColorFilter(C.TEXT) }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(10) })
        head.addView(text("ROLL", 26f, C.TEXT, true).apply { letterSpacing = 0.12f }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(head)
        // new project
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(18))
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22F2A541), pill(0, dpf(22f), dp(1), 0x59F2A541), pill(Color.WHITE, dpf(22f)))
            addView(ImageView(context).apply { setImageResource(R.drawable.i_add); setColorFilter(C.AMBER) }, LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(context.text("New project", 21f, C.TEXT, true).apply { setPadding(0, dp(26), 0, dp(2)) })
            addView(context.text("Pick clips · trim · cut · film looks · export", 12.5f, C.SUB))
            setOnClickListener { newProject() }
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(18), dp(8), dp(18), dp(22)) })
        val all = store.all()
        if (all.isNotEmpty()) {
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(dp(22), 0, dp(22), dp(12))
                addView(context.text("PROJECTS", 12f, C.SUB, true).apply { letterSpacing = 0.14f }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(context.text("${all.size}", 11f, C.FAINT, mono = true))
            })
            val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(18), 0, dp(18), 0) }
            val a = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            all.forEachIndexed { i, p -> (if (i % 2 == 0) a else b).addView(projectCard(p, i)) }
            cols.addView(a, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) }); cols.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(5) })
            col.addView(cols)
        }
        screen.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun projectCard(p: Project, i: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(16))
        val img = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF161616.toInt()); clipToOutline = true; background = pill(0xFF161616.toInt(), dpf(14f)) }
        val box = FrameLayout(context)
        box.addView(img, FrameLayout.LayoutParams(-1, -1))
        box.addView(context.text(fmt(p.lengthMs), 11f, C.TEXT, mono = true).apply { setPadding(dp(6), dp(2), dp(6), dp(2)); background = pill(0x8C000000.toInt(), dpf(6f)) },
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(8), dp(8)) })
        addView(box, LinearLayout.LayoutParams(-1, dp(if (i % 3 == 0) 230 else 140)))
        addView(context.text(p.name, 14.5f, C.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(2), dp(8), 0, 0) })
        addView(context.text(Looks.byId(p.grade.look)?.name ?: if (p.grade.look.startsWith("lut:")) p.grade.look.removePrefix("lut:") else "No look", 11.5f, C.SUB).apply { setPadding(dp(2), dp(1), 0, 0) })
        background = ripple(); setOnClickListener { open(p) }
        setOnLongClickListener { projectMenu(p); true }
        p.clips.firstOrNull()?.let { c ->
            work {
                val bm = frameAt(c, c.startMs + 800, 400)
                main.post { if (bm != null) img.setImageBitmap(gradePreview(bm, p.grade)) }
            }
        }
    }

    // ================================================================ sheets (every menu slides up from the bottom)

    private var sheetView: View? = null
    private var sheetClosed: (() -> Unit)? = null

    private fun sheet(content: View, onClose: (() -> Unit)? = null) {
        closeSheet()
        val dim = FrameLayout(this).apply { setBackgroundColor(0xA6000000.toInt()); isClickable = true; setOnClickListener { closeSheet() } }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; isClickable = true
            background = android.graphics.drawable.LayerDrawable(arrayOf<android.graphics.drawable.Drawable>(
                android.graphics.drawable.GradientDrawable().apply { setColor(0xFF0D0D0D.toInt()); cornerRadii = floatArrayOf(dpf(28f), dpf(28f), dpf(28f), dpf(28f), 0f, 0f, 0f, 0f) },
                android.graphics.drawable.GradientDrawable().apply { setColor(0); setStroke(dp(1), 0x14FFFFFF); cornerRadii = floatArrayOf(dpf(28f), dpf(28f), dpf(28f), dpf(28f), 0f, 0f, 0f, 0f) }))
            setPadding(dp(20), dp(10), dp(20), insetBottom + dp(22))
            addView(View(context).apply { background = pill(0x40FFFFFF, dpf(2f)) }, LinearLayout.LayoutParams(dp(38), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16) })
            addView(content)
        }
        card.setOnApplyWindowInsetsListener { v, ins ->
            val ime = ins.getInsets(WindowInsets.Type.ime()).bottom
            v.setPadding(dp(20), dp(10), dp(20), maxOf(insetBottom, ime) + dp(22)); ins
        }
        dim.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.addView(dim, FrameLayout.LayoutParams(-1, -1))
        sheetView = dim; sheetClosed = onClose
        card.translationY = dpf(400f); card.animate().translationY(0f).setDuration(230).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        card.requestApplyInsets()
    }

    private fun closeSheet() {
        val v = sheetView ?: return
        sheetView = null
        val c = sheetClosed; sheetClosed = null
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
        root.removeView(v); c?.invoke()
    }

    /** An action row in a sheet: icon, title, a line under it. */
    private fun action(icon: Int, title: String, sub: String? = null, color: Int = C.TEXT, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(12), dp(4), dp(12)); background = ripple()
        addView(ImageView(context).apply { setImageResource(icon); setColorFilter(color) }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(18) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(context.text(title, 16f, color, true))
            if (sub != null) addView(context.text(sub, 12.5f, C.SUB).apply { setPadding(0, dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        setOnClickListener { onClick() }
    }

    private fun vcol() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun projectMenu(p: Project) {
        val col = vcol()
        col.addView(headline(p.name, "${fmt(p.lengthMs)} · ${p.clips.size} clip${if (p.clips.size == 1) "" else "s"} · ${Looks.byId(p.grade.look)?.name ?: "no look"}"))
        col.addView(action(R.drawable.i_edit, "Rename") { rename(p) { home() } })
        col.addView(action(R.drawable.i_copy, "Duplicate", "A copy of the edit to try something else") {
            closeSheet(); val c = Project.from(p.json().put("id", java.util.UUID.randomUUID().toString()).put("name", p.name + " copy")); store.save(c); home() })
        col.addView(action(R.drawable.i_trash, "Delete", "Your videos stay in the gallery", 0xFFFF6B5E.toInt()) { confirm("Delete “${p.name}”?", "Only the edit is deleted. Your original videos stay where they are.", "Delete") { store.delete(p); home() } })
        sheet(col)
    }

    private fun confirm(title: String, msg: String, yes: String, then: () -> Unit) {
        val col = vcol()
        col.addView(headline(title, msg))
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        r.addView(word("Cancel", false, 15f) { closeSheet() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(24) })
        r.addView(text(yes, 15f, 0xFFFF6B5E.toInt(), true).apply { setPadding(dp(4), dp(8), dp(4), dp(8)); background = ripple(); setOnClickListener { closeSheet(); then() } })
        col.addView(r)
        sheet(col)
    }

    private fun rename(p: Project, then: () -> Unit) {
        val col = vcol()
        col.addView(text("NAME", 11.5f, C.SUB, true).apply { letterSpacing = 0.14f })
        val e = EditText(this).apply {
            setText(p.name); setSelection(p.name.length); setTextColor(C.TEXT); textSize = 24f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = null; setPadding(0, dp(6), 0, dp(10)); isSingleLine = true
            backgroundTintList = android.content.res.ColorStateList.valueOf(C.AMBER)
        }
        col.addView(e)
        col.addView(View(this).apply { setBackgroundColor(C.AMBER) }, LinearLayout.LayoutParams(-1, dp(2)))
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(14), 0, 0) }
        r.addView(word("Cancel", false, 15f) { closeSheet() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(24) })
        r.addView(text("Save", 15f, C.AMBER, true).apply { setPadding(dp(4), dp(8), dp(4), dp(8)); background = ripple()
            setOnClickListener { p.name = e.text.toString().trim().ifBlank { p.name }; store.save(p); closeSheet(); then() } })
        col.addView(r)
        sheet(col)
        e.requestFocus()
        main.postDelayed({ (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(e, 0) }, 250)
    }

    /** Speed: a big number and a rail of speeds; changes as you tap. */
    private fun speedMenu(i: Int) {
        val p = project ?: return
        snapshot()
        val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)
        val col = vcol()
        col.addView(text("SPEED", 11.5f, C.SUB, true).apply { letterSpacing = 0.14f })
        val big = text("", 54f, C.AMBER, true, mono = true).apply { setPadding(0, dp(2), 0, dp(4)) }
        val note = text("", 12.5f, C.SUB)
        col.addView(big); col.addView(note)
        val rail = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(18), 0, dp(6)) }
        fun draw() {
            val c = p.clips[i]
            big.text = (if (c.speed == c.speed.toInt().toFloat()) c.speed.toInt().toString() else c.speed.toString()) + "×"
            note.text = "Clip ${i + 1} lasts ${fmt(c.lengthMs, true)}" + if (c.speed < 1f) " · slow motion" else if (c.speed > 1f) " · faster" else ""
            rail.removeAllViews()
            speeds.forEach { sp ->
                val on = sp == c.speed
                rail.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(4)); background = ripple()
                    addView(context.text(if (sp == sp.toInt().toFloat()) sp.toInt().toString() else sp.toString().removePrefix("0"), 16f, if (on) C.AMBER else C.TEXT, on, mono = true))
                    addView(View(context).apply { background = pill(if (on) C.AMBER else 0, dpf(2f)) }, LinearLayout.LayoutParams(dp(18), dp(3)).apply { topMargin = dp(6) })
                    setOnClickListener { p.clips[i].speed = sp; store.save(p); reloadPlayer(keepTime = true); refresh(); draw() }
                })
            }
        }
        draw()
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(rail) })
        sheet(col)
    }

    private fun volumeMenu(i: Int) {
        val p = project ?: return
        snapshot()
        val col = vcol()
        col.addView(headline("Clip ${i + 1} volume", "Drag, or double-tap the slider for 100%"))
        col.addView(LrSlider(this, "Volume", p.clips[i].volume, 0f, 2f, null, { "${(it * 100).toInt()}%" }) { v -> p.clips[i].volume = v; clipParams() })
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        r.addView(action(R.drawable.i_mute, "Mute this clip") { p.clips[i].volume = 0f; clipParams(); closeSheet() }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(action(if (p.mute) R.drawable.i_volume else R.drawable.i_mute, if (p.mute) "Sound on for all" else "Mute the whole edit") { p.mute = !p.mute; clipParams(); closeSheet() }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(r)
        sheet(col) { store.save(p); refresh() }
    }

    private fun soundMenu() {
        val p = project ?: return
        val col = vcol()
        col.addView(headline("Sound", "Tap a clip to set its own volume"))
        col.addView(action(if (p.mute) R.drawable.i_volume else R.drawable.i_mute, if (p.mute) "Turn the sound on" else "Mute the whole edit", if (p.mute) "Exports will have sound again" else "Exports will be silent") {
            snapshot(); p.mute = !p.mute; store.save(p); clipParams(); refresh(); closeSheet() })
        sheet(col)
    }

    private fun clipMenu(i: Int) {
        val p = project ?: return
        timeline?.selected = i; editBottomTools()
        val c = p.clips[i]
        val col = vcol()
        col.addView(headline("Clip ${i + 1}", "${fmt(c.lengthMs, true)} · from ${fmt(c.startMs)} to ${fmt(c.endMs)} of the original"))
        fun move(to: Int) { snapshot(); val x = p.clips.removeAt(i); p.clips.add(to, x); timeline?.selected = to; store.save(p); reloadPlayer(true); refresh(); closeSheet() }
        if (i > 0) col.addView(action(R.drawable.i_left, "Move earlier") { move(i - 1) })
        if (i < p.clips.size - 1) col.addView(action(R.drawable.i_right, "Move later") { move(i + 1) })
        col.addView(action(R.drawable.i_aspect, "Frame", "Fit or fill, move, zoom, rotate, flip") { closeSheet(); framePanel(i) })
        if (i < p.clips.size - 1) col.addView(action(R.drawable.i_trans, "Transition", Scene.transition(c.trans).name + " into clip ${i + 2}") { closeSheet(); transitionPanel(i) })
        col.addView(action(R.drawable.i_speed, "Speed", "${c.speed}×") { closeSheet(); speedMenu(i) })
        if (c.still.isEmpty()) col.addView(action(R.drawable.i_reverse, if (c.revOf != null) "Play forwards again" else "Reverse", if (c.revOf != null) "Back to the original clip" else "Plays backwards (without sound)") { closeSheet(); reverseClip(i) })
        col.addView(action(R.drawable.i_freeze, "Freeze frame", "Holds the frame under the playhead for 2 s") { closeSheet(); freezeFrame() })
        col.addView(action(R.drawable.i_volume, "Volume", "${(c.volume * 100).toInt()}%") { closeSheet(); volumeMenu(i) })
        col.addView(action(R.drawable.i_copy, "Duplicate") { closeSheet(); duplicate(i) })
        col.addView(action(R.drawable.i_trash, "Delete", null, 0xFFFF6B5E.toInt()) { closeSheet(); deleteClip(i) })
        sheet(col)
    }

    /** Frame shapes drawn as their own outlines. */
    private fun aspectMenu() {
        val p = project ?: return
        val opts = listOf(Triple("original", "Original", 0f), Triple("9:16", "9:16", 9f / 16f), Triple("4:5", "4:5", 0.8f), Triple("1:1", "1:1", 1f), Triple("4:3", "4:3", 4f / 3f), Triple("16:9", "16:9", 16f / 9f), Triple("21:9", "Cinema", 2.39f))
        val col = vcol()
        col.addView(headline("Frame", "The shape of the finished video"))
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) })
        opts.forEach { (k, l, ratio) ->
            val on = p.aspect == k
            val rr = if (ratio == 0f) p.clips.firstOrNull()?.let { if (it.w > 0 && it.h > 0) it.w.toFloat() / it.h else 16f / 9f } ?: (16f / 9f) else ratio
            val box = 50f
            val w = if (rr >= 1f) box else box * rr; val h = if (rr >= 1f) box / rr else box
            r.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(6), dp(8), dp(6), dp(8)); background = ripple()
                addView(FrameLayout(context).apply { addView(View(context).apply { background = pill(if (on) 0x26F2A541 else 0, dpf(5f), dp(2), if (on) C.AMBER else 0x80FFFFFF.toInt()) },
                    FrameLayout.LayoutParams(dp(w.toInt()), dp(h.toInt()), Gravity.CENTER)) }, LinearLayout.LayoutParams(dp(54), dp(54)))
                addView(context.text(l, 12.5f, if (on) C.AMBER else C.TEXT, on).apply { setPadding(0, dp(8), 0, 0) })
                setOnClickListener { snapshot(); p.aspect = k; store.save(p); previewBox.requestLayout(); applyGrade(); updateFrame(); closeSheet() }
            }, LinearLayout.LayoutParams(dp(70), -2))
        }
        col.addView(text("Tap a clip, then Frame, to place it in the shape (fit, fill, move, zoom, rotate).", 12f, C.SUB).apply { setPadding(dp(4), dp(10), dp(4), 0) })
        sheet(col)
    }

    // ================================================================ picking clips

    private var addingTo: Project? = null

    private fun newProject() {
        addingTo = Project(name = "Roll " + SimpleDateFormat("d MMM", Locale.getDefault()).format(Date()))
        pickVideos()
    }

    private fun pickVideos() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*")); putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(i, 1) } catch (e: Exception) { toast("No file picker found") }
    }

    private fun pickLut() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        try { startActivityForResult(i, 2) } catch (e: Exception) { toast("No file picker found") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) { if (requestCode == 1 && project == null) addingTo = null; return }
        if (requestCode == 2) { data.data?.let { importLut(it) }; return }
        if (requestCode == 3) { data.data?.let { addMusic(it) }; return }
        val uris = ArrayList<Uri>()
        data.clipData?.let { cd -> for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri) } ?: data.data?.let { uris.add(it) }
        val p = addingTo ?: return
        addingTo = null
        toast("Reading ${uris.size} clip${if (uris.size == 1) "" else "s"}…")
        work {
            val clips = uris.mapNotNull { u ->
                if (contentResolver.getType(u)?.startsWith("image/") == true) {
                    // a photo becomes a still clip (3 s, slow push-in)
                    return@mapNotNull try { Stills.stillClip(this, Stills.decodePhoto(this, u), "photo", 3000) } catch (e: Exception) { null }
                }
                try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
                try {
                    val mr = MediaMetadataRetriever(); mr.setDataSource(this, u)
                    val dur = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    var w = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    var h = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rot = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    mr.release()
                    if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                    if (dur <= 0) null else Clip(u.toString(), dur, 0, dur, w = w, h = h)
                } catch (e: Exception) { null }
            }
            main.post {
                if (clips.isEmpty()) { toast("Couldn't read those files"); return@post }
                if (project === p) { snapshot(); val at = (timeline?.selected ?: -1).let { if (it >= 0) it + 1 else p.clips.size }; p.clips.addAll(at, clips); store.save(p); reloadPlayer(keepTime = true); refresh() }
                else { p.clips.addAll(clips); store.save(p); open(p) }
            }
        }
    }

    private fun importLut(u: Uri) {
        work {
            val name = (contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "look.cube")
                .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            val textLines = try { contentResolver.openInputStream(u)?.bufferedReader()?.readLines() } catch (e: Exception) { null }
            val ok = textLines != null && Looks.parseCube(textLines) != null
            if (ok) File(store.lutDir, name).writeText(textLines!!.joinToString("\n"))
            main.post {
                if (!ok) toast("That isn't a 3D .cube LUT") else { toast("Added $name"); project?.let { p -> snapshot(); p.grade.look = "lut:$name"; p.grade.strength = 1f; applyGrade(); filmPanel(Looks.FILM, lutTab = true) } }
            }
        }
    }

    // ================================================================ editor

    private lateinit var previewBox: FrameLayout
    private lateinit var glView: GlPreview
    private var videoSurface: android.view.Surface? = null
    private var videoRatio = 16f / 9f

    private fun open(p: Project, keepHistory: Boolean = false) {
        project = p; if (!keepHistory) { undo.clear(); redo.clear() }; panelOpen = false
        screen.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // top bar
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(10), dp(4)) }
        top.addView(bare(R.drawable.i_back, null) { onBackPressed() })
        val name = text(p.name, 15f, C.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setOnClickListener { rename(p) { (this as TextView).text = p.name } } }
        top.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(6), dp(8)); background = ripple()
            addView(ImageView(context).apply { setImageResource(R.drawable.i_export); setColorFilter(C.AMBER) }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(6) })
            addView(context.text("Export", 14.5f, C.AMBER, true))
            setOnClickListener { openExport() }
        })
        col.addView(top)
        // preview
        previewBox = object : FrameLayout(this) {
            override fun onMeasure(w: Int, h: Int) {
                super.onMeasure(w, h)
                val pw = MeasureSpec.getSize(w); val ph = MeasureSpec.getSize(h)
                val ratio = Render.ratio(p)
                var sw = pw; var sh = (pw / ratio).toInt()
                if (sh > ph) { sh = ph; sw = (ph * ratio).toInt() }
                glView.measure(MeasureSpec.makeMeasureSpec(sw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(sh, MeasureSpec.EXACTLY))
            }
        }
        if (!reusing) {
            glView = GlPreview(this, store.lutDir)
            glView.onSurface = { sf ->
                videoSurface = sf
                player?.let { pl -> pl.setVideoSurface(sf); if (!pl.isPlaying) pl.seekTo(pl.currentMediaItemIndex, pl.currentPosition) }
            }
        }
        previewBox.addView(glView, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        lookTag = text("", 10.5f, C.AMBER, true).apply { letterSpacing = 0.12f; setPadding(dp(8), dp(3), dp(8), dp(3)); background = pill(0x73000000, dpf(6f)) }
        previewBox.addView(lookTag, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(10), 0, 0) })
        // hold the picture to see it without the look
        previewBox.setOnTouchListener { _, e ->
            if (framing >= 0) { frameTouch(e); return@setOnTouchListener true }
            if (editingText != null) { textTouch(e); return@setOnTouchListener true }
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { main.postDelayed(showOriginal, 250); true }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(showOriginal)
                    if (showingOriginal) { showingOriginal = false; glView.bypass = false; updateLookTag() }
                    else if (player?.isPlaying != true && textAt(e.x, e.y)?.let { textEditor(it); true } == true) {}
                    else togglePlay(); true
                }
                else -> true
            }
        }
        col.addView(previewBox, LinearLayout.LayoutParams(-1, 0, 1f))
        // transport
        val tr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(6), dp(8), dp(2)) }
        timeText = text("", 12f, C.TEXT, true, mono = true)
        tr.addView(timeText, LinearLayout.LayoutParams(0, -2, 1f))
        tr.addView(bare(R.drawable.i_undo, null, size = 20) { undoStep() })
        playBtn = ImageView(this).apply { setImageResource(R.drawable.i_play); setColorFilter(C.TEXT); setPadding(dp(10), dp(10), dp(10), dp(10)); background = ripple(); setOnClickListener { togglePlay() } }
        tr.addView(playBtn, LinearLayout.LayoutParams(dp(48), dp(48)))
        tr.addView(bare(R.drawable.i_redo, null, size = 20) { redoStep() })
        tr.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        tr.addView(bare(R.drawable.i_aspect, null, size = 20) { aspectMenu() })
        col.addView(tr)
        bottom = FrameLayout(this)
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            // sideways: the work panel on one side (drag its grip across to move it), the picture in the middle
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val side = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xFF070707.toInt()) }
            val left = prefs().getBoolean("panelLeft", false)
            val grip = FrameLayout(this).apply {
                addView(View(context).apply { background = pill(0x40FFFFFF, dpf(2f)) }, FrameLayout.LayoutParams(dp(4), dp(44), Gravity.CENTER))
                contentDescription = "Drag to move the panel to the other side"
                var downX = 0f
                setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> { downX = e.rawX; true }
                        android.view.MotionEvent.ACTION_MOVE -> { side.translationX = e.rawX - downX; true }
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                            val w = resources.displayMetrics.widthPixels
                            val toLeft = e.rawX < w / 2f
                            side.animate().translationX(0f).setDuration(120).start()
                            if (toLeft != left && kotlin.math.abs(e.rawX - downX) > dp(60)) { prefs().edit().putBoolean("panelLeft", toLeft).apply(); relayout() }
                            v.performClick(); true
                        }
                        else -> true
                    }
                }
            }
            val sideW = (resources.displayMetrics.widthPixels * 0.42f).toInt()
            if (left) { side.addView(bottom, LinearLayout.LayoutParams(0, -1, 1f)); side.addView(grip, LinearLayout.LayoutParams(dp(18), -1)) }
            else { side.addView(grip, LinearLayout.LayoutParams(dp(18), -1)); side.addView(bottom, LinearLayout.LayoutParams(0, -1, 1f)) }
            if (left) { row.addView(side, LinearLayout.LayoutParams(sideW, -1)); row.addView(col, LinearLayout.LayoutParams(0, -1, 1f)) }
            else { row.addView(col, LinearLayout.LayoutParams(0, -1, 1f)); row.addView(side, LinearLayout.LayoutParams(sideW, -1)) }
            screen.addView(row, FrameLayout.LayoutParams(-1, -1))
        } else {
            col.addView(bottom, LinearLayout.LayoutParams(-1, dp(326)))
            screen.addView(col, FrameLayout.LayoutParams(-1, -1))
        }
        editBottom()
        if (!reusing) setupPlayer()
    }

    private var showingOriginal = false
    private val showOriginal = Runnable { showingOriginal = true; glView.bypass = true; lookTag?.text = "ORIGINAL"; lookTag?.visibility = View.VISIBLE }

    /** The timeline and the tools under the preview. */
    private fun editBottom() {
        val b = bottom ?: return
        b.removeAllViews()
        val p = project ?: return
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tl = timeline ?: Timeline(this).also { timeline = it }
        (tl.parent as? ViewGroup)?.removeView(tl)
        tl.project = p
        tl.onScrub = { t -> seekTimeline(t) }
        tl.onSelect = { i -> tl.selected = i; editBottomTools() }
        tl.onTrimmed = { i -> snapshotBefore?.let { pushUndo(it) }; snapshotBefore = null; store.save(p); reloadPlayer(keepTime = true); refreshTime() }
        tl.onAdd = { addingTo = p; pickVideos() }
        tl.onHold = { i -> clipMenu(i) }
        tl.onJoint = { i -> transitionPanel(i) }
        tl.onText = { id -> p.texts.firstOrNull { it.id == id }?.let { textEditor(it) } }
        tl.onAudio = { id -> p.audio.firstOrNull { it.id == id }?.let { audioEditor(it) } }
        tl.onLaneDown = { snapshotBefore = p.json().toString() }
        tl.onLaneMoved = { snapshotBefore?.let { pushUndo(it) }; snapshotBefore = null; store.save(p); updateFrame(); refreshSoundtrack() }
        col.addView(tl, LinearLayout.LayoutParams(-1, dp(178)))
        toolsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true; addView(toolsRow) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        b.addView(col, FrameLayout.LayoutParams(-1, -1))
        editBottomTools()
        // remember the edit before a trim starts, so it can be undone
        tl.setOnTouchListener { _, e -> if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) snapshotBefore = p.json().toString(); false }
    }
    private var snapshotBefore: String? = null
    private var toolsRow: LinearLayout? = null

    private fun editBottomTools() {
        val row = toolsRow ?: return
        row.removeAllViews()
        val sel = timeline?.selected ?: -1
        val dim = if (sel >= 0) C.TEXT else C.FAINT
        listOf(
            bare(R.drawable.i_split, "Split") { split() },
            bare(R.drawable.i_speed, "Speed", dim) { if (sel >= 0) speedMenu(sel) else toast("Tap a clip first") },
            bare(R.drawable.i_aspect, "Frame", dim) { if (sel >= 0) framePanel(sel) else toast("Tap a clip first") },
            bare(R.drawable.i_trans, "Transition", if (sel >= 0 && sel < (project?.clips?.size ?: 0) - 1) C.TEXT else C.FAINT) {
                when { sel < 0 -> toast("Tap a clip first"); sel >= (project?.clips?.size ?: 0) - 1 -> toast("The last clip has nothing after it"); else -> transitionPanel(sel) } },
            bare(R.drawable.i_reverse, "Reverse", if (sel >= 0 && project?.clips?.getOrNull(sel)?.still?.isEmpty() == true) C.TEXT else C.FAINT) { if (sel >= 0) reverseClip(sel) else toast("Tap a clip first") },
            bare(R.drawable.i_freeze, "Freeze") { freezeFrame() },
            bare(R.drawable.i_text, "Text") { textsPanel() },
            bare(R.drawable.i_music, "Audio") { audioPanel() },
            bare(R.drawable.i_cc, "Captions") { captionsPanel() },
            bare(R.drawable.i_filter, "Film") { filmPanel(Looks.FILM) },
            bare(R.drawable.i_adjust, "Adjust") { adjustPanel() },
            bare(R.drawable.i_fx, "Overlay") { overlayPanel() },
            bare(if (project?.mute == true) R.drawable.i_mute else R.drawable.i_volume, "Volume") { if (sel >= 0) volumeMenu(sel) else soundMenu() },
            bare(R.drawable.i_copy, "Copy", dim) { if (sel >= 0) duplicate(sel) else toast("Tap a clip first") },
            bare(R.drawable.i_trash, "Delete", dim) { if (sel >= 0) deleteClip(sel) else toast("Tap a clip first") },
        ).forEach { row.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(2); marginEnd = dp(2) }) }
    }

    // ---------------------------------------------------------------- player

    private fun baseEffects(): List<Effect> = listOf(Presentation.createForAspectRatio(Render.ratio(project!!), Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))

    private fun setupPlayer() {
        val p = project ?: return
        val pl = ExoPlayer.Builder(this).build()
        player = pl
        videoSurface?.let { pl.setVideoSurface(it) }
        glView.setGrade(p.grade); glView.setRatios(videoRatio, Render.ratio(p))
        pl.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { clipParams(); updateFrame() }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playBtn?.setImageResource(if (isPlaying) R.drawable.i_pause else R.drawable.i_play)
                if (isPlaying) main.post(ticker)
                bed?.let { b -> if (isPlaying) { b.seekTo(timelineTime()); b.play() } else b.pause() }
            }
            override fun onPlayerError(error: PlaybackException) { toast("Playback: ${error.errorCodeName}") }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) { videoRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height; project?.let { glView.setRatios(videoRatio, Render.ratio(it)) } }
            }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) { pl.pause(); pl.seekTo(0, 0) } }
        })
        loadItems(); pl.prepare(); refreshDissolves(); refreshSoundtrack()
        updateLookTag(); refreshTime(); updateFrame()
        if (p.clips.isEmpty()) toast("Add a clip with +")
    }

    private fun loadItems() {
        val p = project ?: return; val pl = player ?: return
        pl.setMediaItems(p.clips.map { c ->
            MediaItem.Builder().setUri(Uri.parse(c.uri)).setMediaId(c.id).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.startMs).setEndPositionMs(c.endMs).build()).build()
        })
        clipParams()
    }

    /** Speed and volume follow the clip that's playing. */
    private fun clipParams() {
        val p = project ?: return; val pl = player ?: return
        val c = p.clips.getOrNull(pl.currentMediaItemIndex) ?: return
        pl.playbackParameters = PlaybackParameters(c.speed)
        pl.volume = if (p.mute) 0f else c.volume
        refreshSoundtrack()
    }

    private fun reloadPlayer(keepTime: Boolean) {
        val t = timeline?.time() ?: 0L
        loadItems(); player?.prepare(); refreshDissolves(); refreshSoundtrack()
        if (keepTime) seekTimeline(t.coerceAtMost(maxOf(0, (project?.lengthMs ?: 0) - 1)))
        timeline?.invalidate(); refreshTime()
    }

    private fun releasePlayer() {
        bed?.release(); bed = null; bedKey = ""
        main.removeCallbacks(ticker); player?.release(); player = null; timeline?.release(); timeline = null
        if (::glView.isInitialized) glView.release(); videoSurface = null
    }

    private fun seekTimeline(t: Long) {
        val p = project ?: return; val pl = player ?: return
        if (p.clips.isEmpty()) return
        val (i, off) = p.at(t)
        pl.seekTo(i, (off * p.clips[i].speed).toLong())
        timeline?.setTime(t); refreshTime(); updateFrame(i, off)
        bed?.seekTo(t)
    }

    private fun timelineTime(): Long {
        val p = project ?: return 0; val pl = player ?: return 0
        val i = pl.currentMediaItemIndex.coerceIn(0, maxOf(0, p.clips.size - 1))
        if (p.clips.isEmpty()) return 0
        return p.startOf(i) + (pl.currentPosition / p.clips[i].speed).toLong()
    }

    private val ticker = object : Runnable {
        override fun run() {
            val pl = player ?: return
            if (!pl.isPlaying) return
            timeline?.setTime(timelineTime()); refreshTime(); updateFrame()
            bed?.let { b -> val t = timelineTime(); if (b.isPlaying && kotlin.math.abs(b.currentPosition - t) > 150) b.seekTo(t) }
            main.postDelayed(this, 16)
        }
    }

    private fun togglePlay() { val pl = player ?: return; if (pl.isPlaying) pl.pause() else { if (project?.clips?.isEmpty() != false) return; pl.play() } }

    private fun refreshTime() {
        val p = project ?: return
        timeText?.text = android.text.SpannableStringBuilder(fmt(timeline?.time() ?: 0, true)).apply {
            val s = length; append("  /  ${fmt(p.lengthMs, true)}")
            setSpan(android.text.style.ForegroundColorSpan(C.FAINT), s, length, 0)
        }
    }

    private fun refresh() { timeline?.invalidate(); editBottomTools(); refreshTime(); previewBox.requestLayout(); updateFrame() }

    // ---------------------------------------------------------------- framing and transitions on the viewer

    /** Dissolves: the last frame of each clip that dissolves into the next, for the viewer. */
    private var dissolves: Map<String, Bitmap> = emptyMap()
    private var dissolveKey = ""
    private fun refreshDissolves() {
        val p = project ?: return
        val key = p.clips.joinToString("|") { "${it.uri}@${it.endMs}:${it.trans}" }
        if (key == dissolveKey) return
        dissolveKey = key
        val list = p.clips.map { Clip.from(it.json()) }
        work { val m = Render.dissolveFrames(this, list); main.post { if (dissolveKey == key) { dissolves = m; updateFrame() } } }
    }

    /** Tells the viewer how the clip under the playhead sits in the frame right now. */
    private fun updateFrame(index: Int = -1, localMs: Long = -1) {
        val p = project ?: return
        if (!::glView.isInitialized || p.clips.isEmpty()) return
        val pl = player
        val i = if (index >= 0) index else (pl?.currentMediaItemIndex ?: 0).coerceIn(0, p.clips.size - 1)
        val local = if (localMs >= 0) localMs else ((pl?.currentPosition ?: 0L) / p.clips[i].speed).toLong()
        val f = Scene.frame(p.clips, i, local, Render.ratio(p))
        glView.prevBitmap = f.prev?.let { dissolves[it.id] }
        glView.frame = f
        drawTexts(p.startOf(i) + local)
    }

    // ---------------------------------------------------------------- titles on the viewer

    private var textBufs = arrayOfNulls<Bitmap>(2); private var textBuf = 0
    private var textKey = ""
    /** Draws the titles showing at [t] for the viewer (only when they'd look different). */
    private fun drawTexts(t: Long) {
        val p = project ?: return
        val w = glView.width; val h = glView.height
        if (w <= 0 || h <= 0) return
        if (p.texts.isEmpty()) { if (textKey != "") { textKey = ""; glView.textBitmap = null }; return }
        val key = TextDraw.key(p.texts, t, w, h) + (editingText?.id ?: "")
        if (key == textKey) return
        textKey = key
        textBuf = 1 - textBuf
        val b = textBufs[textBuf]?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { textBufs[textBuf] = it }
        b.eraseColor(Color.TRANSPARENT)
        val c = android.graphics.Canvas(b)
        TextDraw.draw(this, c, p.texts, t, w, h)
        // the title being edited gets a dashed box
        editingText?.let { it2 -> if (t in it2.startMs until it2.endMs) c.drawRect(TextDraw.bounds(this, it2, w, h), android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = dpf(1.5f); color = C.AMBER; pathEffect = android.graphics.DashPathEffect(floatArrayOf(dpf(6f), dpf(4f)), 0f) }) }
        glView.textBitmap = b
    }

    private fun textAt(x: Float, y: Float): TextItem? {
        val p = project ?: return null
        val gx = x - glView.left; val gy = y - glView.top
        val t = timeline?.time() ?: 0L
        return TextDraw.active(p.texts, t).lastOrNull { TextDraw.bounds(this, it, glView.width, glView.height).contains(gx, gy) }
    }

    // ---------------------------------------------------------------- grade

    /** The grade shows on the viewer straight away (drawn by GlPreview), paused or playing. */
    private fun applyGrade() {
        val p = project ?: return
        if (::glView.isInitialized) { glView.setGrade(p.grade); glView.setRatios(videoRatio, Render.ratio(p)); glView.fx = p.grade.fx() }
        updateLookTag()
    }

    private fun updateLookTag() {
        val g = project?.grade ?: return
        val name = Looks.byId(g.look)?.name ?: if (g.look.startsWith("lut:")) g.look.removePrefix("lut:").removeSuffix(".cube") else ""
        lookTag?.text = name.uppercase(); lookTag?.visibility = if (name.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- undo

    private fun snapshot() { pushUndo(project!!.json().toString()) }
    private fun pushUndo(s: String) { undo.add(s); if (undo.size > 40) undo.removeAt(0); redo.clear() }

    private fun restore(s: String) {
        val p = project ?: return
        val q = Project.from(JSONObject(s))
        p.copyFrom(q)
        timeline?.selected = -1
        store.save(p); reloadPlayer(keepTime = true); applyGrade(); refresh()
    }
    private fun undoStep() { val p = project ?: return; if (undo.isEmpty()) return; redo.add(p.json().toString()); restore(undo.removeAt(undo.size - 1)) }
    private fun redoStep() { val p = project ?: return; if (redo.isEmpty()) return; undo.add(p.json().toString()); restore(redo.removeAt(redo.size - 1)) }

    // ---------------------------------------------------------------- clip tools

    /** Cuts the clip under the playhead in two. */
    private fun split() {
        val p = project ?: return
        if (p.clips.isEmpty()) return
        val t = timeline?.time() ?: 0L
        val (i, off) = p.at(t)
        val c = p.clips[i]
        val cut = c.startMs + (off * c.speed).toLong()
        if (cut - c.startMs < 200 || c.endMs - cut < 200) { toast("Too close to the edge of the clip"); return }
        snapshot()
        val b = c.copy(); b.startMs = cut; c.endMs = cut; c.trans = "none"
        p.clips.add(i + 1, b)
        timeline?.selected = i + 1
        store.save(p); reloadPlayer(keepTime = true); refresh()
    }

    private fun duplicate(i: Int) {
        val p = project ?: return
        snapshot(); p.clips.add(i + 1, p.clips[i].copy()); timeline?.selected = i + 1
        store.save(p); reloadPlayer(keepTime = true); refresh()
    }

    private fun deleteClip(i: Int) {
        val p = project ?: return
        snapshot(); p.clips.removeAt(i); timeline?.selected = -1
        store.save(p); reloadPlayer(keepTime = true); refresh()
    }

    // ================================================================ clip panels: frame, transition

    /** A panel for one clip's settings: Cancel puts everything back, Done keeps it (one undo step). */
    private var clipPanelBefore: String? = null
    private fun openClipPanel(view: View) {
        val b = bottom ?: return
        if (clipPanelBefore == null) clipPanelBefore = project!!.json().toString()
        panelOpen = true
        b.removeAllViews(); b.addView(view, FrameLayout.LayoutParams(-1, -1))
    }
    private fun closeClipPanel(keep: Boolean) {
        val p = project ?: return
        val before = clipPanelBefore; clipPanelBefore = null
        framing = -1
        if (before != null) {
            if (!keep) p.copyFrom(Project.from(JSONObject(before)))
            else if (before != p.json().toString()) pushUndo(before)
        }
        store.save(p); panelOpen = false
        reloadPlayer(keepTime = true); editBottom(); updateFrame()
    }
    private fun clipFooter(note: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(14), 0)
        addView(context.word("Cancel", false, 14f) { closeClipPanel(false) })
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(context.text(note, 11.5f, C.FAINT))
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(context.text("Done", 14f, C.AMBER, true).apply { setPadding(dp(4), dp(8), dp(4), dp(8)); background = ripple(); setOnClickListener { closeClipPanel(true) } })
    }

    /** Which clip is being framed (drag and pinch on the picture move and zoom it), or -1. */
    private var framing = -1

    /** Frame: fill or fit the shape, background, position, zoom, rotation, flip, Ken Burns. */
    private fun framePanel(i: Int) {
        val p = project ?: return
        val c = p.clips.getOrNull(i) ?: return
        framing = i
        // show this clip on the viewer
        if ((player?.currentMediaItemIndex ?: -1) != i) seekTimeline(p.startOf(i) + minOf(400L, c.lengthMs / 2))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val s = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(18), dp(6)) }
        fun again() { val keep = clipPanelBefore; framePanel(i); clipPanelBefore = keep }
        fun changed() { updateFrame() }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row1.addView(word("Fill", !c.fit, 14f) { c.fit = false; changed(); again() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(14) })
        row1.addView(word("Fit", c.fit, 14f) { c.fit = true; changed(); again() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(22) })
        row1.addView(text("Background", 12f, C.SUB), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        listOf("blur" to "Blur", "black" to "Black", "white" to "White").forEach { (k, l) ->
            row1.addView(word(l, c.bg == k, 14f) { c.bg = k; changed(); again() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        }
        s.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row1) })
        s.addView(LrSlider(this, "Zoom", c.scale, 0.3f, 3f, null, { "%.2f×".format(it) }) { c.scale = it; changed() }.also { it.value = c.scale })
        s.addView(LrSlider(this, "Left · right", c.ox, -1f, 1f, null, { "%+d".format((it * 100).toInt()) }) { c.ox = it; changed() })
        s.addView(LrSlider(this, "Down · up", c.oy, -1f, 1f, null, { "%+d".format((it * 100).toInt()) }) { c.oy = it; changed() })
        s.addView(LrSlider(this, "Rotate", c.rot, -180f, 180f, null, { "%+.0f°".format(it) }) { c.rot = it; changed() })
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, 0) }
        row2.addView(bare(R.drawable.i_rotate, "90°", size = 20) { c.rot = ((c.rot + 90f + 180f) % 360f) - 180f; changed(); again() })
        row2.addView(bare(R.drawable.i_flip, "Flip", if (c.flip) C.AMBER else C.TEXT, 20) { c.flip = !c.flip; changed(); again() })
        row2.addView(bare(R.drawable.i_zoom, "Ken Burns", if (c.kenBurns) C.AMBER else C.TEXT, 20) { c.kenBurns = !c.kenBurns; changed(); again() })
        row2.addView(bare(R.drawable.i_undo, "Reset", size = 20) { c.fit = false; c.bg = "blur"; c.scale = 1f; c.ox = 0f; c.oy = 0f; c.rot = 0f; c.flip = false; c.kenBurns = false; changed(); again() })
        row2.addView(bare(R.drawable.i_copy, "To all", size = 20) { p.clips.forEach { o -> if (o !== c) o.framingFrom(c) }; toast("Same framing on every clip"); changed() })
        s.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row2) })
        s.addView(text("Drag the picture to move it, pinch to zoom.", 11.5f, C.FAINT).apply { setPadding(dp(4), dp(6), 0, 0) })
        col.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(s) }, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(clipFooter("Clip ${i + 1}"), LinearLayout.LayoutParams(-1, dp(46)))
        openClipPanel(col)
        updateFrame()
    }

    private var touchX = 0f; private var touchY = 0f; private var touchDist = 0f; private var touchScale = 1f
    private fun frameTouch(e: android.view.MotionEvent) {
        val c = project?.clips?.getOrNull(framing) ?: return
        val w = glView.width.coerceAtLeast(1); val h = glView.height.coerceAtLeast(1)
        fun dist() = if (e.pointerCount >= 2) Math.hypot((e.getX(0) - e.getX(1)).toDouble(), (e.getY(0) - e.getY(1)).toDouble()).toFloat() else 0f
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { touchX = e.x; touchY = e.y }
            android.view.MotionEvent.ACTION_POINTER_DOWN -> { touchDist = dist(); touchScale = c.scale }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2 && touchDist > 0f) c.scale = (touchScale * dist() / touchDist).coerceIn(0.3f, 3f)
                else if (e.pointerCount == 1) {
                    c.ox = (c.ox + (e.x - touchX) / w).coerceIn(-1f, 1f); c.oy = (c.oy - (e.y - touchY) / h).coerceIn(-1f, 1f)
                    touchX = e.x; touchY = e.y
                }
                updateFrame()
            }
            android.view.MotionEvent.ACTION_POINTER_UP -> { touchDist = 0f; val k = if (e.actionIndex == 0) 1 else 0; touchX = e.getX(k); touchY = e.getY(k) }
            android.view.MotionEvent.ACTION_UP -> { val keep = clipPanelBefore; framePanel(framing); clipPanelBefore = keep }
        }
    }

    /** Transition from clip [i] into the next one. */
    private fun transitionPanel(i: Int) {
        val p = project ?: return
        val c = p.clips.getOrNull(i) ?: return
        if (i >= p.clips.size - 1) return
        timeline?.selected = i
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun again() { val keep = clipPanelBefore; transitionPanel(i); clipPanelBefore = keep }
        /** Plays the cut with a little run-up so you see it. */
        fun preview() { val at = p.startOf(i + 1); seekTimeline(maxOf(0, at - c.transMs / 2 - 700)); player?.play() }
        col.addView(text("CLIP ${i + 1} → ${i + 2}", 11f, C.SUB, true).apply { letterSpacing = 0.14f; setPadding(dp(18), dp(2), 0, dp(8)) })
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), 0, dp(14), 0) }
        Scene.TRANSITIONS.forEach { t ->
            val on = c.trans == t.id
            strip.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = pill(if (on) 0x26F2A541 else 0xFF141414.toInt(), dpf(14f), dp(if (on) 2 else 1), if (on) C.AMBER else 0x14FFFFFF)
                addView(ImageView(context).apply { setImageResource(transIcon(t.id)); setColorFilter(if (on) C.AMBER else C.TEXT) }, LinearLayout.LayoutParams(dp(26), dp(26)))
                addView(context.text(t.name, 11.5f, if (on) C.AMBER else C.TEXT, true).apply { gravity = Gravity.CENTER; setPadding(dp(4), dp(8), dp(4), 0) })
                setOnClickListener { c.trans = t.id; refreshDissolves(); again(); if (t.id != "none") main.postDelayed({ preview() }, 250) }
            }, LinearLayout.LayoutParams(dp(84), dp(84)).apply { marginEnd = dp(8) })
        }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) }, LinearLayout.LayoutParams(-1, dp(92)))
        val s = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(4), dp(18), 0) }
        if (c.trans != "none") {
            s.addView(LrSlider(this, "Length", c.transMs / 1000f, 0.2f, 2f, null, { "%.1f s".format(it) }) { c.transMs = (it * 1000).toLong(); updateFrame() })
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(bare(R.drawable.i_play, "Play it", size = 20) { preview() })
            r.addView(bare(R.drawable.i_copy, "On every cut", size = 20) { p.clips.dropLast(1).forEach { o -> o.trans = c.trans; o.transMs = c.transMs }; refreshDissolves(); toast("${Scene.transition(c.trans).name} on every cut"); timeline?.invalidate() })
            s.addView(r)
        } else s.addView(text("A straight cut. Pick a transition above; it plays straight away.", 12.5f, C.SUB).apply { setPadding(dp(2), dp(8), 0, 0) })
        col.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(s) }, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(clipFooter("Tap ◆ between clips"), LinearLayout.LayoutParams(-1, dp(46)))
        openClipPanel(col)
        timeline?.invalidate()
    }

    private fun transIcon(id: String) = when (id) {
        "none" -> R.drawable.i_split; "dissolve" -> R.drawable.i_trans; "black", "white" -> R.drawable.i_sun
        "zoom" -> R.drawable.i_zoom; "whip", "slide" -> R.drawable.i_right; "spin" -> R.drawable.i_rotate
        "blur" -> R.drawable.i_drop; "burn" -> R.drawable.i_fx; else -> R.drawable.i_film
    }

    // ---------------------------------------------------------------- titles

    private var editingText: TextItem? = null

    /** All titles: add one, or pick one to edit. */
    private fun textsPanel() {
        val p = project ?: return
        player?.pause()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val s = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(18), dp(6)) }
        s.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22F2A541), pill(0, dpf(16f), dp(1), 0x59F2A541), pill(Color.WHITE, dpf(16f)))
            addView(ImageView(context).apply { setImageResource(R.drawable.i_add); setColorFilter(C.AMBER) }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
            addView(context.text("Add text at ${fmt(timeline?.time() ?: 0, true)}", 15.5f, C.TEXT, true))
            setOnClickListener { addText() }
        })
        val sorted = p.texts.filter { !it.caption }.sortedBy { it.startMs }
        if (sorted.isEmpty()) s.addView(text("Titles sit on top of the look. Tap one on the picture or on the timeline to change it.", 12.5f, C.SUB).apply { setPadding(dp(4), dp(12), dp(4), 0) })
        sorted.forEach { t ->
            s.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(12), dp(4), dp(12)); background = ripple()
                addView(context.text(t.text.replace('\n', ' '), 15f, C.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(context.text("${fmt(t.startMs, true)} – ${fmt(t.endMs, true)}", 11.5f, C.SUB, mono = true))
                setOnClickListener { seekTimeline(t.startMs + minOf(600, (t.endMs - t.startMs) / 2)); textEditor(t) }
            })
        }
        col.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(s) }, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(14), 0)
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(context.text("Done", 14f, C.AMBER, true).apply { setPadding(dp(4), dp(8), dp(4), dp(8)); background = ripple(); setOnClickListener { panelOpen = false; editBottom() } })
        }, LinearLayout.LayoutParams(-1, dp(46)))
        val b = bottom ?: return
        panelOpen = true; b.removeAllViews(); b.addView(col, FrameLayout.LayoutParams(-1, -1))
    }

    private fun addText() {
        val p = project ?: return
        val t0 = (timeline?.time() ?: 0L).coerceAtMost(maxOf(0, p.lengthMs - 500))
        val last = p.texts.lastOrNull { !it.caption }
        val it = TextItem("Your text", t0, minOf(maxOf(p.lengthMs, t0 + 500), t0 + 3000)).apply { last?.let { l -> copyStyleFrom(l); y = 0.5f } }
        clipPanelBefore = p.json().toString()
        p.texts.add(it)
        textEditor(it, fresh = true)
    }

    private var textTab = "style"

    /** Everything about one title: words, font, style, colour, size, motion and timing. */
    private fun textEditor(t: TextItem, fresh: Boolean = false) {
        val p = project ?: return
        player?.pause()
        editingText = t; timeline?.selectedText = t.id
        val now = timeline?.time() ?: 0L
        if (now !in t.startMs until t.endMs) seekTimeline(t.startMs + minOf(500, (t.endMs - t.startMs) / 2))
        fun again() { val keep = clipPanelBefore; textEditor(t); clipPanelBefore = keep }
        fun changed() { textKey = ""; updateFrame(); timeline?.invalidate() }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val edit = EditText(this).apply {
            setText(t.text); setSelection(t.text.length); setTextColor(C.TEXT); textSize = 17f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = pill(0xFF161616.toInt(), dpf(12f)); setPadding(dp(14), dp(10), dp(14), dp(10)); maxLines = 3
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) { t.text = e?.toString() ?: ""; changed() }
            })
        }
        col.addView(edit, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), 0, dp(16), dp(6)) })
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), 0, dp(14), 0) }
        listOf("style" to "Style", "font" to "Font", "color" to "Colour", "motion" to "Motion", "time" to "Timing").forEach { (k, l) ->
            tabs.addView(word(l, textTab == k, 13f) { textTab = k; again() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(14) })
        }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabs) })
        val s = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(2), dp(16), dp(4)) }
        fun chips(opts: List<Pair<String, String>>, sel: String, pick: (String) -> Unit): View {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            opts.forEach { (k, l) -> r.addView(word(l, sel == k, 14f) { pick(k); changed(); again() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(14) }) }
            return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) }
        }
        fun dots(sel: Int, pick: (Int) -> Unit): View {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            TextDraw.COLORS.forEach { col0 ->
                val on = (sel or 0xFF000000.toInt()) == col0
                r.addView(FrameLayout(this).apply {
                    setPadding(dp(5), dp(5), dp(5), dp(5)); background = ripple()
                    addView(View(context).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(col0); setStroke(dp(if (on) 3 else 1), if (on) C.AMBER else 0x40FFFFFF) } },
                        FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
                    setOnClickListener { pick(col0); changed(); again() }
                }, LinearLayout.LayoutParams(dp(40), dp(40)))
            }
            return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) }
        }
        when (textTab) {
            "style" -> {
                s.addView(chips(TextDraw.STYLES, t.style) { t.style = it })
                s.addView(LrSlider(this, "Size", t.size, 0.025f, 0.2f, null, { "${(it * 1000).toInt()}" }) { t.size = it; changed() })
                s.addView(LrSlider(this, "Rotate", t.rot, -45f, 45f, null, { "%+.0f°".format(it) }) { t.rot = it; changed() })
                s.addView(chips(listOf("top" to "Top", "middle" to "Middle", "bottom" to "Bottom", "left" to "Align left", "center" to "Centre", "right" to "Align right"), "") { k ->
                    when (k) { "top" -> t.y = 0.16f; "middle" -> t.y = 0.5f; "bottom" -> t.y = 0.82f; else -> t.align = k } })
                s.addView(text("Drag the words on the picture to move them, pinch to resize.", 11.5f, C.FAINT).apply { setPadding(dp(4), dp(4), 0, 0) })
            }
            "font" -> {
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                TextDraw.FONTS.forEach { f ->
                    val on = t.font == f.id
                    r.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                        background = pill(if (on) 0x26F2A541 else 0xFF141414.toInt(), dpf(12f), dp(if (on) 2 else 1), if (on) C.AMBER else 0x14FFFFFF)
                        addView(TextView(context).apply { text = if (f.caps) "AA" else "Aa"; textSize = 24f; setTextColor(if (on) C.AMBER else C.TEXT); typeface = TextDraw.face(context, f.id); gravity = Gravity.CENTER })
                        addView(context.text(f.name, 10.5f, C.SUB).apply { gravity = Gravity.CENTER })
                        setOnClickListener { t.font = f.id; changed(); again() }
                    }, LinearLayout.LayoutParams(dp(78), dp(78)).apply { marginEnd = dp(8) })
                }
                s.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) })
            }
            "color" -> {
                s.addView(text("TEXT", 11f, C.SUB, true).apply { letterSpacing = 0.14f; setPadding(dp(4), dp(4), 0, 0) })
                s.addView(dots(t.color) { t.color = it })
                if (t.style == "box" || t.style == "pill") {
                    s.addView(text("BOX", 11f, C.SUB, true).apply { letterSpacing = 0.14f; setPadding(dp(4), dp(8), 0, 0) })
                    s.addView(dots(t.boxColor) { c -> t.boxColor = (c and 0x00FFFFFF) or (t.boxColor and 0xFF000000.toInt()).let { a -> if (a == 0) 0xCC000000.toInt() else a } })
                    s.addView(LrSlider(this, "Box opacity", (t.boxColor ushr 24) / 255f, 0f, 1f, null, { "${(it * 100).toInt()}%" }) { v -> t.boxColor = (t.boxColor and 0x00FFFFFF) or ((v * 255).toInt() shl 24); changed() })
                }
            }
            "motion" -> {
                s.addView(text("IN", 11f, C.SUB, true).apply { letterSpacing = 0.14f; setPadding(dp(4), dp(4), 0, 0) })
                s.addView(chips(TextDraw.ANIMS, t.animIn) { t.animIn = it; seekTimeline(t.startMs); player?.play() })
                s.addView(text("OUT", 11f, C.SUB, true).apply { letterSpacing = 0.14f; setPadding(dp(4), dp(8), 0, 0) })
                s.addView(chips(TextDraw.ANIMS.filter { it.first != "type" }, t.animOut) { t.animOut = it; seekTimeline(maxOf(t.startMs, t.endMs - 900)); player?.play() })
            }
            else -> {
                s.addView(text("${fmt(t.startMs, true)} – ${fmt(t.endMs, true)}  ·  %.1f s".format((t.endMs - t.startMs) / 1000f), 15f, C.TEXT, true, mono = true).apply { setPadding(dp(4), dp(6), 0, dp(6)) })
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                r.addView(bare(R.drawable.i_left, "Starts here", size = 20) { val n = timeline?.time() ?: 0L; if (n < t.endMs - 200) { t.startMs = n; changed(); again() } else toast("That's after it ends") })
                r.addView(bare(R.drawable.i_right, "Ends here", size = 20) { val n = timeline?.time() ?: 0L; if (n > t.startMs + 200) { t.endMs = n; changed(); again() } else toast("That's before it starts") })
                r.addView(bare(R.drawable.i_film, "Whole edit", size = 20) { t.startMs = 0; t.endMs = p.lengthMs; changed(); again() })
                s.addView(r)
                s.addView(LrSlider(this, "Length", (t.endMs - t.startMs) / 1000f, 0.5f, maxOf(1f, minOf(30f, p.lengthMs / 1000f)), null, { "%.1f s".format(it) }) { v -> t.endMs = t.startMs + (v * 1000).toLong(); changed() })
                s.addView(text("Or drag its purple bar along the timeline.", 11.5f, C.FAINT).apply { setPadding(dp(4), dp(4), 0, 0) })
            }
        }
        col.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(s) }, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(14), 0)
            addView(context.word("Cancel", false, 14f) { editingText = null; timeline?.selectedText = null; closeClipPanel(false) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(bare(R.drawable.i_copy, null, size = 18) { val c2 = TextItem.from(t.json().put("id", java.util.UUID.randomUUID().toString())).apply { y = (t.y + 0.1f).coerceAtMost(0.9f) }; p.texts.add(c2); textEditor(c2) })
            addView(bare(R.drawable.i_trash, null, 0xFFFF6B5E.toInt(), 18) { p.texts.remove(t); editingText = null; timeline?.selectedText = null; closeClipPanel(true) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(context.text("Done", 14f, C.AMBER, true).apply { setPadding(dp(4), dp(8), dp(4), dp(8)); background = ripple(); setOnClickListener {
                if (t.text.isBlank()) p.texts.remove(t)
                editingText = null; timeline?.selectedText = null; closeClipPanel(true) } })
        }, LinearLayout.LayoutParams(-1, dp(46)))
        openClipPanel(col)
        changed()
        if (fresh) main.postDelayed({ edit.requestFocus(); edit.selectAll(); (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(edit, 0) }, 200)
    }

    private var tDist = 0f; private var tSize = 0f
    private fun textTouch(e: android.view.MotionEvent) {
        val t = editingText ?: return
        val w = glView.width.coerceAtLeast(1); val h = glView.height.coerceAtLeast(1)
        fun dist() = if (e.pointerCount >= 2) Math.hypot((e.getX(0) - e.getX(1)).toDouble(), (e.getY(0) - e.getY(1)).toDouble()).toFloat() else 0f
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                // tapping a different title switches to it
                val other = textAt(e.x, e.y)
                if (other != null && other !== t) { val keep = clipPanelBefore; textEditor(other); clipPanelBefore = keep; return }
                touchX = e.x; touchY = e.y
            }
            android.view.MotionEvent.ACTION_POINTER_DOWN -> { tDist = dist(); tSize = t.size }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2 && tDist > 0f) t.size = (tSize * dist() / tDist).coerceIn(0.02f, 0.25f)
                else if (e.pointerCount == 1) {
                    t.x = (t.x + (e.x - touchX) / w).coerceIn(0.05f, 0.95f); t.y = (t.y + (e.y - touchY) / h).coerceIn(0.05f, 0.95f)
                    touchX = e.x; touchY = e.y
                }
                textKey = ""; updateFrame()
            }
            android.view.MotionEvent.ACTION_POINTER_UP -> { tDist = 0f; val k = if (e.actionIndex == 0) 1 else 0; touchX = e.getX(k); touchY = e.getY(k) }
        }
    }

    // ---------------------------------------------------------------- music and voiceovers

    /** Plays the mixed music + voiceovers alongside the clips. */
    private var bed: ExoPlayer? = null
    private var bedKey = ""
    private fun refreshSoundtrack() {
        val p = project ?: return
        val key = Audio.key(p) + p.mute
        if (key == bedKey) return
        bedKey = key
        if (p.audio.isEmpty() || p.mute) { bed?.release(); bed = null; return }
        val copy = Project.from(p.json())
        work {
            val f = try { Audio.mix(this, copy) } catch (e: Throwable) { null }
            main.post {
                if (bedKey != key || project == null) return@post
                if (f == null) { bed?.release(); bed = null; return@post }
                val b = bed ?: ExoPlayer.Builder(this).build().also { bed = it }
                b.setMediaItem(MediaItem.fromUri(Uri.fromFile(f))); b.prepare()
                b.seekTo(timelineTime())
                if (player?.isPlaying == true) b.play()
            }
        }
    }

    private fun audioPanel() {
        val p = project ?: return
        player?.pause()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val s = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(18), dp(6)) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun big(icon: Int, label: String, color: Int, onClick: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(12), dp(12), dp(12), dp(12))
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22FFFFFF), pill(0, dpf(16f), dp(1), (color and 0x00FFFFFF) or 0x66000000), pill(Color.WHITE, dpf(16f)))
            addView(ImageView(context).apply { setImageResource(icon); setColorFilter(color) }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
            addView(context.text(label, 14f, C.TEXT, true))
            setOnClickListener { onClick() }
        }
        row.addView(big(R.drawable.i_music, "Add music", 0xFF7CD1FF.toInt()) { pickAudio() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        row.addView(big(R.drawable.i_mic, "Voiceover", 0xFFFF7E6B.toInt()) { recordVoice() }, LinearLayout.LayoutParams(0, -2, 1f))
        s.addView(row)
        s.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), dp(10), 0, dp(4))
            addView(context.text("Music dips under the voice", 13.5f, C.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
            addView(context.word(if (p.duck) "On" else "Off", p.duck, 14f) { snapshot(); p.duck = !p.duck; store.save(p); refreshSoundtrack(); audioPanel() })
        })
        s.addView(LrSlider(this, "Sound of the clips", p.clips.firstOrNull()?.volume ?: 1f, 0f, 2f, null, { "${(it * 100).toInt()}%" }) { v -> p.clips.forEach { it.volume = v }; clipParams() })
        p.audio.sortedBy { it.startMs }.forEach { a ->
            s.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), dp(12), dp(2), dp(12)); background = ripple()
                addView(ImageView(context).apply { setImageResource(if (a.kind == "voice") R.drawable.i_mic else R.drawable.i_music); setColorFilter(if (a.kind == "voice") 0xFFFF7E6B.toInt() else 0xFF7CD1FF.toInt()) }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(12) })
                addView(context.text(a.name, 14.5f, C.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(context.text("${fmt(a.startMs, true)} · ${fmt(a.lengthMs)}", 11.5f, C.SUB, mono = true))
                setOnClickListener { audioEditor(a) }
            })
        }
        col.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(s) }, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(14), 0)
            addView(context.text("Music goes in at the playhead", 11.5f, C.FAINT), LinearLayout.LayoutParams(0, -2, 1f))
            addView(context.text("Done", 14f, C.AMBER, true).apply { setPadding(dp(4), dp(8), dp(4), dp(8)); background = ripple(); setOnClickListener { store.save(p); panelOpen = false; editBottom() } })
        }, LinearLayout.LayoutParams(-1, dp(46)))
        val b = bottom ?: return
        panelOpen = true; b.removeAllViews(); b.addView(col, FrameLayout.LayoutParams(-1, -1))
    }

    private fun pickAudio() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*"; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }
        try { startActivityForResult(i, 3) } catch (e: Exception) { toast("No file picker found") }
    }

    private fun addMusic(u: Uri) {
        val p = project ?: return
        try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
        val name = (contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "Music").substringBeforeLast('.')
        work {
            val len = Audio.lengthOf(this, u.toString())
            main.post {
                if (len <= 0) { toast("Couldn't read that sound file"); return@post }
                snapshot()
                val at = (timeline?.time() ?: 0L).let { if (it > p.lengthMs - 1000) 0L else it }
                val a = AudioItem(u.toString(), name, "music", at, 0, minOf(len, maxOf(1000, p.lengthMs - at)), len).apply { fadeOut = minOf(1500, lengthMs / 4) }
                p.audio.add(a); store.save(p); refreshSoundtrack(); timeline?.invalidate()
                audioEditor(a)
            }
        }
    }

    private var recorder: VoiceRecorder? = null
    /** Voiceover: plays the edit from the playhead (quietly) while you speak; Stop puts it on the timeline. */
    private fun recordVoice() {
        val p = project ?: return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 9); return }
        val start = (timeline?.time() ?: 0L).let { if (it > p.lengthMs - 500) 0L else it }
        val file = File(Stills.dir(this), "voice-" + java.util.UUID.randomUUID() + ".wav")
        val r = VoiceRecorder(file)
        if (!r.start()) { toast("Couldn't use the microphone"); return }
        recorder = r
        seekTimeline(start)
        val keepVol = player?.volume ?: 1f
        player?.volume = 0.15f; bed?.volume = 0.15f; player?.play()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(18), dp(10), dp(18), dp(10)) }
        val clock = text("0:00.0", 34f, C.TEXT, true, mono = true)
        val meter = View(this).apply { background = pill(0xFFFF7E6B.toInt(), dpf(3f)) }
        col.addView(text("RECORDING FROM ${fmt(start, true)}", 11f, 0xFFFF7E6B.toInt(), true).apply { letterSpacing = 0.14f })
        col.addView(clock)
        col.addView(FrameLayout(this).apply { addView(meter, FrameLayout.LayoutParams(dp(4), dp(6), Gravity.CENTER_VERTICAL)) }, LinearLayout.LayoutParams(-1, dp(14)).apply { setMargins(dp(40), dp(6), dp(40), dp(12)) })
        val stop = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFFFF5A4A.toInt()) }
            addView(View(context).apply { background = pill(Color.WHITE, dpf(4f)) }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }
        col.addView(stop, LinearLayout.LayoutParams(dp(72), dp(72)))
        col.addView(text("Tap to stop · headphones keep the edit's sound out of the recording", 11.5f, C.SUB).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
        val b = bottom ?: return
        panelOpen = true; b.removeAllViews(); b.addView(col, FrameLayout.LayoutParams(-1, -1))
        val t0 = android.os.SystemClock.elapsedRealtime()
        val tick = object : Runnable { override fun run() {
            if (recorder !== r) return
            val ms = android.os.SystemClock.elapsedRealtime() - t0
            clock.text = fmt(ms, true)
            meter.layoutParams = (meter.layoutParams as FrameLayout.LayoutParams).apply { width = (dp(4) + (col.width - dp(80)) * minOf(1f, r.level * 2.5f)).toInt() }; meter.requestLayout()
            main.postDelayed(this, 50)
        } }
        main.post(tick)
        stop.setOnClickListener {
            recorder = null; player?.pause(); player?.volume = keepVol; bed?.volume = 1f
            val ms = android.os.SystemClock.elapsedRealtime() - t0
            work {
                r.stop()
                main.post {
                    if (ms < 400) { file.delete(); toast("Too short"); audioPanel(); return@post }
                    snapshot()
                    val n = p.audio.count { it.kind == "voice" } + 1
                    val a = AudioItem(Uri.fromFile(file).toString(), "Voiceover $n", "voice", start, 0, minOf(ms, maxOf(500, p.lengthMs - start)), ms)
                    p.audio.add(a); store.save(p); refreshSoundtrack(); timeline?.invalidate(); audioPanel()
                }
            }
        }
    }

    private fun audioEditor(a: AudioItem) {
        val p = project ?: return
        player?.pause()
        timeline?.selectedText = a.id
        fun again() { val keep = clipPanelBefore; audioEditor(a); clipPanelBefore = keep }
        fun changed() { timeline?.invalidate() }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), 0, dp(18), dp(4))
            addView(ImageView(context).apply { setImageResource(if (a.kind == "voice") R.drawable.i_mic else R.drawable.i_music); setColorFilter(if (a.kind == "voice") 0xFFFF7E6B.toInt() else 0xFF7CD1FF.toInt()) }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) })
            addView(context.text(a.name, 16f, C.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(context.text("${fmt(a.startMs, true)} – ${fmt(a.endMs, true)}", 11.5f, C.SUB, mono = true))
        })
        val s = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(18), dp(6)) }
        s.addView(LrSlider(this, "Volume", a.volume, 0f, 2f, null, { "${(it * 100).toInt()}%" }) { a.volume = it })
        s.addView(LrSlider(this, "Fade in", a.fadeIn / 1000f, 0f, 5f, null, { "%.1f s".format(it) }) { a.fadeIn = (it * 1000).toLong() })
        s.addView(LrSlider(this, "Fade out", a.fadeOut / 1000f, 0f, 5f, null, { "%.1f s".format(it) }) { a.fadeOut = (it * 1000).toLong() })
        if (a.sourceMs > 1500) s.addView(LrSlider(this, "Starts in the file at", a.inMs / 1000f, 0f, maxOf(0.1f, (a.sourceMs - 1000) / 1000f), null, { fmt((it * 1000).toLong(), true) }) { v ->
            a.inMs = (v * 1000).toLong(); a.lengthMs = minOf(a.lengthMs, a.sourceMs - a.inMs); changed() })
        s.addView(LrSlider(this, "Length", a.lengthMs / 1000f, 0.5f, maxOf(0.6f, minOf((a.sourceMs - a.inMs) / 1000f, maxOf(1f, (p.lengthMs - a.startMs) / 1000f))), null, { "%.1f s".format(it) }) { v -> a.lengthMs = (v * 1000).toLong(); changed() })
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r.addView(bare(R.drawable.i_left, "Starts here", size = 20) { a.startMs = (timeline?.time() ?: 0L).coerceAtMost(maxOf(0, p.lengthMs - 500)); changed(); again() })
        r.addView(bare(R.drawable.i_film, "From the start", size = 20) { a.startMs = 0; changed(); again() })
        r.addView(bare(R.drawable.i_play, "Listen", size = 20) { store.save(p); bedKey = ""; refreshSoundtrack(); seekTimeline(a.startMs); main.postDelayed({ player?.play() }, 600) })
        s.addView(r)
        s.addView(text("Drag its bar on the timeline to move it.", 11.5f, C.FAINT).apply { setPadding(dp(4), dp(4), 0, 0) })
        col.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(s) }, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(14), 0)
            addView(context.word("Cancel", false, 14f) { timeline?.selectedText = null; closeClipPanel(false) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(bare(R.drawable.i_trash, null, 0xFFFF6B5E.toInt(), 18) { p.audio.remove(a); timeline?.selectedText = null; closeClipPanel(true) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(context.text("Done", 14f, C.AMBER, true).apply { setPadding(dp(4), dp(8), dp(4), dp(8)); background = ripple(); setOnClickListener { timeline?.selectedText = null; closeClipPanel(true) } })
        }, LinearLayout.LayoutParams(-1, dp(46)))
        openClipPanel(col)
    }

    // ---------------------------------------------------------------- captions

    private var capLang = Locale.getDefault().toLanguageTag().let { t -> Captions.LANGS.firstOrNull { it.first.substringBefore('-') == t.substringBefore('-') }?.first ?: "en-US" }
    private var capStyle = "karaoke"
    private var capRun: Captions.Run? = null

    private fun captionsPanel() {
        val p = project ?: return
        player?.pause()
        val caps = p.texts.filter { it.caption }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val s = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(18), dp(6)) }
        s.addView(text("LANGUAGE SPOKEN", 11f, C.SUB, true).apply { letterSpacing = 0.14f; setPadding(dp(2), 0, 0, dp(2)) })
        val lr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Captions.LANGS.forEach { (k, l) -> lr.addView(word(l, capLang == k, 14f) { capLang = k; captionsPanel() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(14) }) }
        s.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(lr) })
        s.addView(text("LOOK", 11f, C.SUB, true).apply { letterSpacing = 0.14f; setPadding(dp(2), dp(10), 0, dp(2)) })
        val sr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Captions.STYLES.forEach { st -> sr.addView(word(st.name, capStyle == st.id, 14f) {
            capStyle = st.id
            if (caps.isNotEmpty()) { snapshot(); caps.forEach { c -> st.apply(c) }; store.save(p); textKey = ""; updateFrame() }
            captionsPanel() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(14) }) }
        s.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(sr) })
        if (caps.isNotEmpty()) {
            val pr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(0.16f to "Top", 0.5f to "Middle", 0.8f to "Bottom").forEach { (y, l) -> pr.addView(word(l, kotlin.math.abs(caps[0].y - y) < 0.05f, 14f) {
                snapshot(); caps.forEach { it.y = y }; store.save(p); textKey = ""; updateFrame(); captionsPanel() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(14) }) }
            s.addView(pr)
            s.addView(text("${caps.size} caption lines · tap one on the picture or the timeline to fix a word", 12f, C.SUB).apply { setPadding(dp(2), dp(6), 0, 0) })
        }
        val status = text("", 12.5f, C.SUB).apply { setPadding(dp(2), dp(10), 0, 0) }
        s.addView(status)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE; progressTintList = android.content.res.ColorStateList.valueOf(C.AMBER); progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x22FFFFFF)
        }
        s.addView(bar, LinearLayout.LayoutParams(-1, dp(12)).apply { topMargin = dp(6) })
        val act = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        act.addView(cta(if (caps.isEmpty()) "Make captions" else "Make them again", R.drawable.i_cc, true) {
            if (capRun != null) return@cta
            status.text = "Listening to the edit…"; bar.visibility = View.VISIBLE; bar.progress = 0
            val copy = Project.from(p.json())
            work {
                val pcm = Captions.sound(this, copy) { pr -> main.post { bar.progress = pr } }
                val ph = Captions.phrases(pcm)
                main.post {
                    status.text = "Writing out ${ph.size} phrase${if (ph.size == 1) "" else "s"}…"
                    capRun = Captions.recognise(this, pcm, ph, capLang, { pr -> bar.progress = pr }) { items, err ->
                        capRun = null; bar.visibility = View.GONE
                        if (items == null) { status.text = err; return@recognise }
                        snapshot()
                        val st = Captions.STYLES.firstOrNull { it.id == capStyle } ?: Captions.STYLES[0]
                        p.texts.removeAll { it.caption }
                        items.forEach { st.apply(it); p.texts.add(it) }
                        store.save(p); textKey = ""; updateFrame(); timeline?.invalidate()
                        toast("${items.size} caption lines"); captionsPanel()
                    }
                }
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f) })
        if (caps.isNotEmpty()) act.addView(cta("Remove", R.drawable.i_trash, false) { snapshot(); p.texts.removeAll { it.caption }; store.save(p); textKey = ""; updateFrame(); timeline?.invalidate(); captionsPanel() }
            .apply { layoutParams = LinearLayout.LayoutParams(-2, dp(52)).apply { marginStart = dp(8) }; setPadding(dp(18), 0, dp(18), 0) })
        s.addView(act)
        col.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(s) }, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(14), 0)
            addView(context.text("Uses the phone's speech recogniser", 11.5f, C.FAINT), LinearLayout.LayoutParams(0, -2, 1f))
            addView(context.text("Done", 14f, C.AMBER, true).apply { setPadding(dp(4), dp(8), dp(4), dp(8)); background = ripple(); setOnClickListener { capRun?.cancel?.invoke(); capRun = null; panelOpen = false; editBottom() } })
        }, LinearLayout.LayoutParams(-1, dp(46)))
        val b = bottom ?: return
        panelOpen = true; b.removeAllViews(); b.addView(col, FrameLayout.LayoutParams(-1, -1))
    }

    // ---------------------------------------------------------------- reverse, freeze

    private fun busySheet(title: String): Pair<TextView, ProgressBar> {
        val col = vcol()
        col.addView(headline(title, "This takes a moment"))
        val pct = text("0%", 13f, C.TEXT, mono = true)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = android.content.res.ColorStateList.valueOf(C.AMBER); progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x22FFFFFF)
        }
        col.addView(pct); col.addView(bar, LinearLayout.LayoutParams(-1, dp(14)).apply { topMargin = dp(8) })
        sheet(col)
        return pct to bar
    }

    private fun reverseClip(i: Int) {
        val p = project ?: return
        val c = p.clips.getOrNull(i) ?: return
        if (c.still.isNotEmpty()) { toast("A still doesn't move, so there's nothing to reverse"); return }
        player?.pause()
        if (c.revOf != null) {
            snapshot()
            Stills.unreverse(this, c)?.let { p.clips[i] = it }
            store.save(p); reloadPlayer(true); refresh(); toast("Plays forwards again"); return
        }
        if (c.lengthMs > 60_000) toast("A long clip: reversing it takes a while")
        val (pct, bar) = busySheet("Reversing clip ${i + 1}")
        val before = p.json().toString()
        work {
            val r = try { Stills.reverse(this, c) { pr -> main.post { pct.text = "$pr%"; bar.progress = pr } } } catch (e: Throwable) { null }
            main.post {
                closeSheet()
                if (r == null) { toast("Couldn't reverse this clip"); return@post }
                pushUndo(before)
                val at = p.clips.indexOf(c).takeIf { it >= 0 } ?: i
                p.clips[at] = r
                store.save(p); reloadPlayer(true); refresh(); toast("Reversed · it plays without sound")
            }
        }
    }

    /** Holds the frame under the playhead for 2 s: the clip is cut there and the still goes in between. */
    private fun freezeFrame() {
        val p = project ?: return
        if (p.clips.isEmpty()) return
        player?.pause()
        val t = timeline?.time() ?: 0L
        val (i, off) = p.at(t)
        val c = p.clips[i]
        val srcMs = c.startMs + (off * c.speed).toLong()
        toast("Freezing ${fmt(t, true)}…")
        work {
            val still = try {
                val mr = MediaMetadataRetriever(); mr.setDataSource(this, Uri.parse(c.uri))
                val k = minOf(1f, 1920f / maxOf(c.w, c.h, 1))
                val bm = mr.getScaledFrameAtTime(srcMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, maxOf(2, (c.w * k).toInt()), maxOf(2, (c.h * k).toInt()))
                mr.release()
                bm?.let { Stills.stillClip(this, it, "freeze", 2000).apply { framingFrom(c) } }
            } catch (e: Throwable) { null }
            main.post {
                if (still == null) { toast("Couldn't grab that frame"); return@post }
                snapshot()
                val idx = p.clips.indexOf(c).takeIf { it >= 0 } ?: i
                when {
                    srcMs - c.startMs < 150 -> p.clips.add(idx, still)
                    c.endMs - srcMs < 150 -> p.clips.add(idx + 1, still)
                    else -> { val b = c.copy(); b.startMs = srcMs; c.endMs = srcMs; c.trans = "none"; p.clips.add(idx + 1, still); p.clips.add(idx + 2, b) }
                }
                timeline?.selected = p.clips.indexOf(still)
                store.save(p); reloadPlayer(true); refresh(); toast("Frozen for 2 s · drag its end to make it longer")
            }
        }
    }

    // ================================================================ panels: film looks and adjust

    private var gradeBefore: Grade? = null

    private fun openPanel(view: View) {
        val b = bottom ?: return
        if (!panelOpen) gradeBefore = Grade().apply { copyFrom(project!!.grade) }
        panelOpen = true
        b.removeAllViews(); b.addView(view, FrameLayout.LayoutParams(-1, -1))
    }

    private fun closePanel(keep: Boolean) {
        val p = project ?: return
        val before = gradeBefore
        if (!keep && before != null) { p.grade.copyFrom(before); applyGrade() }
        if (keep && before != null && before.json().toString() != p.grade.json().toString()) {
            val now = p.grade.json(); p.grade = Grade.from(before.json()); pushUndo(p.json().toString()); p.grade = Grade.from(now)
        }
        store.save(p)
        panelOpen = false; gradeBefore = null
        editBottom()
    }

    private fun panelFooter(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(14), 0)
        addView(context.word("Cancel", false, 14f) { closePanel(false) })
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(context.text("Applies to the whole edit", 11.5f, C.FAINT))
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(context.text("Done", 14f, C.AMBER, true).apply { setPadding(dp(4), dp(8), dp(4), dp(8)); background = ripple(); setOnClickListener { closePanel(true) } })
    }

    private var sample: Bitmap? = null

    private fun filmPanel(tab: String, lutTab: Boolean = false) {
        val p = project ?: return
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), 0, dp(16), dp(6)) }
        (listOf(MINE) + Looks.GROUPS + "LUTs").forEach { t ->
            val on = if (lutTab) t == "LUTs" else t == tab
            tabs.addView(word(t, on, 13f) { if (t == "LUTs") filmPanel(tab, lutTab = true) else filmPanel(t) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(14) })
        }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabs) })
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), 0, dp(14), 0) }
        if (lutTab) {
            strip.addView(lookTile(null, "None", p.grade.look == "none") { p.grade.look = "none"; applyGrade(); filmPanel(tab, true) })
            store.lutDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                strip.addView(lookTile(null, f.name.removeSuffix(".cube"), p.grade.look == "lut:${f.name}", lut = f.name) { p.grade.look = "lut:${f.name}"; applyGrade(); filmPanel(tab, true) })
            }
            strip.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = ripple()
                addView(ImageView(context).apply { setImageResource(R.drawable.i_import); setColorFilter(C.AMBER) }, LinearLayout.LayoutParams(dp(26), dp(26)))
                addView(context.text("Import .cube", 11.5f, C.AMBER, true).apply { setPadding(0, dp(8), 0, 0) })
                setOnClickListener { pickLut() }
            }, LinearLayout.LayoutParams(dp(84), dp(118)))
        } else if (tab == MINE) {
            // your saved grades: tap to use, hold to delete
            Presets(this).all().forEach { pr ->
                strip.addView(lookTile(null, pr.name, false, preset = pr.grade) { p.grade.copyFrom(pr.grade); applyGrade(); toast("“${pr.name}” applied"); filmPanel(tab) }.apply {
                    setOnLongClickListener { confirm("Delete “${pr.name}”?", "The preset goes; edits that used it keep their look.", "Delete") { Presets(this@RollActivity).delete(pr.name); filmPanel(tab) }; true }
                })
            }
            strip.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = ripple()
                addView(ImageView(context).apply { setImageResource(R.drawable.i_add); setColorFilter(C.AMBER) }, LinearLayout.LayoutParams(dp(26), dp(26)))
                addView(context.text("Save this\ngrade", 11.5f, C.AMBER, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
                setOnClickListener { savePreset() }
            }, LinearLayout.LayoutParams(dp(84), dp(118)))
        } else {
            strip.addView(lookTile(null, "None", p.grade.look == "none") { p.grade.look = "none"; applyGrade(); filmPanel(tab) })
            Looks.ALL.filter { it.group == tab }.forEach { l ->
                strip.addView(lookTile(l, l.name, p.grade.look == l.id) {
                    p.grade.look = l.id; p.grade.grain = -1f; p.grade.vignette = -1f; applyGrade(); filmPanel(tab)
                })
            }
        }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) }, LinearLayout.LayoutParams(-1, dp(124)))
        if (p.grade.look != "none") {
            val s = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(6), dp(14), 0) }
            s.addView(slider("Strength", p.grade.strength, 0f, 1f) { p.grade.strength = it; applyGrade() })
            s.addView(slider("Grain", Looks.grainOf(p.grade), 0f, 1f) { p.grade.grain = it; applyGrade() })
            s.addView(slider("Vignette", Looks.vignetteOf(p.grade), 0f, 1f) { p.grade.vignette = it; applyGrade() })
            col.addView(ScrollView(this).apply { addView(s) }, LinearLayout.LayoutParams(-1, 0, 1f))
        } else col.addView(text(when { lutTab -> "Your own .cube LUTs (from Resolve, Premiere, …) show up here."; tab == MINE -> "Save the whole grade (look, Adjust, effects, overlay) and use it on any project. Hold a preset to delete it."
            else -> "Pick a look. Hold the picture to compare with the original." }, 12.5f, C.SUB).apply { setPadding(dp(20), dp(10), dp(20), 0) }, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(panelFooter(), LinearLayout.LayoutParams(-1, dp(46)))
        openPanel(col)
    }

    /** A look shown on a frame of your own video. */
    private fun lookTile(l: Looks.Look?, name: String, on: Boolean, lut: String? = null, preset: Grade? = null, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val img = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = pill(0xFF1A1A1A.toInt(), dpf(10f))
            if (on) foreground = pill(0, dpf(10f), dp(2), C.AMBER)
        }
        addView(img, LinearLayout.LayoutParams(dp(76), dp(92)))
        addView(context.text(name, 11.5f, if (on) C.AMBER else C.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(1), dp(6), 0, 0) },
            LinearLayout.LayoutParams(dp(76), -2))
        background = ripple(); setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) }
        work {
            val s = sample ?: project?.let { p -> p.clips.getOrNull(p.at(timeline?.time() ?: 0).first)?.let { c -> frameAt(c, c.startMs + 500, 180) } }?.also { sample = it }
            if (s != null) {
                val g = preset ?: Grade(look = when { l != null -> l.id; lut != null -> "lut:$lut"; else -> "none" })
                val bm = gradePreview(s, g)
                main.post { img.setImageBitmap(bm) }
            }
        }
    }

    private val MINE = "Mine"

    private fun savePreset() {
        val p = project ?: return
        val col = vcol()
        col.addView(text("SAVE GRADE AS", 11.5f, C.SUB, true).apply { letterSpacing = 0.14f })
        val e = EditText(this).apply {
            val def = Looks.byId(p.grade.look)?.name?.let { "$it · mine" } ?: "My grade"
            setText(def); setSelection(def.length); setTextColor(C.TEXT); textSize = 22f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = null; setPadding(0, dp(6), 0, dp(10)); isSingleLine = true
        }
        col.addView(e)
        col.addView(View(this).apply { setBackgroundColor(C.AMBER) }, LinearLayout.LayoutParams(-1, dp(2)))
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(14), 0, 0) }
        r.addView(word("Cancel", false, 15f) { closeSheet() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(24) })
        r.addView(text("Save", 15f, C.AMBER, true).apply { setPadding(dp(4), dp(8), dp(4), dp(8)); background = ripple()
            setOnClickListener { val n = e.text.toString().trim().ifBlank { "My grade" }; Presets(this@RollActivity).save(n, p.grade); closeSheet(); toast("Saved “$n”"); val keep = gradeBefore; panelOpen = false; filmPanel(MINE); gradeBefore = keep } })
        col.addView(r)
        sheet(col)
        e.requestFocus()
        main.postDelayed({ (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(e, 0) }, 250)
    }

    /** Adjust, laid out like Lightroom: Light, Color and Effects, each a set of fine sliders. */
    private var adjTab = "light"
    private var mixColor = 0
    private fun adjustPanel() {
        val p = project ?: return
        val g = p.grade
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(14), dp(2)) }
        listOf(Triple("light", R.drawable.i_sun, "Light"), Triple("color", R.drawable.i_drop, "Color"), Triple("mix", R.drawable.i_filter, "Mix"), Triple("split", R.drawable.i_split, "Split"), Triple("effects", R.drawable.i_fx, "Effects")).forEach { (k, ic, l) ->
            tabs.addView(bare(ic, l, if (adjTab == k) C.AMBER else C.SUB, 20) { adjTab = k; val keep = gradeBefore; panelOpen = false; adjustPanel(); gradeBefore = keep })
        }
        tabs.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        tabs.addView(word("Reset", false, 13f) {
            when (adjTab) {
                "light" -> { g.exposure = 0f; g.contrast = 0f; g.highlights = 0f; g.shadows = 0f; g.whites = 0f; g.blacks = 0f; g.fade = 0f }
                "color" -> { g.warmth = 0f; g.tint = 0f; g.vibrance = 0f; g.saturation = 0f; g.hue = 0f }
                "mix" -> java.util.Arrays.fill(g.mix, 0f)
                "split" -> { g.shSat = 0f; g.hiSat = 0f; g.balance = 0f }
                else -> { g.grain = -1f; g.vignette = -1f; g.bloom = 0f; g.halation = 0f; g.aberration = 0f; g.sharpen = 0f }
            }
            applyGrade(); val keep = gradeBefore; panelOpen = false; adjustPanel(); gradeBefore = keep
        })
        col.addView(tabs)
        val s = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(18), dp(6)) }
        val pm = { v: Float -> val n = (v * 100).toInt(); if (n > 0) "+$n" else "$n" }
        fun add(name: String, value: Float, min: Float, max: Float, grad: IntArray?, f: (Float) -> String, set: (Float) -> Unit) =
            s.addView(LrSlider(this, name, value, min, max, grad, f) { set(it); applyGrade() })
        when (adjTab) {
            "light" -> {
                add("Exposure", g.exposure, -1f, 1f, null, { "%+.2f EV".format(it * 2f) }) { g.exposure = it }
                add("Contrast", g.contrast, -1f, 1f, null, pm) { g.contrast = it }
                add("Highlights", g.highlights, -1f, 1f, null, pm) { g.highlights = it }
                add("Shadows", g.shadows, -1f, 1f, null, pm) { g.shadows = it }
                add("Whites", g.whites, -1f, 1f, null, pm) { g.whites = it }
                add("Blacks", g.blacks, -1f, 1f, null, pm) { g.blacks = it }
                add("Fade", g.fade, 0f, 1f, null, { "${(it * 100).toInt()}" }) { g.fade = it }
            }
            "color" -> {
                add("Temp", g.warmth, -1f, 1f, intArrayOf(0xFF4A86D9.toInt(), 0xFFB7B7B7.toInt(), 0xFFE8C23A.toInt()), pm) { g.warmth = it }
                add("Tint", g.tint, -1f, 1f, intArrayOf(0xFF4DBB5F.toInt(), 0xFFB7B7B7.toInt(), 0xFFD24FC4.toInt()), pm) { g.tint = it }
                add("Vibrance", g.vibrance, -1f, 1f, null, pm) { g.vibrance = it }
                add("Saturation", g.saturation, -1f, 1f, intArrayOf(0xFF8A8A8A.toInt(), 0xFFFF5A3C.toInt(), 0xFFFFC23C.toInt(), 0xFF3CC86E.toInt(), 0xFF3C8CFF.toInt(), 0xFFB45AFF.toInt()), pm) { g.saturation = it }
                add("Hue", g.hue, -1f, 1f, intArrayOf(0xFF3C8CFF.toInt(), 0xFFB45AFF.toInt(), 0xFFFF5A3C.toInt(), 0xFFFFC23C.toInt(), 0xFF3CC86E.toInt()), { "%+.0f°".format(it * 45f) }) { g.hue = it }
            }
            "mix" -> {
                // pick a colour, then move its hue, saturation and luminance
                val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(6)) }
                Looks.MIX_COLORS.forEachIndexed { i, col ->
                    val on = i == mixColor; val changed = g.mix[i * 3] != 0f || g.mix[i * 3 + 1] != 0f || g.mix[i * 3 + 2] != 0f
                    dots.addView(FrameLayout(this).apply {
                        setPadding(dp(5), dp(5), dp(5), dp(5)); background = ripple()
                        addView(View(context).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(col); if (on) setStroke(dp(3), Color.WHITE) } },
                            FrameLayout.LayoutParams(dp(if (on) 30 else 24), dp(if (on) 30 else 24), Gravity.CENTER))
                        if (changed && !on) addView(View(context).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.WHITE) } },
                            FrameLayout.LayoutParams(dp(5), dp(5), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
                        setOnClickListener { mixColor = i; val keep = gradeBefore; panelOpen = false; adjustPanel(); gradeBefore = keep }
                    }, LinearLayout.LayoutParams(0, dp(42), 1f))
                }
                s.addView(dots)
                val i = mixColor; val col = Looks.MIX_COLORS[i]
                val prev = Looks.MIX_COLORS[(i + 7) % 8]; val next = Looks.MIX_COLORS[(i + 1) % 8]
                s.addView(text(Looks.MIX_NAMES[i].uppercase(), 11f, col, true).apply { letterSpacing = 0.14f; setPadding(dp(4), dp(2), 0, 0) })
                add("Hue", g.mix[i * 3], -1f, 1f, intArrayOf(prev, col, next), pm) { g.mix[i * 3] = it }
                add("Saturation", g.mix[i * 3 + 1], -1f, 1f, intArrayOf(0xFF8A8A8A.toInt(), col), pm) { g.mix[i * 3 + 1] = it }
                add("Luminance", g.mix[i * 3 + 2], -1f, 1f, intArrayOf(0xFF151515.toInt(), col, 0xFFF4F4F4.toInt()), pm) { g.mix[i * 3 + 2] = it }
            }
            "split" -> {
                val rainbow = IntArray(7) { i -> val c = Looks.hueColor(i / 6f); Color.rgb((c[0] * 255).toInt(), (c[1] * 255).toInt(), (c[2] * 255).toInt()) }
                val n100 = { v: Float -> "${(v * 100).toInt()}" }
                s.addView(text("SHADOWS", 11f, C.SUB, true).apply { letterSpacing = 0.14f; setPadding(dp(4), dp(2), 0, 0) })
                add("Hue", g.shHue, 0f, 1f, rainbow, { "${(it * 360).toInt()}°" }) { g.shHue = it }
                add("Amount", g.shSat, 0f, 1f, null, n100) { g.shSat = it }
                s.addView(text("HIGHLIGHTS", 11f, C.SUB, true).apply { letterSpacing = 0.14f; setPadding(dp(4), dp(6), 0, 0) })
                add("Hue", g.hiHue, 0f, 1f, rainbow, { "${(it * 360).toInt()}°" }) { g.hiHue = it }
                add("Amount", g.hiSat, 0f, 1f, null, n100) { g.hiSat = it }
                add("Balance", g.balance, -1f, 1f, null, pm) { g.balance = it }
            }
            else -> {
                val n100 = { v: Float -> "${(v * 100).toInt()}" }
                add("Grain", Looks.grainOf(g), 0f, 1f, null, n100) { g.grain = it }
                add("Vignette", Looks.vignetteOf(g), 0f, 1f, null, n100) { g.vignette = it }
                add("Bloom", g.bloom, 0f, 1f, null, n100) { g.bloom = it }
                add("Halation", g.halation, 0f, 1f, null, n100) { g.halation = it }
                add("Colour fringing", g.aberration, 0f, 1f, null, n100) { g.aberration = it }
                add("Sharpen", g.sharpen, 0f, 1f, null, n100) { g.sharpen = it }
                s.addView(text("Double-tap a slider to reset it. Hold the picture to see the original.", 11.5f, C.FAINT).apply { setPadding(dp(4), dp(10), 0, 0) })
            }
        }
        col.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(s) }, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(panelFooter(), LinearLayout.LayoutParams(-1, dp(46)))
        openPanel(col)
    }

    /** Overlays like InShot's: light leaks, dust, film burn, VHS, bokeh, snow, rain, a 35mm frame, date stamp, cinema bars. */
    private fun overlayPanel() {
        val p = project ?: return
        val g = p.grade
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text("OVERLAYS", 11f, C.SUB, true).apply { letterSpacing = 0.14f; setPadding(dp(18), dp(4), 0, dp(8)) })
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), 0, dp(14), 0) }
        strip.addView(overlayTile("none", "None", g.overlay == "none"))
        Overlays.ALL.forEach { k -> strip.addView(overlayTile(k.id, k.name, g.overlay == k.id)) }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) }, LinearLayout.LayoutParams(-1, dp(124)))
        if (g.overlay != "none") {
            val s = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(4), dp(18), 0) }
            s.addView(LrSlider(this, "Strength", g.overlayAmount, 0f, 1f, null, { "${(it * 100).toInt()}" }) { g.overlayAmount = it; applyGrade() })
            col.addView(s, LinearLayout.LayoutParams(-1, 0, 1f))
        } else col.addView(text("Overlays sit on top of your look and move with the video.", 12.5f, C.SUB).apply { setPadding(dp(20), dp(10), dp(20), 0) }, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(panelFooter(), LinearLayout.LayoutParams(-1, dp(46)))
        openPanel(col)
    }

    private fun overlayTile(id: String, name: String, on: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val img = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = pill(0xFF1A1A1A.toInt(), dpf(10f))
            if (on) foreground = pill(0, dpf(10f), dp(2), C.AMBER)
        }
        addView(img, LinearLayout.LayoutParams(dp(76), dp(92)))
        addView(context.text(name, 11.5f, if (on) C.AMBER else C.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(1), dp(6), 0, 0) },
            LinearLayout.LayoutParams(dp(76), -2))
        background = ripple()
        setOnClickListener { project?.grade?.let { g -> g.overlay = id; if (g.overlayAmount < 0.05f) g.overlayAmount = 0.8f }; applyGrade(); val keep = gradeBefore; panelOpen = false; overlayPanel(); gradeBefore = keep }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) }
        work {
            val p = project ?: return@work
            val s0 = sample ?: p.clips.getOrNull(p.at(timeline?.time() ?: 0).first)?.let { c -> frameAt(c, c.startMs + 500, 180) }?.also { sample = it } ?: return@work
            val base = gradePreview(s0, p.grade).copy(Bitmap.Config.ARGB_8888, true)
            if (id != "none") {
                val ov = Overlays.draw(id, base.width, base.height, 0)
                android.graphics.Canvas(base).drawBitmap(ov, 0f, 0f, android.graphics.Paint().apply { alpha = 220 })
            }
            main.post { img.setImageBitmap(base) }
        }
    }

    // ---------------------------------------------------------------- frames

    private fun frameAt(c: Clip, ms: Long, h: Int, exact: Boolean = false): Bitmap? = try {
        val mr = MediaMetadataRetriever(); mr.setDataSource(this, Uri.parse(c.uri))
        val w = if (c.w > 0 && c.h > 0) h * c.w / c.h else h
        val b = mr.getScaledFrameAtTime(ms * 1000, if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h); mr.release(); b
    } catch (e: Exception) { null }

    /** The grade applied to a small picture on the CPU (for look thumbnails and project covers). */
    private fun gradePreview(src: Bitmap, g: Grade): Bitmap {
        if (g.neutral) return src
        val lut = Looks.lut(g, store.lutDir); val n = Looks.SIZE - 1
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            px[i] = lut[((c shr 16 and 255) * n + 127) / 255][((c shr 8 and 255) * n + 127) / 255][((c and 255) * n + 127) / 255]
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    // ================================================================ export

    private var exportView: View? = null
    private var exFormat = "video"
    private var exShort = 1080; private var exFps = 24; private var exQuality = 2
    private var gifW = 480; private var gifFps = 15; private var gifSecs = 6; private var gifLoop = "forever"
    private var stSquare = true; private var stFps = 30

    private fun openExport() {
        val p = project ?: return
        if (p.clips.isEmpty()) { toast("Add a clip first"); return }
        player?.pause()
        // the last export settings you used
        prefs().let { pr -> exFormat = pr.getString("exFormat", exFormat) ?: exFormat; exShort = pr.getInt("exShort", exShort); exFps = pr.getInt("exFps", exFps); exQuality = pr.getInt("exQuality", exQuality)
            gifW = pr.getInt("gifW", gifW); gifFps = pr.getInt("gifFps", gifFps); gifSecs = pr.getInt("gifSecs", gifSecs); gifLoop = pr.getString("gifLoop", gifLoop) ?: gifLoop
            stSquare = pr.getBoolean("stSquare", stSquare); stFps = pr.getInt("stFps", stFps) }
        val v = FrameLayout(this).apply { background = grain(); isClickable = true }
        exportView = v
        root.addView(v, FrameLayout.LayoutParams(-1, -1))
        renderExport()
    }

    private fun closeExport() { exportView?.let { root.removeView(it) }; exportView = null; exPreview = null }

    private var exPreview: Bitmap? = null

    private fun renderExport(status: String? = null, progress: Int = -1, result: File? = null, resultMime: String? = null, savedUri: Uri? = null) {
        val v = exportView as? FrameLayout ?: return
        val p = project ?: return
        v.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, insetTop, 0, insetBottom) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(16), dp(4)) }
        top.addView(bare(R.drawable.i_back, null) { onBackPressed() })
        top.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(context.text("Export", 19f, C.TEXT, true))
            addView(context.text(p.name + " · " + fmt(p.lengthMs), 11.5f, C.SUB))
        })
        col.addView(top)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(10)) }
        // format cards
        val cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(Triple("video", "MP4", "Video" to "up to 4K"), Triple("gif", "GIF", "Animation" to "loops anywhere"), Triple("sticker", "WEBM", "Telegram" to "512 px · 3 s")).forEachIndexed { i, (k, big, sub) ->
            val on = exFormat == k
            cards.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(10), dp(14))
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22FFFFFF),
                    pill(if (on) 0x1FF2A541 else 0xFF101010.toInt(), dpf(18f), dp(if (on) 2 else 1), if (on) C.AMBER else 0x14FFFFFF), pill(Color.WHITE, dpf(18f)))
                addView(context.text(big, 20f, if (on) C.AMBER else C.TEXT, true, mono = true))
                addView(context.text(sub.first, 13f, C.TEXT, true).apply { setPadding(0, dp(10), 0, 0) })
                addView(context.text(sub.second, 11f, C.SUB))
                setOnClickListener { if (exporting == null) { exFormat = k; renderExport() } }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        body.addView(cards)
        // preview of the frame under the playhead, in the export's shape
        val shape = when (exFormat) { "sticker" -> if (stSquare) 1f else Render.ratio(p); else -> Render.ratio(p) }
        val frame = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = pill(0xFF141414.toInt(), dpf(14f)) }
        val fh = dp(170); val fw = minOf(resources.displayMetrics.widthPixels - dp(36), (fh * shape).toInt())
        body.addView(FrameLayout(this).apply { addView(frame, FrameLayout.LayoutParams(fw, if (fw < (fh * shape).toInt()) (fw / shape).toInt() else fh, Gravity.CENTER)) },
            LinearLayout.LayoutParams(-1, fh).apply { topMargin = dp(16) })
        exPreview?.let { frame.setImageBitmap(it) } ?: work {
            val (ci, off) = p.at(timeline?.time() ?: 0)
            val c = p.clips.getOrNull(ci)
            val bm = c?.let { frameAt(it, it.startMs + (off * it.speed).toLong(), 480, exact = true) }?.let { gradePreview(it, p.grade) }
            main.post { exPreview = bm; frame.setImageBitmap(bm) }
        }
        /** A row of choices: a big value, a small line under it, and an amber bar under the chosen one. */
        fun dial(title: String, opts: List<Pair<String, String>>, sel: Int, pick: (Int) -> Unit) {
            body.addView(text(title.uppercase(), 11f, C.SUB, true).apply { letterSpacing = 0.14f; setPadding(dp(2), dp(22), 0, dp(4)) })
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            opts.forEachIndexed { i, (big, small) ->
                val on = i == sel
                r.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(6), dp(20), dp(6)); background = ripple()
                    addView(context.text(big, 20f, if (on) C.AMBER else C.TEXT, true).apply { maxLines = 1 }, LinearLayout.LayoutParams(-2, -2))
                    if (small.isNotEmpty()) addView(context.text(small, 11f, if (on) C.AMBER else C.SUB).apply { maxLines = 1; setPadding(0, dp(2), 0, 0) }, LinearLayout.LayoutParams(-2, -2))
                    addView(View(context).apply { background = pill(if (on) C.AMBER else 0, dpf(2f)) }, LinearLayout.LayoutParams(dp(24), dp(3)).apply { topMargin = dp(7) })
                    setOnClickListener { if (exporting == null) { pick(i); renderExport() } }
                }, LinearLayout.LayoutParams(-2, -2))
            }
            body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r, FrameLayout.LayoutParams(-2, -2)) })
        }
        val t0 = timeline?.time() ?: 0L
        val chips: List<String>
        when (exFormat) {
            "gif" -> {
                dial("Width", listOf("320 px" to "small", "480 px" to "chats", "720 px" to "sharp"), listOf(320, 480, 720).indexOf(gifW)) { gifW = listOf(320, 480, 720)[it] }
                dial("Frame rate", listOf(12, 15, 24, 25, 30).map { "$it fps" to (if (it <= 15) "lighter" else "smoother") }, listOf(12, 15, 24, 25, 30).indexOf(gifFps)) { gifFps = listOf(12, 15, 24, 25, 30)[it] }
                dial("Length", listOf("3 s" to "from ${fmt(t0)}", "6 s" to "from ${fmt(t0)}", "10 s" to "from ${fmt(t0)}", "All" to fmt(p.lengthMs)), listOf(3, 6, 10, 0).indexOf(gifSecs)) { gifSecs = listOf(3, 6, 10, 0)[it] }
                dial("Loop", listOf("Forever" to "repeats", "Once" to "plays one time", "Bounce" to "forward, back"), listOf("forever", "once", "bounce").indexOf(gifLoop)) { gifLoop = listOf("forever", "once", "bounce")[it] }
                val (w, h) = gifSize(p); val secs = gifRange(p).let { (a, b) -> (b - a) / 1000f }
                chips = listOf("$w × $h", "%.1f s".format(secs), "≈ %.1f MB".format(w * h * gifFps * secs * 0.35f / 1_000_000f * (if (gifLoop == "bounce") 2 else 1)), "Pictures/Roll")
            }
            "sticker" -> {
                dial("Shape", listOf("Square" to "512 × 512", "Keep shape" to "512 on the long side"), if (stSquare) 0 else 1) { stSquare = it == 0 }
                dial("Frame rate", listOf(24, 25, 30).map { "$it fps" to "" }, listOf(24, 25, 30).indexOf(stFps)) { stFps = listOf(24, 25, 30)[it] }
                chips = listOf("VP9 · WEBM", "3 s from ${fmt(t0)}", "no sound", "≤ 256 KB")
            }
            else -> {
                fun dims(sh: Int) = Render.size(p, sh).let { (a, b) -> "$a × $b" }
                dial("Resolution", listOf("720p" to dims(720), "1080p" to dims(1080), "4K" to dims(2160)), listOf(720, 1080, 2160).indexOf(exShort)) { exShort = listOf(720, 1080, 2160)[it] }
                dial("Frame rate", listOf(24 to "film", 25 to "PAL video", 30 to "phone video").map { (f, n) -> "$f fps" to n }, listOf(24, 25, 30).indexOf(exFps)) { exFps = listOf(24, 25, 30)[it] }
                val (w, h) = Render.size(p, exShort)
                val mb = { q: Int -> val keep = exQuality; exQuality = q; val m = (videoBitrate(w, h) / 8f * p.lengthMs / 1000f / 1_000_000f).toInt(); exQuality = keep; "≈ $m MB" }
                dial("Quality", listOf("Small" to mb(0), "Balanced" to mb(1), "Best" to mb(2)), exQuality) { exQuality = it }
                chips = listOf("H.264", "%.1f Mb/s".format(videoBitrate(w, h) / 1_000_000f), if (p.mute) "no sound" else "with sound", "saves to Movies/Roll")
            }
        }
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(20), 0, 0) }
        chips.forEach { c -> chipRow.addView(text(c, 11.5f, C.TEXT, mono = true).apply { setPadding(dp(10), dp(5), dp(10), dp(5)); background = pill(0xFF141414.toInt(), dpf(8f)) },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }) }
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) })
        col.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        // progress and the big button
        val foot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(6), dp(18), dp(20)) }
        if (status != null) {
            foot.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(context.text(status, 12.5f, C.SUB), LinearLayout.LayoutParams(0, -2, 1f))
                if (progress >= 0) addView(context.text("$progress%", 12f, C.TEXT, mono = true))
            })
            if (progress >= 0) foot.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; this.progress = progress; progressTintList = android.content.res.ColorStateList.valueOf(C.AMBER); progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x22FFFFFF)
            }, LinearLayout.LayoutParams(-1, dp(12)).apply { topMargin = dp(6); bottomMargin = dp(10) })
        }
        when {
            exporting != null -> foot.addView(cta("Cancel", R.drawable.i_close, false) { exporting?.cancel?.invoke(); exporting = null; renderExport("Cancelled") })
            result != null && resultMime != null -> foot.addView(cta(if (exFormat == "sticker") "Send to Telegram" else "Share", R.drawable.i_share, true) { shareFile(result, resultMime, savedUri) })
            else -> foot.addView(cta(when (exFormat) { "sticker" -> "Make sticker"; "gif" -> "Make GIF"; else -> "Export video" }, R.drawable.i_export, true) { startExport() })
        }
        col.addView(foot)
        v.addView(col, FrameLayout.LayoutParams(-1, -1))
    }

    /** The one big button: amber, full width. */
    private fun cta(label: String, icon: Int, filled: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33000000),
            pill(if (filled) C.AMBER else 0xFF161616.toInt(), dpf(28f)), pill(Color.WHITE, dpf(28f)))
        val fg = if (filled) 0xFF0A0A0A.toInt() else C.TEXT
        addView(ImageView(context).apply { setImageResource(icon); setColorFilter(fg) }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
        addView(context.text(label, 16.5f, fg, true))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(56))
    }

    private fun videoBitrate(w: Int, h: Int) = (w.toLong() * h * exFps * floatArrayOf(0.06f, 0.1f, 0.16f)[exQuality]).toInt().coerceIn(1_000_000, 80_000_000)

    private fun gifRange(p: Project): Pair<Long, Long> {
        val t0 = (timeline?.time() ?: 0L).coerceAtMost(maxOf(0, p.lengthMs - 500))
        return if (gifSecs == 0) 0L to p.lengthMs else t0 to minOf(p.lengthMs, t0 + gifSecs * 1000L)
    }
    private fun gifSize(p: Project): Pair<Int, Int> { val r = Render.ratio(p); return gifW to ((gifW / r).toInt() / 2 * 2) }

    private fun outDir() = File(cacheDir, "export").apply { mkdirs() }

    private var exDissolve: Map<String, Bitmap>? = null
    private var exSoundtrack: File? = null
    private fun startExport() {
        val p = project ?: return
        store.save(p)
        prefs().edit().putString("exFormat", exFormat).putInt("exShort", exShort).putInt("exFps", exFps).putInt("exQuality", exQuality)
            .putInt("gifW", gifW).putInt("gifFps", gifFps).putInt("gifSecs", gifSecs).putString("gifLoop", gifLoop).putBoolean("stSquare", stSquare).putInt("stFps", stFps).apply()
        if (exporting != null && exDissolve == null) return      // one export at a time
        if (exDissolve == null && p.clips.any { it.trans == "dissolve" }) {
            var gone = false
            exporting = Render.Job { gone = true }
            renderExport("Getting ready…", 0)
            val copy = Project.from(p.json())
            work {
                val m = Render.dissolveFrames(this, copy.clips)
                main.post { if (gone) return@post; exporting = null; exDissolve = m; startExport() }
            }
            return
        }
        val diss = exDissolve ?: emptyMap(); exDissolve = null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safe = p.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "Roll" }
        try {
            when (exFormat) {
                "video" -> {
                    val (w, h) = Render.size(p, exShort)
                    val out = File(outDir(), "$safe-$stamp.mp4")
                    fun finished(err: String?, note: String?) {
                        exporting = null
                        android.util.Log.i("ROLLTEST", if (err != null) "export failed: $err" else "export ok ${out.path} ${out.length()} ${note ?: ""}")
                        if (err != null) renderExport("Couldn't export: $err")
                        else { val u = Render.saveToGallery(this, out, out.name, "video/mp4"); renderExport(note ?: "Saved to Movies/Roll", 100, out, "video/mp4", u) }
                    }
                    if (!p.mute) {
                        // the picture first (no sound), then Roll mixes every sound (clips, music, voiceovers) and puts them together
                        val pic = File(outDir(), "picture-$stamp.mp4")
                        val copy = Project.from(p.json())
                        exporting = Render.export(this, { lvl -> Render.composition(this, p, p.clips, w, h, exFps, false, store.lutDir, lvl, diss) },
                            pic, MimeTypes.VIDEO_H264, videoBitrate(w, h), { pr -> renderExport("Exporting the picture…", pr * 85 / 100) }) { err, _ ->
                            if (err != null) { finished(err, null); return@export }
                            renderExport("Mixing the sound…", 86)
                            work {
                                val e2 = try {
                                    val pcm = Mux.fullMix(this, copy) { pr -> main.post { if (exporting != null) renderExport("Mixing the sound…", 86 + pr * 8 / 100) } }
                                    Mux.join(pic, pcm, out) { pr -> main.post { if (exporting != null) renderExport("Putting it together…", 94 + pr * 6 / 100) } }
                                    null
                                } catch (e: Throwable) { Render.reason(e) }
                                pic.delete()
                                main.post { finished(e2, null) }
                            }
                        }
                    } else {
                        exporting = Render.export(this, { lvl -> Render.composition(this, p, p.clips, w, h, exFps, !p.mute, store.lutDir, lvl, diss) },
                            out, MimeTypes.VIDEO_H264, videoBitrate(w, h), { pr -> renderExport("Exporting…", pr) }) { err, note -> finished(err, note) }
                    }
                    renderExport("Exporting…", 0)
                }
                "gif" -> {
                    val (a, b) = gifRange(p); val (w, h) = gifSize(p)
                    val tmp = File(outDir(), "gif-src.mp4"); val out = File(outDir(), "$safe-$stamp.gif")
                    exporting = Render.export(this, { lvl -> Render.composition(this, p, Render.range(p, a, b), w, h, gifFps, false, store.lutDir, lvl, diss, a) },
                        tmp, MimeTypes.VIDEO_H264, 6_000_000, { pr -> renderExport("Rendering frames…", pr / 2) }) { err, _ ->
                        if (err != null) { exporting = null; renderExport("Couldn't export: $err"); return@export }
                        renderExport("Making the GIF…", 50)
                        work {
                            val ok = try { Render.gif(tmp, out, gifFps, gifLoop) { pr -> main.post { if (exporting != null) renderExport("Making the GIF…", 50 + pr / 2) } }; true } catch (e: Exception) { false }
                            main.post {
                                exporting = null
                                if (!ok) renderExport("Couldn't make the GIF")
                                else { val u = Render.saveToGallery(this, out, out.name, "image/gif"); renderExport("Saved to Pictures/Roll · ${out.length() / 1024} KB", 100, out, "image/gif", u) }
                            }
                        }
                    }
                    renderExport("Rendering frames…", 0)
                }
                else -> {
                    val t0 = (timeline?.time() ?: 0L).coerceAtMost(maxOf(0, p.lengthMs - 500))
                    val r = Render.ratio(p)
                    val (w, h) = if (stSquare) 512 to 512 else if (r >= 1) 512 to ((512 / r).toInt() / 2 * 2) else ((512 * r).toInt() / 2 * 2) to 512
                    val tmp = File(outDir(), "sticker-src.mp4"); val out = File(outDir(), "$safe-$stamp.webm")
                    exporting = Render.export(this, { lvl -> Render.composition(this, p, Render.range(p, t0, minOf(p.lengthMs, t0 + 2990)), w, h, stFps, false, store.lutDir, lvl, diss, t0) },
                        tmp, MimeTypes.VIDEO_H264, 8_000_000, { pr -> renderExport("Rendering…", pr / 2) }) { err, _ ->
                        if (err != null) { exporting = null; renderExport("Couldn't export: $err"); return@export }
                        renderExport("Encoding VP9…", 60)
                        work {
                            var rate = 650_000; var ok = false; var tries = 0
                            while (tries++ < 6) {
                                ok = try { Render.webm(tmp, out, rate) } catch (e: Exception) { false }
                                if (!ok || out.length() <= 256 * 1024) break
                                rate = (rate * 0.68f).toInt()
                                main.post { if (exporting != null) renderExport("Making it smaller…", 70 + tries * 4) }
                            }
                            main.post {
                                exporting = null
                                when {
                                    !ok -> renderExport("This phone can't encode VP9 video, which Telegram needs")
                                    out.length() > 256 * 1024 -> renderExport("Couldn't get it under 256 KB · try a calmer part")
                                    else -> { val u = Render.saveToGallery(this, out, out.name, "video/webm"); renderExport("Sticker ready · ${out.length() / 1024} KB of 256 KB", 100, out, "video/webm", u) }
                                }
                            }
                        }
                    }
                    renderExport("Rendering…", 0)
                }
            }
        } catch (e: Exception) { exporting = null; renderExport("Couldn't start: ${e.message}") }
    }

    private fun shareFile(f: File, mime: String, saved: Uri?) {
        // copy into the share folder so only the chosen app can read it
        ShareProvider.clean(this, 3_600_000L)
        val dir = File(File(cacheDir, "share"), java.util.UUID.randomUUID().toString()).apply { mkdirs() }
        val copy = File(dir, f.name); f.copyTo(copy, true)
        val uri = Uri.parse("content://${ShareProvider.AUTHORITY}/${dir.name}/${Uri.encode(copy.name)}")
        val i = Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        if (exFormat == "sticker") {
            val tg = listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram").firstOrNull { pkg ->
                try { packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
            }
            if (tg != null) { try { startActivity(Intent(i).setPackage(tg)); return } catch (e: Exception) {} }
        }
        startActivity(Intent.createChooser(i, "Share"))
        @Suppress("UNUSED_VARIABLE") val unused = saved
    }
}
