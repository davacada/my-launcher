package com.davacada.roll

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Your own saved grades (look, adjustments, effects, overlay), to use on any project. */
class Presets(ctx: Context) {
    private val f = File(ctx.filesDir, "presets.json")
    class Preset(val name: String, val grade: Grade)

    fun all(): List<Preset> = try {
        val a = JSONArray(f.readText())
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Preset(it.getString("n"), Grade.from(it.getJSONObject("g"))) } }
    } catch (e: Exception) { emptyList() }

    fun save(name: String, g: Grade) = write(all().filter { it.name != name } + Preset(name, Grade().apply { copyFrom(g) }))
    fun delete(name: String) = write(all().filter { it.name != name })

    private fun write(list: List<Preset>) {
        f.writeText(JSONArray().apply { list.forEach { put(JSONObject().put("n", it.name).put("g", it.grade.json())) } }.toString())
    }
}
