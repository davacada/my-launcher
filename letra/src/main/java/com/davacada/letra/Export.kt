package com.davacada.letra

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.io.File
import java.util.UUID

/** Turns notes, songs and scripts into files to share: PDF, plain text, Fountain, audio, or a Letra file. */
object Export {

    private fun safe(n: String) = n.replace(Regex("[\\\\/:*?\"<>|\\n]"), " ").trim().ifBlank { "Letra" }.take(60)

    private fun shareFile(ctx: Context, name: String): File {
        ShareProvider.clean(ctx, 60 * 60 * 1000L)
        val d = File(File(ctx.cacheDir, "share"), UUID.randomUUID().toString()).apply { mkdirs() }
        return File(d, name)
    }

    fun send(ctx: Context, f: File, mime: String, subject: String) {
        val uri = Uri.parse("content://${ShareProvider.AUTHORITY}/${f.parentFile!!.name}/${Uri.encode(f.name)}")
        val i = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Share “$subject”"))
    }

    fun sendText(ctx: Context, text: String, subject: String) {
        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); putExtra(Intent.EXTRA_SUBJECT, subject) }
        ctx.startActivity(Intent.createChooser(i, "Share “$subject”"))
    }

    fun textFile(ctx: Context, n: Note, text: String, ext: String = "txt"): File =
        shareFile(ctx, safe(n.displayTitle()) + "." + ext).apply { writeText(text) }

    fun letraFile(ctx: Context, n: Note): File = shareFile(ctx, safe(n.displayTitle()) + ".letra").apply { writeText(n.json().toString(2)) }

    // ---------- plain text ----------

    fun noteText(n: Note) = n.displayTitle() + "\n\n" + Rich.plain(n.html).trim()

    fun listText(n: Note) = n.displayTitle() + "\n\n" + n.items.joinToString("\n") { (if (it.done) "☑ " else "☐ ") + it.text }

    fun lyrics(n: Note) = buildString {
        append(n.displayTitle()).append("\n\n")
        n.lines.forEach { l -> if (l.section) append("\n[").append(l.text).append("]\n") else append(l.text).append('\n') }
    }.trim()

    /** Chords written on their own line above the words, lined up by character. */
    fun chordLine(l: SongLine): String {
        val sb = StringBuilder()
        l.chords.sortedBy { it.pos }.forEach { c ->
            while (sb.length < c.pos) sb.append(' ')
            if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
            sb.append(c.name)
        }
        return sb.toString()
    }

    fun chordSheet(n: Note) = buildString {
        append(n.displayTitle()).append('\n')
        val meta = listOfNotNull(n.key.ifBlank { null }?.let { "Key $it" }, n.bpm.takeIf { it > 0 }?.let { "$it bpm" }, n.capo.takeIf { it > 0 }?.let { "Capo $it" })
        if (meta.isNotEmpty()) append(meta.joinToString(" · ")).append('\n')
        append('\n')
        n.lines.forEach { l ->
            if (l.section) append("\n[").append(l.text).append("]\n")
            else { if (l.chords.isNotEmpty()) append(chordLine(l)).append('\n'); append(l.text).append('\n') }
        }
    }.trim()

    fun fountain(n: Note) = buildString {
        append("Title: ").append(n.displayTitle()).append("\n\n")
        n.els.forEach { e ->
            when (e.kind) {
                SCENE -> append("\n").append(e.text.uppercase().let { if (it.startsWith("INT") || it.startsWith("EXT") || it.startsWith("I/E")) it else ".$it" }).append("\n\n")
                ACTION -> append(e.text).append("\n\n")
                CHARACTER -> append(e.text.uppercase()).append('\n')
                PAREN -> append("(").append(e.text.trim('(', ')')).append(")\n")
                DIALOGUE -> append(e.text).append("\n\n")
                TRANSITION -> append("> ").append(e.text.uppercase()).append("\n\n")
            }
        }
    }.trim() + "\n"

    fun scriptText(n: Note) = buildString {
        append(n.displayTitle().uppercase()).append("\n\n")
        n.els.forEach { e ->
            when (e.kind) {
                SCENE -> append("\n").append(e.text.uppercase()).append("\n\n")
                ACTION -> append(e.text).append("\n\n")
                CHARACTER -> append("                    ").append(e.text.uppercase()).append('\n')
                PAREN -> append("               (").append(e.text.trim('(', ')')).append(")\n")
                DIALOGUE -> append(wrap(e.text, 35).joinToString("\n") { "          $it" }).append("\n\n")
                TRANSITION -> append("                                        ").append(e.text.uppercase()).append("\n\n")
            }
        }
    }.trim()

    fun sceneList(n: Note) = buildString {
        append(n.displayTitle()).append(" — scenes\n\n")
        var i = 0
        n.els.filter { it.kind == SCENE }.forEach { append(++i).append(". ").append(it.text.uppercase()).append('\n') }
        if (i == 0) append("No scenes yet.")
    }.trim()

    private fun wrap(t: String, w: Int): List<String> {
        val out = ArrayList<String>()
        t.split('\n').forEach { para ->
            var line = StringBuilder()
            para.split(' ').forEach { word ->
                if (line.isNotEmpty() && line.length + 1 + word.length > w) { out.add(line.toString()); line = StringBuilder() }
                if (line.isNotEmpty()) line.append(' ')
                line.append(word)
            }
            out.add(line.toString())
        }
        return out
    }

    // ---------- PDF ----------

    /** A tiny page layout engine: writes lines top to bottom and starts a new page when one fills. */
    private class Pdf(val w: Int, val h: Int, val margin: Float, val top: Float, val footer: String?) {
        val doc = PdfDocument()
        var page: PdfDocument.Page? = null
        var y = 0f
        var n = 0
        fun ensure(need: Float) { if (page == null || y + need > h - margin) newPage() }
        fun newPage() {
            page?.let { finishPage(it) }
            n++
            page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, n).create())
            y = top
            if (footer != null && n > 1) {
                val p = paint(Typeface.MONOSPACE, 10f, 0xFF000000.toInt())
                p.textAlign = Paint.Align.RIGHT
                page!!.canvas.drawText("$n.", w - margin, top - 24f, p)
            }
        }
        fun finishPage(p: PdfDocument.Page) {
            if (footer != null && footer.isNotEmpty() && n == 1) Unit
            doc.finishPage(p)
        }
        fun text(s: String, x: Float, p: Paint, lead: Float) { ensure(lead); y += lead; page!!.canvas.drawText(s, x, y, p) }
        fun gap(g: Float) { y += g }
        fun close(f: File) { page?.let { doc.finishPage(it) }; f.outputStream().use { doc.writeTo(it) }; doc.close() }
    }

    private fun paint(tf: Typeface, size: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = size; this.color = color }

    private fun wrapPaint(t: String, p: Paint, width: Float): List<String> {
        val out = ArrayList<String>()
        t.split('\n').forEach { para ->
            var line = ""
            para.split(' ').forEach { word ->
                val cand = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(cand) > width && line.isNotEmpty()) { out.add(line); line = word } else line = cand
            }
            out.add(line)
        }
        return out
    }

    /** Song as a chord sheet: title, key/tempo, sections in terracotta, chords placed exactly over their syllables. */
    fun songPdf(ctx: Context, n: Note): File {
        val f = shareFile(ctx, safe(n.displayTitle()) + ".pdf")
        val pdf = Pdf(595, 842, 56f, 56f, null)
        val title = paint(Typeface.create(Typeface.SERIF, Typeface.BOLD), 24f, 0xFF3D2F23.toInt())
        val meta = paint(Typeface.SANS_SERIF, 10.5f, 0xFF8A7360.toInt())
        val sec = paint(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD), 10.5f, 0xFFB05E26.toInt()).apply { letterSpacing = 0.12f }
        val words = paint(Typeface.SERIF, 13f, 0xFF2A2018.toInt())
        val chord = paint(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD), 11f, 0xFFB05E26.toInt())
        pdf.newPage()
        pdf.text(n.displayTitle(), 56f, title, 24f)
        val m = listOfNotNull(n.key.ifBlank { null }?.let { "Key of $it" }, n.bpm.takeIf { it > 0 }?.let { "$it bpm" }, n.capo.takeIf { it > 0 }?.let { "Capo $it" })
        if (m.isNotEmpty()) pdf.text(m.joinToString("   ·   "), 56f, meta, 18f)
        pdf.gap(14f)
        n.lines.forEach { l ->
            if (l.section) { pdf.gap(12f); pdf.text(l.text.uppercase(), 56f, sec, 14f); pdf.gap(4f); return@forEach }
            if (l.chords.isNotEmpty()) {
                pdf.ensure(34f)
                pdf.gap(13f)
                val cy = pdf.y
                var lastEnd = 0f
                l.chords.sortedBy { it.pos }.forEach { c ->
                    var x = 56f + words.measureText(l.text, 0, c.pos.coerceIn(0, l.text.length))
                    if (c.pos > l.text.length) x += (c.pos - l.text.length) * words.measureText(" ")
                    x = maxOf(x, lastEnd + 4f)
                    pdf.page!!.canvas.drawText(c.name, x, cy, chord)
                    lastEnd = x + chord.measureText(c.name)
                }
                pdf.text(l.text, 56f, words, 17f)
            } else pdf.text(l.text, 56f, words, 19f)
        }
        pdf.close(f)
        return f
    }

    /** Screenplay in the industry layout: Courier 12, US Letter, 1.5" left margin, dialogue block and character cues indented. */
    fun scriptPdf(ctx: Context, n: Note): File {
        val f = shareFile(ctx, safe(n.displayTitle()) + ".pdf")
        val pdf = Pdf(612, 792, 72f, 72f, "")
        val c = paint(Typeface.MONOSPACE, 12f, 0xFF000000.toInt())
        val cb = paint(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD), 12f, 0xFF000000.toInt())
        val lead = 14.4f
        val L = 108f; val R = 540f
        // title page
        pdf.newPage()
        pdf.gap(250f)
        val tp = paint(Typeface.MONOSPACE, 12f, 0xFF000000.toInt()).apply { textAlign = Paint.Align.CENTER }
        pdf.text(n.displayTitle().uppercase(), 306f, tp, lead)
        pdf.newPage()
        var scene = 0
        n.els.forEach { e ->
            when (e.kind) {
                SCENE -> { scene++; pdf.ensure(lead * 4); pdf.gap(lead); pdf.text("$scene  " + e.text.uppercase(), L - c.measureText("$scene  "), cb, lead); pdf.gap(lead) }
                ACTION -> { wrapPaint(e.text, c, R - L).forEach { pdf.text(it, L, c, lead) }; pdf.gap(lead) }
                CHARACTER -> { pdf.ensure(lead * 3); pdf.text(e.text.uppercase(), 252f, c, lead) }
                PAREN -> wrapPaint("(" + e.text.trim('(', ')') + ")", c, 144f).forEach { pdf.text(it, 216f, c, lead) }
                DIALOGUE -> { wrapPaint(e.text, c, 252f).forEach { pdf.text(it, 180f, c, lead) }; pdf.gap(lead) }
                TRANSITION -> { val t = e.text.uppercase(); pdf.text(t, R - c.measureText(t), c, lead); pdf.gap(lead) }
            }
        }
        pdf.close(f)
        return f
    }

    /** Notes and lists as a clean single-column PDF. */
    fun plainPdf(ctx: Context, n: Note, body: String): File {
        val f = shareFile(ctx, safe(n.displayTitle()) + ".pdf")
        val pdf = Pdf(595, 842, 56f, 56f, null)
        val title = paint(Typeface.create(Typeface.SERIF, Typeface.BOLD), 22f, 0xFF3D2F23.toInt())
        val p = paint(Typeface.SERIF, 12.5f, 0xFF2A2018.toInt())
        pdf.newPage()
        pdf.text(n.displayTitle(), 56f, title, 22f); pdf.gap(12f)
        body.lines().forEach { line -> if (line.isBlank()) pdf.gap(9f) else wrapPaint(line, p, 595f - 112f).forEach { pdf.text(it, 56f, p, 18f) } }
        pdf.close(f)
        return f
    }

    fun take(ctx: Context, n: Note, t: Take, recDir: File): File? {
        val src = File(recDir, t.file); if (!src.isFile) return null
        val f = shareFile(ctx, safe(n.displayTitle() + " - " + t.name) + ".wav")
        src.copyTo(f, true)
        return f
    }

    const val SCENE = "scene"; const val ACTION = "action"; const val CHARACTER = "character"
    const val PAREN = "paren"; const val DIALOGUE = "dialogue"; const val TRANSITION = "transition"
}
