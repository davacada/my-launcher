package com.davacada.gasto

import android.content.Context
import android.content.res.ColorStateList
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
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

/** Matt white with grain, deep green. */
object C {
    const val WHITE = 0xFFF2F1EA.toInt()
    const val INK = 0xFF17261D.toInt()
    const val SUB = 0xFF6E7A70.toInt()
    const val G1 = 0xFF2E6B4C.toInt()
    const val G2 = 0xFF1E4A34.toInt()
    const val SAGE = 0xFF9CC3A4.toInt()
    const val MINT = 0xFFD7E8D6.toInt()
    const val CARD = 0x9EFFFFFF.toInt()
    const val LINE = 0x1217261D
    const val WARN = 0xFFFFF8EE.toInt()
    const val RED = 0xFFC9533B.toInt()
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float) = v * resources.displayMetrics.density

object Fonts {
    private val cache = HashMap<Int, Typeface>()
    fun get(ctx: Context, weight: Int): Typeface = cache.getOrPut(weight) {
        val id = when {
            weight >= 800 -> R.font.inter_extrabold
            weight >= 700 -> R.font.inter_bold
            weight >= 600 -> R.font.inter_semibold
            weight >= 500 -> R.font.inter_medium
            else -> R.font.inter_regular
        }
        ctx.resources.getFont(id)
    }
}

/** Matt white with fine dark grain. */
fun Context.grain(base: Int = C.WHITE, seed: Int = 7): Drawable {
    val n = 192
    val r = Random(seed)
    val px = IntArray(n * n) {
        val v = r.nextFloat()
        when {
            v > 0.93f -> Color.argb(12 + r.nextInt(14), 23, 38, 29)
            v > 0.86f -> Color.argb(6 + r.nextInt(8), 23, 38, 29)
            v < 0.04f -> Color.argb(30, 255, 255, 255)
            else -> 0
        }
    }
    val bm = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).apply { density = resources.displayMetrics.densityDpi }
    val tile = BitmapDrawable(resources, bm).apply { setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    return LayerDrawable(arrayOf<Drawable>(GradientDrawable().apply { setColor(base) }, tile))
}

fun round(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius; if (stroke > 0) setStroke(stroke, strokeColor)
}

fun Context.card(color: Int = C.CARD, radius: Int = 22): Drawable =
    RippleDrawable(ColorStateList.valueOf(0x1417261D), round(color, dpf(radius.toFloat()), dp(1), 0x0F17261D), null)

fun ripple() = RippleDrawable(ColorStateList.valueOf(0x1417261D), null, null)

fun Context.text(s: CharSequence, sp: Float, color: Int = C.INK, weight: Int = 400) = TextView(this).apply {
    text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    typeface = Fonts.get(context, weight)
    includeFontPadding = false
}

fun Context.label(s: String, right: String? = null, onRight: (() -> Unit)? = null): View = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
    setPadding(dp(4), dp(20), dp(4), dp(10))
    addView(text(s.uppercase(), 12f, C.G1, 700).apply { letterSpacing = 0.08f }, LinearLayout.LayoutParams(0, -2, 1f))
    if (right != null) addView(text(right, 12.5f, C.SUB).apply {
        if (onRight != null) { setOnClickListener { onRight() }; background = ripple() }
    })
}

fun Context.vbox(pad: Int = 0) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
fun Context.hbox() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

fun Context.cardBox(padH: Int = 16, padV: Int = 4): LinearLayout = vbox().apply {
    background = card(); setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
}

fun Context.divider() = View(this).apply { setBackgroundColor(C.LINE) }

/** Big money with smaller cents: €412.30 */
fun Context.money(value: Double, sp: Float, color: Int = C.INK): TextView {
    val whole = "€" + value.toLong().toString()
    val cents = String.format(".%02d", Math.round(Math.abs(value) * 100) % 100)
    val b = SpannableStringBuilder(whole + cents)
    b.setSpan(AbsoluteSizeSpan((sp * 0.58f).toInt(), true), whole.length, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    b.setSpan(ForegroundColorSpan(C.SUB), whole.length, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return text(b, sp, color, 800).apply { letterSpacing = -0.03f }
}

fun eur(v: Double, sign: Boolean = false): String {
    val s = String.format("€%.2f", Math.abs(v))
    return if (sign) "−$s" else s
}

/** A rounded square with one letter, coloured from the name. */
fun Context.initial(name: String): View {
    val palette = intArrayOf(0xFFC9533B.toInt(), C.G2, 0xFFB4232A.toInt(), C.SAGE, 0xFF00A1E0.toInt(), 0xFF7A5AF8.toInt(),
        0xFFE0A100.toInt(), 0xFF4285F4.toInt(), 0xFF1DB954.toInt(), 0xFFF37021.toInt(), C.G1)
    val c = palette[Math.abs(name.lowercase().hashCode()) % palette.size]
    return text(name.trim().take(1).uppercase().ifEmpty { "•" }, 15f, if (c == C.SAGE) C.INK else C.WHITE, 800).apply {
        gravity = Gravity.CENTER
        background = round(c, dpf(12f))
    }
}

fun Context.payRow(name: String, sub: String, amount: String, last: Boolean, onClick: (() -> Unit)? = null, onLong: (() -> Unit)? = null): View {
    val col = vbox()
    val row = hbox().apply { setPadding(0, dp(11), 0, dp(11)) }
    row.addView(initial(name), LinearLayout.LayoutParams(dp(38), dp(38)))
    val mid = vbox().apply { setPadding(dp(12), 0, dp(8), 0) }
    mid.addView(text(name, 15f, C.INK, 600).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
    mid.addView(text(sub, 12f, C.SUB).apply { setPadding(0, dp(2), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
    row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(text(amount, 15f, C.INK, 700).apply { fontFeatureSettings = "tnum" })
    col.addView(row)
    if (!last) col.addView(divider(), LinearLayout.LayoutParams(-1, 1))
    if (onClick != null) col.setOnClickListener { onClick() }
    if (onLong != null) col.setOnLongClickListener { onLong(); true }
    if (onClick != null || onLong != null) col.background = ripple()
    return col
}

fun Context.toggle(on: Boolean, onChange: (Boolean) -> Unit): View {
    val v = object : View(this) {
        var state = on
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            p.color = if (state) C.G1 else 0x2617261D
            val h = height.toFloat()
            c.drawRoundRect(RectF(0f, 0f, width.toFloat(), h), h / 2, h / 2, p)
            p.color = C.WHITE
            val r = h / 2 - dpf(3f)
            c.drawCircle(if (state) width - h / 2 else h / 2, h / 2, r, p)
        }
    }
    v.setOnClickListener { v.state = !v.state; v.invalidate(); onChange(v.state) }
    v.contentDescription = "switch"
    return FrameLayout(this).apply { addView(v, FrameLayout.LayoutParams(dp(46), dp(28), Gravity.CENTER)); }
}

fun Context.settingRow(title: String, sub: String?, right: View?, last: Boolean = false, onClick: (() -> Unit)? = null): View {
    val col = vbox()
    val row = hbox().apply { setPadding(0, dp(12), 0, dp(12)) }
    val mid = vbox()
    mid.addView(text(title, 15f, C.INK, 600))
    if (sub != null) mid.addView(text(sub, 12f, C.SUB).apply { setPadding(0, dp(2), 0, 0) })
    row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
    if (right != null) row.addView(right, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
    col.addView(row)
    if (!last) col.addView(divider(), LinearLayout.LayoutParams(-1, 1))
    if (onClick != null) { col.setOnClickListener { onClick() }; col.background = ripple() }
    return col
}

fun Context.chip(label: String, on: Boolean, onClick: () -> Unit) = text(label, 12.5f, if (on) C.WHITE else C.SUB, 600).apply {
    background = round(if (on) C.G1 else 0x0F17261D, dpf(14f))
    setPadding(dp(12), dp(7), dp(12), dp(7))
    setOnClickListener { onClick() }
}

/** The month ring: green for spent. */
class Ring(ctx: Context) : View(ctx) {
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    override fun onDraw(c: Canvas) {
        val w = context.dpf(14f)
        p.strokeWidth = w
        val r = RectF(w / 2, w / 2, width - w / 2, height - w / 2)
        p.color = C.MINT; c.drawArc(r, 0f, 360f, false, p)
        p.color = if (fraction >= 1f) C.RED else C.G1
        if (fraction > 0f) c.drawArc(r, -90f, 360f * fraction, false, p)
    }
}
