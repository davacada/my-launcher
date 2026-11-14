package com.davacada.letra

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.style.ReplacementSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

/** Sandstone: warm sand surfaces, deep umber text, earthy colours per mode. */
object Look {
    const val SAND = 0xFFE8DCC6.toInt()
    const val CARD = 0xFFF5EDDF.toInt()
    const val SHEET = 0xFFF6EEE1.toInt()
    const val PAPER = 0xFFFFFAF1.toInt()
    const val UMBER = 0xFF3D2F23.toInt()
    const val SUB = 0xFF8A7360.toInt()
    const val SOFT = 0xFF5C4A3A.toInt()
    const val FAINT = 0xFFA8927C.toInt()
    const val LINE = 0x1A4A3624
    const val TINT = 0x144A3624          // light umber wash for chips and buttons
    const val TERRACOTTA = 0xFFB05E26.toInt()
    const val SLATE = 0xFF3E5F8F.toInt()
    const val OLIVE = 0xFF5A7A3A.toInt()
    const val WALNUT = 0xFF6B5443.toInt()
    const val RED = 0xFFC0392B.toInt()

    fun color(mode: String) = when (mode) {
        Note.SONG -> TERRACOTTA; Note.SCRIPT -> SLATE; Note.LIST -> OLIVE; else -> WALNUT
    }
    fun icon(mode: String) = when (mode) {
        Note.SONG -> R.drawable.i_song; Note.SCRIPT -> R.drawable.i_script; Note.LIST -> R.drawable.i_list; else -> R.drawable.i_note
    }
    fun name(mode: String) = when (mode) {
        Note.SONG -> "Song"; Note.SCRIPT -> "Script"; Note.LIST -> "List"; else -> "Note"
    }

    /** Fine sand grain laid over the whole screen (a small tile of warm brown specks, repeated). */
    fun grain(ctx: Context): Drawable {
        val n = 160
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val r = Random(7)
        val px = IntArray(n * n) {
            val v = r.nextFloat()
            val a = when { v > 0.9f -> 26; v > 0.7f -> 14; v < 0.08f -> 10; else -> 0 }
            Color.argb(a, 92, 64, 38)
        }
        bmp.setPixels(px, 0, n, 0, 0, n, n)
        return BitmapDrawable(ctx.resources, bmp).apply { setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    }
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float) = v * resources.displayMetrics.density

fun pill(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius; if (stroke > 0) setStroke(stroke, strokeColor)
}
fun ripple(content: Drawable?, mask: Drawable = pill(Color.WHITE, 999f)) =
    RippleDrawable(ColorStateList.valueOf(0x264A3624), content, mask)

fun Context.text(s: CharSequence, sp: Float, color: Int = Look.UMBER, bold: Boolean = false) = TextView(this).apply {
    text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
}

fun Context.iconButton(icon: Int, desc: String, color: Int = Look.UMBER, size: Int = 44, onClick: (View) -> Unit) = ImageView(this).apply {
    setImageResource(icon); setColorFilter(color); contentDescription = desc
    val p = dp(size) * 23 / 100; setPadding(p, p, p, p)
    background = ripple(null)
    setOnClickListener(onClick)
    layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
}

/** A rounded chip: tinted, or filled when [on]. */
fun Context.chip(label: String, color: Int = Look.UMBER, on: Boolean = false, icon: Int? = null, onClick: (() -> Unit)? = null) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(if (icon != null) 10 else 14), 0, dp(14), 0)
    background = ripple(pill(if (on) color else Look.TINT, dpf(17f)))
    val fg = if (on) Look.SAND else color
    if (icon != null) addView(ImageView(context).apply { setImageResource(icon); setColorFilter(fg) }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) })
    addView(context.text(label, 13.5f, fg, bold = true).apply { maxLines = 1 })
    onClick?.let { c -> setOnClickListener { c() } }
    layoutParams = LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(8) }
}

/** A tool in the bar above the keyboard: icon over a word. */
fun Context.tool(icon: Int, label: String, accent: Int, on: Boolean = false, onClick: () -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
    minimumWidth = dp(54); setPadding(dp(6), 0, dp(6), 0)
    background = ripple(if (on) pill(Look.TINT, dpf(14f)) else null, pill(Color.WHITE, dpf(14f)))
    val c = if (on) accent else Look.UMBER
    addView(ImageView(context).apply { setImageResource(icon); setColorFilter(c) }, LinearLayout.LayoutParams(dp(20), dp(20)))
    addView(context.text(label, 10.5f, c, bold = true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
    setOnClickListener { onClick() }
    contentDescription = label
}

/** A big rounded button, filled with the mode colour, or tinted. */
fun Context.bigButton(label: String, icon: Int?, color: Int, filled: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
    setPadding(dp(18), 0, dp(20), 0)
    background = ripple(pill(if (filled) color else Look.TINT, dpf(26f)))
    val fg = if (filled) Look.SAND else Look.UMBER
    if (icon != null) addView(ImageView(context).apply { setImageResource(icon); setColorFilter(fg) }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
    addView(context.text(label, 15f, fg, bold = true))
    setOnClickListener { onClick() }
}

/**
 * The wordmark's first letter: the logo's cursor + L, drawn at the text's size and sitting on its baseline,
 * so "L" + "etra" read as one word.
 */
class LogoLSpan(private val d: Drawable) : ReplacementSpan() {
    // in the drawable's 26.6 x 32 box: the L is 18.8 units tall and its foot sits 27.8 units down
    private fun scale(p: Paint) = (p.textSize * 0.72f) / 18.8f
    override fun getSize(p: Paint, t: CharSequence?, s: Int, e: Int, fm: Paint.FontMetricsInt?): Int = (26.6f * scale(p) + p.textSize * 0.04f).toInt()
    override fun draw(c: Canvas, t: CharSequence?, s: Int, e: Int, x: Float, top: Int, y: Int, bottom: Int, p: Paint) {
        val k = scale(p)
        val l = x.toInt(); val tp = (y - 27.8f * k).toInt()
        d.setBounds(l, tp, (l + 26.6f * k).toInt(), (tp + 32f * k).toInt())
        d.draw(c)
    }
}
