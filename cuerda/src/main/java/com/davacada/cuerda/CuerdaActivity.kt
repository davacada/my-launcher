package com.davacada.cuerda

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class CuerdaActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("cuerda", MODE_PRIVATE) }
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var nav: LinearLayout
    private lateinit var root: FrameLayout
    private lateinit var paper: Paper
    private var wave: Wave? = null
    private var floating: View? = null
    private var top = 0; private var bottom = 0
    private var tab = 0

    // settings
    private var instrumentId: String
        get() = prefs.getString("instrument", "guitar")!!
        set(v) { prefs.edit().putString("instrument", v).apply() }
    private val instrument get() = Instruments.byId(instrumentId)
    private var tuningIdx: Int
        get() = prefs.getInt("tuning:$instrumentId", 0)
        set(v) { prefs.edit().putInt("tuning:$instrumentId", v).apply() }
    private var auto: Boolean
        get() = prefs.getBoolean("auto", true)
        set(v) { prefs.edit().putBoolean("auto", v).apply() }
    private var chromaticMode = false
    private var picked = -1

    private fun tunings(): List<Tuning> {
        val custom = prefs.getString("custom:$instrumentId", null)?.split(",")?.mapNotNull { it.toIntOrNull() }
        return instrument.tunings + (if (custom != null && custom.isNotEmpty()) listOf(Tuning("Custom", custom)) else emptyList())
    }
    private fun tuning(): Tuning? = tunings().getOrNull(tuningIdx) ?: tunings().firstOrNull()

    // audio
    private var mic: Mic? = null
    private val tone = Tone()
    private val ear = ChordEar()
    private var listening = false

    // tuner state
    private val recent = ArrayList<Double>()
    private var lastHeard = 0L
    private var lastTarget: Int? = null
    private var keyboardStart = 60

    // chords state
    data class Hit(val chord: Chords.Chord, val at: Long)
    private val history = ArrayList<Hit>()
    private var listenStart = 0L
    private var lastResult: ChordEar.Result? = null

    // live views
    private var needle: Needle? = null
    private var centsBar: CentsBar? = null
    private var keyboard: Keyboard? = null
    private var noteText: TextView? = null
    private var octText: TextView? = null
    private var centsText: TextView? = null
    private var hzText: TextView? = null
    private var stringButtons = ArrayList<TextView>()
    private var chordText: TextView? = null
    private var chordSub: TextView? = null
    private var confText: TextView? = null
    private var chordBox: ChordBox? = null
    private var keyText: TextView? = null
    private var tempoText: TextView? = null
    private var capoText: TextView? = null
    private var historyRow: LinearLayout? = null
    private var sourceText: TextView? = null
    private var listenLabel: TextView? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Notes.a4 = prefs.getFloat("a4", 440f).toDouble()
        window.setDecorFitsSystemWindows(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.insetsController?.setSystemBarsAppearance(
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)

        root = FrameLayout(this).apply { setBackgroundColor(C.PAPER) }
        paper = Paper(this)
        root.addView(paper, FrameLayout.LayoutParams(-1, -1))
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            setOnScrollChangeListener { _, _, y, _, _ -> paper.scrollOffset = y }
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(nav, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars()); top = b.top; bottom = b.bottom; render(); ins
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        loadHistory()
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startAudio()
    }

    override fun onResume() { super.onResume(); startAudio() }
    override fun onPause() { stopAudio(); tone.stop(); saveHistory(); super.onPause() }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // MARK: audio

    private fun stopAudio() { mic?.stop(); mic = null }

    private fun startAudio() {
        stopAudio()
        if (!hasMic()) return
        if (tab == 0) {
            val (lo, hi) = range()
            val big = lo < 70
            mic = Mic(if (big) 8192 else 4096, if (big) 2048 else 1024) { block ->
                val r = Yin.detect(block, lo, hi)
                mainHandler.post { onPitch(r) }
            }.also { it.start() }
        } else if (tab == 1 && listening) {
            mic = Mic(8192, 2048) { block ->
                val r = ear.feed(block)
                mainHandler.post { onChord(r) }
            }.also { it.start() }
        }
    }

    private fun range(): Pair<Double, Double> {
        val t = tuning()
        if (instrument.chromatic || chromaticMode || t == null) return (if (instrument.id == "piano") 26.0 else 30.0) to 4200.0
        return Notes.freq(t.notes.min() - 6.0) to Notes.freq(t.notes.max() + 14.0)
    }

    // MARK: tuner logic

    private fun onPitch(r: Pair<Double, Double>?) {
        val now = System.currentTimeMillis()
        if (r == null || r.second < 0.75) {
            if (now - lastHeard > 1400) showIdle()
            return
        }
        val f = r.first
        if (recent.isNotEmpty() && abs(Notes.midiOf(f) - Notes.midiOf(recent.last())) > 0.7) recent.clear()
        recent.add(f); if (recent.size > 5) recent.removeAt(0)
        val fm = recent.sorted()[recent.size / 2]
        lastHeard = now
        val midiF = Notes.midiOf(fm)
        val t = tuning()
        val strings = if (instrument.chromatic || chromaticMode || t == null) null else t.notes
        val target: Int = when {
            strings == null -> midiF.roundToInt()
            !auto && picked in strings.indices -> strings[picked]
            else -> strings.minBy { abs(it - midiF) }
        }
        val stretch = if (instrument.id == "piano") Notes.pianoStretch(target) else 0.0
        val cents = Notes.cents(fm, target.toDouble()) - stretch
        lastTarget = target
        noteText?.apply { text = Notes.name(target); setTextColor(C.INK) }
        octText?.text = Notes.octave(target).toString()
        centsText?.apply {
            val c = cents.roundToInt()
            val sign = if (c > 0) "+" else if (c < 0) "−" else ""
            text = when {
                abs(c) <= 3 -> "in tune ✓"
                abs(c) <= 10 -> "$sign${abs(c)} cents · almost there"
                abs(cents) > 50 -> if (c > 0) "too sharp · tune down" else "too flat · tune up"
                c > 0 -> "$sign${abs(c)} cents · a little sharp"
                else -> "$sign${abs(c)} cents · a little flat"
            }
            setTextColor(C.INK); highlight(if (abs(c) <= 10) C.MANGO else C.CORAL)
        }
        hzText?.text = String.format(Locale.US, "%.1f Hz → %.1f Hz", fm, Notes.freq(target + stretch / 100))
        needle?.set(cents, true)
        centsBar?.apply { this.cents = cents; invalidate() }
        keyboard?.apply {
            lit = target
            if (target < startC || target >= startC + 24) { startC = Math.floorDiv(target, 12) * 12; keyboardStart = startC }
            invalidate()
        }
        if (strings != null) stringButtons.forEachIndexed { i, b -> styleString(b, i, strings[i] == target && (auto || i == picked)) }
        if (tone.isPlaying) tone.freq = Notes.freq(target + stretch / 100)
    }

    private fun showIdle() {
        needle?.set(null, false)
        centsBar?.apply { cents = null; invalidate() }
        centsText?.apply { text = if (instrument.id == "piano") "play one key" else "play a string"; setTextColor(C.SUB); highlight(null) }
        hzText?.text = if (hasMic()) String.format(Locale.US, "A4 = %.0f Hz", Notes.a4) else "Allow the microphone to tune"
    }

    // MARK: chord logic

    private fun onChord(r: ChordEar.Result) {
        lastResult = r
        val c = r.chord
        var changed: String? = null
        val now = System.currentTimeMillis() - listenStart
        if (c != null && r.loud && history.lastOrNull()?.chord != c) {
            history.add(Hit(c, now)); changed = c.name()
        }
        val mark = changed ?: if (c != null && r.loud) c.name() else null
        waveLevels.add(r.level to (mark to now)); if (waveLevels.size > 50000) waveLevels.subList(0, 5000).clear()
        wave?.push(r.level, mark, now)
        showChord(r)
    }

    private fun showChord(r: ChordEar.Result?) {
        val c = r?.chord ?: history.lastOrNull()?.chord
        sourceText?.text = when {
            !listening -> if (history.isEmpty()) "tap to listen" else "paused"
            r == null || !r.loud -> "listening…"
            r.suggested -> "singing · chords that fit"
            else -> "listening…"
        }
        chordText?.apply { text = c?.name() ?: "♪"; setTextColor(if (c == null) 0x59000000 or (C.INK and 0xFFFFFF) else C.INK) }
        chordSub?.text = c?.let { "${Notes.name(it.root)} ${it.q.name}" } ?: "no chord yet"
        chordNotes?.text = c?.notes()?.joinToString(" · ") { Notes.name(it) } ?: ""
        val conf = r?.confidence ?: 0.0
        val pct = (conf * 100).roundToInt()
        confText?.text = when {
            c == null || r == null -> ""
            r.suggested -> "a guess from the melody ($pct%)"
            conf > 0.66 -> "pretty sure ($pct%)"
            conf > 0.33 -> "fairly sure ($pct%)"
            else -> "not sure yet ($pct%)"
        }
        chordBox?.apply {
            if (c != null) { val (f, b) = Chords.shape(c); frets = f; barre = b } else frets = null
            invalidate()
        }
        keyText?.text = r?.key?.let { Notes.name(it.first) + if (it.second) " minor" else " major" } ?: "…"
        tempoText?.text = r?.bpm?.let { "$it bpm" } ?: "…"
        capoText?.text = Chords.capo(history.map { it.chord }.distinct())?.let { "fret ${it.first}" } ?: "none"
        renderChart()
    }

    /** "| Am | F | C | G |", the newest in red ink. */
    private fun renderChart() {
        val t = chartText ?: return
        if (history.isEmpty()) { t.text = "chords appear here as the song plays"; t.setTextColor(C.SUB); t.textSize = 22f; return }
        val shown = history.takeLast(10)
        val b = android.text.SpannableStringBuilder(if (history.size > 10) "… " else "")
        b.append("| ")
        shown.forEachIndexed { i, h ->
            val s = b.length
            b.append(h.chord.name())
            if (i == shown.size - 1) b.setSpan(android.text.style.ForegroundColorSpan(C.RED), s, b.length, 0)
            b.append(" | ")
        }
        t.text = b; t.setTextColor(C.INK); t.textSize = 32f
    }

    private fun clock(ms: Long) = String.format(Locale.US, "%d:%02d", ms / 60000, (ms / 1000) % 60)

    // MARK: layout

    private var chordNotes: TextView? = null
    private var chartText: TextView? = null

    private fun subtitle(): String = when (tab) {
        0 -> if (instrument.id == "piano") "piano" else if (chromaticMode || instrument.chromatic) "chromatic" else "${instrument.name.lowercase()} · ${tuning()?.name?.lowercase()}"
        1 -> if (listening) "listening…" else "chords"
        else -> "instruments"
    }

    private fun render() {
        content.removeAllViews()
        floating?.let { root.removeView(it) }; floating = null
        needle = null; centsBar = null; keyboard = null; noteText = null; octText = null; centsText = null; hzText = null
        stringButtons.clear(); chordText = null; chordSub = null; chordNotes = null; confText = null; chordBox = null
        keyText = null; tempoText = null; capoText = null; chartText = null; sourceText = null; listenLabel = null; wave = null
        content.setPadding(dp(54), top + dp(2), dp(20), bottom + dp(120))
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(hand("cuerda", 46f).apply { highlight(C.MANGO) }, LinearLayout.LayoutParams(-2, -2))
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(hand(subtitle(), 20f, C.SUB, bold = false).apply { maxLines = 1 })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        when (tab) {
            0 -> if (instrument.id == "piano") pianoScreen() else tunerScreen()
            1 -> chordScreen()
            else -> instrumentsScreen()
        }
        renderNav()
    }

    private fun renderNav() {
        nav.removeAllViews()
        nav.background = object : android.graphics.drawable.Drawable() {
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x40000000 or (C.INK and 0xFFFFFF); strokeWidth = dpf(1.5f)
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(dpf(5f), dpf(4f)), 0f)
            }
            override fun draw(c: android.graphics.Canvas) {
                c.drawColor(0xEBF6F1E4.toInt()); c.drawLine(bounds.left + dpf(40f), 0f, bounds.right.toFloat(), 0f, p)
            }
            override fun setAlpha(a: Int) {}
            override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
            @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
        nav.setPadding(dp(40), dp(12), 0, bottom + dp(12))
        nav.gravity = Gravity.CENTER_VERTICAL
        listOf(Triple("Tune", C.LAGOON, 0), Triple("Chords", C.MANGO, 1), Triple("Instruments", C.PINK, 2)).forEach { (name, col, i) ->
            val on = tab == i
            nav.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER
                addView(hand(name, 24f, if (on) C.INK else C.SUB).apply { if (on) highlight(col) }, LinearLayout.LayoutParams(-2, -2))
                contentDescription = name
                setOnClickListener { switchTab(i) }
                setPadding(0, dp(6), 0, dp(6))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun switchTab(i: Int) {
        if (tab == i) return
        tab = i
        tone.stop()
        scroll.scrollTo(0, 0)
        render()
        startAudio()
    }

    private fun lp(topMargin: Int = 0, w: Int = -1) = LinearLayout.LayoutParams(w, -2).apply { this.topMargin = dp(topMargin) }

    /** A handwritten button with a highlighter swipe. */
    private fun marker(label: String, color: Int, size: Float = 22f, onClick: () -> Unit) = hand(label, size).apply {
        highlight(color); maxLines = 1
        setOnClickListener { onClick() }
    }

    private fun bigNote(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        noteText = hand("♪", 120f, 0x59000000 or (C.INK and 0xFFFFFF))
        octText = hand("", 36f, C.SUB, bold = false).apply { setPadding(dp(8), dp(14), 0, 0) }
        addView(noteText); addView(octText)
    }

    // MARK: Tuner

    private fun tunerScreen() {
        val t = tuning()
        val strings = if (chromaticMode || instrument.chromatic) null else t?.notes
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(hand(String.format(Locale.US, "A4 = %.0f Hz · %s", Notes.a4, if (strings == null) "any note" else if (auto) "auto string" else "string ${picked + 1}"), 22f, C.INK, bold = false),
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(marker("change ›", C.LAGOON) { changeSheet() })
        })
        needle = Needle(this)
        content.addView(needle, LinearLayout.LayoutParams(-1, dp(170)).apply { topMargin = dp(6) })
        content.addView(bigNote(), lp(-6))
        centsText = hand("", 28f).apply { gravity = Gravity.CENTER }
        content.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL; addView(centsText, LinearLayout.LayoutParams(-2, -2)) }, lp(0))
        hzText = text("", 12f, C.SUB).apply { gravity = Gravity.CENTER }
        content.addView(hzText, lp(6))

        if (strings != null) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
            strings.forEachIndexed { i, n ->
                val b = TextView(this).apply {
                    gravity = Gravity.CENTER; includeFontPadding = false
                    val oct = Notes.octave(n).toString()
                    val s = android.text.SpannableStringBuilder(Notes.name(n) + oct)
                    s.setSpan(android.text.style.RelativeSizeSpan(0.42f), s.length - oct.length, s.length, 0)
                    s.setSpan(android.text.style.ForegroundColorSpan(C.SUB), s.length - oct.length, s.length, 0)
                    text = s; textSize = 26f; typeface = Fonts.hand(context); fontVariationSettings = "'wght' 700"
                    setOnClickListener {
                        if (!auto && picked == i) { auto = true; picked = -1 } else { auto = false; picked = i }
                        if (tone.isPlaying) tone.freq = Notes.freq(n.toDouble())
                        render()
                    }
                }
                styleString(b, i, !auto && picked == i)
                stringButtons.add(b)
                val size = if (strings.size > 5) dp(42) else dp(48)
                row.addView(b, LinearLayout.LayoutParams(size, size).apply { leftMargin = dp(3); rightMargin = dp(3) })
            }
            content.addView(row, lp(28))
        }
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(marker(if (auto || strings == null) "auto string ✓" else "auto string", C.LAGOON) { auto = true; picked = -1; chromaticMode = false; render(); startAudio() })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(marker(if (tone.isPlaying) "stop tone" else "reference tone", C.MANGO) { toggleTone() })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(marker(if (chromaticMode) "chromatic ✓" else "chromatic", C.PINK) { chromaticMode = !chromaticMode; render(); startAudio() })
        }, lp(24))
        showIdle()
    }

    /** Strings are circled letters; the one being tuned is filled with highlighter. */
    private fun styleString(b: TextView, i: Int, on: Boolean) {
        val col = intArrayOf(C.LAGOON, C.MANGO, C.PINK, C.CORAL)[i % 4]
        b.setTextColor(C.INK)
        b.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            if (on) setColor(col) else { setColor(0); setStroke(dp(2), C.INK) }
        }
    }

    private fun toggleTone() {
        val t = tuning()
        val target = lastTarget ?: if (!instrument.chromatic && !chromaticMode && t != null) t.notes[if (picked in t.notes.indices) picked else 0] else 69
        val stretch = if (instrument.id == "piano") Notes.pianoStretch(target) else 0.0
        tone.toggle(Notes.freq(target + stretch / 100))
        render()
    }

    // MARK: Piano

    private fun pianoScreen() {
        content.addView(hand("play one key at a time · stretched tuning", 21f, C.SUB, bold = false))
        content.addView(bigNote(), lp(24))
        centsBar = CentsBar(this)
        content.addView(centsBar, LinearLayout.LayoutParams(-1, dp(34)).apply { topMargin = dp(10) })
        content.addView(LinearLayout(this).apply {
            addView(hand("flat", 18f, C.SUB, false), LinearLayout.LayoutParams(0, -2, 1f))
            addView(hand("sharp", 18f, C.SUB, false).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        centsText = hand("", 28f).apply { gravity = Gravity.CENTER }
        content.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL; addView(centsText, LinearLayout.LayoutParams(-2, -2)) }, lp(6))
        hzText = text("", 12f, C.SUB).apply { gravity = Gravity.CENTER }
        content.addView(hzText, lp(6))
        keyboard = Keyboard(this).apply {
            startC = keyboardStart
            onKey = { midi -> lastTarget = midi; lit = midi; invalidate(); tone.start(Notes.freq(midi + Notes.pianoStretch(midi) / 100)) }
        }
        content.addView(keyboard, LinearLayout.LayoutParams(-1, dp(150)).apply { topMargin = dp(26) })
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(marker("‹ lower", C.LAGOON) { moveOctave(-12) })
            addView(hand("tap a key to hear it", 19f, C.SUB, false).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(marker("higher ›", C.LAGOON) { moveOctave(12) })
        }, lp(10))
        content.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL; addView(marker(if (tone.isPlaying) "stop tone" else "reference tone", C.MANGO) { toggleTone() }) }, lp(14))
        showIdle()
    }

    private fun moveOctave(d: Int) {
        keyboardStart = (keyboardStart + d).coerceIn(12, 96)
        keyboard?.apply { startC = keyboardStart; invalidate() }
    }

    // MARK: Chords

    private fun chordScreen() {
        // big chord circled in red pen, its name and how sure
        val headRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        chordText = hand("♪", 110f).apply {
            gravity = Gravity.CENTER; setPadding(dp(14), dp(4), dp(14), dp(10)); minWidth = dp(110)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0); setStroke(dp(3), C.RED)
            }
        }
        headRow.addView(chordText, LinearLayout.LayoutParams(-2, -2))
        headRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
            chordSub = hand("", 26f); addView(chordSub)
            chordNotes = text("", 12f, C.SUB).apply { setPadding(0, dp(2), 0, 0) }; addView(chordNotes)
            confText = hand("", 21f, C.SUB, false).apply { setPadding(0, dp(4), 0, 0) }; addView(confText)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        chordBox = ChordBox(this)
        headRow.addView(chordBox, LinearLayout.LayoutParams(dp(74), dp(92)))
        content.addView(headRow, lp(2))

        // the waveform with the chords written on top
        wave = Wave(this)
        content.addView(wave, LinearLayout.LayoutParams(-1, dp(170)).apply { topMargin = dp(18) })
        replayWave()

        val stats = LinearLayout(this)
        fun stat(label: String, color: Int, gravity: Int): TextView {
            val v = hand("…", 26f).apply { highlight(color); maxLines = 1 }
            stats.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; this.gravity = gravity
                addView(text(label, 11f, C.SUB, 700).apply { letterSpacing = 0.08f }, LinearLayout.LayoutParams(-2, -2))
                addView(v, LinearLayout.LayoutParams(-2, -2))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            return v
        }
        keyText = stat("KEY", C.LAGOON, Gravity.START); tempoText = stat("TEMPO", C.MANGO, Gravity.CENTER_HORIZONTAL); capoText = stat("CAPO", C.PINK, Gravity.END)
        content.addView(stats, lp(18))

        content.addView(LinearLayout(this).apply {
            gravity = Gravity.BOTTOM
            addView(hand("so far", 22f, C.SUB, false), LinearLayout.LayoutParams(0, -2, 1f))
            addView(marker("save as song ›", C.LAGOON, 20f) { saveSong() })
        }, lp(18))
        chartText = hand("", 32f)
        content.addView(chartText, lp(2))

        content.addView(hand("Singing only, no instruments? Cuerda follows the melody and suggests chords that fit it.", 20f, C.SUB, false).apply { setLineSpacing(0f, 1.0f) }, lp(14))
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(marker("saved songs (${songs().length()}) ›", C.MANGO, 21f) { songsSheet() })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            if (history.isNotEmpty()) addView(hand("clear page", 21f, C.RED).apply {
                setOnClickListener { history.clear(); waveLevels.clear(); ear.reset(); saveHistory(); render() }
            })
        }, lp(12))

        // the record button: red pen circle
        val btn = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xF2F6F1E4.toInt()); setStroke(dp(3), C.RED)
            }
            addView(View(context).apply { background = round(C.RED, if (listening) dpf(4f) else dpf(13f)) },
                FrameLayout.LayoutParams(dp(if (listening) 22 else 26), dp(if (listening) 22 else 26), Gravity.CENTER))
            contentDescription = if (listening) "Stop listening" else "Listen for chords"
            setOnClickListener { toggleListening() }
        }
        root.addView(btn, FrameLayout.LayoutParams(dp(68), dp(68), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = bottom + dp(86) })
        floating = btn
        showChord(lastResult)
    }

    /** Waveform data survives switching tabs. */
    private val waveLevels = ArrayList<Pair<Double, Pair<String?, Long>>>()

    private fun replayWave() {
        val w = wave ?: return
        w.clear()
        for ((lvl, m) in waveLevels) w.push(lvl, m.first, m.second)
    }

    private fun toggleListening() {
        if (!hasMic()) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return }
        listening = !listening
        if (listening) {
            if (history.isEmpty()) { ear.reset(); waveLevels.clear(); listenStart = System.currentTimeMillis() }
            else listenStart = System.currentTimeMillis() - (history.last().at + 2000)
        }
        render()
        startAudio()
    }

    // MARK: saved songs

    private val songsFile by lazy { File(filesDir, "songs.json") }
    private fun songs(): JSONArray = runCatching { JSONArray(songsFile.readText()) }.getOrDefault(JSONArray())

    private fun songText(o: JSONObject): String {
        val a = o.getJSONArray("chords")
        val sb = StringBuilder(o.optString("name")).append("\n")
        o.optString("key").takeIf { it.isNotEmpty() }?.let { sb.append("Key: $it  ") }
        o.optString("capo").takeIf { it.isNotEmpty() }?.let { sb.append("Capo: $it") }
        sb.append("\n\n")
        for (i in 0 until a.length()) { val c = a.getJSONObject(i); sb.append(clock(c.getLong("at"))).append("  ").append(c.getString("c")).append("\n") }
        return sb.toString()
    }

    private fun saveSong() {
        if (history.isEmpty()) { Toast.makeText(this, "Listen to a song first", Toast.LENGTH_SHORT).show(); return }
        val f = EditText(this).apply { hint = "Song name"; setText("Song " + SimpleDateFormat("d MMM HH:mm", Locale.ENGLISH).format(Date())) }
        AlertDialog.Builder(this).setTitle("Save as song").setView(LinearLayout(this).apply { setPadding(dp(22), dp(8), dp(22), 0); addView(f, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton("Save") { _, _ ->
                val o = JSONObject().put("name", f.text.toString().ifBlank { "Song" })
                    .put("key", lastResult?.key?.let { Notes.name(it.first) + if (it.second) " minor" else " major" } ?: "")
                    .put("capo", Chords.capo(history.map { it.chord }.distinct())?.let { "fret ${it.first} (${it.second})" } ?: "")
                    .put("chords", JSONArray().apply { history.forEach { put(JSONObject().put("c", it.chord.name()).put("at", it.at)) } })
                val all = songs(); all.put(o); songsFile.writeText(all.toString())
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                render()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun songsSheet() {
        val all = songs()
        if (all.length() == 0) { Toast.makeText(this, "No saved songs yet", Toast.LENGTH_SHORT).show(); return }
        val names = (0 until all.length()).map { all.getJSONObject(it).optString("name") }.reversed().toTypedArray()
        AlertDialog.Builder(this).setTitle("Saved songs").setItems(names) { _, w ->
            val idx = all.length() - 1 - w
            val o = all.getJSONObject(idx)
            AlertDialog.Builder(this).setTitle(o.optString("name")).setMessage(songText(o))
                .setPositiveButton("Share") { _, _ ->
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, songText(o)), "Share chords"))
                }
                .setNeutralButton("Delete") { _, _ -> all.remove(idx); songsFile.writeText(all.toString()); render() }
                .setNegativeButton("Close", null).show()
        }.show()
    }

    private fun saveHistory() {
        val a = JSONArray(); history.forEach { a.put(JSONObject().put("r", it.chord.root).put("q", it.chord.q.id).put("at", it.at)) }
        prefs.edit().putString("history", a.toString()).apply()
    }

    private fun loadHistory() {
        runCatching {
            val a = JSONArray(prefs.getString("history", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val q = Chords.qualities.firstOrNull { it.id == o.getString("q") } ?: continue
                history.add(Hit(Chords.Chord(o.getInt("r"), q), o.getLong("at")))
            }
        }
    }

    // MARK: Instruments

    private fun instrumentsScreen() {
        if (!instrument.chromatic) {
            content.addView(hand("tuning for ${instrument.name.lowercase()}", 22f, C.SUB, false))
            val flow = android.widget.GridLayout(this).apply { columnCount = 1 }
            val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            var row = LinearLayout(this); var used = 0
            val maxW = resources.displayMetrics.widthPixels - dp(80)
            val paint = android.graphics.Paint().apply { typeface = Fonts.hand(this@CuerdaActivity); textSize = dpf(24f) }
            val items = tunings().mapIndexed { i, t -> Triple(t.name.lowercase(), i, C.MARKERS[i % 4]) } + Triple("+ my own", -1, C.LAGOON)
            for ((name, i, col) in items) {
                val w = (paint.measureText(name) + dp(26)).toInt()
                if (used + w > maxW && used > 0) { wrap.addView(row); row = LinearLayout(this); used = 0 }
                val on = i == tuningIdx
                row.addView(hand(name, 24f, if (on || i < 0) C.INK else C.SUB).apply {
                    if (on || i < 0) highlight(if (i < 0) C.LAGOON else col)
                    setOnClickListener { if (i < 0) customTuning() else { tuningIdx = i; render() } }
                    if (name == "custom") setOnLongClickListener { customTuning(); true }
                }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8); bottomMargin = dp(4) })
                used += w
            }
            wrap.addView(row)
            content.addView(wrap, lp(4))
            tuning()?.let { content.addView(text(Instruments.describe(it), 12.5f, C.SUB).apply { setPadding(dp(2), dp(4), 0, dp(6)) }) }
        }
        for (ins in Instruments.all) {
            val on = ins.id == instrumentId
            content.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(46)
                addView(text(if (on) "✓" else "", 15f, C.RED, 800).apply {
                    gravity = Gravity.CENTER; background = round(0, dpf(4f), dp(2), C.INK)
                }, LinearLayout.LayoutParams(dp(22), dp(22)))
                addView(hand(ins.name, 28f).apply { if (on) highlight(C.MANGO) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                val sub = if (ins.tunings.isNotEmpty()) Instruments.describe(ins.tunings[0]) else ins.blurb
                addView(hand(sub, 20f, C.SUB, false))
                setOnClickListener { instrumentId = ins.id; chromaticMode = false; picked = -1; auto = true; tab = 0; render(); startAudio() }
            })
        }
        content.addView(LinearLayout(this).apply { addView(marker(String.format(Locale.US, "concert pitch: A4 = %.0f Hz ›", Notes.a4), C.PINK, 21f) { pickA4() }) }, lp(16))
    }

    private fun changeSheet() {
        val ts = tunings()
        val items = ts.map { (if (it == tuning()) "● " else "") + it.name + "  ·  " + Instruments.describe(it) } +
            listOf("+ Custom tuning…", String.format(Locale.US, "Concert pitch: A4 = %.0f Hz", Notes.a4), "Other instrument…")
        AlertDialog.Builder(this).setTitle(instrument.name).setItems(items.toTypedArray()) { _, w ->
            when {
                w < ts.size -> { tuningIdx = w; chromaticMode = false; picked = -1; render(); startAudio() }
                w == ts.size -> customTuning()
                w == ts.size + 1 -> pickA4()
                else -> switchTab(2)
            }
        }.show()
    }

    private fun pickA4() {
        val opts = (430..446).toList()
        AlertDialog.Builder(this).setTitle("Concert pitch (A4)").setItems(opts.map { "$it Hz" + if (it == 440) " · standard" else if (it == 432) " · 432" else "" }.toTypedArray()) { _, w ->
            Notes.a4 = opts[w].toDouble(); prefs.edit().putFloat("a4", opts[w].toFloat()).apply(); render(); startAudio()
        }.show()
    }

    private fun customTuning() {
        val base = instrument.tunings.firstOrNull() ?: return
        val f = EditText(this).apply { hint = "e.g. D A D G B E"; setText(tunings().last().let { Instruments.describe(it) }) }
        AlertDialog.Builder(this).setTitle("Custom tuning · ${instrument.name}")
            .setMessage("Type the notes from the lowest string. Add an octave number if you need one (D2).")
            .setView(LinearLayout(this).apply { setPadding(dp(22), dp(4), dp(22), 0); addView(f, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton("Save") { _, _ ->
                val parts = f.text.toString().trim().split(Regex("[\\s,]+")).filter { it.isNotBlank() }
                val notes = parts.mapIndexed { i, s -> Notes.parse(s, base.notes.getOrElse(i) { base.notes.last() }) }
                if (notes.isEmpty() || notes.any { it == null }) { Toast.makeText(this, "Couldn't read those notes", Toast.LENGTH_LONG).show(); return@setPositiveButton }
                prefs.edit().putString("custom:$instrumentId", notes.joinToString(",")).apply()
                tuningIdx = tunings().size - 1
                render(); startAudio()
            }.setNegativeButton("Cancel", null).show()
    }
}
