package com.davacada.roll

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.random.Random

/** Matt black with grain, warm off-white text, amber for what's active. No boxes around buttons. */
object C {
    const val BLACK = 0xFF050505.toInt()
    const val TEXT = 0xFFEFE8DF.toInt()
    const val SUB = 0xFF9A9086.toInt()
    const val FAINT = 0xFF6B6259.toInt()
    const val AMBER = 0xFFF2A541.toInt()
    const val PANEL = 0xF20C0C0C.toInt()
    const val LINE = 0x1FFFFFFF
    const val MONO = "monospace"
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float) = v * resources.displayMetrics.density

/** The background: matt black with a fine grain, one speck per screen pixel. */
fun Context.grain(): Drawable {
    val n = 256
    val r = Random(9)
    val px = IntArray(n * n) {
        val v = r.nextFloat()
        when { v > 0.72f -> Color.argb(9 + r.nextInt(14), 255, 255, 255); v < 0.3f -> Color.argb(30 + r.nextInt(60), 0, 0, 0); else -> 0 }
    }
    val bm = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).apply { density = resources.displayMetrics.densityDpi }
    val tile = BitmapDrawable(resources, bm).apply { setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    return LayerDrawable(arrayOf(GradientDrawable().apply { setColor(0xFF030303.toInt()) }, tile))
}

fun pill(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius; if (stroke > 0) setStroke(stroke, strokeColor)
}

fun ripple(mask: Drawable? = null) = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, mask)

fun Context.text(s: CharSequence, sp: Float, color: Int = C.TEXT, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
    text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    typeface = when { mono -> Typeface.create(C.MONO, if (bold) Typeface.BOLD else Typeface.NORMAL); bold -> Typeface.create("sans-serif-medium", Typeface.BOLD); else -> Typeface.DEFAULT }
}

/** A borderless button: an icon with a small word under it (or just the icon). */
fun Context.bare(icon: Int, label: String?, color: Int = C.TEXT, size: Int = 22, onClick: () -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
    minimumWidth = dp(52); setPadding(dp(6), dp(6), dp(6), dp(6))
    background = ripple()
    addView(ImageView(context).apply { setImageResource(icon); setColorFilter(color) }, LinearLayout.LayoutParams(dp(size), dp(size)))
    if (label != null) addView(context.text(label, 10.5f, color, true).apply { setPadding(0, dp(5), 0, 0) })
    contentDescription = label ?: ""
    setOnClickListener { onClick() }
}

/** A word you tap (amber when chosen). */
fun Context.word(label: String, on: Boolean, sp: Float = 16f, onClick: () -> Unit) = text(label, sp, if (on) C.AMBER else C.TEXT, on).apply {
    setPadding(dp(4), dp(8), dp(4), dp(8)); background = ripple(); setOnClickListener { onClick() }
}

/** A labelled slider row: name, track, value. */
fun Context.slider(name: String, value: Float, min: Float, max: Float, onMove: (Float) -> Unit): View = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    val num = context.text("", 11.5f, C.TEXT, mono = true).apply { gravity = Gravity.END }
    fun show(v: Float) { num.text = if (min < 0) "%+d".format((v * 100).toInt()) else "${(v * 100).toInt()}" }
    addView(context.text(name, 12.5f, C.SUB), LinearLayout.LayoutParams(dp(78), -2))
    addView(SeekBar(context).apply {
        this.max = 1000; progress = (((value - min) / (max - min)) * 1000).toInt()
        progressTintList = ColorStateList.valueOf(C.AMBER); thumbTintList = ColorStateList.valueOf(Color.WHITE)
        progressBackgroundTintList = ColorStateList.valueOf(0x30FFFFFF)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { val v = min + (max - min) * p / 1000f; show(v); if (fromUser) onMove(v) }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }, LinearLayout.LayoutParams(0, dp(36), 1f))
    addView(num, LinearLayout.LayoutParams(dp(40), -2))
    show(value)
}

fun fmt(ms: Long, tenths: Boolean = false): String {
    val s = ms / 1000
    return if (tenths) "%02d:%02d.%d".format(s / 60, s % 60, (ms % 1000) / 100) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * A Lightroom-style slider: name on the left, value on the right, a thin track underneath (optionally a colour
 * gradient, e.g. blue to yellow for Temp), the zero point marked. Double-tap to reset.
 */
class LrSlider(ctx: Context, val name: String, var value: Float, private val min: Float, private val max: Float,
               private val gradient: IntArray?, private val format: (Float) -> String, private val onMove: (Float) -> Unit) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = C.TEXT; textSize = 13.5f * d }
    private val num = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = C.TEXT; textSize = 12.5f * d; typeface = Typeface.MONOSPACE; textAlign = android.graphics.Paint.Align.RIGHT }
    private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF; strokeWidth = 2.5f * d; strokeCap = android.graphics.Paint.Cap.ROUND }
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = C.AMBER; strokeWidth = 2.5f * d; strokeCap = android.graphics.Paint.Cap.ROUND }
    private val knob = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(3f * d, 0f, 1f * d, 0x80000000.toInt()) }
    private val zero = android.graphics.Paint().apply { color = 0x66FFFFFF }
    private val pad = 4f * d
    private var lastTap = 0L

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (54 * d).toInt())

    private fun xOf(v: Float) = pad + (width - 2 * pad) * (v - min) / (max - min)

    override fun onDraw(c: android.graphics.Canvas) {
        val changed = value != (if (min < 0) 0f else min)
        label.color = if (changed) C.TEXT else C.SUB
        c.drawText(name, pad, 18 * d, label)
        num.color = if (changed) C.AMBER else C.SUB
        c.drawText(format(value), width - pad, 18 * d, num)
        val y = 38 * d
        if (gradient != null) {
            track.shader = android.graphics.LinearGradient(pad, 0f, width - pad, 0f, gradient, null, Shader.TileMode.CLAMP)
            c.drawLine(pad, y, width - pad, y, track); track.shader = null
        } else {
            c.drawLine(pad, y, width - pad, y, track)
            val from = if (min < 0) xOf(0f) else xOf(min)
            c.drawLine(from, y, xOf(value), y, fill)
        }
        if (min < 0) c.drawRect(xOf(0f) - 0.75f * d, y - 6 * d, xOf(0f) + 0.75f * d, y - 3.5f * d, zero)
        c.drawCircle(xOf(value), y, 8 * d, knob)
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastTap < 300) { value = if (min < 0) 0f else min; onMove(value); invalidate(); lastTap = 0; return true }
                lastTap = now
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val v = (min + (e.x - pad) / (width - 2 * pad) * (max - min)).coerceIn(min, max)
                value = if (min < 0 && kotlin.math.abs(v) < (max - min) * 0.015f) 0f else v   // snaps to zero
                onMove(value); invalidate()
            }
        }
        return true
    }
}

/** Big centred value with a title above (sheets: speed, volume). */
fun Context.headline(title: String, sub: String? = null): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    addView(context.text(title, 22f, C.TEXT, true))
    if (sub != null) addView(context.text(sub, 12.5f, C.SUB).apply { setPadding(0, dp(3), 0, 0) })
    setPadding(dp(4), 0, dp(4), dp(16))
}
