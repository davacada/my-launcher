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
import android.widget.ImageView
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
    private var confBar: Bar? = null
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

        root = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        root.addView(ImageView(this).apply { setImageResource(R.drawable.cuerda_bg); scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply { background = grain() }, FrameLayout.LayoutParams(-1, -1))
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
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
        noteText?.text = Notes.name(target)
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
            setTextColor(if (abs(c) <= 10) C.MANGO else C.CORAL)
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
        centsText?.apply { text = if (instrument.id == "piano") "Play one key" else "Play a string"; setTextColor(C.SUB) }
        hzText?.text = if (hasMic()) String.format(Locale.US, "A4 = %.0f Hz", Notes.a4) else "Allow the microphone to tune"
    }

    // MARK: chord logic

    private fun onChord(r: ChordEar.Result) {
        lastResult = r
        val c = r.chord
        if (c != null && r.loud) {
            if (history.lastOrNull()?.chord != c) history.add(Hit(c, System.currentTimeMillis() - listenStart))
        }
        showChord(r)
    }

    private fun showChord(r: ChordEar.Result?) {
        val c = r?.chord ?: history.lastOrNull()?.chord
        listenLabel?.text = if (listening) "LISTENING" else "TAP TO LISTEN"
        sourceText?.text = when {
            !listening -> if (history.isEmpty()) "put the phone near the music" else "paused"
            r == null || !r.loud -> "waiting for sound…"
            r.suggested -> "singing · chords that fit"
            else -> "song from speaker"
        }
        chordText?.text = c?.name() ?: "–"
        chordSub?.text = c?.let { ch -> "${Notes.name(ch.root)} ${ch.q.name} · " + ch.notes().joinToString(" ") { Notes.name(it) } } ?: "No chord yet"
        val conf = r?.confidence ?: 0.0
        confBar?.apply { value = conf.toFloat(); color = if (r?.suggested == true) C.LAGOON else C.MANGO; invalidate() }
        confText?.text = when {
            c == null -> ""
            r?.suggested == true -> "suggested · ${(conf * 100).roundToInt()}%"
            conf > 0.66 -> "sure · ${(conf * 100).roundToInt()}%"
            conf > 0.33 -> "likely · ${(conf * 100).roundToInt()}%"
            else -> "guessing · ${(conf * 100).roundToInt()}%"
        }
        chordBox?.apply {
            if (c != null) { val (f, b) = Chords.shape(c); frets = f; barre = b } else frets = null
            invalidate()
        }
        keyText?.text = r?.key?.let { Notes.name(it.first) + if (it.second) " minor" else " major" } ?: "…"
        tempoText?.text = r?.bpm?.let { "$it bpm" } ?: "…"
        val cp = Chords.capo(history.map { it.chord }.distinct())
        capoText?.text = cp?.let { "fret ${it.first}" } ?: "none"
        renderHistory()
    }

    private fun renderHistory() {
        val row = historyRow ?: return
        row.removeAllViews()
        val colors = intArrayOf(C.LAGOON, C.MANGO, C.HIB)
        if (history.isEmpty()) {
            row.addView(text("Chords show up here as the song plays.", 13f, C.SUB).apply { setPadding(dp(4), dp(10), 0, dp(10)) })
            return
        }
        history.forEachIndexed { i, h ->
            val last = i == history.size - 1
            val col = colors[i % 3]
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = if (last) round(C.CORAL, dpf(16f)) else round(tint(col, 0x24), dpf(16f), dp(1), tint(col, 0x66))
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(text(h.chord.name(), 22f, if (last) 0xFF2A0F08.toInt() else col, 800))
                addView(mono(clock(h.at), 10.5f, if (last) 0xFF2A0F08.toInt() else col).apply { setPadding(0, dp(2), 0, 0); alpha = 0.8f })
                minimumWidth = dp(72)
            }
            row.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        }
        (row.parent as? HorizontalScrollView)?.post { (row.parent as HorizontalScrollView).fullScroll(View.FOCUS_RIGHT) }
    }

    private fun clock(ms: Long) = String.format(Locale.US, "%d:%02d", ms / 60000, (ms / 1000) % 60)

    // MARK: layout

    private fun render() {
        content.removeAllViews()
        floating?.let { root.removeView(it) }; floating = null
        needle = null; centsBar = null; keyboard = null; noteText = null; octText = null; centsText = null; hzText = null
        stringButtons.clear(); chordText = null; chordSub = null; confBar = null; confText = null; chordBox = null
        keyText = null; tempoText = null; capoText = null; historyRow = null; sourceText = null; listenLabel = null
        content.setPadding(dp(20), top + dp(4), dp(20), bottom + dp(120))
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(wordmark(34f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(dots())
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
        nav.setBackgroundColor(0xF017213A.toInt())
        nav.setPadding(0, dp(12), 0, bottom + dp(10))
        listOf(Triple("Tune", R.drawable.i_tune, 0), Triple("Chords", R.drawable.i_chords, 1), Triple("Instruments", R.drawable.i_inst, 2)).forEach { (name, icon, i) ->
            val on = tab == i
            nav.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                background = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, null)
                addView(ImageView(context).apply { setImageResource(icon); setColorFilter(if (on) C.CORAL else C.SUB) }, LinearLayout.LayoutParams(dp(22), dp(22)))
                addView(text(name, 11f, if (on) C.CORAL else C.SUB, 600).apply { setPadding(0, dp(5), 0, 0) }, LinearLayout.LayoutParams(-2, -2))
                contentDescription = name
                setOnClickListener { switchTab(i) }
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

    private fun pill(label: String, bg: Int, fg: Int, onClick: () -> Unit) = text(label, 12.5f, fg, 700).apply {
        gravity = Gravity.CENTER
        background = RippleDrawable(ColorStateList.valueOf(0x33000000), round(bg, dpf(14f)), null)
        setPadding(dp(10), dp(11), dp(10), dp(11))
        setOnClickListener { onClick() }
    }

    private fun bigNote(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        noteText = text("–", 96f, C.INK, 800).apply { letterSpacing = -0.04f }
        octText = mono("", 26f, C.SUB).apply { setPadding(dp(4), dp(10), 0, 0) }
        addView(noteText); addView(octText)
    }

    // MARK: Tuner

    private fun tunerScreen() {
        val t = tuning()
        val strings = if (chromaticMode || instrument.chromatic) null else t?.notes
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(eyebrow(if (strings == null) "Chromatic" else "${instrument.name} · ${t?.name}"))
                addView(text(String.format(Locale.US, "A4 = %.0f Hz · %s", Notes.a4, if (strings == null) "any note" else if (auto) "auto" else "string ${picked + 1}"), 13f, C.SUB).apply { setPadding(0, dp(3), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("Change ▾", 13f, C.ON_BRIGHT, 700).apply {
                background = round(C.MANGO, dpf(14f)); setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { changeSheet() }
            })
        })
        needle = Needle(this)
        content.addView(needle, LinearLayout.LayoutParams(-1, dp(180)).apply { topMargin = dp(16) })
        content.addView(bigNote(), lp(-4))
        centsText = text("", 15f, C.SUB, 700).apply { gravity = Gravity.CENTER }
        content.addView(centsText, lp(4))
        hzText = mono("", 12.5f).apply { gravity = Gravity.CENTER }
        content.addView(hzText, lp(4))

        if (strings != null) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
            strings.forEachIndexed { i, n ->
                val b = TextView(this).apply {
                    gravity = Gravity.CENTER; includeFontPadding = false
                    val s = android.text.SpannableStringBuilder(Notes.name(n) + "\n" + Notes.octave(n))
                    s.setSpan(android.text.style.AbsoluteSizeSpan(10, true), s.length - Notes.octave(n).toString().length, s.length, 0)
                    text = s; textSize = 20f; typeface = Fonts.get(context, 800)
                    setOnClickListener {
                        if (!auto && picked == i) { auto = true; picked = -1 } else { auto = false; picked = i }
                        if (tone.isPlaying) tone.freq = Notes.freq(n.toDouble())
                        render()
                    }
                }
                styleString(b, i, !auto && picked == i)
                stringButtons.add(b)
                row.addView(b, LinearLayout.LayoutParams(0, dp(58), 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
            }
            content.addView(row, lp(28))
        }
        content.addView(LinearLayout(this).apply {
            addView(pill(if (auto || strings == null) "Auto string ✓" else "Auto string", C.LAGOON, C.ON_BRIGHT) { auto = true; picked = -1; chromaticMode = false; render(); startAudio() },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(pill(if (tone.isPlaying) "Stop tone" else "Reference tone", C.MANGO, C.ON_BRIGHT) { toggleTone() },
                LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
            addView(pill(if (chromaticMode) "Chromatic ✓" else "Chromatic", C.HIB, C.ON_BRIGHT) { chromaticMode = !chromaticMode; render(); startAudio() },
                LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        }, lp(16))
        showIdle()
    }

    private fun styleString(b: TextView, i: Int, on: Boolean) {
        val cols = intArrayOf(C.CORAL, C.MANGO, C.LAGOON)
        val col = cols[i % 3]
        b.setTextColor(if (on) C.ON_BRIGHT else col)
        b.background = if (on) round(col, dpf(16f)) else round(tint(col, 0x24), dpf(16f), dp(1), tint(col, 0x66))
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
        content.addView(eyebrow("Piano"))
        content.addView(text("Play one key at a time · stretched tuning on", 13f, C.SUB).apply { setPadding(0, dp(3), 0, 0) })
        content.addView(bigNote(), lp(34))
        centsBar = CentsBar(this)
        content.addView(centsBar, LinearLayout.LayoutParams(-1, dp(30)).apply { topMargin = dp(14); leftMargin = dp(10); rightMargin = dp(10) })
        content.addView(LinearLayout(this).apply {
            setPadding(dp(10), 0, dp(10), 0)
            addView(mono("−50", 11f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(mono("0", 11f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(mono("+50", 11f).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        centsText = text("", 15f, C.SUB, 700).apply { gravity = Gravity.CENTER }
        content.addView(centsText, lp(10))
        hzText = mono("", 12.5f).apply { gravity = Gravity.CENTER }
        content.addView(hzText, lp(4))
        keyboard = Keyboard(this).apply {
            startC = keyboardStart
            onKey = { midi -> lastTarget = midi; lit = midi; invalidate(); tone.start(Notes.freq(midi + Notes.pianoStretch(midi) / 100)) }
        }
        content.addView(keyboard, LinearLayout.LayoutParams(-1, dp(150)).apply { topMargin = dp(30) })
        content.addView(LinearLayout(this).apply {
            addView(text("‹ lower octave", 12.5f, C.LAGOON, 600).apply { setOnClickListener { moveOctave(-12) }; setPadding(0, dp(10), 0, dp(10)) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("tap a key to hear it", 12.5f, C.SUB).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10)) }, LinearLayout.LayoutParams(0, -2, 1.2f))
            addView(text("higher ›", 12.5f, C.LAGOON, 600).apply { gravity = Gravity.END; setOnClickListener { moveOctave(12) }; setPadding(0, dp(10), 0, dp(10)) }, LinearLayout.LayoutParams(0, -2, 1f))
        }, lp(8))
        content.addView(pill(if (tone.isPlaying) "Stop tone" else "Reference tone", C.MANGO, C.ON_BRIGHT) { toggleTone() }, lp(8))
        showIdle()
    }

    private fun moveOctave(d: Int) {
        keyboardStart = (keyboardStart + d).coerceIn(12, 96)
        keyboard?.apply { startC = keyboardStart; invalidate() }
    }

    // MARK: Chords

    private fun chordScreen() {
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            listenLabel = eyebrow(if (listening) "Listening" else "Tap to listen")
            addView(listenLabel, LinearLayout.LayoutParams(0, -2, 1f))
            addView(View(context).apply { background = round(if (listening) C.CORAL else C.SUB, dpf(4f)) }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(6) })
            sourceText = text("", 12.5f, C.SUB)
            addView(sourceText)
        })
        val mid = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chordText = text("–", 92f, C.INK, 800).apply { letterSpacing = -0.04f; maxLines = 1 }
        left.addView(chordText)
        chordSub = text("", 14f, C.SUB).apply { setPadding(0, dp(6), 0, 0) }
        left.addView(chordSub)
        confBar = Bar(this)
        left.addView(confBar, LinearLayout.LayoutParams(dp(160), dp(6)).apply { topMargin = dp(10) })
        confText = text("", 11.5f, C.SUB).apply { setPadding(0, dp(5), 0, 0) }
        left.addView(confText)
        mid.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        chordBox = ChordBox(this)
        mid.addView(chordBox, LinearLayout.LayoutParams(dp(92), dp(112)))
        content.addView(mid, lp(18))

        val stats = LinearLayout(this)
        fun stat(label: String, color: Int, i: Int): TextView {
            val v = text("…", 16f, C.ON_BRIGHT, 800).apply { gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0); maxLines = 1 }
            stats.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = round(color, dpf(14f)); setPadding(0, dp(10), 0, dp(10))
                addView(text(label, 11f, C.ON_BRIGHT, 700).apply { alpha = 0.7f })
                addView(v)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(8) })
            return v
        }
        keyText = stat("KEY", C.LAGOON, 0); tempoText = stat("TEMPO", C.MANGO, 1); capoText = stat("CAPO", C.HIB, 2)
        content.addView(stats, lp(22))

        content.addView(LinearLayout(this).apply {
            gravity = Gravity.BOTTOM
            addView(eyebrow("So far", C.CORAL), LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("save as song ›", 12.5f, C.LAGOON, 600).apply { setOnClickListener { saveSong() }; setPadding(dp(6), dp(6), 0, dp(6)) })
        }, lp(22))
        historyRow = LinearLayout(this)
        content.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(historyRow) }, lp(8))

        content.addView(text("Singing only, no instruments? Cuerda hears the melody and suggests the chords that fit it.", 12.5f, C.SUB).apply {
            background = round(C.CARD_DARK, dpf(16f)); setPadding(dp(14), dp(12), dp(14), dp(12)); setLineSpacing(0f, 1.25f)
        }, lp(14))
        content.addView(LinearLayout(this).apply {
            addView(text("Saved songs (${songs().length()}) ›", 13f, C.MANGO, 700).apply { setOnClickListener { songsSheet() }; setPadding(0, dp(12), 0, dp(12)) }, LinearLayout.LayoutParams(0, -2, 1f))
            if (history.isNotEmpty()) addView(text("Clear", 13f, C.SUB, 600).apply {
                setOnClickListener { history.clear(); ear.reset(); saveHistory(); render() }; setPadding(dp(12), dp(12), 0, dp(12))
            })
        }, lp(6))

        // the big listen button, above the menu
        val btn = FrameLayout(this).apply {
            background = round(C.CORAL, dpf(36f))
            addView(if (listening) View(context).apply { background = round(0xFF2A0F08.toInt(), dpf(5f)) }
                    else ImageView(context).apply { setImageResource(R.drawable.i_mic); setColorFilter(0xFF2A0F08.toInt()) },
                FrameLayout.LayoutParams(if (listening) dp(22) else dp(30), if (listening) dp(22) else dp(30), Gravity.CENTER))
            contentDescription = if (listening) "Stop listening" else "Listen for chords"
            setOnClickListener { toggleListening() }
            elevation = dpf(6f)
        }
        root.addView(btn, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = bottom + dp(96) })
        floating = btn
        showChord(lastResult)
    }

    private fun toggleListening() {
        if (!hasMic()) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return }
        listening = !listening
        if (listening) {
            if (history.isEmpty()) { ear.reset(); listenStart = System.currentTimeMillis() }
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
        content.addView(eyebrow("Instruments"))
        if (!instrument.chromatic) {
            val chips = LinearLayout(this)
            val cols = intArrayOf(C.CORAL, C.MANGO, C.LAGOON, C.HIB)
            tunings().forEachIndexed { i, t ->
                val on = i == tuningIdx
                val col = cols[i % 4]
                chips.addView(text(t.name, 12f, if (on) C.ON_BRIGHT else col, 700).apply {
                    background = round(if (on) col else tint(col, 0x24), dpf(13f)); setPadding(dp(11), dp(7), dp(11), dp(7))
                    setOnClickListener { tuningIdx = i; render() }
                    setOnLongClickListener { if (t.name == "Custom") customTuning(); true }
                }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
            }
            chips.addView(text("+ custom", 12f, C.LAGOON, 700).apply {
                background = round(tint(C.LAGOON, 0x24), dpf(13f)); setPadding(dp(11), dp(7), dp(11), dp(7))
                setOnClickListener { customTuning() }
            })
            content.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, lp(12))
            tuning()?.let { content.addView(mono(Instruments.describe(it), 12.5f, C.SUB).apply { setPadding(dp(4), dp(8), 0, 0) }) }
        }
        fun group(title: String, items: List<Instrument>) {
            content.addView(eyebrow(title, C.CORAL).apply { setPadding(dp(4), dp(20), 0, dp(8)) })
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(C.CARD_DARK, dpf(20f)); setPadding(dp(16), dp(2), dp(16), dp(2)) }
            items.forEachIndexed { i, ins ->
                val on = ins.id == instrumentId
                box.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(11), 0, dp(11))
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text(ins.name, 15f, if (on) C.CORAL else C.INK, if (on) 800 else 600))
                        val sub = if (ins.tunings.isNotEmpty()) Instruments.describe(ins.tunings[0]) else ins.blurb
                        addView(text(if (ins.tunings.isNotEmpty() && ins.id in setOf("guitar", "bass", "banjo")) "${ins.blurb} · $sub" else sub, 12f, C.SUB).apply { setPadding(0, dp(2), 0, 0) })
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(text(if (on) "●" else "›", 15f, if (on) C.CORAL else C.SUB))
                    setOnClickListener { instrumentId = ins.id; chromaticMode = false; picked = -1; auto = true; tab = 0; render(); startAudio() }
                })
                if (i < items.size - 1) box.addView(View(this).apply { setBackgroundColor(C.LINE) }, LinearLayout.LayoutParams(-1, 1))
            }
            content.addView(box)
        }
        group("Strings", Instruments.all.filter { !it.chromatic })
        group("Keys & more", Instruments.all.filter { it.chromatic })
        content.addView(text(String.format(Locale.US, "Concert pitch: A4 = %.0f Hz ›", Notes.a4), 13f, C.MANGO, 700).apply {
            setPadding(dp(4), dp(16), 0, dp(8)); setOnClickListener { pickA4() }
        })
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
