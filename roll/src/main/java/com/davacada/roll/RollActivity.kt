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
    }

    override fun onPause() { super.onPause(); player?.pause(); project?.let { store.save(it) }; if (project != null && ::glView.isInitialized) glView.onPause() }
    override fun onResume() { super.onResume(); if (project != null && ::glView.isInitialized) glView.onResume() }
    override fun onDestroy() { releasePlayer(); exporting?.cancel?.invoke(); bg.shutdownNow(); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            sheetView != null -> closeSheet()
            exportView != null -> { if (exporting == null) closeExport() else toast("Exporting · wait or tap Cancel") }
            panelOpen -> closePanel(false)
            project != null -> { store.save(project!!); releasePlayer(); project = null; home() }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

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
            bg.execute {
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
        col.addView(action(R.drawable.i_speed, "Speed", "${c.speed}×") { closeSheet(); speedMenu(i) })
        col.addView(action(R.drawable.i_volume, "Volume", "${(c.volume * 100).toInt()}%") { closeSheet(); volumeMenu(i) })
        col.addView(action(R.drawable.i_copy, "Duplicate") { closeSheet(); duplicate(i) })
        col.addView(action(R.drawable.i_trash, "Delete", null, 0xFFFF6B5E.toInt()) { closeSheet(); deleteClip(i) })
        sheet(col)
    }

    /** Frame shapes drawn as their own outlines. */
    private fun aspectMenu() {
        val p = project ?: return
        val opts = listOf(Triple("original", "Original", 0f), Triple("9:16", "9:16", 9f / 16f), Triple("4:5", "4:5", 0.8f), Triple("1:1", "1:1", 1f), Triple("16:9", "16:9", 16f / 9f))
        val col = vcol()
        col.addView(headline("Frame", "The shape of the finished video"))
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
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
                setOnClickListener { snapshot(); p.aspect = k; store.save(p); previewBox.requestLayout(); applyGrade(); closeSheet() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        col.addView(r)
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
            addCategory(Intent.CATEGORY_OPENABLE); type = "video/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
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
        val uris = ArrayList<Uri>()
        data.clipData?.let { cd -> for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri) } ?: data.data?.let { uris.add(it) }
        val p = addingTo ?: return
        addingTo = null
        toast("Reading ${uris.size} clip${if (uris.size == 1) "" else "s"}…")
        bg.execute {
            val clips = uris.mapNotNull { u ->
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
                if (clips.isEmpty()) { toast("Couldn't read those videos"); return@post }
                if (project === p) { snapshot(); val at = (timeline?.selected ?: -1).let { if (it >= 0) it + 1 else p.clips.size }; p.clips.addAll(at, clips); store.save(p); reloadPlayer(keepTime = true); refresh() }
                else { p.clips.addAll(clips); store.save(p); open(p) }
            }
        }
    }

    private fun importLut(u: Uri) {
        bg.execute {
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

    private fun open(p: Project) {
        project = p; undo.clear(); redo.clear(); panelOpen = false
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
        glView = GlPreview(this, store.lutDir)
        glView.onSurface = { sf ->
            videoSurface = sf
            player?.let { pl -> pl.setVideoSurface(sf); if (!pl.isPlaying) pl.seekTo(pl.currentMediaItemIndex, pl.currentPosition) }
        }
        previewBox.addView(glView, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        lookTag = text("", 10.5f, C.AMBER, true).apply { letterSpacing = 0.12f; setPadding(dp(8), dp(3), dp(8), dp(3)); background = pill(0x73000000, dpf(6f)) }
        previewBox.addView(lookTag, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(10), 0, 0) })
        // hold the picture to see it without the look
        previewBox.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { main.postDelayed(showOriginal, 250); true }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(showOriginal)
                    if (showingOriginal) { showingOriginal = false; glView.bypass = false; updateLookTag() } else togglePlay(); true
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
        col.addView(bottom, LinearLayout.LayoutParams(-1, dp(272)))
        screen.addView(col, FrameLayout.LayoutParams(-1, -1))
        editBottom()
        setupPlayer()
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
        col.addView(tl, LinearLayout.LayoutParams(-1, dp(118)))
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
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { clipParams() }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playBtn?.setImageResource(if (isPlaying) R.drawable.i_pause else R.drawable.i_play); if (isPlaying) main.post(ticker) }
            override fun onPlayerError(error: PlaybackException) { toast("Playback: ${error.errorCodeName}") }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) { videoRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height; project?.let { glView.setRatios(videoRatio, Render.ratio(it)) } }
            }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) { pl.pause(); pl.seekTo(0, 0) } }
        })
        loadItems(); pl.prepare()
        updateLookTag(); refreshTime()
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
    }

    private fun reloadPlayer(keepTime: Boolean) {
        val t = timeline?.time() ?: 0L
        loadItems(); player?.prepare()
        if (keepTime) seekTimeline(t.coerceAtMost(maxOf(0, (project?.lengthMs ?: 0) - 1)))
        timeline?.invalidate(); refreshTime()
    }

    private fun releasePlayer() {
        main.removeCallbacks(ticker); player?.release(); player = null; timeline?.release(); timeline = null
        if (::glView.isInitialized) glView.release(); videoSurface = null
    }

    private fun seekTimeline(t: Long) {
        val p = project ?: return; val pl = player ?: return
        if (p.clips.isEmpty()) return
        val (i, off) = p.at(t)
        pl.seekTo(i, (off * p.clips[i].speed).toLong())
        timeline?.setTime(t); refreshTime()
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
            timeline?.setTime(timelineTime()); refreshTime()
            main.postDelayed(this, 33)
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

    private fun refresh() { timeline?.invalidate(); editBottomTools(); refreshTime(); previewBox.requestLayout() }

    // ---------------------------------------------------------------- grade

    /** The grade shows on the viewer straight away (drawn by GlPreview), paused or playing. */
    private fun applyGrade() {
        val p = project ?: return
        if (::glView.isInitialized) { glView.setGrade(p.grade); glView.setRatios(videoRatio, Render.ratio(p)) }
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
        p.clips.clear(); p.clips.addAll(q.clips); p.grade = q.grade; p.aspect = q.aspect; p.mute = q.mute; p.name = q.name
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
        val b = c.copy(); b.startMs = cut; c.endMs = cut
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
        listOf(Looks.FILM, Looks.CAMERA, Looks.BW, "LUTs").forEach { t ->
            val on = if (lutTab) t == "LUTs" else t == tab
            tabs.addView(word(t, on, 13f) { if (t == "LUTs") filmPanel(tab, lutTab = true) else filmPanel(t) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(14) })
        }
        col.addView(tabs)
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
        } else col.addView(text(if (lutTab) "Your own .cube LUTs (from Resolve, Premiere, …) show up here." else "Pick a look. Hold the picture to compare with the original.", 12.5f, C.SUB).apply { setPadding(dp(20), dp(10), dp(20), 0) }, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(panelFooter(), LinearLayout.LayoutParams(-1, dp(46)))
        openPanel(col)
    }

    /** A look shown on a frame of your own video. */
    private fun lookTile(l: Looks.Look?, name: String, on: Boolean, lut: String? = null, onClick: () -> Unit): View = LinearLayout(this).apply {
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
        bg.execute {
            val s = sample ?: project?.let { p -> p.clips.getOrNull(p.at(timeline?.time() ?: 0).first)?.let { c -> frameAt(c, c.startMs + 500, 180) } }?.also { sample = it }
            if (s != null) {
                val g = Grade(look = when { l != null -> l.id; lut != null -> "lut:$lut"; else -> "none" })
                val bm = gradePreview(s, g)
                main.post { img.setImageBitmap(bm) }
            }
        }
    }

    /** Adjust, laid out like Lightroom: Light, Color and Effects, each a set of fine sliders. */
    private var adjTab = "light"
    private var mixColor = 0
    private fun adjustPanel() {
        val p = project ?: return
        val g = p.grade
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(14), dp(2)) }
        listOf(Triple("light", R.drawable.i_sun, "Light"), Triple("color", R.drawable.i_drop, "Color"), Triple("mix", R.drawable.i_filter, "Mix"), Triple("effects", R.drawable.i_fx, "Effects")).forEach { (k, ic, l) ->
            tabs.addView(bare(ic, l, if (adjTab == k) C.AMBER else C.SUB, 20) { adjTab = k; val keep = gradeBefore; panelOpen = false; adjustPanel(); gradeBefore = keep })
        }
        tabs.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        tabs.addView(word("Reset", false, 13f) {
            when (adjTab) {
                "light" -> { g.exposure = 0f; g.contrast = 0f; g.highlights = 0f; g.shadows = 0f; g.whites = 0f; g.blacks = 0f }
                "color" -> { g.warmth = 0f; g.tint = 0f; g.vibrance = 0f; g.saturation = 0f }
                "mix" -> java.util.Arrays.fill(g.mix, 0f)
                else -> { g.grain = -1f; g.vignette = -1f }
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
            }
            "color" -> {
                add("Temp", g.warmth, -1f, 1f, intArrayOf(0xFF4A86D9.toInt(), 0xFFB7B7B7.toInt(), 0xFFE8C23A.toInt()), pm) { g.warmth = it }
                add("Tint", g.tint, -1f, 1f, intArrayOf(0xFF4DBB5F.toInt(), 0xFFB7B7B7.toInt(), 0xFFD24FC4.toInt()), pm) { g.tint = it }
                add("Vibrance", g.vibrance, -1f, 1f, null, pm) { g.vibrance = it }
                add("Saturation", g.saturation, -1f, 1f, intArrayOf(0xFF8A8A8A.toInt(), 0xFFFF5A3C.toInt(), 0xFFFFC23C.toInt(), 0xFF3CC86E.toInt(), 0xFF3C8CFF.toInt(), 0xFFB45AFF.toInt()), pm) { g.saturation = it }
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
            else -> {
                add("Grain", Looks.grainOf(g), 0f, 1f, null, { "${(it * 100).toInt()}" }) { g.grain = it }
                add("Vignette", Looks.vignetteOf(g), 0f, 1f, null, { "${(it * 100).toInt()}" }) { g.vignette = it }
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
        bg.execute {
            val p = project ?: return@execute
            val s0 = sample ?: p.clips.getOrNull(p.at(timeline?.time() ?: 0).first)?.let { c -> frameAt(c, c.startMs + 500, 180) }?.also { sample = it } ?: return@execute
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
        exPreview?.let { frame.setImageBitmap(it) } ?: bg.execute {
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

    private fun startExport() {
        val p = project ?: return
        store.save(p)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safe = p.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "Roll" }
        try {
            when (exFormat) {
                "video" -> {
                    val (w, h) = Render.size(p, exShort)
                    val out = File(outDir(), "$safe-$stamp.mp4")
                    val comp = Render.composition(this, p, p.clips, w, h, exFps, !p.mute, store.lutDir)
                    exporting = Render.export(this, comp, out, MimeTypes.VIDEO_H264, videoBitrate(w, h), { pr -> renderExport("Exporting…", pr) }) { err ->
                        exporting = null
                        if (err != null) renderExport("Couldn't export: $err")
                        else { val u = Render.saveToGallery(this, out, out.name, "video/mp4"); renderExport("Saved to Movies/Roll", 100, out, "video/mp4", u) }
                    }
                    renderExport("Exporting…", 0)
                }
                "gif" -> {
                    val (a, b) = gifRange(p); val (w, h) = gifSize(p)
                    val tmp = File(outDir(), "gif-src.mp4"); val out = File(outDir(), "$safe-$stamp.gif")
                    val comp = Render.composition(this, p, Render.range(p, a, b), w, h, gifFps, false, store.lutDir)
                    exporting = Render.export(this, comp, tmp, MimeTypes.VIDEO_H264, 6_000_000, { pr -> renderExport("Rendering frames…", pr / 2) }) { err ->
                        if (err != null) { exporting = null; renderExport("Couldn't export: $err"); return@export }
                        renderExport("Making the GIF…", 50)
                        bg.execute {
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
                    val comp = Render.composition(this, p, Render.range(p, t0, minOf(p.lengthMs, t0 + 2990)), w, h, stFps, false, store.lutDir)
                    exporting = Render.export(this, comp, tmp, MimeTypes.VIDEO_H264, 8_000_000, { pr -> renderExport("Rendering…", pr / 2) }) { err ->
                        if (err != null) { exporting = null; renderExport("Couldn't export: $err"); return@export }
                        renderExport("Encoding VP9…", 60)
                        bg.execute {
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
