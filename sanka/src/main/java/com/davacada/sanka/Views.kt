package com.davacada.sanka

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

const val ACCENT = 0xFFC9A2FF.toInt()
const val BG = 0xFF0B0F16.toInt()

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float) = v * resources.displayMetrics.density
fun pill(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius; if (stroke > 0) setStroke(stroke, strokeColor)
}
fun oval(color: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL; setColor(color); if (stroke > 0) setStroke(stroke, strokeColor)
}

fun Context.label(text: String, sp: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
    this.text = text; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

fun Context.iconButton(icon: Int, desc: String, size: Int = 44, onClick: (View) -> Unit) = ImageView(this).apply {
    setImageResource(icon); contentDescription = desc
    val p = dp(size) / 4; setPadding(p, p, p, p)
    background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, oval(Color.WHITE))
    setOnClickListener(onClick)
}

/** A rounded glass button with an icon and a word, like the launcher's menus. */
fun Context.glassButton(icon: Int?, text: String, color: Int = Color.WHITE, filled: Boolean = false, onClick: () -> Unit) =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        setPadding(dp(18), 0, dp(20), 0)
        background = pill(if (filled) Color.WHITE else 0x1FFFFFFF, dpf(26f))
        val fg = if (filled) BG else color
        if (icon != null) addView(ImageView(context).apply { setImageResource(icon); setColorFilter(fg) }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
        addView(context.label(text, 15f, fg, bold = true))
        setOnClickListener { onClick() }
    }

/** PIN dots and a number pad. */
class PinPad(ctx: Context, private val showFingerprint: Boolean, private val onFingerprint: () -> Unit, private val onEntered: (String) -> Unit) :
    LinearLayout(ctx) {
    private var digits = ""
    private val dots = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
    private var maxLen = 8
    var minLen = 4
    var autoSubmitAt = 0 // when > 0, submits as soon as this many digits are typed
    private val ok: TextView

    init {
        orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(dots, LayoutParams(-1, ctx.dp(28)).apply { bottomMargin = ctx.dp(36) })
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "fp", "0", "del")
        ok = ctx.label("OK", 17f, BG, bold = true).apply {
            gravity = Gravity.CENTER; background = pill(Color.WHITE, ctx.dpf(28f)); visibility = INVISIBLE
            setOnClickListener { if (digits.length >= minLen) submit() }
        }
        keys.chunked(3).forEach { row ->
            val r = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
            row.forEach { k ->
                val v: View = when (k) {
                    "fp" -> if (showFingerprint) ctx.iconButton(R.drawable.ic_fingerprint, "Use fingerprint", 72) { onFingerprint() } else View(ctx)
                    "del" -> ctx.iconButton(R.drawable.ic_backspace, "Delete digit", 72) { if (digits.isNotEmpty()) { digits = digits.dropLast(1); render() } }
                    else -> ctx.label(k, 28f).apply {
                        gravity = Gravity.CENTER
                        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x40FFFFFF), oval(0x1AFFFFFF, ctx.dp(1), 0x2EFFFFFF), null)
                        setOnClickListener { press(k) }
                    }
                }
                r.addView(v, LayoutParams(ctx.dp(72), ctx.dp(72)).apply { setMargins(ctx.dp(14), ctx.dp(7), ctx.dp(14), ctx.dp(7)) })
            }
            addView(r)
        }
        addView(ok, LayoutParams(ctx.dp(140), ctx.dp(52)).apply { topMargin = ctx.dp(18) })
        render()
    }

    private fun press(k: String) {
        if (digits.length >= maxLen) return
        digits += k; render()
        if (autoSubmitAt > 0 && digits.length == autoSubmitAt) postDelayed({ submit() }, 120)
    }

    private fun submit() { val d = digits; onEntered(d) }

    fun clear() { digits = ""; render() }

    fun shake() {
        ObjectAnimator.ofFloat(dots, "translationX", 0f, 24f, -24f, 18f, -18f, 8f, 0f).setDuration(380).start()
        postDelayed({ clear() }, 380)
    }

    private fun render() {
        dots.removeAllViews()
        val n = maxOf(digits.length, if (autoSubmitAt > 0) autoSubmitAt else minLen)
        for (i in 0 until n) {
            dots.addView(View(context).apply {
                background = if (i < digits.length) oval(Color.WHITE) else oval(Color.TRANSPARENT, context.dp(2), 0x80FFFFFF.toInt())
            }, LayoutParams(context.dp(14), context.dp(14)).apply { setMargins(context.dp(8), 0, context.dp(8), 0) })
        }
        ok.visibility = if (autoSubmitAt == 0 && digits.length >= minLen) VISIBLE else INVISIBLE
    }
}

/** The lock screen's background: slow, soft glows of violet and blue drifting over the dark. */
class AuroraView(ctx: Context) : View(ctx) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val start = android.os.SystemClock.uptimeMillis()
    // per glow: size (share of the screen's longer side), drift speed, phase
    private val blobs = listOf(
        floatArrayOf(0f, 0.75f, 0.11f, 0f),
        floatArrayOf(0f, 0.65f, 0.08f, 2.1f),
        floatArrayOf(0f, 0.55f, 0.13f, 4.2f),
        floatArrayOf(0f, 0.45f, 0.09f, 1.3f),
    )
    private val colors = intArrayOf(0x8A5B2FD6.toInt(), 0x702D5BD8, 0x5CC9A2FF, 0x4AD14B9E)

    override fun onDraw(c: android.graphics.Canvas) {
        c.drawColor(BG)
        val t = (android.os.SystemClock.uptimeMillis() - start) / 1000f
        val w = width.toFloat(); val h = height.toFloat(); val big = maxOf(w, h)
        blobs.forEachIndexed { i, b ->
            val sp = b[2]; val ph = b[3]
            val cx = w * (0.5f + 0.38f * kotlin.math.sin(t * sp + ph))
            val cy = h * (0.42f + 0.32f * kotlin.math.cos(t * sp * 0.8f + ph * 1.7f))
            val r = big * b[1]
            paint.shader = android.graphics.RadialGradient(cx, cy, r, colors[i], 0x000B0F16, android.graphics.Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, paint)
        }
        // darker at the bottom so the keypad stays easy to read
        paint.shader = android.graphics.LinearGradient(0f, h * 0.45f, 0f, h, 0x000B0F16, 0xCC0B0F16.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        postInvalidateDelayed(33)
    }
}

/** A photo you can pinch, drag and double-tap to zoom. Single taps and swipes (when not zoomed) are passed on. */
class ZoomImageView(ctx: Context, private val onTap: () -> Unit, private val onSwipe: (Int) -> Unit) : ImageView(ctx) {
    private val m = Matrix()
    private var base = Matrix()
    private var zoom = 1f
    private var bmp: Bitmap? = null

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val f = (zoom * d.scaleFactor).coerceIn(1f, 6f) / zoom
            zoom *= f; m.postScale(f, f, d.focusX, d.focusY); fix(); return true
        }
    })
    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.05f) reset() else { zoom = 2.5f; m.postScale(2.5f, 2.5f, e.x, e.y); fix() }
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (zoom > 1.01f) { m.postTranslate(-dx, -dy); fix() }
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (zoom <= 1.01f && kotlin.math.abs(vx) > kotlin.math.abs(vy) * 1.5f && kotlin.math.abs(vx) > 600) onSwipe(if (vx < 0) 1 else -1)
            return true
        }
    })

    init { scaleType = ScaleType.MATRIX }

    fun show(b: Bitmap?) { bmp = b; setImageBitmap(b); reset() }

    private fun reset() {
        val b = bmp ?: return
        if (width == 0) { post { reset() }; return }
        base.setRectToRect(RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()), RectF(0f, 0f, width.toFloat(), height.toFloat()), Matrix.ScaleToFit.CENTER)
        m.set(base); zoom = 1f; imageMatrix = m
    }

    private fun fix() {
        val b = bmp ?: return
        val r = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()); m.mapRect(r)
        var dx = 0f; var dy = 0f
        if (r.width() <= width) dx = width / 2f - r.centerX() else { if (r.left > 0) dx = -r.left; if (r.right < width) dx = width - r.right }
        if (r.height() <= height) dy = height / 2f - r.centerY() else { if (r.top > 0) dy = -r.top; if (r.bottom < height) dy = height - r.bottom }
        m.postTranslate(dx, dy); imageMatrix = m
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); reset() }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress) gestures.onTouchEvent(e)
        return true
    }
}

/** A square grid cell: thumbnail, video length, and a tick when selected. */
class Cell(ctx: Context, private val square: Boolean = true, radius: Float = 10f) : FrameLayout(ctx) {
    val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val badge = ctx.label("", 11f, bold = true).apply {
        setPadding(ctx.dp(6), ctx.dp(2), ctx.dp(6), ctx.dp(2)); background = pill(0x99000000.toInt(), ctx.dpf(8f))
    }
    private val tick = ImageView(ctx).apply { setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3)) }
    var boundKey: String = ""
    private val cornerDp = radius

    init {
        clipToOutline = true
        background = pill(0x14FFFFFF, ctx.dpf(radius))
        addView(image, LayoutParams(-1, -1))
        addView(badge, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(ctx.dp(5), 0, 0, ctx.dp(5)) })
        addView(tick, LayoutParams(ctx.dp(22), ctx.dp(22), Gravity.TOP or Gravity.END).apply { setMargins(0, ctx.dp(6), ctx.dp(6), 0) })
    }

    fun set(key: String, durationMs: Long, video: Boolean, selecting: Boolean, selected: Boolean) {
        if (boundKey != key) image.setImageBitmap(null)
        boundKey = key
        badge.visibility = if (video) VISIBLE else GONE
        badge.text = if (durationMs > 0) "%d:%02d".format(durationMs / 60000, (durationMs / 1000) % 60) else "▶"
        tick.visibility = if (selecting) VISIBLE else GONE
        tick.background = if (selected) oval(ACCENT) else oval(0x33000000, context.dp(2), Color.WHITE)
        tick.setImageResource(if (selected) R.drawable.ic_check else 0)
        tick.setColorFilter(BG)
        foreground = if (selected) pill(0x33C9A2FF, context.dpf(cornerDp), context.dp(3), ACCENT) else null
    }

    override fun onMeasure(w: Int, h: Int) = if (square) super.onMeasure(w, w) else super.onMeasure(w, h)
}

/**
 * Lays its children out like a photo collage: each keeps its own shape (tall ones narrow, wide ones wide),
 * every row is the same height, and each full row fills the width exactly. [aspects] holds width/height per child.
 */
class JustifiedLayout(ctx: Context, private val targetDp: Int = 150) : android.view.ViewGroup(ctx) {
    var aspects: List<Float> = emptyList()
    private val gap = ctx.dp(3)
    private val frames = ArrayList<android.graphics.Rect>()

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val width = MeasureSpec.getSize(wSpec) - paddingLeft - paddingRight
        val target = context.dp(targetDp).toFloat()
        frames.clear()
        var y = paddingTop
        var start = 0
        val n = minOf(childCount, aspects.size)
        while (start < n) {
            var end = start; var sum = 0f; var h = target
            while (end < n) {
                sum += aspects[end]; end++
                h = (width - gap * (end - start - 1)) / sum
                if (h <= target) break
            }
            val last = end >= n && h > target
            if (last) h = target
            var x = paddingLeft.toFloat()
            for (i in start until end) {
                val w = if (!last && i == end - 1) (paddingLeft + width - x) else aspects[i] * h
                frames.add(android.graphics.Rect(x.toInt(), y, (x + w).toInt(), (y + h).toInt()))
                x += w + gap
            }
            y += h.toInt() + gap
            start = end
        }
        for (i in 0 until childCount) {
            val f = frames.getOrNull(i)
            val c = getChildAt(i)
            if (f == null) { c.measure(0, 0); continue }
            c.measure(MeasureSpec.makeMeasureSpec(f.width(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(f.height(), MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(MeasureSpec.getSize(wSpec), (y - gap + paddingBottom).coerceAtLeast(0))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) frames.getOrNull(i)?.let { getChildAt(i).layout(it.left, it.top, it.right, it.bottom) }
    }
}
