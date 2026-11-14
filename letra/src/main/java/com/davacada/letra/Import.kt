package com.davacada.letra

import org.json.JSONObject

/**
 * Text shared into Letra from another app: works out whether it's a song, a script, a list or a note,
 * and turns it into one, keeping sections, chords, checkboxes and screenplay elements.
 */
object Import {

    data class Guess(val mode: String, val why: String)

    private val CHORD = Regex("^[A-G][#b]?(m|maj|min|dim|aug|sus|add)?\\d*(sus\\d|add\\d|maj\\d|b\\d|#\\d)*(/[A-G][#b]?)?$")
    private val SECTION = Regex("^\\s*[\\[(]?\\s*(verse|verso|chorus|coro|estribillo|pre-?chorus|pre-?coro|precoro|bridge|puente|hook|intro|outro|refrain|interlude|instrumental|solo|tag|final)\\b[^\\]\\n]{0,12}[\\])]?\\s*:?\\s*$", RegexOption.IGNORE_CASE)
    private val SCENE = Regex("^(\\.(?=[A-Za-z])|(INT|EXT|INT\\./EXT|I/E|EST)\\.?\\s)", RegexOption.IGNORE_CASE)
    private val TRANSITION = Regex("^(>\\s*.+|[A-Z ]+TO:|FADE (IN|OUT)\\.?:?|CUT TO:?|DISSOLVE TO:?|SMASH CUT.*)$")
    private val BULLET = Regex("^\\s*([-*•●▪◦–]|☐|☑|✓|✔|✅|⬜|\\[[ xX✓]?]|\\d{1,2}[.)])\\s+")
    private val DONE = Regex("^\\s*(☑|✓|✔|✅|\\[[xX✓]])\\s*")
    private val INLINE_CHORD = Regex("\\[([A-G][#b]?[^\\]\\s]{0,8})]")

    private fun isChordLine(l: String): Boolean {
        val t = l.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it != "|" && it != "-" }
        return t.isNotEmpty() && t.all { CHORD.matches(it.trim('(', ')')) } && !(t.size == 1 && t[0] == "A" && l.trim().length > 1)
    }

    private fun isCue(l: String): Boolean {
        val t = l.trim().removeSuffix("^").replace(Regex("\\s*\\(.*\\)$"), "")
        return t.length in 2..38 && t == t.uppercase() && t.any { it.isLetter() } && !SCENE.containsMatchIn(t) && !TRANSITION.matches(t) && !t.endsWith(":")
    }

    fun guess(text: String): Guess {
        val all = text.replace("\r\n", "\n").lines()
        val lines = all.map { it.trimEnd() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return Guess(Note.NOTE, "")

        // script
        val scenes = lines.count { SCENE.containsMatchIn(it.trim()) }
        var cues = 0
        for (i in 0 until all.size - 1) if (isCue(all[i]) && all[i + 1].isNotBlank() && (i == 0 || all[i - 1].isBlank())) cues++
        val trans = lines.count { TRANSITION.matches(it.trim()) }
        val script = scenes * 3 + minOf(cues, 6) + trans * 2
        if (scenes >= 1 && script >= 5 || scenes >= 2) return Guess(Note.SCRIPT, "scene headings and character names")

        // song
        val chordLines = lines.count { isChordLine(it) }
        val inline = lines.count { INLINE_CHORD.containsMatchIn(it) && !it.trim().startsWith("[") || INLINE_CHORD.findAll(it).count() >= 2 }
        val sections = lines.count { SECTION.matches(it) }
        val avg = lines.map { it.length }.average()
        val stanzas = text.split(Regex("\\n\\s*\\n")).count { it.isNotBlank() }
        val rhyme = Words.rhymeGroups(lines.filter { !isChordLine(it) && !SECTION.matches(it) }).size.toFloat() / lines.size
        val song = sections * 3 + minOf(chordLines, 6) * 2 + minOf(inline, 6) * 2 +
            (if (avg < 48 && lines.size >= 6 && stanzas >= 2) 2 else 0) + (if (rhyme > 0.35f && avg < 60) 2 else 0)

        // list
        val bullets = lines.count { BULLET.containsMatchIn(it) }
        val shortLines = lines.count { it.length <= 32 && !it.trimEnd().endsWith(".") }
        val list = if (bullets >= maxOf(2, (lines.size * 0.6).toInt())) 6 else if (lines.size >= 3 && shortLines == lines.size && avg < 22 && stanzas <= 1) 4 else 0

        return when {
            song >= 5 && song >= list -> Guess(Note.SONG, when {
                sections > 0 && chordLines + inline > 0 -> "sections and chords"
                sections > 0 -> "verse / chorus sections"
                chordLines + inline > 0 -> "chords"
                else -> "short lines in verses that rhyme"
            })
            list >= 4 -> Guess(Note.LIST, if (bullets > 0) "items with bullets or checkboxes" else "a column of short items")
            else -> Guess(Note.NOTE, "ordinary text")
        }
    }

    /** Turns [text] into a note of [mode]. [subject] is the title the other app sent, if any. */
    fun build(text: String, mode: String, subject: String?): Note {
        val n = Note(mode = mode)
        var lines = text.replace("\r\n", "\n").lines().toMutableList()
        // Fountain title page / "Title:" line
        val tl = lines.indexOfFirst { it.isNotBlank() }
        if (tl >= 0 && lines[tl].trim().startsWith("Title:", true)) {
            n.title = lines[tl].substringAfter(":").trim(); lines = lines.drop(tl + 1).dropWhile { it.contains(':') && !SCENE.containsMatchIn(it) && it.isNotBlank() }.toMutableList()
        } else if (!subject.isNullOrBlank() && subject.length < 80) n.title = subject.trim()
        else if (tl >= 0 && mode != Note.LIST && lines[tl].trim().length < 50 && (tl + 1 >= lines.size || lines[tl + 1].isBlank()) &&
            !SECTION.matches(lines[tl]) && !SCENE.containsMatchIn(lines[tl]) && !isChordLine(lines[tl])) {
            n.title = lines[tl].trim().trim('#', ' ', '*'); lines = lines.drop(tl + 1).toMutableList()
        }
        when (mode) {
            Note.SONG -> song(n, lines)
            Note.SCRIPT -> script(n, lines)
            Note.LIST -> lines.filter { it.isNotBlank() }.forEach { l -> n.items.add(Item(BULLET.replace(l, "").trim(), DONE.containsMatchIn(l))) }
            else -> n.html = JSONObject().put("t", lines.joinToString("\n").trim()).put("s", org.json.JSONArray()).toString()
        }
        if (mode == Note.LIST && n.items.isEmpty()) n.items.add(Item("", false))
        return n
    }

    private fun song(n: Note, lines: List<String>) {
        var pending: List<Chord>? = null
        var blank = false
        for (raw in lines) {
            val l = raw.trimEnd()
            if (l.isBlank()) { if (pending != null) { n.lines.add(SongLine(false, "", pending.toMutableList())); pending = null }; blank = true; continue }
            if (SECTION.matches(l)) {
                pending?.let { n.lines.add(SongLine(false, "", it.toMutableList())) }; pending = null
                n.lines.add(SongLine(true, l.trim().trim('[', ']', '(', ')', ':', ' ').replaceFirstChar { it.uppercase() })); blank = false; continue
            }
            if (isChordLine(l)) {
                pending?.let { n.lines.add(SongLine(false, "", it.toMutableList())) }
                pending = Regex("\\S+").findAll(l).filter { it.value != "|" && it.value != "-" }.map { Chord(it.range.first, it.value.trim('(', ')')) }.toList()
                continue
            }
            // ChordPro-style [C]words
            val chords = ArrayList<Chord>()
            val sb = StringBuilder(); var i = 0
            val mm = INLINE_CHORD.findAll(l).toList()
            if (mm.isNotEmpty() && mm.all { CHORD.matches(it.groupValues[1]) }) {
                var last = 0
                for (m in mm) { sb.append(l, last, m.range.first); chords.add(Chord(sb.length, m.groupValues[1])); last = m.range.last + 1 }
                sb.append(l.substring(last)); i = 1
            }
            val text = if (i == 1) sb.toString() else l
            if (blank && n.lines.isNotEmpty() && !n.lines.last().section) n.lines.add(SongLine(true, "Verse ${n.lines.count { it.section && it.text.startsWith("Verse") } + 1}"))
            if (n.lines.isEmpty()) n.lines.add(SongLine(true, "Verse 1"))
            n.lines.add(SongLine(false, text.trim().let { t -> t }, (pending ?: chords).toMutableList()))
            pending = null; blank = false
        }
        pending?.let { n.lines.add(SongLine(false, "", it.toMutableList())) }
        if (n.lines.none { !it.section }) n.lines.add(SongLine(false, ""))
    }

    private fun script(n: Note, lines: List<String>) {
        var prev = ""          // previous element kind
        var i = 0
        while (i < lines.size) {
            val l = lines[i].trim()
            if (l.isEmpty()) { prev = ""; i++; continue }
            val kind = when {
                SCENE.containsMatchIn(l) -> Export.SCENE
                TRANSITION.matches(l) -> Export.TRANSITION
                l.startsWith("(") && l.endsWith(")") && (prev == Export.CHARACTER || prev == Export.DIALOGUE) -> Export.PAREN
                (prev == Export.CHARACTER || prev == Export.PAREN || prev == Export.DIALOGUE) -> Export.DIALOGUE
                isCue(l) && i + 1 < lines.size && lines[i + 1].isNotBlank() -> Export.CHARACTER
                l.startsWith("@") -> Export.CHARACTER
                else -> Export.ACTION
            }
            val t = when (kind) {
                Export.SCENE -> l.removePrefix(".").uppercase()
                Export.TRANSITION -> l.removePrefix(">").trim().uppercase()
                Export.CHARACTER -> l.removePrefix("@").uppercase()
                Export.PAREN -> l.trim('(', ')')
                else -> l
            }
            // dialogue / action continuing on the next line joins the same element
            val last = n.els.lastOrNull()
            if (last != null && last.kind == kind && prev == kind && (kind == Export.DIALOGUE || kind == Export.ACTION)) last.text += " " + t
            else n.els.add(El(kind, t))
            prev = kind; i++
        }
        if (n.els.isEmpty()) n.els.add(El(Export.SCENE, ""))
    }

    /** A Letra file (shared from Letra on another phone) comes back exactly as it was. */
    fun letraFile(text: String): Note? = try {
        val o = JSONObject(text)
        if (!o.has("mode") || !o.has("id")) null
        else Note.from(o.put("id", java.util.UUID.randomUUID().toString()).put("takes", org.json.JSONArray()))
    } catch (e: Exception) { null }
}
