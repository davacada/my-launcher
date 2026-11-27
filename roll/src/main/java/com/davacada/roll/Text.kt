package com.davacada.roll

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.min

/** A title or a caption on the edit, from [startMs] to [endMs] (edit time). */
class TextItem(
    var text: String = "Your text",
    var startMs: Long = 0, var endMs: Long = 3000,
    val id: String = UUID.randomUUID().toString(),
) {
    var x = 0.5f; var y = 0.5f        // centre, as a share of the frame (0,0 top left)
    var size = 0.07f                  // letter height, as a share of the frame's height
    var font = "montserrat"
    var color = Color.WHITE
    var style = "shadow"              // plain, shadow, outline, box, pill, neon
    var boxColor = 0xCC000000.toInt()
    var align = "center"              // left, center, right
    var animIn = "pop"; var animOut = "fade"
    var rot = 0f
    var caption = false               // made by Auto captions (styled together)
    /** Which text channel it sits on (T1 = 0, T2 = 1 …). */
    var track = 0
    /** Captions: when each word starts (ms from [startMs]), for the word-by-word highlight. */
    val words = mutableListOf<Long>()
    var highlight = 0xFFF2A541.toInt()

    fun json(): JSONObject = JSONObject().put("id", id).put("t", text).put("s", startMs).put("e", endMs).put("x", x.toDouble()).put("y", y.toDouble())
        .put("sz", size.toDouble()).put("f", font).put("c", color).put("st", style).put("bc", boxColor).put("al", align)
        .put("ai", animIn).put("ao", animOut).put("r", rot.toDouble()).put("cap", caption).put("hl", highlight).put("tk", track)
        .put("w", JSONArray().apply { words.forEach { put(it) } })

    fun copyStyleFrom(o: TextItem) { x = o.x; y = o.y; size = o.size; font = o.font; color = o.color; style = o.style; boxColor = o.boxColor; align = o.align; animIn = o.animIn; animOut = o.animOut; rot = o.rot; highlight = o.highlight }

    companion object {
        fun from(o: JSONObject) = TextItem(o.optString("t"), o.optLong("s"), o.optLong("e"), o.optString("id", UUID.randomUUID().toString())).apply {
            x = o.optDouble("x", 0.5).toFloat(); y = o.optDouble("y", 0.5).toFloat(); size = o.optDouble("sz", 0.07).toFloat()
            font = o.optString("f", "montserrat"); color = o.optInt("c", Color.WHITE); style = o.optString("st", "shadow"); boxColor = o.optInt("bc", 0xCC000000.toInt())
            align = o.optString("al", "center"); animIn = o.optString("ai", "pop"); animOut = o.optString("ao", "fade"); rot = o.optDouble("r", 0.0).toFloat()
            caption = o.optBoolean("cap"); highlight = o.optInt("hl", 0xFFF2A541.toInt()); track = o.optInt("tk", 0)
            o.optJSONArray("w")?.let { a -> for (i in 0 until a.length()) words.add(a.optLong(i)) }
        }
    }
}

/** Draws titles and captions; the same drawing is used on the viewer and in the export. */
object TextDraw {
    class Font(val id: String, val name: String, val file: String?, val weight: Int = 0, val caps: Boolean = false)
    val FONTS = listOf(
        Font("montserrat", "Montserrat", "f_montserrat.ttf"), Font("anton", "Anton", "f_anton.ttf", caps = true),
        Font("bebas", "Bebas", "f_bebas.ttf", caps = true), Font("archivo", "Archivo", "f_archivo.ttf"),
        Font("playfair", "Playfair", "f_playfair.ttf", 700), Font("dmserif", "DM Serif", "f_dmserif.ttf"),
        Font("marker", "Marker", "f_marker.ttf"), Font("caveat", "Caveat", "f_caveat.ttf", 700),
        Font("pacifico", "Pacifico", "f_pacifico.ttf"), Font("spacemono", "Space Mono", "f_spacemono.ttf"),
        Font("sans", "Sans", null), Font("serif", "Serif", null),
    )
    val COLORS = intArrayOf(Color.WHITE, 0xFF0A0A0A.toInt(), 0xFFF2A541.toInt(), 0xFFFFE14D.toInt(), 0xFFFF5A5A.toInt(), 0xFFFF7EB6.toInt(), 0xFF7CD1FF.toInt(), 0xFF6EE7A8.toInt(), 0xFFB794FF.toInt())
    val STYLES = listOf("plain" to "Plain", "shadow" to "Shadow", "outline" to "Outline", "box" to "Box", "pill" to "Pill", "neon" to "Neon")
    val ANIMS = listOf("none" to "None", "fade" to "Fade", "pop" to "Pop", "slide" to "Slide up", "type" to "Typewriter", "blur" to "Blur", "zoom" to "Zoom")
    const val ANIM_MS = 350L

    private val faces = HashMap<String, Typeface>()
    fun face(ctx: Context, id: String): Typeface = faces.getOrPut(id) {
        val f = FONTS.firstOrNull { it.id == id } ?: FONTS[0]
        when {
            f.file == null -> if (f.id == "serif") Typeface.create(Typeface.SERIF, Typeface.BOLD) else Typeface.create("sans-serif-black", Typeface.NORMAL)
            else -> try {
                val b = Typeface.Builder(ctx.assets, "fonts/" + f.file)
                if (f.weight > 0) b.setFontVariationSettings("'wght' ${f.weight}")
                b.build() ?: Typeface.DEFAULT_BOLD
            } catch (e: Exception) { Typeface.DEFAULT_BOLD }
        }
    }

    fun active(items: List<TextItem>, t: Long) = items.filter { t >= it.startMs && t < it.endMs && it.text.isNotBlank() }

    /** A key that changes whenever the picture of the text would change (so it's only redrawn then). */
    fun key(items: List<TextItem>, t: Long, w: Int, h: Int): String = buildString {
        append(w).append('x').append(h)
        for (it in active(items, t)) {
            append('|').append(it.json().toString().hashCode())
            val local = t - it.startMs; val left = it.endMs - t
            if (local < ANIM_MS && it.animIn != "none") append(":i").append(local / 16)
            if (left < ANIM_MS && it.animOut != "none") append(":o").append(left / 16)
            if (it.caption && it.words.isNotEmpty()) append(":w").append(it.words.count { w -> w <= local })
        }
    }

    /** Draws everything showing at [t] onto [c] (a frame of [w] × [h]). */
    fun draw(ctx: Context, c: Canvas, items: List<TextItem>, t: Long, w: Int, h: Int) {
        for (it in active(items, t)) one(ctx, c, it, t, w, h)
    }

    private fun one(ctx: Context, c: Canvas, it: TextItem, t: Long, w: Int, h: Int) {
        val local = t - it.startMs; val left = it.endMs - t
        val pin = if (it.animIn == "none") 1f else (local.toFloat() / ANIM_MS).coerceIn(0f, 1f)
        val pout = if (it.animOut == "none") 1f else (left.toFloat() / ANIM_MS).coerceIn(0f, 1f)
        fun easeOut(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)
        fun back(x: Float): Float { val k = 1.70158f; val y = x - 1; return 1 + (k + 1) * y * y * y + k * y * y }
        var alpha = 1f; var scale = 1f; var dy = 0f; var blur = 0f; var chars = Int.MAX_VALUE
        fun apply(kind: String, p: Float, entering: Boolean) {
            when (kind) {
                "fade" -> alpha *= easeOut(p)
                "pop" -> { alpha *= min(1f, p * 3f); scale *= if (entering) 0.4f + 0.6f * back(p) else 0.7f + 0.3f * p }
                "slide" -> { alpha *= easeOut(p); dy += (1f - easeOut(p)) * h * 0.06f * (if (entering) 1f else -1f) }
                "type" -> if (entering) chars = (it.text.length * p).toInt() else alpha *= easeOut(p)
                "blur" -> { alpha *= easeOut(p); blur = maxOf(blur, (1f - p) * h * 0.02f) }
                "zoom" -> { alpha *= easeOut(p); scale *= if (entering) 1.8f - 0.8f * easeOut(p) else 0.6f + 0.4f * p }
            }
        }
        apply(it.animIn, pin, true); apply(it.animOut, pout, false)
        if (alpha <= 0.01f) return
        val font = FONTS.firstOrNull { f -> f.id == it.font }
        val raw = if (font?.caps == true) it.text.uppercase() else it.text
        val shown = if (chars < raw.length) raw.substring(0, chars.coerceAtLeast(0)) else raw
        if (shown.isEmpty()) return
        val px = it.size * h
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face(ctx, it.font); textSize = px; color = it.color }
        // captions: the word being said is highlighted
        val body: CharSequence = if (it.caption && it.words.isNotEmpty()) {
            val sp = SpannableString(shown)
            val spoken = it.words.count { w -> w <= local } - 1
            if (spoken >= 0) {
                var start = 0; var n = 0
                for (m in Regex("\\S+").findAll(shown)) { if (n == spoken) { start = m.range.first; sp.setSpan(ForegroundColorSpan(it.highlight), start, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); break }; n++ }
            }
            sp
        } else shown
        val maxW = (w * 0.86f).toInt()
        val al = when (it.align) { "left" -> Layout.Alignment.ALIGN_NORMAL; "right" -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_CENTER }
        val probe = StaticLayout.Builder.obtain(body, 0, body.length, paint, maxW).setAlignment(al).setLineSpacing(0f, 1.02f).setIncludePad(false).build()
        var lw = 0f; for (i in 0 until probe.lineCount) lw = maxOf(lw, probe.getLineWidth(i))
        val tw = minOf(maxW, (lw + 2).toInt())
        val lay = StaticLayout.Builder.obtain(body, 0, body.length, paint, tw).setAlignment(al).setLineSpacing(0f, 1.02f).setIncludePad(false).build()
        val bw = tw.toFloat(); val bh = lay.height.toFloat()
        c.save()
        c.translate(it.x * w, it.y * h + dy)
        c.rotate(it.rot); c.scale(scale, scale)
        c.translate(-bw / 2, -bh / 2)
        val a = (alpha * 255).toInt()
        val pad = px * 0.32f
        when (it.style) {
            "box", "pill" -> {
                val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = it.boxColor; this.alpha = (Color.alpha(it.boxColor) * alpha).toInt() }
                val r = if (it.style == "pill") (bh + pad) / 2f else px * 0.14f
                c.drawRoundRect(RectF(-pad, -pad * 0.55f, bw + pad, bh + pad * 0.55f), r, r, bp)
            }
        }
        if (blur > 0.5f) paint.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        when (it.style) {
            "shadow" -> { paint.setShadowLayer(px * 0.09f, 0f, px * 0.04f, Color.argb((a * 0.8f).toInt(), 0, 0, 0)) }
            "outline" -> {
                val o = TextPaint(paint).apply { style = Paint.Style.STROKE; strokeWidth = px * 0.11f; strokeJoin = Paint.Join.ROUND; color = if (luma(it.color) > 0.5f) Color.BLACK else Color.WHITE; this.alpha = a; clearShadowLayer() }
                StaticLayout.Builder.obtain(shown, 0, shown.length, o, tw).setAlignment(al).setLineSpacing(0f, 1.02f).setIncludePad(false).build().draw(c)
            }
            "neon" -> { paint.setShadowLayer(px * 0.35f, 0f, 0f, it.color); val g = TextPaint(paint).apply { this.alpha = a }; StaticLayout.Builder.obtain(body, 0, body.length, g, tw).setAlignment(al).setIncludePad(false).build().draw(c) }
        }
        paint.alpha = a
        lay.draw(c)
        c.restore()
    }

    private fun luma(c: Int) = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f

    /** Where a text item sits on a [w] × [h] frame (for tapping it on the viewer). */
    fun bounds(ctx: Context, it: TextItem, w: Int, h: Int): RectF {
        val px = it.size * h
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face(ctx, it.font); textSize = px }
        val lay = StaticLayout.Builder.obtain(it.text, 0, it.text.length, paint, (w * 0.86f).toInt()).setIncludePad(false).build()
        var lw = 0f; for (i in 0 until lay.lineCount) lw = maxOf(lw, lay.getLineWidth(i))
        val cx = it.x * w; val cy = it.y * h
        return RectF(cx - lw / 2 - px * 0.4f, cy - lay.height / 2f - px * 0.3f, cx + lw / 2 + px * 0.4f, cy + lay.height / 2f + px * 0.3f)
    }
}

/** Export: titles and captions over clip [index], drawn at the frame's full size, on top of the grade. */
@UnstableApi
class TextOverlay(private val ctx: Context, private val items: List<TextItem>, private val clipStartMs: Long) : BitmapOverlay() {
    private var size = Size(2, 2)
    private var bmp: Bitmap? = null
    private var spare: Bitmap? = null
    private var lastKey = ""
    private var firstUs = Long.MIN_VALUE

    override fun configure(videoSize: Size) {
        size = videoSize
        bmp = Bitmap.createBitmap(videoSize.width, videoSize.height, Bitmap.Config.ARGB_8888)
        spare = Bitmap.createBitmap(videoSize.width, videoSize.height, Bitmap.Config.ARGB_8888)
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val b = bmp ?: Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { bmp = it }
        if (firstUs == Long.MIN_VALUE) firstUs = presentationTimeUs
        val t = clipStartMs + (presentationTimeUs - firstUs) / 1000
        val k = TextDraw.key(items, t, b.width, b.height)
        if (k != lastKey) {
            // draw into the other bitmap, so Media3 sees a new picture and uploads it
            lastKey = k
            val n = spare ?: Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
            n.eraseColor(Color.TRANSPARENT)
            TextDraw.draw(ctx, Canvas(n), items, t, n.width, n.height)
            spare = b; bmp = n
            return n
        }
        return b
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = OverlaySettings.Builder().build()
}
