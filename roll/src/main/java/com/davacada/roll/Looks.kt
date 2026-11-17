package com.davacada.roll

import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Film looks, worked out as colour transforms and baked (with the Adjust sliders) into one 3D LUT,
 * so a whole grade costs the phone a single lookup per pixel.
 */
object Looks {
    class Look(val id: String, val name: String, val group: String, val note: String, val grain: Float, val vignette: Float, val f: (FloatArray) -> Unit)

    const val FILM = "Film stocks"; const val CAMERA = "Cameras"; const val BW = "Black & white"

    private fun luma(c: FloatArray) = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]
    private fun sat(c: FloatArray, s: Float) { val l = luma(c); for (i in 0..2) c[i] = l + (c[i] - l) * s }
    private fun curve(c: FloatArray, contrast: Float, pivot: Float = 0.45f) { for (i in 0..2) c[i] = pivot + (c[i] - pivot) * contrast }
    /** Gentle S-curve: [amt] > 0 adds contrast without clipping. */
    private fun s(c: FloatArray, amt: Float) { for (i in 0..2) { val x = c[i].coerceIn(0f, 1f); val y = x * x * (3 - 2 * x); c[i] = x + (y - x) * amt } }
    private fun lift(c: FloatArray, black: Float, white: Float = 1f) { for (i in 0..2) c[i] = black + c[i] * (white - black) }
    private fun mul(c: FloatArray, r: Float, g: Float, b: Float) { c[0] *= r; c[1] *= g; c[2] *= b }
    /** Colours the shadows and highlights differently (split toning). */
    private fun split(c: FloatArray, sh: FloatArray, hi: FloatArray, amt: Float) {
        val l = luma(c).coerceIn(0f, 1f); val ws = (1 - l) * (1 - l); val wh = l * l
        for (i in 0..2) c[i] += (sh[i] * ws + hi[i] * wh) * amt
    }
    private fun bw(c: FloatArray, r: Float = 0.3f, g: Float = 0.59f, b: Float = 0.11f) { val l = c[0] * r + c[1] * g + c[2] * b; c[0] = l; c[1] = l; c[2] = l }

    val ALL = listOf(
        Look("portra400", "Portra 400", FILM, "Kodak · soft, warm skin", 0.18f, 0.15f) { c -> lift(c, 0.035f, 0.97f); s(c, 0.25f); mul(c, 1.04f, 1.0f, 0.94f); sat(c, 0.9f); split(c, floatArrayOf(0f, 0.01f, 0.02f), floatArrayOf(0.03f, 0.015f, -0.02f), 1f) },
        Look("gold200", "Gold 200", FILM, "Kodak · golden, nostalgic", 0.22f, 0.2f) { c -> lift(c, 0.04f, 0.98f); mul(c, 1.07f, 1.02f, 0.86f); sat(c, 1.15f); s(c, 0.3f) },
        Look("ektar100", "Ektar 100", FILM, "Kodak · punchy, vivid", 0.1f, 0.1f) { c -> s(c, 0.55f); sat(c, 1.38f); mul(c, 1.02f, 1f, 0.98f) },
        Look("superia400", "Superia 400", FILM, "Fuji · cool greens", 0.2f, 0.15f) { c -> lift(c, 0.03f); split(c, floatArrayOf(-0.01f, 0.03f, 0.02f), floatArrayOf(0.0f, 0.01f, -0.01f), 1f); sat(c, 1.08f); s(c, 0.3f) },
        Look("velvia50", "Velvia 50", FILM, "Fuji slide · deep colour", 0.08f, 0.25f) { c -> s(c, 0.7f); sat(c, 1.65f); for (i in 0..2) c[i] = c[i].coerceAtLeast(0f).pow(1.08f) },
        Look("cinestill800t", "CineStill 800T", FILM, "Tungsten · night, teal shadows", 0.3f, 0.2f) { c -> mul(c, 0.94f, 1f, 1.1f); split(c, floatArrayOf(-0.03f, 0.02f, 0.05f), floatArrayOf(0.05f, 0.01f, -0.03f), 1f); s(c, 0.35f); lift(c, 0.03f) },
        Look("portra160", "Portra 160", FILM, "Kodak · clean daylight", 0.12f, 0.1f) { c -> lift(c, 0.025f, 0.98f); mul(c, 1.02f, 1.01f, 0.97f); sat(c, 0.95f); s(c, 0.15f) },
        Look("trix400", "Tri-X 400", BW, "Gritty, contrasty", 0.5f, 0.3f) { c -> bw(c, 0.35f, 0.55f, 0.1f); s(c, 0.9f); curve(c, 1.15f) },
        Look("hp5", "HP5", BW, "Soft, silvery", 0.35f, 0.2f) { c -> bw(c); lift(c, 0.05f, 0.97f); s(c, 0.3f) },
        Look("delta3200", "Delta 3200", BW, "Night, heavy grain", 0.8f, 0.35f) { c -> bw(c); lift(c, 0.07f, 0.95f); s(c, 0.5f) },
        Look("super8", "Super 8", CAMERA, "Home movie, Kodachrome tones", 0.55f, 0.55f) { c -> lift(c, 0.06f, 0.96f); mul(c, 1.08f, 1.02f, 0.85f); sat(c, 1.25f); s(c, 0.5f) },
        Look("vhs", "VHS", CAMERA, "Camcorder, bleeding colour", 0.35f, 0.3f) { c -> lift(c, 0.08f, 0.92f); mul(c, 1.03f, 0.97f, 1.05f); sat(c, 1.3f); curve(c, 0.9f) },
        Look("hi8", "Hi8", CAMERA, "2000s camcorder", 0.25f, 0.2f) { c -> lift(c, 0.04f, 0.97f); mul(c, 0.98f, 1.0f, 1.04f); sat(c, 1.12f); s(c, 0.35f) },
        Look("16mm", "16mm Bolex", CAMERA, "Heavy grain, warm mids", 0.65f, 0.45f) { c -> lift(c, 0.04f, 0.96f); split(c, floatArrayOf(0f, 0.01f, 0.02f), floatArrayOf(0.04f, 0.02f, -0.02f), 1f); sat(c, 0.92f); s(c, 0.45f) },
        Look("cine709", "Cine 709", CAMERA, "Cinema roll-off, gentle contrast", 0.1f, 0.15f) { c -> for (i in 0..2) { val x = c[i]; c[i] = if (x < 0.7f) x else 0.7f + (1 - (1 - (x - 0.7f) / 0.6f).let { it * it }) * 0.3f }; s(c, 0.2f); sat(c, 0.95f) },
        Look("teal", "Blockbuster", CAMERA, "Teal shadows, warm skin", 0.12f, 0.25f) { c -> split(c, floatArrayOf(-0.04f, 0.02f, 0.06f), floatArrayOf(0.06f, 0.02f, -0.05f), 1f); s(c, 0.45f); sat(c, 1.05f) },
    )

    fun byId(id: String) = ALL.firstOrNull { it.id == id }

    const val SIZE = 33

    /**
     * Builds the LUT for [g]: the look (or an imported .cube, blended by strength), then exposure,
     * contrast, saturation and warmth. Cube is indexed [R][G][B], each an ARGB colour.
     */
    fun lut(g: Grade, lutDir: File): Array<Array<IntArray>> {
        val n = SIZE
        val look = byId(g.look)
        val cube = if (g.look.startsWith("lut:")) loadCube(File(lutDir, g.look.removePrefix("lut:"))) else null
        val c = FloatArray(3); val o = FloatArray(3)
        val expo = 2f.pow(g.exposure * 1.5f)
        val warm = g.warmth * 0.08f
        return Array(n) { r -> Array(n) { gg -> IntArray(n) { b ->
            c[0] = r / (n - 1f); c[1] = gg / (n - 1f); c[2] = b / (n - 1f)
            o[0] = c[0]; o[1] = c[1]; o[2] = c[2]
            if (look != null) look.f(c) else if (cube != null) cube.apply(c)
            for (i in 0..2) c[i] = o[i] + (c[i] - o[i]) * g.strength
            for (i in 0..2) c[i] *= expo
            light(c, g)
            if (g.mixed) colorMix(c, g.mix)
            if (g.contrast != 0f) curve(c, 1f + g.contrast * 0.6f, 0.5f)
            if (g.vibrance != 0f) { val mx = maxOf(c[0], c[1], c[2]); val mn = minOf(c[0], c[1], c[2]); val s0 = if (mx > 0f) (mx - mn) / mx else 0f; sat(c, 1f + g.vibrance * (1f - s0) * 0.9f) }
            if (g.saturation != 0f) sat(c, 1f + g.saturation)
            if (warm != 0f) { c[0] += warm; c[2] -= warm }
            if (g.tint != 0f) { c[1] -= g.tint * 0.06f; c[0] += g.tint * 0.025f; c[2] += g.tint * 0.025f }
            val rr = (c[0].coerceIn(0f, 1f) * 255 + 0.5f).toInt(); val g2 = (c[1].coerceIn(0f, 1f) * 255 + 0.5f).toInt(); val bb = (c[2].coerceIn(0f, 1f) * 255 + 0.5f).toInt()
            (0xFF shl 24) or (rr shl 16) or (g2 shl 8) or bb
        } } }
    }

    private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    /** Lightroom-style Light sliders: highlights and shadows move their own tones; whites and blacks the ends. */
    private fun light(c: FloatArray, g: Grade) {
        if (g.highlights == 0f && g.shadows == 0f && g.whites == 0f && g.blacks == 0f) return
        val l = luma(c).coerceIn(0f, 1f)
        val d = g.highlights * 0.28f * smooth(0.45f, 1f, l) + g.shadows * 0.3f * (1f - smooth(0f, 0.55f, l))
        for (i in 0..2) c[i] += d
        if (g.whites != 0f) { val wp = 1f - g.whites * 0.2f; for (i in 0..2) c[i] = c[i] / wp }
        if (g.blacks != 0f) { val bp = -g.blacks * 0.08f; for (i in 0..2) c[i] = (c[i] - bp) / (1f - bp) }
    }

    /** Centre hue (degrees) of each colour in the mix. */
    val MIX_HUES = floatArrayOf(0f, 30f, 60f, 120f, 180f, 225f, 270f, 315f)
    val MIX_NAMES = listOf("Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta")
    val MIX_COLORS = intArrayOf(0xFFE8443A.toInt(), 0xFFF08A2E.toInt(), 0xFFE9CF3A.toInt(), 0xFF4FBF5A.toInt(), 0xFF3CC4C4.toInt(), 0xFF3E7BE6.toInt(), 0xFF8B54E0.toInt(), 0xFFD84FB4.toInt())

    /** Lightroom's Color Mix: each colour's hue, saturation and luminance, blended smoothly between neighbours. */
    private fun colorMix(c: FloatArray, mix: FloatArray) {
        val r = c[0].coerceIn(0f, 1f); val g = c[1].coerceIn(0f, 1f); val b = c[2].coerceIn(0f, 1f)
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
        if (d < 0.004f) return
        var h = when (mx) { r -> 60f * (((g - b) / d) % 6f); g -> 60f * ((b - r) / d + 2f); else -> 60f * ((r - g) / d + 4f) }
        if (h < 0) h += 360f
        var l = (mx + mn) / 2f
        var s = d / (1f - kotlin.math.abs(2f * l - 1f)).coerceAtLeast(0.0001f)
        var dh = 0f; var ds = 0f; var dl = 0f
        for (i in 0 until 8) {
            var dist = kotlin.math.abs(h - MIX_HUES[i]); if (dist > 180f) dist = 360f - dist
            val w = (1f - dist / 45f).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
            if (w > 0f) { dh += mix[i * 3] * w * 30f; ds += mix[i * 3 + 1] * w; dl += mix[i * 3 + 2] * w }
        }
        h = (h + dh + 360f) % 360f
        s = (s * (1f + ds)).coerceIn(0f, 1f)
        l = (l + dl * 0.25f * s).coerceIn(0f, 1f)
        val cc = (1f - kotlin.math.abs(2f * l - 1f)) * s; val x = cc * (1f - kotlin.math.abs((h / 60f) % 2f - 1f)); val m = l - cc / 2f
        val (r1, g1, b1) = when ((h / 60f).toInt()) { 0 -> Triple(cc, x, 0f); 1 -> Triple(x, cc, 0f); 2 -> Triple(0f, cc, x); 3 -> Triple(0f, x, cc); 4 -> Triple(x, 0f, cc); else -> Triple(cc, 0f, x) }
        c[0] = r1 + m; c[1] = g1 + m; c[2] = b1 + m
    }

    fun grainOf(g: Grade) = if (g.grain >= 0f) g.grain else (byId(g.look)?.grain ?: 0f)
    fun vignetteOf(g: Grade) = if (g.vignette >= 0f) g.vignette else (byId(g.look)?.vignette ?: 0f)

    /** A .cube 3D LUT (Resolve, Premiere, …), sampled with trilinear interpolation. */
    class Cube(val n: Int, val data: FloatArray, val min: FloatArray, val max: FloatArray) {
        fun apply(c: FloatArray) {
            val p = FloatArray(3)
            for (i in 0..2) p[i] = ((c[i] - min[i]) / (max[i] - min[i])).coerceIn(0f, 1f) * (n - 1)
            val r0 = p[0].toInt().coerceAtMost(n - 2); val g0 = p[1].toInt().coerceAtMost(n - 2); val b0 = p[2].toInt().coerceAtMost(n - 2)
            val fr = p[0] - r0; val fg = p[1] - g0; val fb = p[2] - b0
            for (ch in 0..2) {
                fun v(r: Int, g: Int, b: Int) = data[((b * n + g) * n + r) * 3 + ch] // .cube: red changes fastest
                val c00 = v(r0, g0, b0) * (1 - fr) + v(r0 + 1, g0, b0) * fr
                val c10 = v(r0, g0 + 1, b0) * (1 - fr) + v(r0 + 1, g0 + 1, b0) * fr
                val c01 = v(r0, g0, b0 + 1) * (1 - fr) + v(r0 + 1, g0, b0 + 1) * fr
                val c11 = v(r0, g0 + 1, b0 + 1) * (1 - fr) + v(r0 + 1, g0 + 1, b0 + 1) * fr
                val c0 = c00 * (1 - fg) + c10 * fg; val c1 = c01 * (1 - fg) + c11 * fg
                c[ch] = c0 * (1 - fb) + c1 * fb
            }
        }
    }

    private val cubeCache = HashMap<String, Cube>()

    fun loadCube(f: File): Cube? = cubeCache[f.path] ?: parseCube(f.readLines()).also { if (it != null) cubeCache[f.path] = it }

    /** Reads a .cube file's text. Returns null if it isn't a 3D LUT. */
    fun parseCube(lines: List<String>): Cube? {
        var n = 0; val mn = floatArrayOf(0f, 0f, 0f); val mx = floatArrayOf(1f, 1f, 1f)
        val vals = ArrayList<Float>()
        for (raw in lines) {
            val l = raw.trim()
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("TITLE")) continue
            val p = l.split(Regex("\\s+"))
            when (p[0]) {
                "LUT_3D_SIZE" -> n = p[1].toInt()
                "LUT_1D_SIZE" -> return null
                "DOMAIN_MIN" -> for (i in 0..2) mn[i] = p[i + 1].toFloat()
                "DOMAIN_MAX" -> for (i in 0..2) mx[i] = p[i + 1].toFloat()
                else -> if (p.size >= 3) p[0].toFloatOrNull()?.let { r -> vals.add(r); vals.add(p[1].toFloat()); vals.add(p[2].toFloat()) }
            }
        }
        if (n < 2 || vals.size < n * n * n * 3) return null
        return Cube(n, vals.toFloatArray(), mn, mx)
    }

    @Suppress("unused") private fun clamp(x: Float) = max(0f, min(1f, x))
}
