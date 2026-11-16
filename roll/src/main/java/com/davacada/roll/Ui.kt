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

/** The background: near-black with a fine, visible grain. */
fun Context.grain(): Drawable {
    val n = 192
    val r = Random(9)
    val px = IntArray(n * n) {
        val v = r.nextFloat()
        when { v > 0.55f -> Color.argb(20 + r.nextInt(26), 255, 255, 255); v < 0.2f -> Color.argb(r.nextInt(50), 0, 0, 0); else -> 0 }
    }
    val tile = BitmapDrawable(resources, Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)).apply { setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    return LayerDrawable(arrayOf(GradientDrawable().apply { setColor(C.BLACK) }, tile))
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
