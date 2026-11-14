package com.davacada.letra

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class LetraActivity : Activity() {
    lateinit var store: Store
    lateinit var root: FrameLayout
    private lateinit var screen: FrameLayout
    private var overlay: View? = null
    private var notes = mutableListOf<Note>()
    private var filter = "all"
    private var query = ""
    var editor: Editor? = null
    val main = Handler(Looper.getMainLooper())
    private var micThen: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        Thread { Words.load(this) }.start()
        window.setDecorFitsSystemWindows(false)
        root = FrameLayout(this).apply { setBackgroundColor(Look.SAND); foreground = Look.grain(this@LetraActivity) }
        screen = FrameLayout(this)
        root.addView(screen, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, ins ->
            val bars = ins.getInsets(WindowInsets.Type.systemBars())
            val ime = ins.getInsets(WindowInsets.Type.ime())
            screen.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            (overlay as? ViewGroup)?.getChildAt(0)?.let { it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, maxOf(bars.bottom, ime.bottom) + dp(12)) }
            ins
        }
        setContentView(root)
        home()
    }

    override fun onPause() {
        super.onPause()
        editor?.let { it.commit(); store.save(it.n) }
    }

    override fun onDestroy() { Click.stop(); editor?.release(); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (overlay != null) { closeSheet(); return }
        val e = editor
        if (e != null) { if (e.back()) return; closeEditor(); return }
        if (query.isNotEmpty()) { query = ""; home(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---------------------------------------------------------------- home

    fun home() {
        hideKeyboard()
        editor = null
        notes = store.all()
        screen.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.addView(col, FrameLayout.LayoutParams(-1, -1))

        // wordmark + search
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(22), dp(14), dp(10), dp(4)) }
        top.addView(wordmark(), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(iconButton(R.drawable.i_search, "Search") { openSearch() })
        col.addView(top)

        if (query.isNotEmpty() || searching) col.addView(searchBar())

        // filters
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(20), dp(10), dp(20), dp(10)) }
        listOf("all" to "All", Note.NOTE to "Notes", Note.LIST to "Lists", Note.SONG to "Songs", Note.SCRIPT to "Scripts").forEach { (k, l) ->
            val c = if (k == "all") Look.UMBER else Look.color(k)
            chips.addView(chip(l, c, filter == k, if (k == "all") null else Look.icon(k)) { filter = k; home() })
        }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        val shown = notes.filter { (filter == "all" || it.mode == filter) && (query.isBlank() || haystack(it).contains(query.lowercase())) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(110)) }
        if (shown.isEmpty()) list.addView(empty())
        var lastGroup = ""
        shown.forEach { n ->
            val g = group(n.updated)
            if (g != lastGroup) { list.addView(text(g.uppercase(), 11.5f, Look.SUB, true).apply { letterSpacing = 0.1f; setPadding(dp(6), dp(14), 0, dp(8)) }); lastGroup = g }
            list.addView(card(n))
        }
        col.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))

        // new
        val fab = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(18), 0, dp(22), 0)
            background = ripple(pill(Look.UMBER, dpf(30f)))
            elevation = dpf(6f)
            addView(ImageView(context).apply { setImageResource(R.drawable.i_add); setColorFilter(Look.SAND) }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(8) })
            addView(context.text("New", 16f, Look.SAND, true))
            setOnClickListener { newSheet() }
        }
        screen.addView(fab, FrameLayout.LayoutParams(-2, dp(58), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(20), dp(22)) })
    }

    private var searching = false
    private fun openSearch() { searching = true; home(); main.post { (screen.findViewWithTag<View>("search") as? EditText)?.let { it.requestFocus(); showKeyboard(it) } } }

    private fun searchBar(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(4), 0); background = pill(Look.CARD, dpf(24f)) }
        row.addView(ImageView(this).apply { setImageResource(R.drawable.i_search); setColorFilter(Look.SUB) }, LinearLayout.LayoutParams(dp(20), dp(20)))
        val f = EditText(this).apply {
            tag = "search"; hint = "Search words, lyrics, scenes…"; setText(query); setSelection(query.length)
            background = null; textSize = 16f; setTextColor(Look.UMBER); setHintTextColor(Look.FAINT); isSingleLine = true
            addTextChangedListener(after { s -> query = s; main.removeCallbacksAndMessages("q"); main.postAtTime({ refreshKeepSearch() }, "q", android.os.SystemClock.uptimeMillis() + 250) })
        }
        row.addView(f, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(iconButton(R.drawable.i_close, "Close search", Look.SUB, 40) { query = ""; searching = false; hideKeyboard(); home() })
        return FrameLayout(this).apply { setPadding(dp(16), dp(4), dp(16), 0); addView(row) }
    }

    private fun refreshKeepSearch() {
        val sel = (screen.findViewWithTag<View>("search") as? EditText)?.selectionStart ?: query.length
        home()
        (screen.findViewWithTag<View>("search") as? EditText)?.let { it.requestFocus(); it.setSelection(sel.coerceAtMost(it.text.length)) }
    }

    private fun haystack(n: Note) = (n.title + " " + when (n.mode) {
        Note.LIST -> n.items.joinToString(" ") { it.text }
        Note.SONG -> n.lines.joinToString(" ") { it.text } + " " + n.takes.joinToString(" ") { t -> t.heard.joinToString(" ") { it.text } }
        Note.SCRIPT -> n.els.joinToString(" ") { it.text }
        else -> Rich.plain(n.html)
    }).lowercase()

    fun wordmark(sizeSp: Float = 30f): TextView {
        val s = SpannableStringBuilder("Letra")
        val d = getDrawable(R.drawable.wordmark_l)!!
        s.setSpan(LogoLSpan(d), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text(s, sizeSp, Look.UMBER).apply { typeface = Typeface.create("serif", Typeface.BOLD); letterSpacing = -0.01f; contentDescription = "Letra" }
    }

    private fun group(t: Long): String {
        val c = Calendar.getInstance(); val now = Calendar.getInstance(); c.timeInMillis = t
        fun day(x: Calendar) = x.get(Calendar.YEAR) * 400 + x.get(Calendar.DAY_OF_YEAR)
        val d = day(now) - day(c)
        return when {
            d <= 0 -> "Today"; d == 1 -> "Yesterday"; d < 7 -> "This week"
            c.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> SimpleDateFormat("MMMM", Locale.getDefault()).format(Date(t))
            else -> SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(t))
        }
    }

    private fun empty() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(30), dp(70), dp(30), 0)
        addView(ImageView(context).apply { setImageResource(R.drawable.ic_glyph) }, LinearLayout.LayoutParams(dp(110), dp(110)))
        addView(context.text(if (query.isNotEmpty()) "Nothing matches “$query”" else "A blank page", 20f, Look.UMBER, true).apply { typeface = Typeface.create("serif", Typeface.BOLD); gravity = Gravity.CENTER })
        addView(context.text(if (query.isNotEmpty()) "Try another word." else "Tap New to write a note, a list, a song or a script.", 14.5f, Look.SUB).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
    }

    private fun card(n: Note): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(14), dp(16), dp(14))
            background = ripple(pill(Look.CARD, dpf(20f)), pill(Color.WHITE, dpf(20f)))
            setOnClickListener { open(n) }
            setOnLongClickListener { cardMenu(n); true }
        }
        val accent = Look.color(n.mode)
        c.addView(ImageView(this).apply {
            setImageResource(Look.icon(n.mode)); setColorFilter(accent)
            background = pill((accent and 0x00FFFFFF) or 0x1F000000, dpf(14f)); setPadding(dp(9), dp(9), dp(9), dp(9))
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(13) })
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(text(n.displayTitle(), 16.5f, Look.UMBER, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = Typeface.create("serif", Typeface.BOLD) })
        val prev = preview(n)
        if (prev.isNotBlank()) body.addView(text(prev, 13.5f, Look.SOFT).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0); setLineSpacing(0f, 1.1f)
            if (n.mode == Note.SCRIPT) typeface = Typeface.MONOSPACE
        })
        body.addView(text(meta(n), 11.5f, Look.SUB, true).apply { setPadding(0, dp(7), 0, 0) })
        c.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
        return FrameLayout(this).apply { setPadding(0, 0, 0, dp(10)); addView(c) }
    }

    private fun preview(n: Note) = when (n.mode) {
        Note.LIST -> n.items.take(4).joinToString("   ") { (if (it.done) "✓ " else "○ ") + it.text }
        Note.SONG -> n.lines.filter { !it.section && it.text.isNotBlank() }.take(2).joinToString(" / ") { it.text }
        Note.SCRIPT -> n.els.filter { it.text.isNotBlank() }.take(2).joinToString(" — ") { if (it.kind == Export.SCENE || it.kind == Export.CHARACTER) it.text.uppercase() else it.text }
        else -> Rich.plain(n.html).replace('\n', ' ').trim()
    }

    private fun meta(n: Note): String {
        val time = SimpleDateFormat(if (System.currentTimeMillis() - n.updated < 86_400_000L) "HH:mm" else "d MMM", Locale.getDefault()).format(Date(n.updated))
        val extra = when (n.mode) {
            Note.LIST -> "${n.items.count { it.done }}/${n.items.size} done"
            Note.SONG -> listOfNotNull(n.key.ifBlank { null }?.let { "Key $it" }, n.takes.size.takeIf { it > 0 }?.let { "$it take" + if (it > 1) "s" else "" }).joinToString(" · ")
            Note.SCRIPT -> n.els.count { it.kind == Export.SCENE }.let { "$it scene" + if (it != 1) "s" else "" }
            else -> Rich.plain(n.html).split(Regex("\\s+")).count { it.isNotBlank() }.let { "$it words" }
        }
        return listOf(Look.name(n.mode), extra, time).filter { it.isNotBlank() }.joinToString("  ·  ")
    }

    private fun cardMenu(n: Note) {
        sheet(n.displayTitle(), listOf(
            act(R.drawable.i_share, "Share…") { closeSheet(); shareSheet(n) },
            act(R.drawable.i_copy, "Duplicate") {
                closeSheet()
                val c = Note.from(n.json().put("id", java.util.UUID.randomUUID().toString()).put("title", n.displayTitle() + " (copy)").put("takes", org.json.JSONArray()))
                store.save(c); home()
            },
            act(R.drawable.i_trash, "Delete") { closeSheet(); confirmDelete(n) },
        ))
    }

    fun confirmDelete(n: Note) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("Delete “${n.displayTitle()}”?")
            .setMessage(if (n.takes.isNotEmpty()) "Its ${n.takes.size} recording(s) will be deleted too." else "This can't be undone.")
            .setPositiveButton("Delete") { _, _ -> editor?.release(); editor = null; store.delete(n); home() }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------------------------------------------------------------- new

    private fun newSheet() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val modes = listOf(
            Triple(Note.NOTE, "Note", "Thoughts, ideas, formatted text"),
            Triple(Note.LIST, "List", "Checklists, groceries, to-dos"),
            Triple(Note.SONG, "Song", "Lyrics, chords, rhymes, voice takes written out"),
            Triple(Note.SCRIPT, "Script", "Scenes, characters, dialogue"),
        )
        modes.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, (m, name, sub) ->
                val col = Look.color(m)
                val t = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(14), dp(16))
                    background = ripple(pill(Look.PAPER, dpf(20f)), pill(Color.WHITE, dpf(20f)))
                    addView(ImageView(context).apply { setImageResource(Look.icon(m)); setColorFilter(Look.SAND); background = pill(col, dpf(13f)); setPadding(dp(8), dp(8), dp(8), dp(8)) }, LinearLayout.LayoutParams(dp(40), dp(40)))
                    addView(context.text(name, 17f, Look.UMBER, true).apply { typeface = Typeface.create("serif", Typeface.BOLD); setPadding(0, dp(12), 0, dp(2)) })
                    addView(context.text(sub, 12.5f, Look.SUB).apply { setLineSpacing(0f, 1.1f) })
                    setOnClickListener { closeSheet(); create(m) }
                }
                row.addView(t, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i == 0) marginEnd = dp(10) })
            }
            box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        showSheet("New", box)
    }

    private fun create(mode: String) {
        val n = Note(mode = mode)
        when (mode) {
            Note.LIST -> n.items.add(Item("", false))
            Note.SONG -> { n.lines.add(SongLine(true, "Verse 1")); n.lines.add(SongLine(false, "")) }
            Note.SCRIPT -> n.els.add(El(Export.SCENE, ""))
        }
        open(n, fresh = true)
    }

    // ---------------------------------------------------------------- editor

    fun open(n: Note, fresh: Boolean = false) {
        searching = false
        screen.removeAllViews()
        val e = when (n.mode) { Note.LIST -> ListEditor(this, n); Note.SONG -> SongEditor(this, n); Note.SCRIPT -> ScriptEditor(this, n); else -> NoteEditor(this, n) }
        editor = e
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // top bar
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(6), dp(8), 0) }
        bar.addView(iconButton(R.drawable.i_back, "Back") { onBackPressed() })
        val accent = Look.color(n.mode)
        bar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(12), 0)
            background = pill((accent and 0x00FFFFFF) or 0x1F000000, dpf(15f))
            addView(ImageView(context).apply { setImageResource(Look.icon(n.mode)); setColorFilter(accent) }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) })
            addView(context.text(Look.name(n.mode), 13f, accent, true))
        }, LinearLayout.LayoutParams(-2, dp(30)))
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        e.topActions().forEach { bar.addView(it) }
        bar.addView(iconButton(R.drawable.i_share, "Share") { e.commit(); store.save(n); shareSheet(n) })
        bar.addView(iconButton(R.drawable.i_trash, "Delete") { confirmDelete(n) })
        col.addView(bar)

        val title = EditText(this).apply {
            setText(n.title); hint = n.displayTitle(); background = null
            setTextColor(Look.UMBER); setHintTextColor(Look.FAINT); textSize = 27f
            typeface = Typeface.create("serif", Typeface.BOLD)
            setPadding(dp(22), dp(10), dp(22), dp(4))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            addTextChangedListener(after { s -> n.title = s; e.dirty() })
            setOnEditorActionListener { _, _, _ -> e.focusFirst(); true }
        }
        col.addView(title)
        e.header()?.let { col.addView(it) }
        col.addView(e.view(), LinearLayout.LayoutParams(-1, 0, 1f))
        e.tools()?.let { t ->
            col.addView(View(this).apply { setBackgroundColor(Look.LINE) }, LinearLayout.LayoutParams(-1, 1))
            col.addView(t)
        }
        screen.addView(col, FrameLayout.LayoutParams(-1, -1))
        if (fresh) main.postDelayed({ if (n.mode == Note.NOTE || n.mode == Note.LIST) e.focusFirst() else { title.requestFocus(); showKeyboard(title) } }, 120)
    }

    fun closeEditor() {
        val e = editor ?: return home()
        e.commit(); e.release()
        if (e.n.isEmpty()) store.delete(e.n) else store.save(e.n)
        editor = null
        home()
    }

    private fun Note.isEmpty() = title.isBlank() && takes.isEmpty() && when (mode) {
        Note.LIST -> items.all { it.text.isBlank() }
        Note.SONG -> lines.all { it.section || it.text.isBlank() }
        Note.SCRIPT -> els.all { it.text.isBlank() }
        else -> Rich.plain(html).isBlank()
    }

    // ---------------------------------------------------------------- share

    fun shareSheet(n: Note) {
        val t = n.displayTitle()
        val opts = when (n.mode) {
            Note.SONG -> buildList {
                add(act(R.drawable.i_pdf, "Chord sheet (PDF)") { Export.send(this@LetraActivity, Export.songPdf(this@LetraActivity, n), "application/pdf", t) })
                add(act(R.drawable.i_chord, "Lyrics with chords (text)") { Export.sendText(this@LetraActivity, Export.chordSheet(n), t) })
                add(act(R.drawable.i_text, "Lyrics only") { Export.sendText(this@LetraActivity, Export.lyrics(n), t) })
                n.takes.forEach { tk -> add(act(R.drawable.i_audio, "Recording: ${tk.name} (${fmtTime(tk.durationMs)})") { Export.take(this@LetraActivity, n, tk, store.recDir)?.let { Export.send(this@LetraActivity, it, "audio/wav", "$t – ${tk.name}") } }) }
                add(act(R.drawable.i_file, "Letra file (to open in Letra)") { Export.send(this@LetraActivity, Export.letraFile(this@LetraActivity, n), "application/json", t) })
            }
            Note.SCRIPT -> listOf(
                act(R.drawable.i_pdf, "Screenplay (PDF)") { Export.send(this, Export.scriptPdf(this, n), "application/pdf", t) },
                act(R.drawable.i_file, "Fountain (.fountain)") { Export.send(this, Export.textFile(this, n, Export.fountain(n), "fountain"), "text/plain", t) },
                act(R.drawable.i_text, "Plain text") { Export.sendText(this, Export.scriptText(n), t) },
                act(R.drawable.i_list, "Scene list") { Export.sendText(this, Export.sceneList(n), "$t – scenes") },
            )
            Note.LIST -> listOf(
                act(R.drawable.i_text, "Text") { Export.sendText(this, Export.listText(n), t) },
                act(R.drawable.i_check, "Only what's left to do") { Export.sendText(this, t + "\n\n" + n.items.filter { !it.done && it.text.isNotBlank() }.joinToString("\n") { "☐ " + it.text }, t) },
                act(R.drawable.i_pdf, "PDF") { Export.send(this, Export.plainPdf(this, n, Export.listText(n).substringAfter("\n\n")), "application/pdf", t) },
            )
            else -> listOf(
                act(R.drawable.i_text, "Text") { Export.sendText(this, Export.noteText(n), t) },
                act(R.drawable.i_pdf, "PDF") { Export.send(this, Export.plainPdf(this, n, Rich.plain(n.html)), "application/pdf", t) },
                act(R.drawable.i_copy, "Copy to clipboard") {
                    (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText(t, Export.noteText(n)))
                },
            )
        }
        sheet("Share “$t”", opts.map { (i, l, f) -> act(i, l) { closeSheet(); try { f() } catch (e: Exception) { toast("Couldn't share: ${e.message}") } } })
    }

    // ---------------------------------------------------------------- sheets & helpers

    fun sheet(title: String, rows: List<Triple<Int, String, () -> Unit>>) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rows.forEach { (icon, label, act) ->
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0)
                background = ripple(null, pill(Color.WHITE, dpf(14f)))
                addView(ImageView(context).apply { setImageResource(icon); setColorFilter(Look.UMBER); background = pill(Look.TINT, dpf(12f)); setPadding(dp(8), dp(8), dp(8), dp(8)) }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(14) })
                addView(context.text(label, 15.5f, Look.UMBER))
                setOnClickListener { act() }
            }, LinearLayout.LayoutParams(-1, dp(54)))
        }
        showSheet(title, box)
    }

    /** A sheet that slides up from the bottom over a dimmed screen. */
    fun showSheet(title: String?, content: View, onClose: (() -> Unit)? = null) {
        closeSheet()
        hideKeyboard()
        val dim = FrameLayout(this).apply { setBackgroundColor(0x663D2F23); isClickable = true; setOnClickListener { closeSheet() } }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; isClickable = true
            setPadding(dp(18), dp(10), dp(18), dp(24))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Look.SHEET); cornerRadii = floatArrayOf(dpf(26f), dpf(26f), dpf(26f), dpf(26f), 0f, 0f, 0f, 0f) }
            foreground = Look.grain(this@LetraActivity); clipToOutline = true
        }
        card.addView(View(this).apply { background = pill(0x334A3624, 9f) }, LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
        if (title != null) card.addView(text(title, 18f, Look.UMBER, true).apply { typeface = Typeface.create("serif", Typeface.BOLD); maxLines = 2; setPadding(dp(6), 0, dp(6), dp(12)) })
        card.addView(content)
        dim.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        dim.tag = onClose
        root.addView(dim, FrameLayout.LayoutParams(-1, -1))
        overlay = dim
        root.requestApplyInsets()
        card.translationY = dpf(400f)
        card.animate().translationY(0f).setDuration(220).start()
    }

    fun closeSheet() {
        val o = overlay ?: return
        overlay = null
        @Suppress("UNCHECKED_CAST") (o.tag as? (() -> Unit))?.invoke()
        root.removeView(o)
    }

    fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    fun showKeyboard(v: View) { v.requestFocus(); (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, 0) }
    fun hideKeyboard() { currentFocus?.let { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0) } }

    fun withMic(then: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) then()
        else { micThen = then; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) micThen?.invoke()
            else toast("Letra needs the microphone to record")
            micThen = null
        }
    }
}

fun act(icon: Int, label: String, f: () -> Unit) = Triple(icon, label, f)

fun after(f: (String) -> Unit) = object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun afterTextChanged(s: Editable?) { f(s?.toString() ?: "") }
}
