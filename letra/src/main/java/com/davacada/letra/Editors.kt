package com.davacada.letra

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** One mode's editing screen: the content, the tools above the keyboard, and saving. */
abstract class Editor(val a: LetraActivity, val n: Note) {
    abstract fun view(): View
    open fun header(): View? = null
    open fun tools(): View? = null
    open fun topActions(): List<View> = emptyList()
    open fun commit() {}
    open fun back(): Boolean = false
    open fun focusFirst() {}
    private val saver = Runnable { commit(); a.store.save(n) }
    fun dirty() { a.main.removeCallbacks(saver); a.main.postDelayed(saver, 1500) }
    open fun release() { a.main.removeCallbacks(saver) }

    fun toolbar(vararg v: View): View {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(8), 0, a.dp(8), 0) }
        v.forEach { row.addView(it, LinearLayout.LayoutParams(-2, a.dp(50)).apply { marginEnd = a.dp(2) }) }
        return HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false; setBackgroundColor(Look.CARD)
            setPadding(0, a.dp(4), 0, a.dp(4))
            addView(row)
        }
    }
}

/**
 * A one-line field for list items, lyric lines and script elements. Enter splits the line (the editor is told
 * through [onSplit]); backspace at the very start asks the editor to join it with the line above.
 */
open class LineEdit(ctx: Context) : EditText(ctx) {
    var onBackAtStart: (() -> Boolean)? = null
    var onSplit: ((before: String, after: List<String>) -> Unit)? = null
    private var busy = false
    val quiet get() = busy

    init {
        background = null
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
        setHorizontallyScrolling(false); maxLines = Int.MAX_VALUE
        setTextColor(Look.UMBER); setHintTextColor(Look.FAINT)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (busy || s == null) return
                val t = s.toString()
                val i = t.indexOf('\n')
                if (i < 0) return
                val parts = t.split('\n')
                busy = true
                setText(parts[0]); setSelection(parts[0].length)
                busy = false
                onSplit?.invoke(parts[0], parts.drop(1))
            }
        })
        setOnKeyListener { _, code, e ->
            code == KeyEvent.KEYCODE_DEL && e.action == KeyEvent.ACTION_DOWN && selectionStart == 0 && selectionEnd == 0 && onBackAtStart?.invoke() == true
        }
    }

    fun setQuiet(t: String) { busy = true; setText(t); busy = false }

    override fun onCreateInputConnection(out: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(out) ?: return null
        out.imeOptions = out.imeOptions or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        return object : InputConnectionWrapper(ic, true) {
            override fun deleteSurroundingText(before: Int, after: Int): Boolean {
                if (before >= 1 && after == 0 && selectionStart == 0 && selectionEnd == 0 && onBackAtStart?.invoke() == true) return true
                return super.deleteSurroundingText(before, after)
            }
            override fun sendKeyEvent(e: KeyEvent): Boolean {
                if (e.keyCode == KeyEvent.KEYCODE_DEL && e.action == KeyEvent.ACTION_DOWN && selectionStart == 0 && selectionEnd == 0 && onBackAtStart?.invoke() == true) return true
                return super.sendKeyEvent(e)
            }
        }
    }
}

/** Formatted note text, stored as plain text plus bold / italic / heading ranges. */
object Rich {
    class Head : RelativeSizeSpan(1.32f)
    class Bold : StyleSpan(Typeface.BOLD)
    class Italic : StyleSpan(Typeface.ITALIC)

    fun plain(stored: String): String = try { JSONObject(stored).optString("t") } catch (e: Exception) { stored }

    fun load(stored: String): SpannableStringBuilder {
        if (stored.isBlank()) return SpannableStringBuilder()
        return try {
            val o = JSONObject(stored)
            val s = SpannableStringBuilder(o.optString("t"))
            o.optJSONArray("s")?.let { a ->
                for (i in 0 until a.length()) {
                    val r = a.getJSONArray(i); val st = r.getInt(0).coerceIn(0, s.length); val en = r.getInt(1).coerceIn(st, s.length)
                    when (r.getString(2)) {
                        "b" -> s.setSpan(Bold(), st, en, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
                        "i" -> s.setSpan(Italic(), st, en, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
                        "h" -> { s.setSpan(Head(), st, en, Spanned.SPAN_EXCLUSIVE_INCLUSIVE); s.setSpan(Bold(), st, en, Spanned.SPAN_EXCLUSIVE_INCLUSIVE) }
                    }
                }
            }
            s
        } catch (e: Exception) { SpannableStringBuilder(stored) }
    }

    fun save(s: Spanned): String {
        val a = JSONArray()
        val heads = s.getSpans(0, s.length, Head::class.java)
        heads.forEach { a.put(JSONArray().put(s.getSpanStart(it)).put(s.getSpanEnd(it)).put("h")) }
        s.getSpans(0, s.length, Bold::class.java).forEach { b ->
            val st = s.getSpanStart(b); val en = s.getSpanEnd(b)
            if (heads.none { s.getSpanStart(it) == st && s.getSpanEnd(it) == en } && en > st) a.put(JSONArray().put(st).put(en).put("b"))
        }
        s.getSpans(0, s.length, Italic::class.java).forEach { if (s.getSpanEnd(it) > s.getSpanStart(it)) a.put(JSONArray().put(s.getSpanStart(it)).put(s.getSpanEnd(it)).put("i")) }
        return JSONObject().put("t", s.toString()).put("s", a).toString()
    }
}

// ====================================================================== Note

class NoteEditor(a: LetraActivity, n: Note) : Editor(a, n) {
    private lateinit var ed: EditText
    private lateinit var count: TextView
    private var busy = false

    override fun view(): View {
        ed = EditText(a).apply {
            background = null; gravity = Gravity.TOP
            setTextColor(Look.UMBER); setHintTextColor(Look.FAINT); hint = "Start writing…"
            textSize = 17f; setLineSpacing(0f, 1.3f)
            setPadding(a.dp(22), a.dp(6), a.dp(22), a.dp(40))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(Rich.load(n.html), android.widget.TextView.BufferType.EDITABLE)
            addTextChangedListener(object : TextWatcher {
                private var nl = -1
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, before: Int, c: Int) { nl = if (!busy && c == 1 && before == 0 && s != null && s[st] == '\n') st else -1 }
                override fun afterTextChanged(s: Editable?) {
                    if (s == null || busy) return
                    busy = true
                    if (nl >= 0) continueBullet(s, nl)
                    trimHeads(s)
                    busy = false
                    updateCount(); dirty()
                }
            })
        }
        return ScrollView(a).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(ed) }
    }

    /** Pressing Enter on a "• " line starts the next bullet; on an empty bullet it ends the list. */
    private fun continueBullet(s: Editable, at: Int) {
        val ls = s.lastIndexOf('\n', at - 1) + 1
        val line = s.substring(ls, at)
        if (line == "• ") { s.delete(ls, at + 1) }
        else if (line.startsWith("• ")) s.insert(at + 1, "• ")
    }

    /** A heading covers one line; stop it at the line's end. */
    private fun trimHeads(s: Editable) {
        s.getSpans(0, s.length, Rich.Head::class.java).forEach { h ->
            val st = s.getSpanStart(h); val en = s.getSpanEnd(h)
            val cut = s.indexOf('\n', st)
            if (cut in st until en) {
                val b = s.getSpans(st, en, Rich.Bold::class.java).firstOrNull { s.getSpanStart(it) == st && s.getSpanEnd(it) == en }
                s.setSpan(h, st, cut, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
                b?.let { s.setSpan(it, st, cut, Spanned.SPAN_EXCLUSIVE_INCLUSIVE) }
            }
        }
    }

    private fun updateCount() { if (::count.isInitialized) count.text = "${ed.text.split(Regex("\\s+")).count { it.isNotBlank() }} words" }

    override fun tools(): View {
        count = a.text("", 12f, Look.SUB, true).apply { gravity = Gravity.CENTER; setPadding(a.dp(10), 0, a.dp(6), 0) }
        updateCount()
        return toolbar(
            a.tool(R.drawable.i_bold, "Bold", Look.WALNUT) { style(Rich.Bold::class.java) { Rich.Bold() } },
            a.tool(R.drawable.i_italic, "Italic", Look.WALNUT) { style(Rich.Italic::class.java) { Rich.Italic() } },
            a.tool(R.drawable.i_heading, "Heading", Look.WALNUT) { heading() },
            a.tool(R.drawable.i_bullets, "Bullets", Look.WALNUT) { bullets() },
            a.tool(R.drawable.i_list, "To list", Look.WALNUT) { toList() },
            count,
        )
    }

    /** Toggles bold/italic on the selection, or on the word under the cursor. */
    private fun <T : StyleSpan> style(cls: Class<T>, make: () -> T) {
        val s = ed.text
        var st = ed.selectionStart; var en = ed.selectionEnd
        if (st == en) {
            while (st > 0 && !s[st - 1].isWhitespace()) st--
            while (en < s.length && !s[en].isWhitespace()) en++
            if (st == en) return
        }
        val existing = s.getSpans(st, en, cls).filter { sp ->
            val a0 = s.getSpanStart(sp); val b0 = s.getSpanEnd(sp)
            val partOfHeading = sp is Rich.Bold && s.getSpans(a0, b0, Rich.Head::class.java).any { h -> s.getSpanStart(h) == a0 && s.getSpanEnd(h) == b0 }
            a0 <= st && b0 >= en && !partOfHeading
        }
        if (existing.isNotEmpty()) existing.forEach { sp ->
            val a0 = s.getSpanStart(sp); val b0 = s.getSpanEnd(sp)
            s.removeSpan(sp)
            if (a0 < st) s.setSpan(make(), a0, st, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
            if (b0 > en) s.setSpan(make(), en, b0, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
        } else s.setSpan(make(), st, en, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
        dirty()
    }

    private fun lineBounds(): Pair<Int, Int> {
        val s = ed.text; val c = ed.selectionStart.coerceAtLeast(0)
        val st = s.lastIndexOf('\n', c - 1) + 1
        val en = s.indexOf('\n', c).let { if (it < 0) s.length else it }
        return st to en
    }

    private fun heading() {
        val s = ed.text; val (st, en) = lineBounds()
        val hs = s.getSpans(st, maxOf(en, st), Rich.Head::class.java)
        if (hs.isNotEmpty()) {
            hs.forEach { h -> val a0 = s.getSpanStart(h); val b0 = s.getSpanEnd(h); s.removeSpan(h); s.getSpans(a0, b0, Rich.Bold::class.java).filter { s.getSpanStart(it) == a0 && s.getSpanEnd(it) == b0 }.forEach { s.removeSpan(it) } }
        } else if (en > st) {
            s.setSpan(Rich.Head(), st, en, Spanned.SPAN_EXCLUSIVE_INCLUSIVE); s.setSpan(Rich.Bold(), st, en, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
        }
        dirty()
    }

    private fun bullets() {
        val s = ed.text
        val a0 = s.lastIndexOf('\n', ed.selectionStart.coerceAtLeast(0) - 1) + 1
        var b0 = ed.selectionEnd.coerceAtLeast(0)
        val starts = mutableListOf(a0)
        var i = s.indexOf('\n', a0)
        while (i in 0 until b0) { starts.add(i + 1); i = s.indexOf('\n', i + 1) }
        val all = starts.all { s.startsWith("• ", it) }
        busy = true
        starts.reversed().forEach { p -> if (all) s.delete(p, p + 2) else if (!s.startsWith("• ", p)) { s.insert(p, "• "); b0 += 2 } }
        busy = false
        if (!all && ed.selectionStart == a0) ed.setSelection(minOf(a0 + 2, s.length))
        dirty()
    }

    /** Turns the note's lines into a checklist. */
    private fun toList() {
        commit()
        val lines = ed.text.toString().split('\n').map { it.removePrefix("• ").trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) { a.toast("Write a few lines first"); return }
        n.mode = Note.LIST; n.items.clear(); lines.forEach { n.items.add(Item(it, false)) }; n.html = ""
        a.store.save(n); release(); a.open(n)
    }

    override fun commit() { if (::ed.isInitialized && n.mode == Note.NOTE) n.html = Rich.save(ed.text) }
    override fun focusFirst() { ed.setSelection(ed.text.length); a.showKeyboard(ed) }
}

// ====================================================================== List

class ListEditor(a: LetraActivity, n: Note) : Editor(a, n) {
    private val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(12), a.dp(4), a.dp(14), a.dp(60)) }
    private val rows = HashMap<Item, LineEdit>()
    private var showDone = true

    override fun view(): View { render(); return ScrollView(a).apply { isVerticalScrollBarEnabled = false; addView(box) } }

    private fun render(focus: Item? = null, at: Int = -1) {
        box.removeAllViews(); rows.clear()
        val open = n.items.filter { !it.done }; val done = n.items.filter { it.done }
        open.forEach { box.addView(row(it)) }
        box.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(8), 0, 0, 0)
            background = ripple(null, pill(0xFFFFFFFF.toInt(), a.dpf(14f)))
            addView(ImageView(a).apply { setImageResource(R.drawable.i_add); setColorFilter(Look.OLIVE) }, LinearLayout.LayoutParams(a.dp(22), a.dp(22)).apply { marginEnd = a.dp(14) })
            addView(a.text("Add item", 16f, Look.OLIVE, true))
            setOnClickListener { val ni = Item("", false); n.items.add(n.items.indexOfLast { x -> !x.done } + 1, ni); render(ni, 0) }
        }, LinearLayout.LayoutParams(-1, a.dp(48)))
        if (done.isNotEmpty()) {
            box.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(8), a.dp(18), 0, a.dp(6))
                addView(a.text((if (showDone) "▾  " else "▸  ") + "Completed · ${done.size}", 12.5f, Look.SUB, true).apply { letterSpacing = 0.06f })
                setOnClickListener { showDone = !showDone; render() }
            })
            if (showDone) done.forEach { box.addView(row(it)) }
        }
        focus?.let { f -> rows[f]?.let { e -> a.main.post { e.requestFocus(); e.setSelection(if (at < 0) e.text.length else at.coerceAtMost(e.text.length)); a.showKeyboard(e) } } }
    }

    private fun checkBox(done: Boolean) = ImageView(a).apply {
        background = if (done) pill(Look.OLIVE, 999f) else pill(0, 999f, a.dp(2), 0x664A3624)
        if (done) { setImageResource(R.drawable.i_checkmark); setColorFilter(Look.SAND); setPadding(a.dp(3), a.dp(3), a.dp(3), a.dp(3)) }
    }

    private fun row(it: Item): View {
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP; setPadding(a.dp(8), 0, 0, 0) }
        val check = checkBox(it.done)
        val tap = FrameLayout(a).apply {
            setPadding(0, a.dp(13), a.dp(12), a.dp(8))
            addView(check, FrameLayout.LayoutParams(a.dp(22), a.dp(22)))
            setOnClickListener { _ -> commit(); it.done = !it.done; dirty(); render() }
        }
        r.addView(tap)
        val e = LineEdit(a).apply {
            setQuiet(it.text); hint = "Item"; textSize = 16.5f
            setPadding(0, a.dp(12), 0, a.dp(12))
            if (it.done) { setTextColor(Look.FAINT); paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG }
            addTextChangedListener(after { s -> if (!s.contains('\n')) { it.text = s; dirty() } })
            onSplit = { before, after ->
                it.text = before
                var idx = n.items.indexOf(it); var last = it
                after.forEach { t -> val ni = Item(t, it.done); n.items.add(++idx, ni); last = ni }
                dirty(); render(last, if (after.size == 1) 0 else -1)
            }
            onBackAtStart = {
                val i = n.items.indexOf(it)
                val prev = n.items.subList(0, i).lastOrNull { p -> p.done == it.done }
                when {
                    prev != null -> { val at = prev.text.length; prev.text += text.toString(); n.items.remove(it); dirty(); render(prev, at); true }
                    text.isEmpty() && n.items.size > 1 -> { n.items.remove(it); dirty(); render(n.items.firstOrNull { p -> !p.done }); true }
                    else -> false
                }
            }
        }
        rows[it] = e
        r.addView(e, LinearLayout.LayoutParams(0, -2, 1f))
        return r
    }

    override fun tools() = toolbar(
        a.tool(R.drawable.i_add, "Add", Look.OLIVE) { val i = Item("", false); n.items.add(n.items.indexOfLast { !it.done } + 1, i); render(i, 0) },
        a.tool(R.drawable.i_check, "Uncheck all", Look.OLIVE) { commit(); n.items.forEach { it.done = false }; dirty(); render() },
        a.tool(R.drawable.i_trash, "Clear done", Look.OLIVE) { commit(); n.items.removeAll { it.done }; if (n.items.isEmpty()) n.items.add(Item("", false)); dirty(); render() },
        a.tool(R.drawable.i_text, "Sort A–Z", Look.OLIVE) { commit(); val s = n.items.sortedWith(compareBy<Item> { it.done }.thenBy { it.text.lowercase() }); n.items.clear(); n.items.addAll(s); dirty(); render() },
    )

    override fun commit() { rows.forEach { (i, e) -> if (!e.text.contains('\n')) i.text = e.text.toString() } }
    override fun focusFirst() { val f = n.items.firstOrNull { !it.done } ?: Item("", false).also { n.items.add(0, it) }; render(f) }
}

