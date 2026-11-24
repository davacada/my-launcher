package com.davacada.cuerda

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/** Notes, instruments, tunings, chords. MIDI numbers: A4 = 69, middle C = 60. */
object Notes {
    val SHARP = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    val FLAT = arrayOf("C", "D♭", "D", "E♭", "E", "F", "G♭", "G", "A♭", "A", "B♭", "B")
    var a4 = 440.0

    fun freq(midi: Double) = a4 * 2.0.pow((midi - 69) / 12.0)
    fun midiOf(f: Double) = 69 + 12 * log2(f / a4)
    fun name(midi: Int, flats: Boolean = false) = (if (flats) FLAT else SHARP)[((midi % 12) + 12) % 12]
    fun octave(midi: Int) = Math.floorDiv(midi, 12) - 1
    fun cents(f: Double, targetMidi: Double) = 1200 * log2(f / freq(targetMidi))

    /** "D", "D2", "Bb3", "F#" → midi; without an octave, the one nearest [near]. */
    fun parse(s: String, near: Int): Int? {
        val m = Regex("""^\s*([A-Ga-g])([#♯b♭]?)(-?\d)?\s*$""").find(s) ?: return null
        val base = intArrayOf(9, 11, 0, 2, 4, 5, 7)["ABCDEFG".indexOf(m.groupValues[1].uppercase())]
        val acc = when (m.groupValues[2]) { "#", "♯" -> 1; "b", "♭" -> -1; else -> 0 }
        val pc = (base + acc + 12) % 12
        m.groupValues[3].toIntOrNull()?.let { return (it + 1) * 12 + pc }
        var best = pc; var bd = 999
        for (o in 0..9) { val c = o * 12 + pc; if (abs(c - near) < bd) { bd = abs(c - near); best = c } }
        return best
    }

    /** Pianos are tuned "stretched": low notes a little flat, high notes a little sharp. */
    fun pianoStretch(midi: Int): Double {
        val n = (midi - 69).toDouble()
        return if (n >= 0) 0.085 * n.pow(1.6) else -0.051 * (-n).pow(1.6)
    }
}

data class Tuning(val name: String, val notes: List<Int>)

data class Instrument(val id: String, val name: String, val blurb: String, val tunings: List<Tuning>) {
    val chromatic get() = id == "chromatic" || id == "piano"
}

object Instruments {
    private fun t(name: String, vararg n: Int) = Tuning(name, n.toList())
    val all = listOf(
        Instrument("guitar", "Guitar", "6 strings", listOf(
            t("Standard", 40, 45, 50, 55, 59, 64), t("Drop D", 38, 45, 50, 55, 59, 64), t("Half step down", 39, 44, 49, 54, 58, 63),
            t("Full step down", 38, 43, 48, 53, 57, 62), t("DADGAD", 38, 45, 50, 55, 57, 62), t("Open G", 38, 43, 50, 55, 59, 62),
            t("Open D", 38, 45, 50, 54, 57, 62), t("Open E", 40, 47, 52, 56, 59, 64), t("Drop C", 36, 43, 48, 53, 57, 62))),
        Instrument("bass", "Bass", "4 or 5 strings", listOf(t("Standard", 28, 33, 38, 43), t("Drop D", 26, 33, 38, 43), t("5-string", 23, 28, 33, 38, 43))),
        Instrument("ukulele", "Ukulele", "soprano, concert, tenor", listOf(t("Standard", 67, 60, 64, 69), t("Low G", 55, 60, 64, 69), t("Baritone", 50, 55, 59, 64))),
        Instrument("violin", "Violin", "G D A E", listOf(t("Standard", 55, 62, 69, 76))),
        Instrument("viola", "Viola", "C G D A", listOf(t("Standard", 48, 55, 62, 69))),
        Instrument("cello", "Cello", "C G D A", listOf(t("Standard", 36, 43, 50, 57))),
        Instrument("mandolin", "Mandolin", "G D A E · pairs", listOf(t("Standard", 55, 62, 69, 76))),
        Instrument("banjo", "Banjo", "5 strings", listOf(t("Open G", 67, 50, 55, 59, 62), t("Double C", 67, 48, 55, 60, 62), t("Open D", 66, 50, 54, 57, 62))),
        Instrument("piano", "Piano", "88 keys · stretched tuning", emptyList()),
        Instrument("chromatic", "Chromatic", "any note, any instrument", emptyList()),
    )
    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all[0]
    fun describe(t: Tuning) = t.notes.joinToString(" ") { Notes.name(it) }
}

/** Chord qualities: intervals and weights (root counts most, extensions less). */
data class Quality(val id: String, val suffix: String, val name: String, val intervals: IntArray, val penalty: Double)

object Chords {
    val qualities = listOf(
        Quality("maj", "", "major", intArrayOf(0, 4, 7), 0.0),
        Quality("min", "m", "minor", intArrayOf(0, 3, 7), 0.0),
        Quality("7", "7", "dominant 7", intArrayOf(0, 4, 7, 10), 0.035),
        Quality("m7", "m7", "minor 7", intArrayOf(0, 3, 7, 10), 0.035),
        Quality("maj7", "maj7", "major 7", intArrayOf(0, 4, 7, 11), 0.04),
        Quality("sus4", "sus4", "suspended 4", intArrayOf(0, 5, 7), 0.05),
        Quality("sus2", "sus2", "suspended 2", intArrayOf(0, 2, 7), 0.05),
        Quality("dim", "dim", "diminished", intArrayOf(0, 3, 6), 0.06),
    )

    data class Chord(val root: Int, val q: Quality) {
        fun name(flats: Boolean = false) = Notes.name(root, flats) + q.suffix
        fun notes() = q.intervals.map { (root + it) % 12 }
    }

    /** Guitar shapes: per string (low E → high E) fret, -1 muted. Open shapes where they exist, otherwise barre shapes. */
    private val open = mapOf(
        "C" to intArrayOf(-1, 3, 2, 0, 1, 0), "D" to intArrayOf(-1, -1, 0, 2, 3, 2), "E" to intArrayOf(0, 2, 2, 1, 0, 0),
        "G" to intArrayOf(3, 2, 0, 0, 0, 3), "A" to intArrayOf(-1, 0, 2, 2, 2, 0), "Am" to intArrayOf(-1, 0, 2, 2, 1, 0),
        "Em" to intArrayOf(0, 2, 2, 0, 0, 0), "Dm" to intArrayOf(-1, -1, 0, 2, 3, 1), "E7" to intArrayOf(0, 2, 0, 1, 0, 0),
        "A7" to intArrayOf(-1, 0, 2, 0, 2, 0), "D7" to intArrayOf(-1, -1, 0, 2, 1, 2), "G7" to intArrayOf(3, 2, 0, 0, 0, 1),
        "C7" to intArrayOf(-1, 3, 2, 3, 1, 0), "B7" to intArrayOf(-1, 2, 1, 2, 0, 2), "Cmaj7" to intArrayOf(-1, 3, 2, 0, 0, 0),
        "Fmaj7" to intArrayOf(-1, -1, 3, 2, 1, 0), "Amaj7" to intArrayOf(-1, 0, 2, 1, 2, 0), "Dmaj7" to intArrayOf(-1, -1, 0, 2, 2, 2),
        "Gmaj7" to intArrayOf(3, 2, 0, 0, 0, 2), "Emaj7" to intArrayOf(0, 2, 1, 1, 0, 0),
        "Am7" to intArrayOf(-1, 0, 2, 0, 1, 0), "Em7" to intArrayOf(0, 2, 0, 0, 0, 0), "Dm7" to intArrayOf(-1, -1, 0, 2, 1, 1),
        "Dsus4" to intArrayOf(-1, -1, 0, 2, 3, 3), "Asus4" to intArrayOf(-1, 0, 2, 2, 3, 0), "Esus4" to intArrayOf(0, 2, 2, 2, 0, 0),
        "Dsus2" to intArrayOf(-1, -1, 0, 2, 3, 0), "Asus2" to intArrayOf(-1, 0, 2, 2, 0, 0), "Csus2" to intArrayOf(-1, 3, 0, 0, 3, 3),
        "Gsus4" to intArrayOf(3, 3, 0, 0, 1, 3), "Csus4" to intArrayOf(-1, 3, 3, 0, 1, 1),
        "Bdim" to intArrayOf(-1, 2, 3, 4, 3, -1), "F" to intArrayOf(1, 3, 3, 2, 1, 1), "Fm" to intArrayOf(1, 3, 3, 1, 1, 1),
    )
    private val eShape = mapOf("maj" to intArrayOf(0, 2, 2, 1, 0, 0), "min" to intArrayOf(0, 2, 2, 0, 0, 0), "7" to intArrayOf(0, 2, 0, 1, 0, 0),
        "m7" to intArrayOf(0, 2, 0, 0, 0, 0), "maj7" to intArrayOf(0, -1, 1, 1, 0, -1), "sus4" to intArrayOf(0, 2, 2, 2, 0, 0),
        "sus2" to intArrayOf(0, 2, 4, 4, 0, 0), "dim" to intArrayOf(0, 1, 2, 0, -1, -1))
    private val aShape = mapOf("maj" to intArrayOf(-1, 0, 2, 2, 2, 0), "min" to intArrayOf(-1, 0, 2, 2, 1, 0), "7" to intArrayOf(-1, 0, 2, 0, 2, 0),
        "m7" to intArrayOf(-1, 0, 2, 0, 1, 0), "maj7" to intArrayOf(-1, 0, 2, 1, 2, 0), "sus4" to intArrayOf(-1, 0, 2, 2, 3, 0),
        "sus2" to intArrayOf(-1, 0, 2, 2, 0, 0), "dim" to intArrayOf(-1, 0, 1, 2, 1, -1))

    /** Frets for a guitar in standard tuning, plus whether the lowest fretted note is a barre. */
    fun shape(c: Chord): Pair<IntArray, Boolean> {
        open[c.name()]?.let { return it to (c.name() in setOf("F", "Fm")) }
        open[c.name(true)]?.let { return it to false }
        val eFret = ((c.root - 4) % 12 + 12) % 12           // root on the low E string
        val aFret = ((c.root - 9) % 12 + 12) % 12           // root on the A string
        val useE = (eFret in 1..7 && eFret <= aFret) || aFret == 0
        val (base, f) = if (useE) eShape[c.q.id]!! to eFret else aShape[c.q.id]!! to aFret
        val fr = if (f == 0) 12 else f
        return IntArray(6) { if (base[it] < 0) -1 else base[it] + fr } to true
    }

    // Krumhansl key profiles
    private val major = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val minor = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    /** Best key for a pitch-class profile: (tonic, isMinor, score). */
    fun key(chroma: DoubleArray): Triple<Int, Boolean, Double> {
        var best = Triple(0, false, -2.0)
        for (minorKey in listOf(false, true)) for (t in 0 until 12) {
            val prof = if (minorKey) minor else major
            val r = corr(chroma) { i -> prof[((i - t) % 12 + 12) % 12] }
            if (r > best.third) best = Triple(t, minorKey, r)
        }
        return best
    }

    private fun corr(a: DoubleArray, b: (Int) -> Double): Double {
        val ma = a.average(); var mb = 0.0; for (i in 0 until 12) mb += b(i); mb /= 12
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in 0 until 12) { val x = a[i] - ma; val y = b(i) - mb; num += x * y; da += x * x; db += y * y }
        return if (da == 0.0 || db == 0.0) 0.0 else num / Math.sqrt(da * db)
    }

    /** The chords that belong to a key (I ii iii IV V vi, plus V7). */
    fun diatonic(tonic: Int, minorKey: Boolean): List<Chord> {
        val maj = qualities[0]; val min = qualities[1]; val dom = qualities[2]
        val steps = if (!minorKey) listOf(0 to maj, 2 to min, 4 to min, 5 to maj, 7 to maj, 9 to min, 7 to dom)
        else listOf(0 to min, 3 to maj, 5 to min, 7 to min, 7 to maj, 8 to maj, 10 to maj, 7 to dom)
        return steps.map { (s, q) -> Chord((tonic + s) % 12, q) }
    }

    /** Capo that lets most chords be played as easy open shapes. */
    fun capo(chords: List<Chord>): Pair<Int, String>? {
        if (chords.isEmpty()) return null
        val easy = setOf("C", "D", "E", "G", "A", "Am", "Em", "Dm", "E7", "A7", "D7", "G7", "C7", "B7", "Am7", "Em7", "Dm7", "Cmaj7", "Fmaj7")
        var best = 0; var bestScore = -1
        for (capo in 0..7) {
            val score = chords.count { c -> Chord(((c.root - capo) % 12 + 12) % 12, c.q).name() in easy }
            if (score > bestScore) { bestScore = score; best = capo }
        }
        val asNow = chords.count { it.name() in easy }
        if (best == 0 || bestScore <= asNow) return null
        val shapes = chords.distinctBy { it.name() }.take(4).joinToString(" ") { Chord(((it.root - best) % 12 + 12) % 12, it.q).name() }
        return best to shapes
    }

    fun nearestMidi(f: Double) = Notes.midiOf(f).roundToInt()
}
