package com.davacada.cuerda

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

object C {
    const val BG = 0xFF17213A.toInt()
    const val INK = 0xFFFFF3E0.toInt()
    const val SUB = 0xFFA9B8C8.toInt()
    const val CORAL = 0xFFFF7A59.toInt()
    const val MANGO = 0xFFFFC24B.toInt()
    const val LAGOON = 0xFF46D6C3.toInt()
    const val HIB = 0xFFFF5E7E.toInt()
    const val CARD = 0x1FFFF3E0
    const val CARD_DARK = 0x8C121A2E.toInt()
    const val LINE = 0x1AFFF3E0
    const val ON_BRIGHT = 0xFF12203A.toInt()
    const val TRACK = 0xFF0F1628.toInt()
    const val TICK = 0xFF46527A.toInt()
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float) = v * resources.displayMetrics.density

object Fonts {
    private val cache = HashMap<Int, Typeface>()
    fun get(ctx: Context, w: Int): Typeface = cache.getOrPut(w) {
        ctx.resources.getFont(when { w >= 800 -> R.font.inter_extrabold; w >= 700 -> R.font.inter_bold; w >= 600 -> R.font.inter_semibold; else -> R.font.inter_regular })
    }
    val mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
}

fun Context.text(s: CharSequence, sp: Float, color: Int = C.INK, weight: Int = 400) = TextView(this).apply {
    text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    typeface = Fonts.get(context, weight); includeFontPadding = false
}

fun Context.mono(s: CharSequence, sp: Float, color: Int = C.SUB) = TextView(this).apply {
    text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); typeface = Fonts.mono; includeFontPadding = false
}

fun round(color: Int, r: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = r; if (stroke > 0) setStroke(stroke, strokeColor)
}

fun tint(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

fun Context.eyebrow(s: String, color: Int = C.LAGOON) = text(s.uppercase(), 12f, color, 700).apply { letterSpacing = 0.1f }

/** "cuerda" with the e in mango. */
fun Context.wordmark(sp: Float): TextView {
    val b = SpannableStringBuilder("cuerda")
    b.setSpan(ForegroundColorSpan(C.MANGO), 2, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return text(b, sp, C.INK, 800).apply { letterSpacing = -0.045f }
}

fun Context.dots(): View = LinearLayout(this).apply {
    gravity = Gravity.CENTER_VERTICAL
    for (c in intArrayOf(C.CORAL, C.MANGO, C.LAGOON)) addView(View(context).apply { background = round(c, dpf(5f)) },
        LinearLayout.LayoutParams(dp(10), dp(10)).apply { leftMargin = dp(6) })
}

/** Light grain over the painting. */
fun Context.grain(): Drawable {
    val n = 160; val r = Random(5)
    val px = IntArray(n * n) {
        val v = r.nextFloat()
        when { v > 0.94f -> Color.argb(26, 255, 243, 224); v < 0.05f -> Color.argb(30, 0, 0, 0); else -> 0 }
    }
    val bm = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).apply { density = resources.displayMetrics.densityDpi }
    return BitmapDrawable(resources, bm).apply { setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
}

/** The tuner needle: ±50 cents over a 100° arc, green zone in the middle. */
class Needle(ctx: Context) : View(ctx) {
    var cents = 0.0; var active = false
    private var shown = 0.0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    fun set(c: Double?, on: Boolean) { active = on; if (c != null) cents = c.coerceIn(-50.0, 50.0); invalidate() }
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height * 0.96f
        val r = Math.min(width * 0.46f, height * 0.9f)
        for (a in -50..50 step 5) {
            val rad = Math.toRadians(a.toDouble())
            val inner = if (a % 10 == 0) r * 0.86f else r * 0.91f
            p.color = if (abs(a) <= 5) C.MANGO else C.TICK
            p.strokeWidth = context.dpf(if (a % 10 == 0) 2.4f else 1.4f)
            c.drawLine(cx + (inner * sin(rad)).toFloat(), cy - (inner * cos(rad)).toFloat(), cx + (r * sin(rad)).toFloat(), cy - (r * cos(rad)).toFloat(), p)
        }
        // ease toward the reading
        shown += (cents - shown) * 0.35
        val rad = Math.toRadians(if (active) shown else 0.0)
        p.color = if (!active) C.TICK else if (abs(shown) <= 5) C.MANGO else C.INK
        p.strokeWidth = context.dpf(3f)
        c.drawLine(cx, cy, cx + (r * 0.84f * sin(rad)).toFloat(), cy - (r * 0.84f * cos(rad)).toFloat(), p)
        p.color = C.CORAL
        c.drawCircle(cx, cy, context.dpf(7f), p)
        if (active && abs(shown - cents) > 0.3) postInvalidateOnAnimation()
    }
}

/** Piano: a straight sharp/flat bar. */
class CentsBar(ctx: Context) : View(ctx) {
    var cents: Double? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val h = context.dpf(10f); val y = height / 2f
        p.color = C.TRACK; c.drawRoundRect(RectF(0f, y - h / 2, width.toFloat(), y + h / 2), h / 2, h / 2, p)
        p.color = C.TICK; c.drawRect(width / 2f - context.dpf(1f), y - context.dpf(11f), width / 2f + context.dpf(1f), y + context.dpf(11f), p)
        cents?.let {
            val x = (width / 2f + (it.coerceIn(-50.0, 50.0) / 50.0 * (width / 2f - context.dpf(10f)))).toFloat()
            p.color = if (abs(it) <= 5) C.MANGO else C.CORAL
            c.drawCircle(x, y, context.dpf(10f), p)
        }
    }
}

/** Two octaves of piano keys, starting at [startC] (a C), with [lit] highlighted. */
class Keyboard(ctx: Context) : View(ctx) {
    var startC = 60
    var lit: Int? = null
    var onKey: ((Int) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val whiteSteps = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val blackAfter = mapOf(0 to 1, 1 to 3, 3 to 6, 4 to 8, 5 to 10)   // white index → black semitone
    init {
        setOnTouchListener { _, e ->
            if (e.action == android.view.MotionEvent.ACTION_UP) keyAt(e.x, e.y)?.let { onKey?.invoke(it) }
            true
        }
    }
    private fun keyAt(x: Float, y: Float): Int? {
        val ww = width / 14f
        val wi = (x / ww).toInt().coerceIn(0, 13)
        if (y < height * 0.6f) {
            for (side in listOf(wi - 1, wi)) {
                if (side < 0) continue
                val sem = blackAfter[side % 7] ?: continue
                val bx = (side + 1) * ww
                if (abs(x - bx) < ww * 0.32f) return startC + (side / 7) * 12 + sem
            }
        }
        return startC + (wi / 7) * 12 + whiteSteps[wi % 7]
    }
    override fun onDraw(c: Canvas) {
        val ww = width / 14f; val gap = context.dpf(1.5f); val r = context.dpf(8f)
        p.textAlign = Paint.Align.CENTER; p.textSize = context.dpf(11f); p.typeface = Fonts.get(context, 700)
        for (i in 0 until 14) {
            val midi = startC + (i / 7) * 12 + whiteSteps[i % 7]
            val on = midi == lit
            p.color = if (on) C.CORAL else C.INK
            c.drawRoundRect(RectF(i * ww + gap, -r, (i + 1) * ww - gap, height.toFloat()), r, r, p)
            p.color = if (on) C.ON_BRIGHT else C.SUB
            val label = Notes.name(midi) + if (on || midi % 12 == 0) Notes.octave(midi).toString() else ""
            c.drawText(label, i * ww + ww / 2, height - context.dpf(9f), p)
        }
        for (i in 0 until 13) {
            val sem = blackAfter[i % 7] ?: continue
            val midi = startC + (i / 7) * 12 + sem
            val bx = (i + 1) * ww
            p.color = if (midi == lit) C.CORAL else C.BG
            c.drawRoundRect(RectF(bx - ww * 0.3f, -r, bx + ww * 0.3f, height * 0.6f), r * 0.7f, r * 0.7f, p)
        }
    }
}

/** A guitar chord box. */
class ChordBox(ctx: Context) : View(ctx) {
    var frets: IntArray? = null
    var barre = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val f = frets ?: return
        val fretted = f.filter { it > 0 }
        val low = fretted.minOrNull() ?: 1
        val start = if ((fretted.maxOrNull() ?: 0) <= 4) 1 else low
        val left = context.dpf(18f); val right = width - context.dpf(8f); val top = context.dpf(16f); val bottom = height - context.dpf(6f)
        val sw = (right - left) / 5; val fh = (bottom - top) / 5
        p.color = C.TICK; p.strokeWidth = context.dpf(1.5f)
        for (s in 0..5) c.drawLine(left + s * sw, top, left + s * sw, bottom, p)
        for (k in 0..5) { p.strokeWidth = context.dpf(if (k == 0 && start == 1) 4f else 1.5f); c.drawLine(left, top + k * fh, right, top + k * fh, p) }
        p.textSize = context.dpf(10f); p.typeface = Fonts.get(context, 700); p.textAlign = Paint.Align.CENTER
        if (start > 1) { p.color = C.SUB; c.drawText(start.toString(), left - context.dpf(10f), top + fh * 0.7f, p) }
        // barre
        if (barre && fretted.isNotEmpty()) {
            val strings = f.indices.filter { f[it] == low }
            if (strings.size >= 2) {
                p.color = C.CORAL
                val y = top + (low - start + 0.5f) * fh
                c.drawRoundRect(RectF(left + strings.first() * sw - sw * 0.3f, y - fh * 0.28f, left + strings.last() * sw + sw * 0.3f, y + fh * 0.28f), fh, fh, p)
            }
        }
        for (s in 0..5) {
            val x = left + s * sw
            when {
                f[s] < 0 -> { p.color = C.SUB; c.drawText("×", x, top - context.dpf(4f), p) }
                f[s] == 0 -> { p.style = Paint.Style.STROKE; p.color = C.SUB; p.strokeWidth = context.dpf(1.4f); c.drawCircle(x, top - context.dpf(8f), context.dpf(3.5f), p); p.style = Paint.Style.FILL }
                else -> { p.color = C.CORAL; c.drawCircle(x, top + (f[s] - start + 0.5f) * fh, Math.min(sw, fh) * 0.32f, p) }
            }
        }
    }
}

/** A thin confidence bar. */
class Bar(ctx: Context) : View(ctx) {
    var value = 0f
    var color = C.MANGO
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        p.color = C.TRACK; c.drawRoundRect(RectF(0f, 0f, width.toFloat(), h), h / 2, h / 2, p)
        p.color = color; c.drawRoundRect(RectF(0f, 0f, width * value.coerceIn(0f, 1f), h), h / 2, h / 2, p)
    }
}
