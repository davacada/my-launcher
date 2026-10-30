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
class EdgeGlowView(context: Context, val kind: Kind) : View(context) {

    enum class Kind(val colors: IntArray, val durationMs: Long) {
        NOTIFICATION(intArrayOf(0xFFFFB020.toInt(), 0xFFFF7A1A.toInt()), 2600),
        MUSIC(intArrayOf(0xFFB14DFF.toInt(), 0xFFD06BFF.toInt()), 3200),
        CALL(intArrayOf(0xFF00E5FF.toInt(), 0xFF3DF5FF.toInt()), Long.MAX_VALUE)
    }

    var onFinished: (() -> Unit)? = null
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
        val progress = if (kind.durationMs == Long.MAX_VALUE) 0f else (t / dur).coerceIn(0f, 1f)
        // fade in quickly, out at the end
        val envelope = if (kind == Kind.CALL) 1f else (minOf(1f, t / 250f) * minOf(1f, (1f - progress) / 0.25f)).coerceIn(0f, 1f)

        when (kind) {
            Kind.NOTIFICATION -> {
                // A bright comet running round the edge, with a soft trail of the full border underneath.
                drawGlow(canvas, path, kind.colors[1], 0.25f * envelope)
                val m = measure
                if (m != null) {
                    val len = m.length
                    val head = (t / 1300f % 1f) * len
                    val tail = len * 0.35f
                    segment.reset()
                    if (head - tail >= 0) m.getSegment(head - tail, head, segment, true)
                    else { m.getSegment(len + head - tail, len, segment, true); m.getSegment(0f, head, segment, true) }
                    drawGlow(canvas, segment, kind.colors[0], envelope)
                }
            }
            Kind.MUSIC -> {
                val breath = 0.35f + 0.65f * (0.5f - 0.5f * kotlin.math.cos(2 * PI * t / 1600f).toFloat())
                drawGlow(canvas, path, kind.colors[0], breath * envelope)
            }
            Kind.CALL -> {
                val pulse = 0.3f + 0.7f * max(0f, sin(2 * PI * t / 800f).toFloat())
                drawGlow(canvas, path, kind.colors[0], pulse)
            }
        }

        if (progress >= 1f && !finished) { finished = true; onFinished?.invoke(); return }
        postInvalidateOnAnimation()
    }

    /** Several strokes from wide/faint to thin/bright fake a soft neon blur cheaply. */
    private fun drawGlow(canvas: Canvas, p: Path, color: Int, strength: Float) {
        if (strength <= 0.01f) return
        val layers = floatArrayOf(22f, 14f, 8f, 4f, 2f)
        val alphas = floatArrayOf(0.08f, 0.14f, 0.25f, 0.55f, 1f)
        for (i in layers.indices) {
            paint.color = color
            paint.alpha = (255 * alphas[i] * strength).toInt().coerceIn(0, 255)
            paint.strokeWidth = layers[i] * d
            canvas.drawPath(p, paint)
        }
    }
}
