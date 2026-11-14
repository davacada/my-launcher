package com.davacada.letra

import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.davacada.letra.Export.ACTION
import com.davacada.letra.Export.CHARACTER
import com.davacada.letra.Export.DIALOGUE
import com.davacada.letra.Export.PAREN
import com.davacada.letra.Export.SCENE
import com.davacada.letra.Export.TRANSITION

/**
 * Screenplay writing: every paragraph is an element (scene heading, action, character, parenthetical,
 * dialogue, transition) laid out like a real script. Enter moves to the element that usually comes next.
 */
class ScriptEditor(a: LetraActivity, n: Note) : Editor(a, n) {
    private val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(18), a.dp(6), a.dp(18), a.dp(80)) }
    private lateinit var scroll: ScrollView
    private val edits = LinkedHashMap<El, LineEdit>()
    private var focused: El? = null
    private var toolsBox: LinearLayout? = null

    private val KINDS = listOf(SCENE to "Scene", ACTION to "Action", CHARACTER to "Character", PAREN to "Paren", DIALOGUE to "Dialogue", TRANSITION to "Transition")

    private fun next(kind: String) = when (kind) {
        SCENE -> ACTION; CHARACTER -> DIALOGUE; PAREN -> DIALOGUE; DIALOGUE -> ACTION; TRANSITION -> SCENE; else -> ACTION
    }

    override fun view(): View {
        render()
        scroll = ScrollView(a).apply { isVerticalScrollBarEnabled = false; addView(box) }
        return scroll
    }

    private fun render(focus: El? = null, at: Int = -1) {
        box.removeAllViews(); edits.clear()
        if (n.els.isEmpty()) n.els.add(El(SCENE, ""))
        var scene = 0
        n.els.forEach { e -> if (e.kind == SCENE) scene++; box.addView(row(e, scene)) }
        focus?.let { f -> edits[f]?.let { e -> a.main.post { e.requestFocus(); e.setSelection(if (at < 0) e.text.length else at.coerceAtMost(e.text.length)); a.showKeyboard(e) } } }
    }

    private fun hintFor(k: String) = when (k) {
        SCENE -> "INT. PLACE – DAY"; ACTION -> "What we see…"; CHARACTER -> "NAME"; PAREN -> "(quietly)"; DIALOGUE -> "What they say…"; else -> "CUT TO:"
    }

    private fun style(e: LineEdit, k: String) {
        val w = a.resources.displayMetrics.widthPixels
        e.hint = hintFor(k)
        e.typeface = Typeface.create(Typeface.MONOSPACE, if (k == SCENE || k == CHARACTER) Typeface.BOLD else Typeface.NORMAL)
        e.textSize = 15f
        e.setTextColor(if (k == SCENE) Look.SLATE else Look.UMBER)
        val caps = k == SCENE || k == CHARACTER || k == TRANSITION
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or (if (caps) InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS else InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        e.gravity = if (k == TRANSITION) Gravity.END else Gravity.START
        val (l, r) = when (k) {
            CHARACTER -> w * 30 / 100 to 0
            PAREN -> w * 20 / 100 to w * 16 / 100
            DIALOGUE -> w * 11 / 100 to w * 9 / 100
            else -> 0 to 0
        }
        val top = when (k) { SCENE -> a.dp(18); CHARACTER -> a.dp(12); ACTION, TRANSITION -> a.dp(8); else -> 0 }
        e.setPadding(l, top, r, a.dp(4))
        if (k == PAREN) e.setTypeface(Typeface.MONOSPACE, Typeface.ITALIC)
    }

    private fun row(el: El, scene: Int): View {
        val e = LineEdit(a).apply { setQuiet(el.text) }
        style(e, el.kind)
        e.addTextChangedListener(after { s ->
            if (s.contains('\n')) return@after
            el.text = s; dirty()
            if (el.kind == CHARACTER) charactersSoon()
        })
        e.setOnFocusChangeListener { _, has -> if (has) { focused = el; renderTools() } }
        e.onSplit = { before, after ->
            el.text = before
            var idx = n.els.indexOf(el); var last = el
            var kind = el.kind
            // Enter on an empty character/paren line turns it back into action
            if (before.isBlank() && after.all { it.isBlank() } && (el.kind == CHARACTER || el.kind == PAREN || el.kind == DIALOGUE)) {
                el.kind = ACTION; dirty(); render(el, 0)
            } else {
                after.forEach { t -> kind = next(kind); val ne = El(kind, t); n.els.add(++idx, ne); last = ne }
                if (el.kind == CHARACTER) charactersSoon()
                dirty(); render(last, if (after.size == 1) 0 else -1)
            }
        }
        e.onBackAtStart = {
            val i = n.els.indexOf(el)
            val prev = if (i > 0) n.els[i - 1] else null
            when {
                prev == null -> false
                e.text.isEmpty() -> { n.els.removeAt(i); dirty(); render(prev); true }
                prev.kind == el.kind -> { val at = prev.text.length; prev.text += (if (prev.text.isNotEmpty()) " " else "") + e.text; n.els.removeAt(i); dirty(); render(prev, at); true }
                else -> false
            }
        }
        edits[el] = e
        if (el.kind != SCENE) return e
        return LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            addView(a.text("$scene", 12f, Look.SLATE, true).apply { typeface = Typeface.MONOSPACE; setPadding(0, 0, a.dp(8), a.dp(8)) }, LinearLayout.LayoutParams(a.dp(26), -2))
            addView(e, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    // ---------------------------------------------------------------- tools: element type + characters

    override fun tools(): View {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        toolsBox = col
        renderTools()
        return col
    }

    private val charRun = Runnable { renderTools() }
    private fun charactersSoon() { a.main.removeCallbacks(charRun); a.main.postDelayed(charRun, 600) }

    private fun characters() = n.els.filter { it.kind == CHARACTER && it.text.isNotBlank() }
        .map { it.text.trim().uppercase().replace(Regex("\\s*\\(.*\\)$"), "") }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

    private fun renderTools() {
        val col = toolsBox ?: return
        col.removeAllViews()
        val cur = focused?.kind
        val chars = characters()
        if (chars.isNotEmpty()) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(6)) }
            chars.forEach { c -> row.addView(a.chip(c, Look.SLATE) { speak(c) }) }
            col.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(Look.SHEET); addView(row) })
        }
        val tools = KINDS.map { (k, label) ->
            a.tool(iconFor(k), label, Look.SLATE, k == cur) { setKind(k) }
        }
        col.addView(toolbar(*tools.toTypedArray(), a.tool(R.drawable.i_list, "Scenes", Look.SLATE) { scenesSheet() }))
    }

    private fun iconFor(k: String) = when (k) {
        SCENE -> R.drawable.i_script; ACTION -> R.drawable.i_text; CHARACTER -> R.drawable.i_heading
        PAREN -> R.drawable.i_italic; DIALOGUE -> R.drawable.i_bullets; else -> R.drawable.i_up
    }

    private fun setKind(k: String) {
        val el = focused ?: n.els.lastOrNull() ?: return
        val pos = edits[el]?.selectionStart ?: -1
        el.kind = k
        if (k == SCENE || k == CHARACTER || k == TRANSITION) el.text = el.text.uppercase()
        if (k == PAREN) el.text = el.text.trim('(', ')')
        dirty(); render(el, pos); renderTools()
    }

    /** Tapping a character's name starts a new line of dialogue for them. */
    private fun speak(name: String) {
        val el = focused
        if (el != null && el.text.isBlank() && el.kind != DIALOGUE) {
            el.kind = CHARACTER; el.text = name
            val d = El(DIALOGUE, ""); n.els.add(n.els.indexOf(el) + 1, d)
            dirty(); render(d, 0); return
        }
        val at = if (el != null) n.els.indexOf(el) + 1 else n.els.size
        val c = El(CHARACTER, name); val d = El(DIALOGUE, "")
        n.els.add(at, c); n.els.add(at + 1, d)
        dirty(); render(d, 0)
    }

    private fun scenesSheet() {
        val scenes = n.els.filter { it.kind == SCENE }
        if (scenes.isEmpty()) { a.toast("No scenes yet"); return }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        scenes.forEachIndexed { i, s ->
            col.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(8), 0, a.dp(8), 0)
                background = ripple(null, pill(0xFFFFFFFF.toInt(), a.dpf(14f)))
                addView(a.text("${i + 1}", 13f, Look.SLATE, true).apply { typeface = Typeface.MONOSPACE }, LinearLayout.LayoutParams(a.dp(30), -2))
                addView(a.text(s.text.ifBlank { "Untitled scene" }.uppercase(), 14.5f, Look.UMBER).apply { typeface = Typeface.MONOSPACE; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                setOnClickListener { a.closeSheet(); val v = edits[s]; if (v != null) { scroll.smoothScrollTo(0, (v.parent as? View)?.top ?: v.top); render(s) } }
            }, LinearLayout.LayoutParams(-1, a.dp(48)))
        }
        a.showSheet("Scenes", ScrollView(a).apply { addView(col) })
    }

    override fun commit() { edits.forEach { (el, e) -> if (!e.text.contains('\n')) el.text = e.text.toString() } }
    override fun focusFirst() { n.els.firstOrNull()?.let { render(it) } }
}

