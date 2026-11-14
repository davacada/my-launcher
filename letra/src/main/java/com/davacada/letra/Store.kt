package com.davacada.letra

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One chord sitting over a character of a lyric line. */
data class Chord(var pos: Int, var name: String)

/** A song line: a section heading ("Verse 1") or a lyric with chords over it. */
class SongLine(var section: Boolean, var text: String, val chords: MutableList<Chord> = mutableListOf())

/** A piece of what was heard during a recording, at [at] ms into the take. */
data class Heard(val at: Long, val text: String)

/** A recording attached to a song, with the words written out. */
class Take(var file: String, var name: String, var durationMs: Long, val heard: MutableList<Heard> = mutableListOf(), val flags: MutableList<Long> = mutableListOf())

class Item(var text: String, var done: Boolean)

/** A screenplay element: scene, action, character, paren, dialogue or transition. */
class El(var kind: String, var text: String)

class Note(
    val id: String = UUID.randomUUID().toString(),
    var mode: String = NOTE,
    var title: String = "",
    val created: Long = System.currentTimeMillis(),
    var updated: Long = System.currentTimeMillis(),
) {
    var html = ""                                 // note: formatted text
    val items = mutableListOf<Item>()             // list
    var key = ""; var bpm = 0; var capo = 0       // song
    val lines = mutableListOf<SongLine>()
    val takes = mutableListOf<Take>()
    val els = mutableListOf<El>()                 // script

    fun displayTitle() = title.ifBlank { when (mode) { SONG -> "Untitled song"; SCRIPT -> "Untitled script"; LIST -> "Untitled list"; else -> "Untitled note" } }

    fun json(): JSONObject = JSONObject().apply {
        put("id", id); put("mode", mode); put("title", title); put("created", created); put("updated", updated)
        put("html", html)
        put("items", JSONArray().apply { items.forEach { put(JSONObject().put("t", it.text).put("d", it.done)) } })
        put("key", key); put("bpm", bpm); put("capo", capo)
        put("lines", JSONArray().apply { lines.forEach { l ->
            put(JSONObject().put("s", l.section).put("t", l.text).put("c", JSONArray().apply { l.chords.forEach { c -> put(JSONArray().put(c.pos).put(c.name)) } }))
        } })
        put("takes", JSONArray().apply { takes.forEach { t ->
            put(JSONObject().put("f", t.file).put("n", t.name).put("d", t.durationMs)
                .put("h", JSONArray().apply { t.heard.forEach { h -> put(JSONArray().put(h.at).put(h.text)) } })
                .put("fl", JSONArray().apply { t.flags.forEach { put(it) } }))
        } })
        put("els", JSONArray().apply { els.forEach { put(JSONObject().put("k", it.kind).put("t", it.text)) } })
    }

    companion object {
        const val NOTE = "note"; const val LIST = "list"; const val SONG = "song"; const val SCRIPT = "script"

        fun from(o: JSONObject) = Note(o.getString("id"), o.optString("mode", NOTE), o.optString("title"), o.optLong("created"), o.optLong("updated")).apply {
            html = o.optString("html")
            o.optJSONArray("items")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { items.add(Item(it.optString("t"), it.optBoolean("d"))) } }
            key = o.optString("key"); bpm = o.optInt("bpm"); capo = o.optInt("capo")
            o.optJSONArray("lines")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { l ->
                val sl = SongLine(l.optBoolean("s"), l.optString("t"))
                l.optJSONArray("c")?.let { c -> for (j in 0 until c.length()) c.getJSONArray(j).let { sl.chords.add(Chord(it.getInt(0), it.getString(1))) } }
                lines.add(sl)
            } }
            o.optJSONArray("takes")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { t ->
                val tk = Take(t.optString("f"), t.optString("n"), t.optLong("d"))
                t.optJSONArray("h")?.let { h -> for (j in 0 until h.length()) h.getJSONArray(j).let { tk.heard.add(Heard(it.getLong(0), it.getString(1))) } }
                t.optJSONArray("fl")?.let { f -> for (j in 0 until f.length()) tk.flags.add(f.getLong(j)) }
                takes.add(tk)
            } }
            o.optJSONArray("els")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { els.add(El(it.optString("k"), it.optString("t"))) } }
        }
    }
}

/** Every note is a small JSON file in the app's private storage; recordings sit next to them. */
class Store(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "notes").apply { mkdirs() }
    val recDir = File(ctx.filesDir, "recordings").apply { mkdirs() }

    fun all(): MutableList<Note> = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { f -> try { Note.from(JSONObject(f.readText())) } catch (e: Exception) { null } }
        .sortedByDescending { it.updated }.toMutableList()

    fun save(n: Note) {
        n.updated = System.currentTimeMillis()
        val tmp = File(dir, n.id + ".tmp")
        tmp.writeText(n.json().toString())
        tmp.renameTo(File(dir, n.id + ".json"))
    }

    fun delete(n: Note) {
        File(dir, n.id + ".json").delete()
        n.takes.forEach { File(recDir, it.file).delete() }
    }
}
