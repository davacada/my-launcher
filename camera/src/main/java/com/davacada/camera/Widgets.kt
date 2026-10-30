package com.davacada.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Draws on top of the picture: grid, zebras, focus peaking and false colour. */
class OverlayView(context: Context) : View(context) {
    var previewRect = RectF()
        set(v) { field = v; invalidate() }
    var grid = true
        set(v) { field = v; invalidate() }
    private var frame: Bitmap? = null
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FFFFFF; strokeWidth = 1.2f * resources.displayMetrics.density }
    private val bmpPaint = Paint().apply { isFilterBitmap = false }

    fun setFrame(pixels: IntArray?, w: Int, h: Int) {
        if (pixels == null) { if (frame != null) { frame = null; invalidate() }; return }
        val b = frame?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.setPixels(pixels, 0, w, 0, 0, w, h)
        frame = b
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = previewRect
        if (r.isEmpty) return
        frame?.let { canvas.drawBitmap(it, null, r, bmpPaint) }
        if (grid) {
            for (i in 1..2) {
                val x = r.left + r.width() * i / 3f
                val y = r.top + r.height() * i / 3f
                canvas.drawLine(x, r.top, x, r.bottom, line)
                canvas.drawLine(r.left, y, r.right, y, line)
            }
        }
    }
}

/** Luma histogram. */
class HistogramView(context: Context) : View(context) {
    private var data = IntArray(64)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xC8FFFFFF.toInt() }
    private val path = Path()

    fun setData(h: IntArray) { data = h; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val max = (data.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
        val w = width.toFloat(); val h = height.toFloat()
        path.reset(); path.moveTo(0f, h)
        for (i in data.indices) {
            val x = w * i / (data.size - 1)
            val v = Math.sqrt((data[i] / max).toDouble()).toFloat()
            path.lineTo(x, h - v * h)
        }
        path.lineTo(w, h); path.close()
        canvas.drawPath(path, fill)
    }
}

/** Two-bar microphone meter. */
class AudioMeterView(context: Context) : View(context) {
    var level = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF33E07A.toInt() }
    private val hot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF453A.toInt() }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val bw = 7 * d; val gap = 5 * d
        val total = bw * 2 + gap
        val left = (width - total) / 2f
        for (i in 0..1) {
            val x = left + i * (bw + gap)
            canvas.drawRoundRect(x, 0f, x + bw, height.toFloat(), 3 * d, 3 * d, off)
            val lv = (level * (if (i == 0) 1f else 0.94f))
            val top = height * (1f - lv)
            canvas.drawRoundRect(x, top, x + bw, height.toFloat(), 3 * d, 3 * d, if (lv > 0.9f) hot else on)
        }
    }
}

/** White ring with a red dot (video) or white dot (photo); turns into a square while recording. */
class RecordButton(context: Context) : View(context) {
    var recording = false
        set(v) { field = v; invalidate() }
    var photo = false
        set(v) { field = v; invalidate() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(width, height) / 2f - 2 * d
        ring.strokeWidth = 3 * d
        canvas.drawCircle(cx, cy, r, ring)
        dot.color = if (photo) Color.WHITE else 0xFFFF3B30.toInt()
        if (recording) {
            val s = r * 0.42f
            canvas.drawRoundRect(cx - s, cy - s, cx + s, cy + s, 5 * d, 5 * d, dot)
        } else {
            canvas.drawCircle(cx, cy, r - 6 * d, dot)
        }
    }
}
