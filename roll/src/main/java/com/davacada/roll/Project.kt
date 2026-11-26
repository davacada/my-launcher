package com.davacada.roll

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * One clip on the timeline: a piece [startMs, endMs) of a video, played at [speed].
 * Photos and frozen frames are short still videos Roll makes itself ([still]); a reversed clip points at a
 * reversed copy, and remembers the original in [revOf] so it can be turned back.
 */
class Clip(
    var uri: String,
    var sourceMs: Long,            // full length of the source video
    var startMs: Long,
    var endMs: Long,
    var speed: Float = 1f,
    var volume: Float = 1f,
    var w: Int = 0, var h: Int = 0, // as shown (rotation applied)
    val id: String = UUID.randomUUID().toString(),
) {
    // framing: how the clip sits in the frame
    var fit = false                // false: fill the frame (crop) · true: whole picture, with a background around it
    var bg = "blur"                // background when it doesn't fill: blur, black, white
    var scale = 1f                 // 1 = as fitted/filled
    var ox = 0f; var oy = 0f       // position, in frame widths / heights (+x right, +y up)
    var rot = 0f                   // degrees, clockwise
    var flip = false
    var kenBurns = false           // a slow push-in over the clip
    // the transition into the next clip
    var trans = "none"; var transMs = 500L
    // what kind of clip
    var still = ""                 // "" a video · "photo" · "freeze"
    var revOf: String? = null      // JSON of the clip before it was reversed

    /** How long it lasts in the edit (after speed). */
    val lengthMs get() = ((endMs - startMs) / speed).toLong()
    val aspect get() = if (w > 0 && h > 0) w.toFloat() / h else 16f / 9f

    fun json(): JSONObject = JSONObject().put("u", uri).put("src", sourceMs).put("s", startMs).put("e", endMs)
        .put("sp", speed.toDouble()).put("v", volume.toDouble()).put("w", w).put("h", h).put("id", id)
        .put("fit", fit).put("bg", bg).put("sc", scale.toDouble()).put("ox", ox.toDouble()).put("oy", oy.toDouble()).put("rot", rot.toDouble())
        .put("fl", flip).put("kb", kenBurns).put("tr", trans).put("trm", transMs).put("still", still).apply { revOf?.let { put("rev", it) } }

    fun copy() = from(json().put("id", UUID.randomUUID().toString()))
    fun framingFrom(o: Clip) { fit = o.fit; bg = o.bg; scale = o.scale; ox = o.ox; oy = o.oy; rot = o.rot; flip = o.flip }

    companion object {
        fun from(o: JSONObject) = Clip(o.getString("u"), o.optLong("src"), o.optLong("s"), o.optLong("e"),
            o.optDouble("sp", 1.0).toFloat(), o.optDouble("v", 1.0).toFloat(), o.optInt("w"), o.optInt("h"), o.optString("id", UUID.randomUUID().toString())).apply {
            fit = o.optBoolean("fit"); bg = o.optString("bg", "blur"); scale = o.optDouble("sc", 1.0).toFloat()
            ox = o.optDouble("ox", 0.0).toFloat(); oy = o.optDouble("oy", 0.0).toFloat(); rot = o.optDouble("rot", 0.0).toFloat()
            flip = o.optBoolean("fl"); kenBurns = o.optBoolean("kb"); trans = o.optString("tr", "none"); transMs = o.optLong("trm", 500L)
            still = o.optString("still", ""); revOf = if (o.has("rev")) o.optString("rev") else null
        }
    }
}

/** The colour grade: a film look (or your own LUT) plus the adjust sliders, grain and vignette. */
class Grade(
    var look: String = "none",     // a look id from Looks, or "lut:<file name>"
    var strength: Float = 1f,
    var grain: Float = -1f,        // -1 = the look's own amount
    var vignette: Float = -1f,
    var exposure: Float = 0f,      // -1..1
    var contrast: Float = 0f,      // -1..1
    var saturation: Float = 0f,    // -1..1
    var warmth: Float = 0f,        // -1..1 (Temp)
    var tint: Float = 0f,          // -1 green .. 1 magenta
    var highlights: Float = 0f, var shadows: Float = 0f, var whites: Float = 0f, var blacks: Float = 0f,
    var vibrance: Float = 0f,
    var overlay: String = "none",  // an Overlays id
    var overlayAmount: Float = 0.8f,
    /** Colour mix: hue, saturation, luminance (-1..1) for red, orange, yellow, green, aqua, blue, purple, magenta. */
    val mix: FloatArray = FloatArray(24),
) {
    // more colour: fade, hue, split toning
    var fade = 0f; var hue = 0f
    var shHue = 0.55f; var shSat = 0f; var hiHue = 0.09f; var hiSat = 0f; var balance = 0f
    // light effects drawn on the picture itself: bloom, halation, colour fringing, sharpen
    var bloom = 0f; var halation = 0f; var aberration = 0f; var sharpen = 0f
    fun fx() = floatArrayOf(bloom, halation, aberration, sharpen)
    val mixed get() = mix.any { it != 0f }
    /** True when nothing changes the colours. */
    val neutral get() = look == "none" && exposure == 0f && contrast == 0f && saturation == 0f && warmth == 0f && tint == 0f &&
        highlights == 0f && shadows == 0f && whites == 0f && blacks == 0f && vibrance == 0f && !mixed && fade == 0f && hue == 0f && shSat == 0f && hiSat == 0f

    fun json(): JSONObject = JSONObject().put("look", look).put("str", strength.toDouble()).put("gr", grain.toDouble()).put("vi", vignette.toDouble())
        .put("ex", exposure.toDouble()).put("co", contrast.toDouble()).put("sa", saturation.toDouble()).put("wa", warmth.toDouble())
        .put("ti", tint.toDouble()).put("hi", highlights.toDouble()).put("sh", shadows.toDouble()).put("wh", whites.toDouble()).put("bl", blacks.toDouble()).put("vb", vibrance.toDouble())
        .put("ov", overlay).put("ova", overlayAmount.toDouble()).put("mix", JSONArray().apply { mix.forEach { put(it.toDouble()) } })
        .put("fd", fade.toDouble()).put("hu", hue.toDouble()).put("shh", shHue.toDouble()).put("shs", shSat.toDouble()).put("hih", hiHue.toDouble()).put("his", hiSat.toDouble()).put("bal", balance.toDouble())
        .put("bloom", bloom.toDouble()).put("hal", halation.toDouble()).put("ab", aberration.toDouble()).put("shp", sharpen.toDouble())
    fun copyFrom(g: Grade) {
        look = g.look; strength = g.strength; grain = g.grain; vignette = g.vignette; exposure = g.exposure; contrast = g.contrast; saturation = g.saturation; warmth = g.warmth
        tint = g.tint; highlights = g.highlights; shadows = g.shadows; whites = g.whites; blacks = g.blacks; vibrance = g.vibrance
        overlay = g.overlay; overlayAmount = g.overlayAmount; g.mix.copyInto(mix)
        fade = g.fade; hue = g.hue; shHue = g.shHue; shSat = g.shSat; hiHue = g.hiHue; hiSat = g.hiSat; balance = g.balance
        bloom = g.bloom; halation = g.halation; aberration = g.aberration; sharpen = g.sharpen
    }
    companion object {
        fun from(o: JSONObject?) = if (o == null) Grade() else Grade(o.optString("look", "none"), o.optDouble("str", 1.0).toFloat(), o.optDouble("gr", -1.0).toFloat(),
            o.optDouble("vi", -1.0).toFloat(), o.optDouble("ex", 0.0).toFloat(), o.optDouble("co", 0.0).toFloat(), o.optDouble("sa", 0.0).toFloat(), o.optDouble("wa", 0.0).toFloat(),
            o.optDouble("ti", 0.0).toFloat(), o.optDouble("hi", 0.0).toFloat(), o.optDouble("sh", 0.0).toFloat(), o.optDouble("wh", 0.0).toFloat(), o.optDouble("bl", 0.0).toFloat(), o.optDouble("vb", 0.0).toFloat(),
            o.optString("ov", "none"), o.optDouble("ova", 0.8).toFloat(),
            FloatArray(24).also { m -> o.optJSONArray("mix")?.let { a -> for (i in 0 until minOf(24, a.length())) m[i] = a.optDouble(i).toFloat() } }).apply {
            fade = o.optDouble("fd", 0.0).toFloat(); hue = o.optDouble("hu", 0.0).toFloat(); shHue = o.optDouble("shh", 0.55).toFloat(); shSat = o.optDouble("shs", 0.0).toFloat()
            hiHue = o.optDouble("hih", 0.09).toFloat(); hiSat = o.optDouble("his", 0.0).toFloat(); balance = o.optDouble("bal", 0.0).toFloat()
            bloom = o.optDouble("bloom", 0.0).toFloat(); halation = o.optDouble("hal", 0.0).toFloat(); aberration = o.optDouble("ab", 0.0).toFloat(); sharpen = o.optDouble("shp", 0.0).toFloat()
        }
    }
}

class Project(val id: String = UUID.randomUUID().toString(), var name: String = "Untitled", val created: Long = System.currentTimeMillis()) {
    var updated = System.currentTimeMillis()
    val clips = mutableListOf<Clip>()
    var grade = Grade()
    var aspect = "original"        // original, 9:16, 4:5, 1:1, 4:3, 16:9, 21:9
    val texts = mutableListOf<TextItem>()
    val audio = mutableListOf<AudioItem>()
    var duck = true                // music dips under voiceovers
    // track controls: video (clips), titles, audio (music + voice)
    var vMute = false; var vSolo = false; var vLock = false
    var tHide = false; var tLock = false
    var aMute = false; var aSolo = false; var aLock = false
    /** Whether the clips' own sound is heard (mute / solo). */
    fun clipSound() = !mute && !vMute && !(aSolo && !vSolo)
    /** Whether music and voiceovers are heard. */
    fun musicSound() = !mute && !aMute && !(vSolo && !aSolo)
    var mute = false

    val lengthMs get() = clips.sumOf { it.lengthMs }

    /** Where each clip starts on the timeline. */
    fun startOf(i: Int) = clips.take(i).sumOf { it.lengthMs }

    /** Which clip is at [t] on the timeline, and how far into it. */
    fun at(t: Long): Pair<Int, Long> {
        var acc = 0L
        clips.forEachIndexed { i, c -> if (t < acc + c.lengthMs || i == clips.size - 1) return i to (t - acc).coerceIn(0, c.lengthMs); acc += c.lengthMs }
        return 0 to 0L
    }

    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("created", created).put("updated", updated)
        .put("clips", JSONArray().apply { clips.forEach { put(it.json()) } }).put("grade", grade.json()).put("aspect", aspect).put("mute", mute)
        .put("texts", JSONArray().apply { texts.forEach { put(it.json()) } })
        .put("audio", JSONArray().apply { audio.forEach { put(it.json()) } }).put("duck", duck)
        .put("tracks", JSONObject().put("vm", vMute).put("vs", vSolo).put("vl", vLock).put("th", tHide).put("tl", tLock).put("am", aMute).put("as", aSolo).put("al", aLock))

    /** Takes everything from [q] (undo / redo). */
    fun copyFrom(q: Project) {
        clips.clear(); clips.addAll(q.clips); grade = q.grade; aspect = q.aspect; mute = q.mute; name = q.name
        texts.clear(); texts.addAll(q.texts); audio.clear(); audio.addAll(q.audio); duck = q.duck
        vMute = q.vMute; vSolo = q.vSolo; vLock = q.vLock; tHide = q.tHide; tLock = q.tLock; aMute = q.aMute; aSolo = q.aSolo; aLock = q.aLock
    }

    companion object {
        fun from(o: JSONObject) = Project(o.getString("id"), o.optString("name", "Untitled"), o.optLong("created")).apply {
            updated = o.optLong("updated")
            o.optJSONArray("clips")?.let { a -> for (i in 0 until a.length()) clips.add(Clip.from(a.getJSONObject(i))) }
            grade = Grade.from(o.optJSONObject("grade")); aspect = o.optString("aspect", "original"); mute = o.optBoolean("mute")
            o.optJSONArray("texts")?.let { a -> for (i in 0 until a.length()) texts.add(TextItem.from(a.getJSONObject(i))) }
            o.optJSONArray("audio")?.let { a -> for (i in 0 until a.length()) audio.add(AudioItem.from(a.getJSONObject(i))) }
            duck = o.optBoolean("duck", true)
            o.optJSONObject("tracks")?.let { t -> vMute = t.optBoolean("vm"); vSolo = t.optBoolean("vs"); vLock = t.optBoolean("vl"); tHide = t.optBoolean("th"); tLock = t.optBoolean("tl")
                aMute = t.optBoolean("am"); aSolo = t.optBoolean("as"); aLock = t.optBoolean("al") }
        }
    }
}

class Projects(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "projects").apply { mkdirs() }
    val lutDir = File(ctx.filesDir, "luts").apply { mkdirs() }

    fun all() = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { try { Project.from(JSONObject(it.readText())) } catch (e: Exception) { null } }
        .sortedByDescending { it.updated }

    fun save(p: Project) {
        p.updated = System.currentTimeMillis()
        val t = File(dir, p.id + ".tmp"); t.writeText(p.json().toString()); t.renameTo(File(dir, p.id + ".json"))
    }

    fun delete(p: Project) {
        File(dir, p.id + ".json").delete()
        // let go of the videos this project used, unless another project still uses them
        val still = all().flatMap { q -> q.clips.map { it.uri } }.toSet()
        val mine = File(ctx.filesDir, "media").absolutePath
        p.clips.map { it.uri }.distinct().filter { it !in still && it.contains(mine) }.forEach { u -> try { File(Uri.parse(u).path!!).delete() } catch (e: Exception) {} }
        p.clips.map { it.uri }.distinct().filter { it !in still }.forEach { u ->
            try { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(u), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
        }
    }
}
