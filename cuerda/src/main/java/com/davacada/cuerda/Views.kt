package com.davacada.cuerda

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Notebook: cream paper, ink, highlighters. */
object C {
    const val PAPER = 0xFFF6F1E4.toInt()
    const val INK = 0xFF1F2A44.toInt()
    const val SUB = 0xFF6B7280.toInt()
    const val RULE = 0x4D6E96C8
    const val MARGIN = 0x73DC5A5A
    const val RED = 0xFFE2574C.toInt()
    const val MANGO = 0xFFFFD45C.toInt()
    const val LAGOON = 0xFF7FE3D6.toInt()
    const val CORAL = 0xFFFF9C83.toInt()
    const val PINK = 0xFFFF9DB5.toInt()
    const val HOLE = 0xFFE4DCC8.toInt()
    val MARKERS = intArrayOf(LAGOON, MANGO, PINK, CORAL)
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float) = v * resources.displayMetrics.density

object Fonts {
    private val cache = HashMap<Int, Typeface>()
    fun get(ctx: Context, w: Int): Typeface = cache.getOrPut(w) {
        ctx.resources.getFont(when { w >= 800 -> R.font.inter_extrabold; w >= 700 -> R.font.inter_bold; w >= 600 -> R.font.inter_semibold; else -> R.font.inter_regular })
    }
    private var hand: Typeface? = null
    var handOk = true
    /** Caveat, or a fallback if the font can't be loaded on this phone. */
    fun hand(ctx: Context): Typeface = hand ?: (try { ctx.resources.getFont(R.font.caveat) } catch (e: Throwable) {
        handOk = false; Typeface.create("casual", Typeface.BOLD)
    }).also { hand = it }
}

/** Inter, for small print. */
fun Context.text(s: CharSequence, sp: Float, color: Int = C.INK, weight: Int = 400) = TextView(this).apply {
    text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    typeface = Fonts.get(context, weight); includeFontPadding = false
}

/** Handwriting (Caveat). */
fun Context.hand(s: CharSequence, sp: Float, color: Int = C.INK, bold: Boolean = true) = TextView(this).apply {
    text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    typeface = Fonts.hand(context)
    if (Fonts.handOk) runCatching { fontVariationSettings = if (bold) "'wght' 700" else "'wght' 500" }
    includeFontPadding = false
}

/** A highlighter swipe behind a word: soft ends, slightly uneven corners. */
class Highlight(private val color: Int, private val alpha: Int = 225) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(c: Canvas) {
        val b = bounds
        val w = b.width().toFloat(); val h = b.height().toFloat()
        p.shader = LinearGradient(b.left.toFloat(), 0f, b.right.toFloat(), 0f,
            intArrayOf(color and 0x00FFFFFF, color, color, color and 0x00FFFFFF), floatArrayOf(0f, 0.05f, 0.94f, 1f), Shader.TileMode.CLAMP)
        p.alpha = alpha
        val path = Path().apply {
            moveTo(b.left + w * 0.02f, b.top + h * 0.16f)
            quadTo(b.left + w * 0.5f, b.top + h * 0.02f, b.right - w * 0.01f, b.top + h * 0.10f)
            lineTo(b.right.toFloat(), b.bottom - h * 0.14f)
            quadTo(b.left + w * 0.5f, b.bottom.toFloat(), b.left.toFloat(), b.bottom - h * 0.06f)
            close()
        }
        c.drawPath(path, p)
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

fun TextView.highlight(color: Int?) {
    if (color == null) { background = null; return }
    background = Highlight(color)
    val ctx = context
    setPadding(ctx.dp(7), ctx.dp(1), ctx.dp(7), ctx.dp(3))
}

fun round(color: Int, r: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = r; if (stroke > 0) setStroke(stroke, strokeColor)
}

/** The page: paper, grain, blue rules, red margin, punched holes. */
class Paper(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grain: Paint
    var scrollOffset = 0
        set(v) { field = v; invalidate() }
    init {
        val n = 160; val r = Random(9)
        val px = IntArray(n * n) {
            val v = r.nextFloat()
            when { v > 0.93f -> Color.argb(22, 80, 64, 40); v > 0.88f -> Color.argb(12, 80, 64, 40); v < 0.03f -> Color.argb(40, 255, 255, 255); else -> 0 }
        }
        grain = Paint().apply { shader = BitmapShader(Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    }
    override fun onDraw(c: Canvas) {
        c.drawColor(C.PAPER)
        val step = context.dpf(28f)
        p.color = C.RULE; p.strokeWidth = context.dpf(1f)
        var y = context.dpf(12f) - (scrollOffset % step)
        while (y < height) { c.drawLine(0f, y, width.toFloat(), y, p); y += step }
        p.color = C.MARGIN; p.strokeWidth = context.dpf(1.5f)
        val mx = context.dpf(40f); c.drawLine(mx, 0f, mx, height.toFloat(), p)
        p.color = C.HOLE
        val holes = 14; val gap = height / holes.toFloat()
        for (i in 0 until holes) c.drawCircle(context.dpf(11f), gap * (i + 0.5f), context.dpf(5.5f), p)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), grain)
    }
}

/** The tuner needle, drawn in pencil: ticks over ±50 cents, highlighter on the in-tune zone. */
/**
 * A needle with a little mass: readings set where it wants to go, and a damped spring moves it there
 * frame by frame, so it glides, settles with a tiny overshoot and drifts home when the sound stops.
 */
class Spring(private val stiffness: Double = 90.0, private val damping: Double = 14.0) {
    var pos = 0.0; private set
    private var vel = 0.0
    var target = 0.0
    /** Advances by [dt] seconds; returns true while still moving. */
    fun step(dt: Double): Boolean {
        var t = dt.coerceIn(0.0, 0.05)
        while (t > 0) {                         // small sub-steps keep it stable on slow frames
            val h = minOf(t, 0.008)
            val a = stiffness * (target - pos) - damping * vel
            vel += a * h; pos += vel * h; t -= h
        }
        return abs(target - pos) > 0.05 || abs(vel) > 0.05
    }
}

class Needle(ctx: Context) : View(ctx) {
    private var active = false
    private val spring = Spring()
    private var lastFrame = 0L
    private var glow = 0f                        // fades the needle ink in and out
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    fun set(c: Double?, on: Boolean) {
        active = on
        spring.target = if (on && c != null) c.coerceIn(-55.0, 55.0) else 0.0
        if (lastFrame == 0L) lastFrame = System.nanoTime()
        postInvalidateOnAnimation()
    }
    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0.016 else (now - lastFrame) / 1e9
        lastFrame = now
        val moving = spring.step(dt)
        glow += ((if (active) 1f else 0f) - glow) * (1f - Math.exp(-dt * 6).toFloat())

        val cx = width / 2f; val cy = height * 0.97f
        val r = min(width * 0.45f, height * 0.9f)
        p.style = Paint.Style.STROKE; p.color = C.MANGO; p.alpha = 200; p.strokeWidth = context.dpf(15f)
        val oval = RectF(cx - r * 0.97f, cy - r * 0.97f, cx + r * 0.97f, cy + r * 0.97f)
        c.drawArc(oval, -90f - 7f, 14f, false, p)
        p.alpha = 255; p.style = Paint.Style.FILL
        for (a in -50..50 step 5) {
            val rad = Math.toRadians(a.toDouble())
            val inner = if (a % 10 == 0) r * 0.85f else r * 0.92f
            p.color = C.INK; p.alpha = if (a % 10 == 0) 220 else 130
            p.strokeWidth = context.dpf(if (a % 10 == 0) 2f else 1.2f)
            c.drawLine(cx + (inner * sin(rad)).toFloat(), cy - (inner * cos(rad)).toFloat(), cx + (r * sin(rad)).toFloat(), cy - (r * cos(rad)).toFloat(), p)
        }
        val rad = Math.toRadians(spring.pos.coerceIn(-55.0, 55.0))
        p.color = blend(C.SUB, C.INK, glow); p.alpha = 255
        p.strokeWidth = context.dpf(2.6f)
        c.drawLine(cx, cy, cx + (r * 0.82f * sin(rad)).toFloat(), cy - (r * 0.82f * cos(rad)).toFloat(), p)
        p.color = C.RED; c.drawCircle(cx, cy, context.dpf(7f), p)
        if (moving || abs(glow - (if (active) 1f else 0f)) > 0.01f) postInvalidateOnAnimation() else lastFrame = 0L
    }
    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())
}

/** Piano: a pencil line with the reading as a red dot. */
class CentsBar(ctx: Context) : View(ctx) {
    var cents: Double? = null
        set(v) { field = v; spring.target = v?.coerceIn(-50.0, 50.0) ?: 0.0; if (lastFrame == 0L) lastFrame = System.nanoTime(); postInvalidateOnAnimation() }
    private val spring = Spring(110.0, 17.0)
    private var lastFrame = 0L
    private var alphaDot = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0.016 else (now - lastFrame) / 1e9
        lastFrame = now
        val moving = spring.step(dt)
        alphaDot += ((if (cents != null) 1f else 0f) - alphaDot) * (1f - Math.exp(-dt * 7).toFloat())
        val y = height / 2f
        p.color = C.MANGO; p.alpha = 190; p.strokeWidth = context.dpf(14f)
        c.drawLine(width / 2f - width * 0.05f, y, width / 2f + width * 0.05f, y, p)
        p.alpha = 255; p.color = C.INK; p.strokeWidth = context.dpf(2f)
        c.drawLine(context.dpf(6f), y, width - context.dpf(6f), y, p)
        for (k in -5..5) {
            val x = width / 2f + k * (width / 2f - context.dpf(10f)) / 5f
            c.drawLine(x, y - context.dpf(if (k == 0) 11f else 6f), x, y + context.dpf(if (k == 0) 11f else 6f), p)
        }
        if (alphaDot > 0.01f) {
            val x = (width / 2f + (spring.pos / 50.0 * (width / 2f - context.dpf(10f)))).toFloat()
            p.color = C.RED; p.alpha = (255 * alphaDot).toInt(); c.drawCircle(x, y, context.dpf(9f), p)
        }
        if (moving || abs(alphaDot - (if (cents != null) 1f else 0f)) > 0.01f) postInvalidateOnAnimation() else lastFrame = 0L
    }
}

/** Two octaves of hand-drawn keys starting at [startC]. */
class Keyboard(ctx: Context) : View(ctx) {
    var startC = 60
    var lit: Int? = null
    var onKey: ((Int) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val whiteSteps = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val blackAfter = mapOf(0 to 1, 1 to 3, 3 to 6, 4 to 8, 5 to 10)
    init {
        setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_UP) keyAt(e.x, e.y)?.let { onKey?.invoke(it) }; true }
    }
    private fun keyAt(x: Float, y: Float): Int? {
        val ww = width / 14f
        val wi = (x / ww).toInt().coerceIn(0, 13)
        if (y < height * 0.6f) for (side in listOf(wi - 1, wi)) {
            if (side < 0) continue
            val sem = blackAfter[side % 7] ?: continue
            if (abs(x - (side + 1) * ww) < ww * 0.32f) return startC + (side / 7) * 12 + sem
        }
        return startC + (wi / 7) * 12 + whiteSteps[wi % 7]
    }
    override fun onDraw(c: Canvas) {
        val ww = width / 14f; val r = context.dpf(6f)
        p.textAlign = Paint.Align.CENTER; p.textSize = context.dpf(17f); p.typeface = Fonts.hand(context)
        for (i in 0 until 14) {
            val midi = startC + (i / 7) * 12 + whiteSteps[i % 7]
            val on = midi == lit
            val rect = RectF(i * ww + context.dpf(1f), 0f, (i + 1) * ww - context.dpf(1f), height.toFloat())
            p.style = Paint.Style.FILL; p.color = if (on) C.MANGO else 0xFFFBF8EF.toInt(); c.drawRoundRect(rect, r, r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = context.dpf(1.6f); p.color = C.INK; c.drawRoundRect(rect, r, r, p)
            p.style = Paint.Style.FILL; p.color = if (on) C.INK else C.SUB
            val label = Notes.name(midi) + if (on || midi % 12 == 0) Notes.octave(midi).toString() else ""
            c.drawText(label, i * ww + ww / 2, height - context.dpf(9f), p)
        }
        for (i in 0 until 13) {
            val sem = blackAfter[i % 7] ?: continue
            val midi = startC + (i / 7) * 12 + sem
            val bx = (i + 1) * ww
            p.color = if (midi == lit) C.RED else C.INK
            c.drawRoundRect(RectF(bx - ww * 0.3f, 0f, bx + ww * 0.3f, height * 0.6f), r * 0.7f, r * 0.7f, p)
        }
    }
}

/** A guitar chord box in ink. */
class ChordBox(ctx: Context) : View(ctx) {
    var frets: IntArray? = null
    var barre = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    override fun onDraw(c: Canvas) {
        val f = frets ?: return
        val fretted = f.filter { it > 0 }
        val low = fretted.minOrNull() ?: 1
        val start = if ((fretted.maxOrNull() ?: 0) <= 4) 1 else low
        val left = context.dpf(16f); val right = width - context.dpf(6f); val top = context.dpf(16f); val bottom = height - context.dpf(4f)
        val sw = (right - left) / 5; val fh = (bottom - top) / 5
        p.color = C.INK; p.strokeWidth = context.dpf(1.3f)
        for (s in 0..5) c.drawLine(left + s * sw, top, left + s * sw, bottom, p)
        for (k in 0..5) { p.strokeWidth = context.dpf(if (k == 0 && start == 1) 3.5f else 1.3f); c.drawLine(left, top + k * fh, right, top + k * fh, p) }
        p.typeface = Fonts.hand(context); p.textSize = context.dpf(16f); p.textAlign = Paint.Align.CENTER
        if (start > 1) { p.color = C.SUB; c.drawText(start.toString(), left - context.dpf(9f), top + fh * 0.8f, p) }
        if (barre && fretted.isNotEmpty()) {
            val strings = f.indices.filter { f[it] == low }
            if (strings.size >= 2) {
                p.color = C.RED
                val y = top + (low - start + 0.5f) * fh
                c.drawRoundRect(RectF(left + strings.first() * sw - sw * 0.3f, y - fh * 0.28f, left + strings.last() * sw + sw * 0.3f, y + fh * 0.28f), fh, fh, p)
            }
        }
        for (s in 0..5) {
            val x = left + s * sw
            when {
                f[s] < 0 -> { p.color = C.SUB; c.drawText("×", x, top - context.dpf(3f), p) }
                f[s] == 0 -> { p.style = Paint.Style.STROKE; p.color = C.INK; p.strokeWidth = context.dpf(1.3f); c.drawCircle(x, top - context.dpf(7f), context.dpf(3.3f), p); p.style = Paint.Style.FILL }
                else -> { p.color = C.RED; c.drawCircle(x, top + (f[s] - start + 0.5f) * fh, min(sw, fh) * 0.32f, p) }
            }
        }
    }
}

/**
 * The listening strip: an ink waveform of everything heard, with each chord change marked by a red dashed line
 * and the chord written on top. Follows the newest sound while listening; drag to look back.
 */
class Wave(ctx: Context) : View(ctx) {
    data class Mark(val index: Int, val name: String, val ms: Long)
    private val levels = FloatArray(60000)
    private var count = 0
    private val marks = ArrayList<Mark>()
    private var peak = 0.02f
    private var offset = 0f          // bars scrolled back from the newest
    private var lastX = 0f
    private var dragging = false
    var follow = true
    private val barW get() = context.dpf(2.6f)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.RED; strokeWidth = context.dpf(1.3f); style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(context.dpf(4f), context.dpf(4f)), 0f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.dpf(28f); typeface = Fonts.hand(context); color = C.INK; isFakeBoldText = true }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.dpf(10.5f); color = C.SUB; typeface = Fonts.get(context, 400) }
    private val hl = Paint(Paint.ANTI_ALIAS_FLAG)

    fun clear() { count = 0; marks.clear(); peak = 0.02f; offset = 0f; follow = true; invalidate() }

    fun push(level: Double, chordName: String?, ms: Long) {
        if (count >= levels.size) { System.arraycopy(levels, 1000, levels, 0, levels.size - 1000); count -= 1000; marks.replaceAll { it.copy(index = it.index - 1000) }; marks.removeAll { it.index < 0 } }
        levels[count] = level.toFloat()
        peak = max(peak * 0.999f, level.toFloat())
        if (chordName != null && marks.lastOrNull()?.name != chordName) marks.add(Mark(count, chordName, ms))
        count++
        if (follow) offset = 0f
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; dragging = true; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                offset = (offset + (e.x - lastX) / barW).coerceIn(0f, max(0f, count - width / barW))
                follow = offset < 1f
                lastX = e.x; invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; parent?.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val top = context.dpf(40f); val bottom = height - context.dpf(18f)
        val mid = (top + bottom) / 2; val half = (bottom - top) / 2
        val visible = (width / barW).toInt()
        val end = count - offset.toInt()
        val start = end - visible
        p.color = C.INK; p.alpha = 90; p.strokeWidth = context.dpf(0.8f)
        c.drawLine(0f, mid, width.toFloat(), mid, p)
        p.strokeWidth = context.dpf(1.5f)
        for (i in max(0, start) until end) {
            val x = (i - start) * barW + barW / 2
            val v = (levels[i] / peak).coerceIn(0.03f, 1f)
            p.alpha = if (i >= end - 8 && follow) 110 else 220
            c.drawLine(x, mid - v * half, x, mid + v * half, p)
        }
        p.alpha = 255
        if (count == 0) {
            small.textAlign = Paint.Align.CENTER
            c.drawText("the sound will draw itself here", width / 2f, mid + context.dpf(4f), small)
            small.textAlign = Paint.Align.LEFT
        }
        // chord marks
        var lastLabelRight = -1f
        marks.forEachIndexed { k, m ->
            if (m.index < start - 40 || m.index > end) return@forEachIndexed
            val x = (m.index - start) * barW
            val path = Path().apply { moveTo(x, top - context.dpf(4f)); lineTo(x, bottom) }
            c.drawPath(path, dash)
            val tw = label.measureText(m.name)
            val lx = max(x - context.dpf(4f), lastLabelRight + context.dpf(4f))
            hl.color = C.MARKERS[k % 4]; hl.alpha = 220
            c.drawRoundRect(RectF(lx - context.dpf(4f), context.dpf(4f), lx + tw + context.dpf(5f), top - context.dpf(4f)), context.dpf(6f), context.dpf(6f), hl)
            c.drawText(m.name, lx, top - context.dpf(10f), label)
            lastLabelRight = lx + tw + context.dpf(5f)
            c.drawText(clock(m.ms), x + context.dpf(3f), height - context.dpf(3f), small)
        }
        if (!follow && count > 0) {
            small.textAlign = Paint.Align.RIGHT
            c.drawText("drag left for now ›", width.toFloat(), height - context.dpf(3f), small)
            small.textAlign = Paint.Align.LEFT
        }
    }

    private fun clock(ms: Long) = String.format(java.util.Locale.US, "%d:%02d", ms / 60000, (ms / 1000) % 60)
}
