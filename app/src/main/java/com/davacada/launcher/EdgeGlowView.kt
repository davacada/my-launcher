package com.davacada.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.RoundedCorner
import android.view.View
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** The glowing border: a sweep for notifications, a breath for music, a fast pulse for calls. */
class EdgeGlowView(context: Context, val kind: Kind, private val loop: Boolean = false) : View(context) {

    enum class Kind(val colors: IntArray, val durationMs: Long) {
        NOTIFICATION(intArrayOf(0xFFFFB347.toInt(), 0xFFFF8C3A.toInt()), 4200),
        MUSIC(intArrayOf(0xFFB566FF.toInt(), 0xFFCB86FF.toInt()), 4200),
        CALL(intArrayOf(0xFF00E5FF.toInt(), 0xFF3DF5FF.toInt()), Long.MAX_VALUE)
    }

    var onFinished: (() -> Unit)? = null
    private val CYCLE = 4200f
    private val start = SystemClock.uptimeMillis()
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val segment = Path()
    private var measure: PathMeasure? = null
    private var finished = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val radius = cornerRadius()
        val inset = 2f * d
        path.reset()
        path.addRoundRect(RectF(inset, inset, w - inset, h - inset), radius, radius, Path.Direction.CW)
        measure = PathMeasure(path, true)
    }

    private fun cornerRadius(): Float {
        if (Build.VERSION.SDK_INT >= 31) {
            rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.let { if (it > 0) return it.toFloat() }
        }
        return 30f * d
    }

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - start).toFloat()
        val dur = kind.durationMs.toFloat()
        val progress = if (loop || kind.durationMs == Long.MAX_VALUE) 0f else (t / dur).coerceIn(0f, 1f)

        when (kind) {
            Kind.NOTIFICATION, Kind.MUSIC -> {
                // A soft breath every ~4 s: fade in, hold, fade out, rest. Repeats until the screen is turned on.
                val p = (t % CYCLE) / CYCLE
                val e = when {
                    p < 0.35f -> smooth(p / 0.35f)
                    p < 0.55f -> 1f
                    p < 0.9f -> 1f - smooth((p - 0.55f) / 0.35f)
                    else -> 0f
                }
                drawGlow(canvas, path, kind.colors[0], 0.55f * e)
            }
            Kind.CALL -> {
                val pulse = 0.3f + 0.7f * max(0f, sin(2 * PI * t / 800f).toFloat())
                drawGlow(canvas, path, kind.colors[0], pulse)
            }
        }

        if (progress >= 1f && !finished) { finished = true; onFinished?.invoke(); return }
        postInvalidateOnAnimation()
    }

    private fun smooth(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }

    /** Several strokes from wide/faint to thin/bright fake a soft neon blur cheaply. */
    private fun drawGlow(canvas: Canvas, p: Path, color: Int, strength: Float) {
        if (strength <= 0.01f) return
        val layers = floatArrayOf(16f, 10f, 6f, 3f, 1.5f)
        val alphas = floatArrayOf(0.06f, 0.11f, 0.2f, 0.42f, 0.8f)
        for (i in layers.indices) {
            paint.color = color
            paint.alpha = (255 * alphas[i] * strength).toInt().coerceIn(0, 255)
            paint.strokeWidth = layers[i] * d
            canvas.drawPath(p, paint)
        }
    }
}
