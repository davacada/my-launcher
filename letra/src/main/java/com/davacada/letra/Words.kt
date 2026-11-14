package com.davacada.letra

import android.content.Context
import java.text.Normalizer

/**
 * Syllables and rhymes for English and Spanish, worked out offline from spelling.
 * Rhymes come from a bundled list of common words (most common first).
 */
object Words {
    @Volatile private var en: List<String> = emptyList()
    @Volatile private var es: List<String> = emptyList()
    @Volatile private var enSet: Set<String> = emptySet()
    @Volatile private var esSet: Set<String> = emptySet()

    @Synchronized fun load(ctx: Context) {
        if (en.isNotEmpty()) return
        val e = ctx.assets.open("words_en.txt").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val s = ctx.assets.open("words_es.txt").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        enSet = HashSet(e); esSet = HashSet(s); es = s; en = e
    }

    private fun plain(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
    private const val ES_MARKS = "áéíóúñü¿¡"

    /** Spanish if it has Spanish letters or is a Spanish word that isn't English. */
    fun isSpanish(word: String): Boolean {
        val w = word.lowercase()
        if (w.any { it in ES_MARKS }) return true
        return w in esSet && w !in enSet
    }

    fun lineIsSpanish(line: String): Boolean {
        val ws = tokens(line)
        if (ws.isEmpty()) return false
        var s = 0; var e = 0
        for (w in ws) { val l = w.lowercase(); if (l.any { it in ES_MARKS }) s += 2; if (l in esSet) s++; if (l in enSet) e++ }
        return s > e
    }

    fun tokens(line: String) = Regex("[\\p{L}']+").findAll(line).map { it.value }.toList()

    // ---------- syllables ----------

    fun syllables(line: String): Int {
        val sp = lineIsSpanish(line)
        return tokens(line).sumOf { if (sp) esSyl(it) else enSyl(it) }
    }

    private fun enSyl(word: String): Int {
        var w = word.lowercase().replace("'", "")
        if (w.length <= 3) return 1
        w = w.replace(Regex("(?:[^laeiouy]es|ed|[^laeiouy]e)$"), "").replace(Regex("^y"), "")
        val n = Regex("[aeiouy]{1,2}").findAll(w).count()
        return n.coerceAtLeast(1)
    }

    private val STRONG = "aeoáéó"
    private fun esSyl(word: String): Int {
        val w = word.lowercase().replace("qu", "q").replace("gue", "ge").replace("gui", "gi")
        var n = 0; var prevV = false; var prev = ' '
        for (ch in w) {
            val v = ch in "aeiouáéíóúü"
            if (v) {
                // two strong vowels, or an accented weak vowel next to a vowel, split into two syllables
                if (!prevV) n++
                else if ((ch in STRONG && prev in STRONG) || ch in "íú" || prev in "íú") n++
            }
            prevV = v; prev = ch
        }
        return n.coerceAtLeast(1)
    }

    // ---------- rhymes ----------

    /** The part of a word that has to match for a rhyme: from its last stressed vowel to the end. */
    fun tail(word: String, spanish: Boolean): String {
        val w = word.lowercase().replace("'", "")
        return if (spanish) esTail(w) else enTail(w)
    }

    private fun enTail(w: String): String {
        val groups = Regex("[aeiouy]+").findAll(w).toList()
        if (groups.isEmpty()) return w
        var g = groups.last()
        // a silent final e ("time", "love") belongs to the vowel before it
        if (groups.size > 1 && g.range.first == w.length - 1 && w.endsWith("e")) g = groups[groups.size - 2]
        var t = w.substring(g.range.first)
        // "-ing", "-er", "-y" endings: include the previous vowel so "singing" rhymes with "bringing"
        if (groups.size > 1 && (t == "ing" || t.startsWith("er") || t == "y" || t == "ies" || t == "ed")) {
            val p = groups[groups.indexOf(g) - 1]
            t = w.substring(p.range.first)
        }
        return t
    }

    private fun esTail(w0: String): String {
        val accented = w0.indexOfFirst { it in "áéíóú" }
        val w = plain(w0)
        val vowels = Regex("[aeiou]+").findAll(w).toList()
        if (vowels.isEmpty()) return w
        val stressIdx = when {
            accented >= 0 -> vowels.indexOfLast { it.range.first <= accented }.coerceAtLeast(0)
            // ends in a vowel, n or s: stress on the one before last; otherwise on the last
            w.last() in "aeiouns" && vowels.size > 1 -> vowels.size - 2
            else -> vowels.size - 1
        }
        val g = vowels[stressIdx]
        // inside a diphthong the stress sits on the strong vowel
        val strong = g.value.indexOfLast { it in "aeo" }
        val start = g.range.first + if (strong >= 0) strong else g.value.length - 1
        return w.substring(start)
    }

    /** Assonance key for Spanish: just the vowels of the tail ("amor" / "corazón" -> "o"). */
    private fun vowelsOf(t: String) = t.filter { it in "aeiou" }

    fun rhymes(word: String, limit: Int = 24): List<String> {
        val sp = isSpanish(word)
        val list = if (sp) es else en
        val t = tail(word, sp)
        val me = plain(word.lowercase())
        if (t.isEmpty()) return emptyList()
        val perfect = ArrayList<String>(); val near = ArrayList<String>()
        for (c in list) {
            if (c.length < 2 || plain(c) == me) continue
            val ct = tail(c, sp)
            if (ct == t) { if (perfect.size < limit) perfect.add(c) }
            else if (sp && vowelsOf(ct) == vowelsOf(t) && ct.length == t.length && near.size < limit) near.add(c)
            else if (!sp && t.length >= 3 && ct.length >= 3 && ct.takeLast(3) == t.takeLast(3) && near.size < limit) near.add(c)
            if (perfect.size >= limit) break
        }
        return (perfect + near).distinct().take(limit)
    }

    /** Last word of a lyric line, or null. */
    fun lastWord(line: String) = tokens(line).lastOrNull()

    /** Lines (by index) whose last words rhyme with another line's, grouped: index -> group number. */
    fun rhymeGroups(lines: List<String>): Map<Int, Int> {
        val keys = lines.map { l -> lastWord(l)?.let { w -> val sp = isSpanish(w) || lineIsSpanish(l); tail(w, sp).let { if (sp) vowelsOf(it) + "|" + it.takeLast(1) else it } } }
        val byKey = HashMap<String, MutableList<Int>>()
        keys.forEachIndexed { i, k -> if (k != null && k.length >= 2) byKey.getOrPut(k) { mutableListOf() }.add(i) }
        val out = HashMap<Int, Int>(); var g = 0
        byKey.values.filter { it.size > 1 }.sortedBy { it.first() }.forEach { idx -> idx.forEach { out[it] = g }; g++ }
        return out
    }
}

/** Chords: transposing, and the list shown in the chord picker. */
object Chords {
    val SHARP = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val FLAT = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
    val KINDS = listOf("", "m", "7", "maj7", "m7", "sus2", "sus4", "add9", "dim", "6")

    private fun idx(root: String) = SHARP.indexOf(root).takeIf { it >= 0 } ?: FLAT.indexOf(root)

    fun transpose(chord: String, steps: Int): String {
        val m = Regex("^([A-G][#b]?)(.*?)(?:/([A-G][#b]?))?$").find(chord) ?: return chord
        val (root, rest, bass) = m.destructured
        fun mv(r: String): String { val i = idx(r); if (i < 0) return r; val n = ((i + steps) % 12 + 12) % 12; return if (r.contains('b')) FLAT[n] else SHARP[n] }
        return mv(root) + rest + if (bass.isNotEmpty()) "/" + mv(bass) else ""
    }

    /** Chords that belong to a major or minor key, for the quick row in the picker. */
    fun inKey(key: String): List<String> {
        val minor = key.endsWith("m")
        val root = key.removeSuffix("m").ifBlank { "C" }
        val i = idx(root).coerceAtLeast(0)
        val steps = if (minor) listOf(0 to "m", 2 to "dim", 3 to "", 5 to "m", 7 to "m", 8 to "", 10 to "")
                    else listOf(0 to "", 2 to "m", 4 to "m", 5 to "", 7 to "", 9 to "m", 11 to "dim")
        val names = if (root.contains('b') || root == "F" || key in listOf("Dm", "Gm", "Cm", "Fm")) FLAT else SHARP
        return steps.map { (s, k) -> names[(i + s) % 12] + k }
    }
}
