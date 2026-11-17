package com.davacada.roll

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Overlays laid over the picture (like InShot's): light leaks, dust, film burn, VHS, bokeh, snow, rain, a 35mm
 * border, a date stamp, cinema bars. All drawn by Roll itself; some move (several frames), some breathe.
 * Grain and vignette are overlays too.
 */
object Overlays {
    class Kind(val id: String, val name: String, val fps: Float, val frames: Int)

    val ALL = listOf(
        Kind("leak_warm", "Light leak", 0f, 1), Kind("leak_rose", "Rose leak", 0f, 1), Kind("prism", "Prism", 0f, 1),
        Kind("burn", "Film burn", 0f, 1), Kind("dust", "Dust", 12f, 6), Kind("vhs", "VHS", 8f, 4),
        Kind("bokeh", "Bokeh", 0f, 1), Kind("snow", "Snow", 12f, 12), Kind("rain", "Rain", 18f, 8),
        Kind("border35", "35mm frame", 0f, 1), Kind("datestamp", "Date stamp", 0f, 1), Kind("letterbox", "Cinema bars", 0f, 1),
    )
    fun byId(id: String) = ALL.firstOrNull { it.id == id }

    /** How strongly a breathing overlay shows at time [t] (seconds): leaks and burns drift in and out. */
    fun breath(id: String, t: Double): Float = when (id) {
        "leak_warm", "leak_rose", "prism" -> (0.75 + 0.25 * sin(t * 2 * PI / 5.0)).toFloat()
        "burn" -> (0.7 + 0.3 * sin(t * 2 * PI / 3.0)).toFloat()
        else -> 1f
    }

    /** Draws frame [n] of overlay [id] at [w] × [h]. */
    fun draw(id: String, w: Int, h: Int, n: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = Random(n * 31 + id.hashCode())
        val m = max(w, h).toFloat()
        fun glow(x: Float, y: Float, rad: Float, col: Int) { p.shader = RadialGradient(x, y, rad, col, col and 0x00FFFFFF, Shader.TileMode.CLAMP); c.drawCircle(x, y, rad, p); p.shader = null }
        when (id) {
            "leak_warm" -> { glow(w * 0.05f, h * 0.15f, m * 0.6f, 0xCCFF7A1A.toInt()); glow(w * 0.0f, h * 0.55f, m * 0.35f, 0x99FF3A1A.toInt()); glow(w * 0.2f, h * 0.05f, m * 0.25f, 0xAAFFD27A.toInt()) }
            "leak_rose" -> { glow(w * 1.0f, h * 0.2f, m * 0.6f, 0xBBFF4F8B.toInt()); glow(w * 0.9f, h * 0.7f, m * 0.4f, 0x88FF8A3D.toInt()) }
            "prism" -> {
                p.shader = LinearGradient(0f, 0f, w.toFloat(), h * 0.4f, intArrayOf(0x00FF0000, 0x55FF3B3B, 0x55FFD23B, 0x553BFF7A, 0x553BB4FF, 0x55B43BFF, 0x00B43BFF), null, Shader.TileMode.CLAMP)
                c.save(); c.rotate(-18f, w / 2f, h / 2f); c.drawRect(-w * 0.5f, h * 0.05f, w * 1.5f, h * 0.38f, p); c.restore(); p.shader = null
            }
            "burn" -> { glow(w * 1.02f, h * 0.5f, m * 0.45f, 0xF2FFF1C8.toInt()); glow(w * 1.0f, h * 0.45f, m * 0.62f, 0xCCFF6A00.toInt()); glow(w * 0.95f, h * 0.9f, m * 0.3f, 0xAAD21A00.toInt()) }
            "dust" -> {
                p.color = Color.WHITE
                repeat((w * h / 9000).coerceIn(30, 400)) { p.alpha = 60 + r.nextInt(150); c.drawCircle(r.nextFloat() * w, r.nextFloat() * h, 0.6f + r.nextFloat() * m * 0.0025f, p) }
                p.color = Color.BLACK
                repeat((w * h / 20000).coerceIn(10, 150)) { p.alpha = 80 + r.nextInt(120); c.drawCircle(r.nextFloat() * w, r.nextFloat() * h, 0.6f + r.nextFloat() * m * 0.002f, p) }
                p.color = Color.WHITE; p.strokeWidth = max(1f, m * 0.0012f)
                repeat(r.nextInt(3)) { val x = r.nextFloat() * w; p.alpha = 50 + r.nextInt(70); c.drawLine(x, 0f, x + r.nextFloat() * 6 - 3, h.toFloat(), p) }
                // a hair now and then
                if (r.nextInt(3) == 0) { p.style = Paint.Style.STROKE; p.alpha = 120; p.color = Color.BLACK; val x = r.nextFloat() * w; val y = r.nextFloat() * h
                    c.drawArc(x, y, x + m * 0.08f, y + m * 0.05f, 10f, 160f, false, p); p.style = Paint.Style.FILL }
            }
            "vhs" -> {
                p.color = Color.BLACK; p.alpha = 40
                var y = 0f; val step = max(2f, h / 240f); while (y < h) { c.drawRect(0f, y, w.toFloat(), y + step / 2, p); y += step }
                // a tracking band that moves
                p.color = Color.WHITE; p.alpha = 28; val by = (h * (0.15f + 0.22f * n)) % h; c.drawRect(0f, by, w.toFloat(), by + h * 0.03f, p)
                p.alpha = 230; p.typeface = Typeface.MONOSPACE; p.textSize = h * 0.055f; p.setShadowLayer(h * 0.006f, 0f, 0f, Color.BLACK)
                c.drawText("PLAY ▶", w * 0.06f, h * 0.12f, p)
                p.textSize = h * 0.045f
                c.drawText(SimpleDateFormat("a hh:mm", Locale.US).format(Date()), w * 0.06f, h * 0.88f, p)
                c.drawText(SimpleDateFormat("MMM. dd yyyy", Locale.US).format(Date()).uppercase(), w * 0.06f, h * 0.94f, p)
            }
            "bokeh" -> repeat(26) {
                val x = r.nextFloat() * w; val y = r.nextFloat() * h; val rad = m * (0.02f + r.nextFloat() * 0.06f)
                val col = listOf(0x66FFD27A, 0x55FFFFFF, 0x55FF9A7A, 0x447AC8FF)[r.nextInt(4)]
                glow(x, y, rad, col)
            }
            "snow" -> {
                val rs = Random(77)
                repeat(140) {
                    val x0 = rs.nextFloat() * w; val y0 = rs.nextFloat() * h; val sp = 0.6f + rs.nextFloat(); val rad = m * (0.002f + rs.nextFloat() * 0.005f)
                    val y = (y0 + n * h / 12f * sp) % h; val x = x0 + sin((n + it) * 0.6) .toFloat() * m * 0.006f
                    p.color = Color.WHITE; p.alpha = 140 + rs.nextInt(100); c.drawCircle(x, y, rad, p)
                }
            }
            "rain" -> {
                val rs = Random(91); p.color = Color.WHITE; p.strokeWidth = max(1f, m * 0.0012f)
                repeat(220) {
                    val x0 = rs.nextFloat() * w; val y0 = rs.nextFloat() * h; val len = m * (0.02f + rs.nextFloat() * 0.035f)
                    val y = (y0 + n * h / 8f * (0.8f + rs.nextFloat() * 0.4f)) % h
                    p.alpha = 50 + rs.nextInt(70); c.drawLine(x0, y, x0 - len * 0.18f, y + len, p)
                }
            }
            "border35" -> {
                val side = w * 0.085f
                p.color = 0xFF0B0907.toInt(); c.drawRect(0f, 0f, side, h.toFloat(), p); c.drawRect(w - side, 0f, w.toFloat(), h.toFloat(), p)
                p.color = 0xFFE8E2D6.toInt(); val hw = side * 0.42f; val hh = side * 0.3f; var y = side * 0.25f
                while (y < h) { c.drawRoundRect((side - hw) / 2, y, (side + hw) / 2, y + hh, hh * 0.25f, hh * 0.25f, p); c.drawRoundRect(w - (side + hw) / 2, y, w - (side - hw) / 2, y + hh, hh * 0.25f, hh * 0.25f, p); y += hh * 2.1f }
                p.color = 0xFFF2A541.toInt(); p.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); p.textSize = side * 0.32f
                c.save(); c.rotate(-90f, side * 0.5f, h * 0.5f); c.drawText("ROLL 400  ▸ 24", side * 0.5f - side * 2.2f, h * 0.5f + p.textSize * 0.35f, p); c.restore()
            }
            "datestamp" -> {
                p.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); p.textSize = h * 0.06f; p.color = 0xFFFF8A2A.toInt(); p.setShadowLayer(h * 0.012f, 0f, 0f, 0xCCFF5A00.toInt())
                val t = "'" + SimpleDateFormat("yy  MM  dd", Locale.US).format(Date())
                p.textAlign = Paint.Align.RIGHT; c.drawText(t, w * 0.93f, h * 0.92f, p)
            }
            "letterbox" -> {
                val target = 2.39f; val ratio = w.toFloat() / h
                p.color = Color.BLACK
                if (ratio < target) { val bar = (h - w / target) / 2; c.drawRect(0f, 0f, w.toFloat(), bar, p); c.drawRect(0f, h - bar, w.toFloat(), h.toFloat(), p) }
                else { val bar = (w - h * target) / 2; c.drawRect(0f, 0f, bar, h.toFloat(), p); c.drawRect(w - bar, 0f, w.toFloat(), h.toFloat(), p) }
            }
        }
        return b
    }

    /** Grain: specks of light and dark, one frame of several. */
    fun grain(w: Int, h: Int, n: Int): Bitmap {
        val r = Random(n * 977 + 3)
        val px = IntArray(w * h)
        for (i in px.indices) {
            val v = r.nextFloat()
            px[i] = when { v > 0.6f -> Color.argb(((v - 0.6f) * 255).toInt(), 255, 255, 255); v < 0.4f -> Color.argb(((0.4f - v) * 255).toInt(), 0, 0, 0); else -> 0 }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    fun vignette(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(w / 2f, h / 2f, max(w, h) * 0.75f, intArrayOf(0, 0, 0x99000000.toInt(), 0xEE000000.toInt()), floatArrayOf(0f, 0.45f, 0.8f, 1f), Shader.TileMode.CLAMP)
        Canvas(b).drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        return b
    }
}

/**
 * A full-frame overlay for Media3: a few pre-drawn frames that loop, stretched over the video, at [amount] opacity.
 * The frames are drawn once the video size is known.
 */
@UnstableApi
class FrameOverlay(private val kind: String, private val amount: Float) : BitmapOverlay() {
    private var frames: List<Bitmap> = emptyList()
    private var bg = Size(1, 1)
    private var fps = 0f

    override fun configure(videoSize: Size) {
        bg = videoSize
        val vw = videoSize.width; val vh = videoSize.height
        when (kind) {
            "grain" -> {
                // half resolution: each speck covers about 2 × 2 pixels, like real 35mm grain
                val w = maxOf(2, vw / 2); val h = maxOf(2, vh / 2)
                frames = (0 until 4).map { Overlays.grain(w, h, it) }; fps = 12f
            }
            "vignette" -> { frames = listOf(Overlays.vignette(maxOf(2, vw / 4), maxOf(2, vh / 4))); fps = 0f }
            else -> {
                val k = Overlays.byId(kind) ?: return
                val scale = if (k.frames > 1) 0.5f else 0.75f
                val w = maxOf(2, (vw * scale).toInt()); val h = maxOf(2, (vh * scale).toInt())
                frames = (0 until k.frames).map { Overlays.draw(kind, w, h, it) }; fps = k.fps
            }
        }
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (frames.isEmpty()) frames = listOf(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))
        val i = if (fps <= 0f || frames.size == 1) 0 else ((presentationTimeUs / 1_000_000.0 * fps).toLong() % frames.size).toInt()
        return frames[i]
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val b = frames.firstOrNull()
        val sx = if (b != null) bg.width.toFloat() / b.width else 1f; val sy = if (b != null) bg.height.toFloat() / b.height else 1f
        val a = when (kind) { "grain" -> amount * 0.55f; "vignette" -> amount; else -> amount * Overlays.breath(kind, presentationTimeUs / 1_000_000.0) }
        return OverlaySettings.Builder().setScale(sx, sy).setAlphaScale(a.coerceIn(0f, 1f)).build()
    }

    override fun release() { super.release(); frames = emptyList() }
}
