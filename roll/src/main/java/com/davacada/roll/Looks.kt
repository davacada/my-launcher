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

    const val FILM = "Film stocks"; const val CINEMA = "Cinema"; const val CAMERA = "Cameras"; const val MOODS = "Moods"; const val BW = "Black & white"
    val GROUPS = listOf(FILM, CINEMA, CAMERA, MOODS, BW)

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
        // cinema stocks and looks
        Look("vision250d", "Vision3 250D", CINEMA, "Kodak cinema · daylight", 0.14f, 0.12f) { c -> lift(c, 0.02f, 0.985f); split(c, floatArrayOf(-0.01f, 0.01f, 0.025f), floatArrayOf(0.025f, 0.012f, -0.01f), 1f); s(c, 0.3f); sat(c, 0.96f) },
        Look("vision500t", "Vision3 500T", CINEMA, "Kodak cinema · tungsten night", 0.24f, 0.18f) { c -> mul(c, 0.95f, 1f, 1.08f); split(c, floatArrayOf(-0.02f, 0.012f, 0.045f), floatArrayOf(0.045f, 0.015f, -0.02f), 1f); s(c, 0.35f); lift(c, 0.025f) },
        Look("eterna", "Eterna", CINEMA, "Fuji · muted, soft", 0.12f, 0.15f) { c -> lift(c, 0.04f, 0.97f); sat(c, 0.74f); split(c, floatArrayOf(-0.01f, 0.02f, 0.015f), floatArrayOf(0.01f, 0.005f, -0.005f), 1f); s(c, 0.15f) },
        Look("bleach", "Bleach bypass", CINEMA, "Silver kept · hard, desaturated", 0.25f, 0.3f) { c -> val l = luma(c); val k = l * l * (3 - 2 * l); for (i in 0..2) c[i] = c[i] * 0.55f + k * 0.45f; sat(c, 0.55f); s(c, 0.55f) },
        Look("alexa", "Alexa 709", CINEMA, "Digital cinema · clean, warm skin", 0.06f, 0.1f) { c -> for (i in 0..2) { val x = c[i]; c[i] = if (x < 0.72f) x else 0.72f + (1 - (1 - (x - 0.72f) / 0.56f).let { it * it }) * 0.28f }; mul(c, 1.02f, 1.0f, 0.98f); s(c, 0.25f) },
        Look("noir", "Neo-noir", CINEMA, "Deep teal shadows, hard light", 0.3f, 0.45f) { c -> split(c, floatArrayOf(-0.05f, 0.025f, 0.07f), floatArrayOf(0.04f, 0.01f, -0.04f), 1f); sat(c, 0.72f); s(c, 0.65f); lift(c, 0.015f, 0.97f) },
        // moods
        Look("classicchrome", "Classic Chrome", MOODS, "Fuji · documentary, cool and muted", 0.1f, 0.12f) { c -> sat(c, 0.78f); split(c, floatArrayOf(-0.01f, 0.01f, 0.02f), floatArrayOf(0.02f, 0.012f, -0.012f), 1f); s(c, 0.38f); lift(c, 0.02f) },
        Look("kodachrome", "Kodachrome 64", MOODS, "Rich reds, deep blues", 0.12f, 0.2f) { c -> s(c, 0.55f); sat(c, 1.25f); mul(c, 1.05f, 1f, 0.93f); lift(c, 0.015f) },
        Look("polaroid", "Polaroid", MOODS, "Instant · faded, warm", 0.2f, 0.35f) { c -> lift(c, 0.08f, 0.93f); mul(c, 1.03f, 1.0f, 0.94f); sat(c, 0.85f); split(c, floatArrayOf(0f, 0.01f, 0.03f), floatArrayOf(0.02f, 0.025f, -0.02f), 1f) },
        Look("lomo", "Lomo", MOODS, "Saturated, dark corners", 0.25f, 0.65f) { c -> s(c, 0.8f); sat(c, 1.35f); mul(c, 1f, 1.05f, 0.9f) },
        Look("agfa", "Agfa Vista", MOODS, "Warm reds, summer", 0.18f, 0.15f) { c -> mul(c, 1.08f, 1f, 0.93f); sat(c, 1.15f); s(c, 0.3f); lift(c, 0.02f) },
        Look("crossproc", "Cross process", MOODS, "Slide in negative chemistry", 0.2f, 0.25f) { c -> c[0] = 0.5f + (c[0] - 0.5f) * 1.25f; c[1] = c[1] * 1.04f + 0.02f; c[2] = 0.12f + c[2] * 0.72f; s(c, 0.3f); sat(c, 1.15f) },
        Look("matte", "Matte", MOODS, "Lifted blacks, soft", 0.1f, 0.1f) { c -> lift(c, 0.1f, 0.95f); s(c, 0.2f); sat(c, 0.9f) },
        Look("moodygreen", "Moody green", MOODS, "Forest shadows, warm light", 0.15f, 0.3f) { c -> split(c, floatArrayOf(-0.02f, 0.03f, 0.01f), floatArrayOf(0.035f, 0.02f, -0.02f), 1f); mul(c, 0.97f, 1.02f, 0.96f); sat(c, 0.85f); s(c, 0.4f); lift(c, 0.03f) },
        Look("golden", "Golden hour", MOODS, "Late sun on everything", 0.12f, 0.2f) { c -> mul(c, 1.08f, 1.01f, 0.85f); s(c, 0.25f); sat(c, 1.08f); split(c, floatArrayOf(0f, 0f, 0f), floatArrayOf(0.04f, 0.02f, -0.03f), 1f) },
        Look("panf", "Pan F 50", BW, "Fine grain, crisp", 0.12f, 0.15f) { c -> bw(c, 0.25f, 0.65f, 0.1f); s(c, 0.6f) },
        Look("sepia", "Sepia", BW, "Old print, warm", 0.3f, 0.35f) { c -> bw(c); lift(c, 0.05f, 0.95f); s(c, 0.3f); mul(c, 1.08f, 0.98f, 0.82f) },
    )

    fun byId(id: String) = ALL.firstOrNull { it.id == id }

    const val SIZE = 33

    /**
     * Builds the LUT for [g]: the look (or an imported .cube, blended by strength), then exposure,
     * contrast, saturation and warmth. Cube is indexed [R][G][B], each an ARGB colour.
     */
    fun lut(g: Grade, lutDir: File): Array<Array<IntArray>> {
        val first = lut1(g, lutDir)
        val nx = g.next ?: return first
        // an adjustment layer: its colours applied to what the first grade made
        val second = lut(nx, lutDir)   // (that grade may have another one after it)
        val n = SIZE; val k = (n - 1) / 255f
        fun ch(v: Int, sh: Int) = (v shr sh and 255) * k
        return Array(n) { r -> Array(n) { gg -> IntArray(n) { b ->
            val v = first[r][gg][b]
            sample(second, ch(v, 16), ch(v, 8), ch(v, 0))
        } } }
    }

    /** Trilinear lookup in a cube (coordinates 0..SIZE-1). */
    private fun sample(cube: Array<Array<IntArray>>, x: Float, y: Float, z: Float): Int {
        val n = SIZE - 1
        val x0 = x.toInt().coerceIn(0, n); val y0 = y.toInt().coerceIn(0, n); val z0 = z.toInt().coerceIn(0, n)
        val x1 = minOf(n, x0 + 1); val y1 = minOf(n, y0 + 1); val z1 = minOf(n, z0 + 1)
        val fx = x - x0; val fy = y - y0; val fz = z - z0
        var out = 0xFF shl 24
        for (sh in intArrayOf(16, 8, 0)) {
            fun c(a: Int, b: Int, d: Int) = (cube[a][b][d] shr sh and 255).toFloat()
            val c00 = c(x0, y0, z0) + (c(x1, y0, z0) - c(x0, y0, z0)) * fx; val c10 = c(x0, y1, z0) + (c(x1, y1, z0) - c(x0, y1, z0)) * fx
            val c01 = c(x0, y0, z1) + (c(x1, y0, z1) - c(x0, y0, z1)) * fx; val c11 = c(x0, y1, z1) + (c(x1, y1, z1) - c(x0, y1, z1)) * fx
            val v = (c00 + (c10 - c00) * fy) + ((c01 + (c11 - c01) * fy) - (c00 + (c10 - c00) * fy)) * fz
            out = out or ((v + 0.5f).toInt().coerceIn(0, 255) shl sh)
        }
        return out
    }

    private fun lut1(g: Grade, lutDir: File): Array<Array<IntArray>> {
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
            if (g.hue != 0f) hueShift(c, g.hue * 45f)
            if (g.shSat > 0f || g.hiSat > 0f) splitTone(c, g)
            if (g.fade > 0f) lift(c, g.fade * 0.16f, 1f - g.fade * 0.04f)
            if (g.tint != 0f) { c[1] -= g.tint * 0.06f; c[0] += g.tint * 0.025f; c[2] += g.tint * 0.025f }
            val rr = (c[0].coerceIn(0f, 1f) * 255 + 0.5f).toInt(); val g2 = (c[1].coerceIn(0f, 1f) * 255 + 0.5f).toInt(); val bb = (c[2].coerceIn(0f, 1f) * 255 + 0.5f).toInt()
            (0xFF shl 24) or (rr shl 16) or (g2 shl 8) or bb
        } } }
    }

    /** A colour for a hue (0..1), full strength. */
    fun hueColor(h: Float): FloatArray {
        val x = (h % 1f) * 6f; val f = x - x.toInt()
        return when (x.toInt()) { 0 -> floatArrayOf(1f, f, 0f); 1 -> floatArrayOf(1 - f, 1f, 0f); 2 -> floatArrayOf(0f, 1f, f); 3 -> floatArrayOf(0f, 1 - f, 1f); 4 -> floatArrayOf(f, 0f, 1f); else -> floatArrayOf(1f, 0f, 1 - f) }
    }

    /** Split toning: shadows one colour, highlights another; balance moves the point between them. */
    private fun splitTone(c: FloatArray, g: Grade) {
        val l = luma(c).coerceIn(0f, 1f)
        val pivot = 0.5f + g.balance * 0.3f
        val ws = (1f - smooth(0f, pivot, l)) * g.shSat * 0.18f; val wh = smooth(pivot, 1f, l) * g.hiSat * 0.18f
        val sc = hueColor(g.shHue); val hc = hueColor(g.hiHue)
        for (i in 0..2) c[i] += (sc[i] - 0.5f) * ws + (hc[i] - 0.5f) * wh
    }

    /** Turns every hue by [deg] degrees (keeps brightness). */
    private fun hueShift(c: FloatArray, deg: Float) {
        val a = Math.toRadians(deg.toDouble()); val cs = Math.cos(a).toFloat(); val sn = Math.sin(a).toFloat()
        val k = 0.57735f
        val r = c[0]; val gg = c[1]; val b = c[2]
        // rotate around the grey axis (Rodrigues)
        val dot = (r + gg + b) * k
        val cx = k * (b - gg); val cy = k * (r - b); val cz = k * (gg - r)
        c[0] = r * cs + cx * sn + k * dot * (1 - cs)
        c[1] = gg * cs + cy * sn + k * dot * (1 - cs)
        c[2] = b * cs + cz * sn + k * dot * (1 - cs)
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
