package com.davacada.letra

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** A lyric line that draws its chords above the words, exactly over the letters they belong to. */
@SuppressLint("ViewConstructor")
class LyricEdit(ctx: Context, val line: SongLine) : LineEdit(ctx) {
    var chordMode = false
        set(v) { field = v; layoutChords() }
    var onChordTap: ((chord: Chord?, pos: Int) -> Unit)? = null
    private val band = ctx.dpf(19f)
    private val cp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); textSize = ctx.dpf(13.5f); color = Look.TERRACOTTA }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ctx.dpf(12f); color = 0x55B05E26 }
    private var downX = 0f; private var downY = 0f; private var downT = 0L

    init {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (quiet) return
                line.chords.forEach { c ->
                    if (c.pos >= start + count) c.pos += after - count
                    else if (c.pos > start) c.pos = start
                }
            }
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {}
        })
        layoutChords()
    }

    fun layoutChords() {
        val show = line.chords.isNotEmpty() || chordMode
        setLineSpacing(if (show) band else 0f, 1f)
        setPadding(paddingLeft, if (show) band.toInt() + context.dp(6) else context.dp(6), paddingRight, context.dp(6))
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val l = layout ?: return
        val show = line.chords.isNotEmpty() || chordMode
        if (!show) return
        var lastLine = -1; var lastEnd = 0f
        val sp = paint.measureText(" ")
        line.chords.sortedBy { it.pos }.forEach { ch ->
            val p = ch.pos.coerceIn(0, text.length)
            val ln = l.getLineForOffset(p)
            var x = compoundPaddingLeft + l.getPrimaryHorizontal(p) + if (ch.pos > text.length) (ch.pos - text.length) * sp else 0f
            if (ln == lastLine) x = maxOf(x, lastEnd + context.dpf(5f)) else lastEnd = 0f
            val y = extendedPaddingTop + l.getLineTop(ln) - context.dpf(4f)
            c.drawText(ch.name, x, y, cp)
            lastLine = ln; lastEnd = x + cp.measureText(ch.name)
        }
        if (chordMode && line.chords.isEmpty()) c.drawText("tap a word to put a chord over it", compoundPaddingLeft.toFloat(), extendedPaddingTop - context.dpf(5f), hint)
    }

    /** In chord mode a tap places/edits a chord; otherwise only taps on an existing chord are taken. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val l = layout
        val show = line.chords.isNotEmpty() || chordMode
        if (l == null || !show) return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; downT = SystemClock.uptimeMillis() }
            MotionEvent.ACTION_UP -> {
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (abs(e.x - downX) < slop && abs(e.y - downY) < slop && SystemClock.uptimeMillis() - downT < 400) {
                    val ly = e.y - extendedPaddingTop + scrollY
                    // which text line's chord band was touched?
                    val inBandLine = when {
                        ly < 0 -> 0
                        else -> { val ln = l.getLineForVertical(ly.toInt()); if (ly > l.getLineBottom(ln) - band && ln + 1 < l.lineCount) ln + 1 else if (ly > l.getLineBottom(ln) - band && ln + 1 >= l.lineCount && chordMode) -1 else -1 }
                    }
                    val lx = e.x - compoundPaddingLeft
                    val hitLine = if (inBandLine >= 0) inBandLine else l.getLineForVertical(ly.toInt().coerceAtLeast(0))
                    val off = l.getOffsetForHorizontal(hitLine, lx)
                    val near = line.chords.minByOrNull { ch -> abs(l.getPrimaryHorizontal(ch.pos.coerceIn(0, text.length)) - lx) + if (l.getLineForOffset(ch.pos.coerceIn(0, text.length)) == hitLine) 0f else 9999f }
                    val nearOk = near != null && abs(l.getPrimaryHorizontal(near.pos.coerceIn(0, text.length)) - lx) < context.dpf(26f) && l.getLineForOffset(near.pos.coerceIn(0, text.length)) == hitLine
                    if (inBandLine >= 0 && nearOk) { onChordTap?.invoke(near, near!!.pos); return true }
                    if (chordMode) {
                        // snap to the start of the word that was tapped
                        var p = off
                        while (p > 0 && p <= text.length && !text[p - 1].isWhitespace()) p--
                        onChordTap?.invoke(if (nearOk) near else null, p)
                        return true
                    }
                }
            }
        }
        if (chordMode) return true
        return super.onTouchEvent(e)
    }
}

class SongEditor(a: LetraActivity, n: Note) : Editor(a, n) {
    private val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(14), a.dp(2), a.dp(12), a.dp(80)) }
    private lateinit var scroll: ScrollView
    private val edits = LinkedHashMap<SongLine, LineEdit>()
    private val syl = HashMap<SongLine, TextView>()
    private var chordMode = false
    private var rhymesOn = false
    private var focused: SongLine? = null
    private lateinit var rhymeStrip: LinearLayout
    private lateinit var rhymeRow: LinearLayout
    private lateinit var rhymeLabel: TextView
    private lateinit var metaRow: LinearLayout
    private var toolsBox: LinearLayout? = null
    private var player: MediaPlayer? = null
    private var playing: Take? = null
    private val playTexts = HashMap<Take, TextView>()
    private var rec: Recorder? = null
    private var recView: View? = null
    private var recFinish: (() -> Unit)? = null
    private val RHYME_COLORS = intArrayOf(0xFFB05E26.toInt(), 0xFF3E5F8F.toInt(), 0xFF5A7A3A.toInt(), 0xFF8E4A7A.toInt(), 0xFFC4952A.toInt(), 0xFF2F7F7A.toInt())

    override fun view(): View {
        render()
        scroll = ScrollView(a).apply { isVerticalScrollBarEnabled = false; addView(box) }
        return scroll
    }

    // ---------------------------------------------------------------- header: key, tempo, capo

    override fun header(): View {
        metaRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(a.dp(20), a.dp(2), a.dp(20), a.dp(8)) }
        renderMeta()
        return HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(metaRow) }
    }

    private fun renderMeta() {
        metaRow.removeAllViews()
        metaRow.addView(a.chip(if (n.key.isBlank()) "Key" else "Key ${n.key}", Look.TERRACOTTA, false, R.drawable.i_chord) { keySheet() })
        metaRow.addView(a.chip(if (n.bpm > 0) "${n.bpm} bpm" else "Tempo", Look.TERRACOTTA, false, R.drawable.i_metro) { tempoSheet() })
        metaRow.addView(a.chip(if (n.capo > 0) "Capo ${n.capo}" else "Capo", Look.TERRACOTTA) { capoSheet() })
    }

    // ---------------------------------------------------------------- lines

    private fun render(focus: SongLine? = null, at: Int = -1) {
        box.removeAllViews(); edits.clear(); syl.clear(); playTexts.clear()
        if (n.lines.isEmpty()) n.lines.add(SongLine(false, ""))
        n.lines.forEach { l -> box.addView(if (l.section) sectionRow(l) else lyricRow(l)) }
        box.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, a.dp(14), 0, 0)
            addView(a.chip("Add section", Look.TERRACOTTA, false, R.drawable.i_section) { addSection(n.lines.lastOrNull()) })
        })
        takes()
        refreshMarks()
        focus?.let { f -> edits[f]?.let { e -> a.main.post { e.requestFocus(); e.setSelection(if (at < 0) e.text.length else at.coerceAtMost(e.text.length)); a.showKeyboard(e) } } }
    }

    private fun sectionRow(l: SongLine): View {
        val e = LineEdit(a).apply {
            setQuiet(l.text); hint = "Section"
            textSize = 12.5f; letterSpacing = 0.12f; setTextColor(Look.TERRACOTTA); setHintTextColor(0x88B05E26.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setPadding(a.dp(12), a.dp(5), a.dp(12), a.dp(5))
            background = pill(0x1FB05E26, a.dpf(13f))
        }
        wire(e, l)
        edits[l] = e
        return LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(a.dp(6), a.dp(18), 0, a.dp(4))
            addView(e, LinearLayout.LayoutParams(-2, -2))
        }
    }

    private fun lyricRow(l: SongLine): View {
        val e = LyricEdit(a, l).apply {
            setQuiet(l.text); hint = "…"
            textSize = 18.5f; typeface = Typeface.create("serif", Typeface.NORMAL)
            setPadding(a.dp(8), paddingTop, a.dp(4), paddingBottom)
            this.chordMode = this@SongEditor.chordMode
            onChordTap = { ch, pos -> chordSheet(l, ch, pos) }
            layoutChords()
        }
        wire(e, l)
        edits[l] = e
        val s = a.text("", 11.5f, Look.FAINT, true).apply { gravity = Gravity.END or Gravity.BOTTOM; setPadding(0, 0, a.dp(2), a.dp(12)); compoundDrawablePadding = a.dp(5) }
        syl[l] = s
        return LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            addView(e, LinearLayout.LayoutParams(0, -2, 1f))
            addView(s, LinearLayout.LayoutParams(a.dp(38), -1))
        }
    }

    private fun wire(e: LineEdit, l: SongLine) {
        e.addTextChangedListener(after { s -> if (!s.contains('\n')) { l.text = s; dirty(); marksSoon() } })
        e.setOnFocusChangeListener { _, has -> if (has) { focused = l; if (rhymesOn) updateRhymes() } }
        e.onSplit = { before, after ->
            val cut = before.length + 1
            l.text = before
            val moved = l.chords.filter { it.pos >= cut }.onEach { it.pos -= cut }
            l.chords.removeAll(moved)
            var idx = n.lines.indexOf(l); var last = l
            after.forEachIndexed { i, t ->
                val nl = SongLine(false, t)
                if (i == 0) nl.chords.addAll(moved)
                n.lines.add(++idx, nl); last = nl
            }
            dirty(); render(last, if (after.size == 1) 0 else -1)
        }
        e.onBackAtStart = {
            val i = n.lines.indexOf(l)
            val prev = if (i > 0) n.lines[i - 1] else null
            when {
                prev == null -> false
                l.section && l.text.isEmpty() -> { n.lines.removeAt(i); dirty(); render(prev); true }
                prev.section && !l.section && e.text.isEmpty() -> { n.lines.removeAt(i); dirty(); render(prev); true }
                prev.section -> false
                !l.section -> {
                    val shift = prev.text.length
                    prev.text += e.text.toString()
                    l.chords.forEach { prev.chords.add(Chord(it.pos + shift, it.name)) }
                    n.lines.removeAt(i); dirty(); render(prev, shift); true
                }
                else -> false
            }
        }
    }

    private val marksRun = Runnable { refreshMarks(); if (rhymesOn) updateRhymes() }
    private fun marksSoon() { a.main.removeCallbacks(marksRun); a.main.postDelayed(marksRun, 350) }

    /** Syllable counts on the right, and a coloured dot for lines whose endings rhyme (within each section). */
    private fun refreshMarks() {
        var sec = mutableListOf<SongLine>()
        val sections = mutableListOf<List<SongLine>>()
        n.lines.forEach { if (it.section) { sections.add(sec); sec = mutableListOf() } else sec.add(it) }
        sections.add(sec)
        sections.forEach { lines ->
            val g = Words.rhymeGroups(lines.map { it.text })
            lines.forEachIndexed { i, l ->
                val tv = syl[l] ?: return@forEachIndexed
                tv.text = if (l.text.isBlank()) "" else Words.syllables(l.text).toString()
                val grp = g[i]
                tv.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, if (grp != null) dot(RHYME_COLORS[grp % RHYME_COLORS.size]) else null, null)
            }
        }
    }

    private fun dot(c: Int) = pill(c, 99f).apply { setSize(a.dp(7), a.dp(7)) }

    private fun currentEdit(): LineEdit? = focused?.let { edits[it] }

    private fun addSection(after: SongLine?, name: String? = null) {
        val label = name ?: run {
            val count = n.lines.count { it.section && it.text.startsWith("Verse") }
            if (n.lines.none { it.section && it.text.startsWith("Chorus") } && count >= 1) "Chorus" else "Verse ${count + 1}"
        }
        val i = if (after == null) n.lines.size else n.lines.indexOf(after) + 1
        val s = SongLine(true, label); val l = SongLine(false, "")
        n.lines.add(i, s); n.lines.add(i + 1, l)
        dirty(); render(l, 0)
    }

    private fun sectionSheet() {
        val box = flow()
        listOf("Verse", "Pre-chorus", "Chorus", "Bridge", "Hook", "Intro", "Outro", "Refrain", "Estribillo", "Coro", "Puente").forEach { s ->
            box.addView(a.chip(s, Look.TERRACOTTA) {
                a.closeSheet()
                val label = if (s == "Verse") "Verse ${n.lines.count { it.section && it.text.startsWith("Verse") } + 1}" else s
                addSection(focused ?: n.lines.lastOrNull(), label)
            }.apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = a.dp(8) })
        }
        // copy an existing section (e.g. repeat the chorus)
        val existing = n.lines.filter { it.section && it.text.isNotBlank() }.map { it.text }.distinct()
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(box)
        if (existing.isNotEmpty()) {
            col.addView(a.text("REPEAT A SECTION", 11f, Look.SUB, true).apply { letterSpacing = 0.1f; setPadding(a.dp(4), a.dp(10), 0, a.dp(8)) })
            val rep = flow()
            existing.forEach { name -> rep.addView(a.chip("+ $name", Look.UMBER) { a.closeSheet(); repeatSection(name) }.apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = a.dp(8) }) }
            col.addView(rep)
        }
        a.showSheet("Add a section", col)
    }

    private fun repeatSection(name: String) {
        val start = n.lines.indexOfFirst { it.section && it.text == name }
        if (start < 0) return
        val body = n.lines.drop(start + 1).takeWhile { !it.section }
        val at = focused?.let { f -> var i = n.lines.indexOf(f) + 1; while (i < n.lines.size && !n.lines[i].section) i++; i } ?: n.lines.size
        val copy = listOf(SongLine(true, name)) + body.map { SongLine(false, it.text, it.chords.map { c -> Chord(c.pos, c.name) }.toMutableList()) }
        n.lines.addAll(at, copy)
        dirty(); render()
    }

    /** A wrapping row of chips. */
    private fun flow(): LinearLayout = FlowLayout(a)

    // ---------------------------------------------------------------- tools

    override fun tools(): View {
        rhymeLabel = a.text("", 12f, Look.SUB, true).apply { setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(2)) }
        rhymeRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(a.dp(12), a.dp(4), a.dp(12), a.dp(8)) }
        rhymeStrip = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; setBackgroundColor(Look.SHEET)
            addView(rhymeLabel)
            addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(rhymeRow) })
        }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        toolsBox = col
        renderTools()
        return col
    }

    private fun renderTools() {
        val col = toolsBox ?: return
        col.removeAllViews()
        col.addView(rhymeStrip)
        col.addView(toolbar(
            a.tool(R.drawable.i_section, "Section", Look.TERRACOTTA) { sectionSheet() },
            a.tool(R.drawable.i_chord, "Chords", Look.TERRACOTTA, chordMode) { chordMode = !chordMode; edits.values.forEach { (it as? LyricEdit)?.chordMode = chordMode }; if (chordMode) { a.hideKeyboard(); a.toast("Tap a word to put a chord over it") }; renderTools() },
            a.tool(R.drawable.i_rhyme, "Rhymes", Look.TERRACOTTA, rhymesOn) { rhymesOn = !rhymesOn; rhymeStrip.visibility = if (rhymesOn) View.VISIBLE else View.GONE; if (rhymesOn) updateRhymes(); renderTools() },
            a.tool(R.drawable.i_metro, if (Click.running) "${n.bpm.takeIf { it > 0 } ?: 90} bpm" else "Click", Look.TERRACOTTA, Click.running) { toggleClick() },
            a.tool(R.drawable.i_mic, "Record", Look.RED) { recordChoice() },
        ))
    }

    private fun toggleClick() {
        if (Click.running) Click.stop() else { if (n.bpm <= 0) n.bpm = 90; Click.start(n.bpm); renderMeta() }
        renderTools()
    }

    /** Rhymes for the last word of the line above (or of this line, if it's the first in its section). */
    private fun updateRhymes() {
        if (!::rhymeRow.isInitialized) return
        rhymeRow.removeAllViews()
        val f = focused
        val target: String? = f?.let { cur ->
            val i = n.lines.indexOf(cur)
            val prev = n.lines.subList(0, maxOf(i, 0)).lastOrNull()?.takeIf { !it.section && it.text.isNotBlank() }
            Words.lastWord(prev?.text ?: cur.text)
        } ?: n.lines.lastOrNull { !it.section && it.text.isNotBlank() }?.let { Words.lastWord(it.text) }
        if (target == null) { rhymeLabel.text = "Write a line, and rhymes for its last word show up here"; return }
        val list = Words.rhymes(target)
        rhymeLabel.text = if (list.isEmpty()) "No rhymes found for “$target”" else "Rhymes with “$target”"
        list.forEach { w -> rhymeRow.addView(a.chip(w, Look.TERRACOTTA) { insertWord(w) }) }
    }

    private fun insertWord(w: String) {
        val e = currentEdit() ?: edits.entries.lastOrNull { !it.key.section }?.value ?: return
        val t = e.text; val p = e.selectionStart.coerceAtLeast(0)
        val pre = if (p > 0 && !t[p - 1].isWhitespace()) " " else ""
        t.insert(p, pre + w)
        e.requestFocus()
    }

    // ---------------------------------------------------------------- chords

    private fun chordSheet(l: SongLine, existing: Chord?, pos: Int) {
        var root = existing?.name?.let { Regex("^[A-G][#b]?").find(it)?.value } ?: n.key.removeSuffix("m").ifBlank { "C" }
        var kind = existing?.name?.let { nm -> Regex("^[A-G][#b]?(.*)$").find(nm)?.groupValues?.get(1) } ?: ""
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val preview = a.text("", 30f, Look.TERRACOTTA, true).apply { typeface = Typeface.create("serif", Typeface.BOLD); setPadding(a.dp(4), 0, 0, a.dp(8)) }
        val roots = flow(); val kinds = flow()
        fun place(name: String) {
            if (existing != null) existing.name = name else l.chords.add(Chord(pos, name))
            a.closeSheet(); dirty(); edits[l]?.let { (it as? LyricEdit)?.layoutChords() }
        }
        fun redraw() {
            preview.text = root + kind
            roots.removeAllViews(); kinds.removeAllViews()
            Chords.SHARP.forEach { r -> roots.addView(a.chip(r, Look.TERRACOTTA, r == root) { root = r; redraw() }.apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = a.dp(8) }) }
            Chords.KINDS.forEach { k -> kinds.addView(a.chip(if (k.isEmpty()) "maj" else k, Look.UMBER, k == kind) { kind = k; redraw() }.apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = a.dp(8) }) }
        }
        col.addView(preview)
        val quick = (if (n.key.isNotBlank()) Chords.inKey(n.key) else emptyList()) + n.lines.flatMap { it.chords.map { c -> c.name } }
        if (quick.isNotEmpty()) {
            col.addView(a.text(if (n.key.isNotBlank()) "IN ${n.key.uppercase()} · IN THIS SONG" else "IN THIS SONG", 11f, Look.SUB, true).apply { letterSpacing = 0.1f; setPadding(a.dp(4), 0, 0, a.dp(8)) })
            val q = flow()
            quick.distinct().take(14).forEach { c -> q.addView(a.chip(c, Look.TERRACOTTA, false) { place(c) }.apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = a.dp(8) }) }
            col.addView(q)
        }
        col.addView(a.text("ROOT", 11f, Look.SUB, true).apply { letterSpacing = 0.1f; setPadding(a.dp(4), a.dp(8), 0, a.dp(8)) })
        col.addView(roots)
        col.addView(a.text("TYPE", 11f, Look.SUB, true).apply { letterSpacing = 0.1f; setPadding(a.dp(4), a.dp(8), 0, a.dp(8)) })
        col.addView(kinds)
        val btns = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, a.dp(14), 0, 0) }
        if (existing != null) btns.addView(a.bigButton("Remove", R.drawable.i_trash, Look.TERRACOTTA, false) {
            l.chords.remove(existing); a.closeSheet(); dirty(); edits[l]?.let { (it as? LyricEdit)?.layoutChords() }
        }, LinearLayout.LayoutParams(0, a.dp(52), 1f).apply { marginEnd = a.dp(10) })
        btns.addView(a.bigButton(if (existing != null) "Change" else "Place chord", R.drawable.i_checkmark, Look.TERRACOTTA, true) { place(root + kind) }, LinearLayout.LayoutParams(0, a.dp(52), 1f))
        col.addView(btns)
        redraw()
        a.showSheet(if (existing != null) "Change chord" else "Chord over “${wordAt(l.text, pos)}”", ScrollView(a).apply { addView(col) })
    }

    private fun wordAt(t: String, p: Int): String { var e = p.coerceIn(0, t.length); while (e < t.length && !t[e].isWhitespace()) e++; return t.substring(p.coerceIn(0, t.length), e).ifBlank { "…" } }

    private fun keySheet() {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        listOf("MAJOR" to "", "MINOR" to "m").forEach { (lab, suf) ->
            col.addView(a.text(lab, 11f, Look.SUB, true).apply { letterSpacing = 0.1f; setPadding(a.dp(4), a.dp(4), 0, a.dp(8)) })
            val f = flow()
            listOf("C", "Db", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B").forEach { k ->
                val key = k + suf
                f.addView(a.chip(key, Look.TERRACOTTA, n.key == key) { n.key = key; dirty(); renderMeta(); a.closeSheet() }.apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = a.dp(8) })
            }
            col.addView(f)
        }
        col.addView(a.text("TRANSPOSE THE WHOLE SONG", 11f, Look.SUB, true).apply { letterSpacing = 0.1f; setPadding(a.dp(4), a.dp(12), 0, a.dp(8)) })
        val t = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        t.addView(a.bigButton("Down ½", R.drawable.i_down, Look.TERRACOTTA, false) { transpose(-1) }, LinearLayout.LayoutParams(0, a.dp(50), 1f).apply { marginEnd = a.dp(10) })
        t.addView(a.bigButton("Up ½", R.drawable.i_up, Look.TERRACOTTA, false) { transpose(1) }, LinearLayout.LayoutParams(0, a.dp(50), 1f))
        col.addView(t)
        if (n.key.isNotBlank()) col.addView(a.chip("No key", Look.SUB) { n.key = ""; dirty(); renderMeta(); a.closeSheet() }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = a.dp(12) })
        a.showSheet("Key", col)
    }

    private fun transpose(steps: Int) {
        n.lines.forEach { l -> l.chords.forEach { it.name = Chords.transpose(it.name, steps) } }
        if (n.key.isNotBlank()) n.key = Chords.transpose(n.key, steps)
        dirty(); renderMeta(); edits.values.forEach { it.invalidate() }
        a.toast(if (n.key.isNotBlank()) "Now in ${n.key}" else "Chords moved ${if (steps > 0) "up" else "down"} a half step")
    }

    private fun tempoSheet() {
        if (n.bpm <= 0) n.bpm = 90
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val big = a.text("", 54f, Look.UMBER, true).apply { typeface = Typeface.create("serif", Typeface.BOLD); gravity = Gravity.CENTER }
        fun show() { big.text = "${n.bpm}"; renderMeta(); if (Click.running) Click.start(n.bpm); dirty() }
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(a.bigButton("−", null, Look.TERRACOTTA, false) { n.bpm = (n.bpm - 1).coerceAtLeast(30); show() }, LinearLayout.LayoutParams(a.dp(64), a.dp(52)))
        row.addView(big, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(a.bigButton("+", null, Look.TERRACOTTA, false) { n.bpm = (n.bpm + 1).coerceAtMost(260); show() }, LinearLayout.LayoutParams(a.dp(64), a.dp(52)))
        col.addView(row, LinearLayout.LayoutParams(-1, -2))
        col.addView(a.text("beats per minute", 13f, Look.SUB).apply { setPadding(0, 0, 0, a.dp(14)) })
        val taps = ArrayList<Long>()
        val b = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        b.addView(a.bigButton("Tap tempo", null, Look.TERRACOTTA, false) {
            val now = SystemClock.uptimeMillis()
            if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
            taps.add(now); if (taps.size > 6) taps.removeAt(0)
            if (taps.size >= 2) { n.bpm = (60000.0 / ((taps.last() - taps.first()).toDouble() / (taps.size - 1))).toInt().coerceIn(30, 260); show() }
        }, LinearLayout.LayoutParams(0, a.dp(52), 1f).apply { marginEnd = a.dp(10) })
        b.addView(a.bigButton(if (Click.running) "Stop click" else "Play click", R.drawable.i_metro, Look.TERRACOTTA, true) { toggleClick(); a.closeSheet() }, LinearLayout.LayoutParams(0, a.dp(52), 1f))
        col.addView(b, LinearLayout.LayoutParams(-1, -2))
        show()
        a.showSheet("Tempo", col)
    }

    private fun capoSheet() {
        val f = flow()
        (0..9).forEach { c -> f.addView(a.chip(if (c == 0) "None" else "$c", Look.TERRACOTTA, n.capo == c) { n.capo = c; dirty(); renderMeta(); a.closeSheet() }.apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = a.dp(8) }) }
        a.showSheet("Capo", f)
    }

    // ---------------------------------------------------------------- takes

    private fun takes() {
        if (n.takes.isEmpty()) return
        box.addView(a.text("TAKES", 11.5f, Look.SUB, true).apply { letterSpacing = 0.12f; setPadding(a.dp(6), a.dp(34), 0, a.dp(10)); tag = "takes" })
        n.takes.reversed().forEach { t -> box.addView(takeCard(t)) }
    }

    private fun takeCard(t: Take): View {
        val c = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(14), a.dp(12), a.dp(10), a.dp(12)); background = pill(Look.CARD, a.dpf(20f)) }
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val play = ImageView(a).apply {
            setImageResource(if (playing == t) R.drawable.i_pausefill else R.drawable.i_play); setColorFilter(Look.SAND)
            background = ripple(pill(Look.TERRACOTTA, 999f)); setPadding(a.dp(11), a.dp(11), a.dp(11), a.dp(11))
            contentDescription = "Play"
            setOnClickListener { togglePlay(t) }
        }
        top.addView(play, LinearLayout.LayoutParams(a.dp(44), a.dp(44)).apply { marginEnd = a.dp(12) })
        val info = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        info.addView(a.text(t.name, 15.5f, Look.UMBER, true).apply { setOnClickListener { renameTake(t) } })
        val time = a.text(fmtTime(t.durationMs) + takeDate(t), 12f, Look.SUB)
        playTexts[t] = time
        info.addView(time)
        top.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(a.iconButton(R.drawable.i_more, "More", Look.SUB, 40) { takeMenu(t) })
        c.addView(top)
        if (t.flags.isNotEmpty()) {
            val fl = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, a.dp(10), 0, 0) }
            t.flags.forEach { ms -> fl.addView(a.chip(fmtTime(ms), Look.RED, false, R.drawable.i_flag) { seek(t, ms) }) }
            c.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(fl) })
        }
        if (t.heard.isNotEmpty()) {
            val tx = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(4), a.dp(10), a.dp(4), 0) }
            t.heard.forEach { h ->
                tx.addView(LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(0, a.dp(3), 0, a.dp(3))
                    addView(a.text(fmtTime(h.at), 11.5f, Look.FAINT, true), LinearLayout.LayoutParams(a.dp(42), -2))
                    addView(a.text(h.text, 15f, Look.SOFT).apply { typeface = Typeface.create("serif", Typeface.ITALIC) }, LinearLayout.LayoutParams(0, -2, 1f))
                    setOnClickListener { seek(t, h.at) }
                })
            }
            c.addView(tx)
            c.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, a.dp(10), 0, 0)
                addView(a.chip("Add words to the song", Look.TERRACOTTA, true, R.drawable.i_add) { addHeard(t) })
            })
        } else c.addView(a.text("No words were written out for this take.", 12.5f, Look.FAINT).apply { setPadding(a.dp(4), a.dp(8), 0, 0) })
        return FrameLayout(a).apply { setPadding(0, 0, 0, a.dp(10)); addView(c) }
    }

    private fun takeDate(t: Take): String = File(a.store.recDir, t.file).lastModified().takeIf { it > 0 }?.let { "  ·  " + SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(it)) } ?: ""

    private fun takeMenu(t: Take) {
        a.sheet(t.name, listOf(
            act(R.drawable.i_add, "Add its words to the song") { a.closeSheet(); addHeard(t) },
            act(R.drawable.i_text, "Rename") { a.closeSheet(); renameTake(t) },
            act(R.drawable.i_share, "Share recording") { a.closeSheet(); commit(); Export.take(a, n, t, a.store.recDir)?.let { Export.send(a, it, "audio/wav", n.displayTitle() + " – " + t.name) } },
            act(R.drawable.i_trash, "Delete take") {
                a.closeSheet()
                android.app.AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert).setTitle("Delete “${t.name}”?")
                    .setPositiveButton("Delete") { _, _ -> stopPlaying(); File(a.store.recDir, t.file).delete(); n.takes.remove(t); a.store.save(n); render() }
                    .setNegativeButton("Cancel", null).show()
            },
        ))
    }

    private fun renameTake(t: Take) {
        val e = android.widget.EditText(a).apply { setText(t.name); setSelection(t.name.length); setTextColor(Look.UMBER) }
        android.app.AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert).setTitle("Name this take")
            .setView(FrameLayout(a).apply { setPadding(a.dp(20), a.dp(4), a.dp(20), 0); addView(e) })
            .setPositiveButton("Save") { _, _ -> t.name = e.text.toString().ifBlank { t.name }; a.store.save(n); render() }
            .setNegativeButton("Cancel", null).show()
    }

    /** Puts what was heard into the lyrics, as a new section, one phrase per line. */
    private fun addHeard(t: Take) {
        commit()
        val lines = t.heard.flatMap { h -> splitPhrase(h.text) }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return
        n.lines.add(SongLine(true, "From ${t.name}"))
        lines.forEach { n.lines.add(SongLine(false, it.replaceFirstChar { c -> c.uppercase() })) }
        dirty(); render()
        a.main.postDelayed({ scroll.smoothScrollTo(0, box.height) }, 150)
        a.toast("Added ${lines.size} lines")
    }

    private fun splitPhrase(t: String): List<String> {
        val w = t.trim().split(Regex("\\s+"))
        if (w.size <= 9) return listOf(t.trim())
        val k = (w.size + 7) / 8
        val per = (w.size + k - 1) / k
        return w.chunked(per).map { it.joinToString(" ") }
    }

    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return; val t = playing ?: return
            playTexts[t]?.text = fmtTime(p.currentPosition.toLong()) + " / " + fmtTime(t.durationMs)
            a.main.postDelayed(this, 250)
        }
    }

    private fun togglePlay(t: Take, at: Long = -1) {
        if (playing == t && at < 0) { stopPlaying(); render(); return }
        stopPlaying()
        val f = File(a.store.recDir, t.file)
        if (!f.isFile) { a.toast("This recording is missing"); return }
        try {
            player = MediaPlayer().apply {
                setDataSource(f.path); prepare()
                if (at > 0) seekTo(at.toInt())
                start()
                setOnCompletionListener { stopPlaying(); render() }
            }
            playing = t
            render()
            a.main.post(tick)
        } catch (e: Exception) { a.toast("Couldn't play: ${e.message}") }
    }

    private fun seek(t: Take, ms: Long) { if (playing == t) player?.seekTo(ms.toInt()) else togglePlay(t, ms) }

    private fun stopPlaying() {
        a.main.removeCallbacks(tick)
        player?.let { try { it.stop() } catch (e: Exception) {}; it.release() }
        player = null; playing = null
    }

    // ---------------------------------------------------------------- recording

    /** Two ways to record: writing the words out as you sing, or singing over the lyrics you already have. */
    private fun recordChoice() {
        commit()
        val hasLyrics = n.lines.any { !it.section && it.text.isNotBlank() }
        a.sheet("Record", listOf(
            act(R.drawable.i_text, "Record and transcribe · the words are written out as you sing") { a.closeSheet(); a.withMic { startRecording(false) } },
            act(R.drawable.i_song, if (hasLyrics) "Record to the lyrics · see your lyrics while you record" else "Record to the lyrics · (write some lyrics first)") {
                a.closeSheet()
                if (hasLyrics) a.withMic { startRecording(true) } else a.toast("This song has no lyrics yet")
            },
        ))
    }

    /** The song as you see it while recording to it: sections, chords over the words, big and easy to read. */
    private fun lyricsSheet(): View {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(18), a.dp(14), a.dp(14), a.dp(40)) }
        n.lines.forEach { l ->
            if (l.section) box.addView(a.text(l.text.uppercase(), 12f, Look.TERRACOTTA, true).apply { letterSpacing = 0.12f; setPadding(a.dp(2), a.dp(18), 0, a.dp(4)) })
            else if (l.text.isNotBlank() || l.chords.isNotEmpty()) box.addView(LyricEdit(a, l).apply {
                setQuiet(l.text); textSize = 21f; typeface = Typeface.create("serif", Typeface.NORMAL)
                keyListener = null; isFocusable = false; isCursorVisible = false; isLongClickable = false
                setPadding(a.dp(2), paddingTop, a.dp(2), paddingBottom); layoutChords()
            })
        }
        return ScrollView(a).apply { background = pill(Look.CARD, a.dpf(20f)); addView(box); isVerticalScrollBarEnabled = false }
    }

    private fun startRecording(withLyrics: Boolean) {
        commit(); a.store.save(n)
        stopPlaying()
        if (Click.running) Click.stop()
        a.hideKeyboard()
        val name = "take-${System.currentTimeMillis()}.wav"
        val file = File(a.store.recDir, name)
        val prefs = a.getSharedPreferences("letra", Context.MODE_PRIVATE)
        var lang = prefs.getString("lang", if (Locale.getDefault().language == "es") "es-ES" else "en-US")!!
        val r = Recorder(a, file, lang, transcribe = !withLyrics)
        val heard = ArrayList<Heard>(); val flags = ArrayList<Long>()

        val v = FrameLayout(a).apply { setBackgroundColor(Look.SAND); foreground = Look.grain(a); isClickable = true }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(22), a.dp(18), a.dp(22), a.dp(22)) }
        v.addView(col, FrameLayout.LayoutParams(-1, -1))
        v.setOnApplyWindowInsetsListener { _, ins ->
            val b = ins.getInsets(android.view.WindowInsets.Type.systemBars())
            col.setPadding(a.dp(22), b.top + a.dp(18), a.dp(22), b.bottom + a.dp(22)); ins
        }

        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val dotV = View(a).apply { background = pill(Look.RED, 99f) }
        top.addView(dotV, LinearLayout.LayoutParams(a.dp(10), a.dp(10)).apply { marginEnd = a.dp(10) })
        val state = a.text("Recording", 14f, Look.RED, true)
        top.addView(state, LinearLayout.LayoutParams(0, -2, 1f))
        val langChip = LinearLayout(a)
        top.addView(langChip)
        col.addView(top)
        col.addView(a.text(n.displayTitle(), 15f, Look.SUB).apply { setPadding(0, a.dp(14), 0, 0); typeface = Typeface.create("serif", Typeface.ITALIC) })
        val timer = a.text("0:00", 62f, Look.UMBER, true).apply { typeface = Typeface.create("serif", Typeface.BOLD); setPadding(0, a.dp(2), 0, a.dp(4)) }
        col.addView(timer)
        val wave = Wave(a)
        col.addView(wave, LinearLayout.LayoutParams(-1, a.dp(64)))

        col.addView(a.text(if (withLyrics) "YOUR LYRICS" else "WORDS AS YOU SING", 11f, Look.SUB, true).apply { letterSpacing = 0.12f; setPadding(0, a.dp(20), 0, a.dp(8)) })
        val words = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12)) }
        val partial = a.text("", 17f, Look.SUB).apply { typeface = Typeface.create("serif", Typeface.ITALIC) }
        val note = a.text("Listening…", 13f, Look.FAINT)
        words.addView(note); words.addView(partial)
        val wordsScroll = ScrollView(a).apply { background = pill(Look.CARD, a.dpf(20f)); addView(words); isVerticalScrollBarEnabled = false }
        col.addView(if (withLyrics) lyricsSheet() else wordsScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val btns = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, a.dp(18), 0, 0) }
        val flagB = a.bigButton("Flag", R.drawable.i_flag, Look.RED, false) {
            flags.add(r.elapsedMs)
            a.toast("Flagged ${fmtTime(r.elapsedMs)}")
        }
        lateinit var pauseB: LinearLayout
        pauseB = a.bigButton("Pause", R.drawable.i_pausefill, Look.UMBER, false) {
            r.pause(!r.isPaused)
            ((pauseB.getChildAt(1)) as TextView).text = if (r.isPaused) "Resume" else "Pause"
            ((pauseB.getChildAt(0)) as ImageView).setImageResource(if (r.isPaused) R.drawable.i_play else R.drawable.i_pausefill)
            state.text = if (r.isPaused) "Paused" else "Recording"
        }
        val stopB = a.bigButton("Stop", R.drawable.i_stopfill, Look.RED, true) { finishRecording(r, file, name, heard, flags) }
        btns.addView(flagB, LinearLayout.LayoutParams(0, a.dp(56), 1f).apply { marginEnd = a.dp(8) })
        btns.addView(pauseB, LinearLayout.LayoutParams(0, a.dp(56), 1.2f).apply { marginEnd = a.dp(8) })
        btns.addView(stopB, LinearLayout.LayoutParams(0, a.dp(56), 1.1f))
        col.addView(btns)

        fun langUi() {
            langChip.removeAllViews()
            langChip.addView(a.chip(if (lang.startsWith("es")) "Español" else "English", Look.UMBER, false) {
                lang = if (lang.startsWith("es")) "en-US" else "es-ES"
                prefs.edit().putString("lang", lang).apply()
                r.setLanguage(lang); langUi()
            })
        }
        if (!withLyrics) langUi()
        else { timer.textSize = 40f; wave.layoutParams = LinearLayout.LayoutParams(-1, a.dp(40)) }

        r.listener = object : Recorder.Listener {
            override fun level(rms: Float) { wave.push(rms); timer.text = fmtTime(r.elapsedMs); dotV.alpha = if (r.isPaused) 0.3f else if ((r.elapsedMs / 500) % 2 == 0L) 1f else 0.35f }
            override fun partial(text: String) { partial.text = text; if (text.isNotEmpty()) wordsScroll.post { wordsScroll.fullScroll(View.FOCUS_DOWN) } }
            override fun heard(atMs: Long, text: String) {
                heard.add(Heard(atMs, text))
                if (note.text == "Listening…") note.visibility = View.GONE
                words.addView(LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(0, a.dp(3), 0, a.dp(3))
                    addView(a.text(fmtTime(atMs), 11.5f, Look.FAINT, true), LinearLayout.LayoutParams(a.dp(42), -2))
                    addView(a.text(text, 17f, Look.UMBER).apply { typeface = Typeface.create("serif", Typeface.NORMAL) }, LinearLayout.LayoutParams(0, -2, 1f))
                }, words.childCount - 1)
                wordsScroll.post { wordsScroll.fullScroll(View.FOCUS_DOWN) }
            }
            override fun transcribing(ok: Boolean, why: String) {
                if (!ok) { note.visibility = View.VISIBLE; note.text = why + ". The sound is still being recorded." }
                else if (heard.isEmpty()) { note.visibility = View.VISIBLE; note.text = "Listening…" }
            }
        }
        if (!r.start()) { a.toast("Couldn't open the microphone"); file.delete(); return }
        rec = r; recView = v
        recFinish = { finishRecording(r, file, name, heard, flags) }
        RecService.start(a, n.displayTitle()) // keeps the microphone going if the screen locks or you switch apps
        a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        a.root.addView(v, FrameLayout.LayoutParams(-1, -1))
        v.requestApplyInsets()
    }

    private fun finishRecording(r: Recorder, file: File, name: String, heard: List<Heard>, flags: List<Long>) {
        r.listener = null
        val ms = r.stop()
        RecService.stop(a)
        rec = null; recFinish = null
        recView?.let { a.root.removeView(it) }; recView = null
        a.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (ms < 500) { file.delete(); return }
        val t = Take(name, "Take ${n.takes.size + 1}", ms, heard.toMutableList(), flags.toMutableList())
        n.takes.add(t)
        a.store.save(n)
        render()
        a.main.postDelayed({ box.findViewWithTag<View>("takes")?.let { scroll.smoothScrollTo(0, it.top) } }, 200)
    }

    /** While recording, Back does nothing: only Stop ends the take. */
    override fun back(): Boolean {
        if (recFinish == null) return false
        a.toast("Recording · press Stop to finish")
        return true
    }

    override fun commit() { edits.forEach { (l, e) -> if (!e.text.contains('\n')) l.text = e.text.toString() } }
    override fun focusFirst() { val f = n.lines.firstOrNull { !it.section } ?: return; edits[f]?.let { a.showKeyboard(it) } }
    override fun release() { super.release(); stopPlaying(); Click.stop(); recFinish?.invoke() } // a take in progress is kept
}

/** The rolling level meter on the recording screen. */
class Wave(ctx: Context) : View(ctx) {
    private val levels = FloatArray(64)
    private var head = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Look.TERRACOTTA; strokeCap = Paint.Cap.ROUND; strokeWidth = ctx.dpf(4f) }
    fun push(rms: Float) { levels[head] = (Math.sqrt(rms.toDouble()) * 2.2).toFloat().coerceIn(0.04f, 1f); head = (head + 1) % levels.size; invalidate() }
    override fun onDraw(c: Canvas) {
        val n = levels.size; val step = width / n.toFloat(); val mid = height / 2f
        for (i in 0 until n) {
            val v = levels[(head + i) % n]
            p.alpha = (90 + 165 * i / n)
            val x = step * i + step / 2
            c.drawLine(x, mid - v * mid, x, mid + v * mid, p)
        }
    }
}

/** Lays chips out left to right, wrapping onto new rows. */
class FlowLayout(ctx: Context) : LinearLayout(ctx) {
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            measureChild(c, MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val lp = c.layoutParams as MarginLayoutParams
            val cw = c.measuredWidth + lp.leftMargin + lp.rightMargin
            if (x + cw > w && x > 0) { x = 0; y += rowH; rowH = 0 }
            x += cw; rowH = maxOf(rowH, c.measuredHeight + lp.topMargin + lp.bottomMargin)
        }
        setMeasuredDimension(w, y + rowH)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i); val lp = c.layoutParams as MarginLayoutParams
            val cw = c.measuredWidth + lp.leftMargin + lp.rightMargin
            if (x + cw > w && x > 0) { x = 0; y += rowH; rowH = 0 }
            c.layout(x + lp.leftMargin, y + lp.topMargin, x + lp.leftMargin + c.measuredWidth, y + lp.topMargin + c.measuredHeight)
            x += cw; rowH = maxOf(rowH, c.measuredHeight + lp.topMargin + lp.bottomMargin)
        }
    }
}
