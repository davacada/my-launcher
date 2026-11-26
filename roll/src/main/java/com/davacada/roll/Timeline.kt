package com.davacada.roll

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The timeline: clips laid end to end as film strips, a fixed white playhead in the middle, a time
 * ruler above. Swipe to scrub, pinch to zoom, tap a clip to select it, drag its amber ends to trim.
 */
class Timeline(ctx: Context) : View(ctx) {
    var project: Project? = null
        set(v) { field = v; invalidate() }
    var selected = -1
        set(v) { field = v; invalidate() }
    var onScrub: ((Long) -> Unit)? = null
    var onSelect: ((Int) -> Unit)? = null
    var onTrimmed: ((Int) -> Unit)? = null
    var onAdd: (() -> Unit)? = null
    var onHold: ((Int) -> Unit)? = null
    /** Tapped the diamond between clip i and i + 1. */
    var onJoint: ((Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    var pxPerMs = dp(60f) / 1000f                      // 60dp per second
        private set
    private var timeMs = 0L                             // time under the playhead

    private val clipTop get() = dp(26f)
    private val clipH get() = dp(64f)
    private val handleW get() = dp(12f)

    // the timeline has its own colour: deep steel blue, so the tracks stand apart from the black app
    private val panel = Paint().apply { color = 0xFF0A1218.toInt() }
    private val lane = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF132029.toInt() }
    private val ruler = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6C8797.toInt(); textSize = dp(10f); typeface = Typeface.MONOSPACE }
    private val tick = Paint().apply { color = 0x4D6C8797 }
    private val frameBg = Paint().apply { color = 0xFF1A1A1A.toInt() }
    private val amber = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.AMBER }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.AMBER; style = Paint.Style.STROKE; strokeWidth = dp(2.5f) }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = dp(10f); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    private val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val plus = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }
    private val plusLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND }
    private val clipPath = Path(); private val r = RectF()
    private val diamond = Path()
    private val jointFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val jointShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(3f) }

    // thumbnails: one frame per second of source, per video
    private val frames = HashMap<String, Bitmap?>()
    private val loading = HashSet<String>()
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun setTime(t: Long) { timeMs = t; invalidate() }
    fun time() = timeMs

    private val edgeX get() = width / 2f - timeMs * pxPerMs  // x where the edit starts

    override fun onDraw(c: Canvas) {
        val p = project ?: return
        val x0 = edgeX
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), panel)
        c.drawRoundRect(dp(6f), clipTop - dp(4f), width - dp(6f), clipTop + clipH + dp(4f), dp(10f), dp(10f), lane)
        // ruler
        val step = when { pxPerMs * 1000 > dp(90f) -> 1000L; pxPerMs * 2000 > dp(70f) -> 2000L; pxPerMs * 5000 > dp(60f) -> 5000L; else -> 10000L }
        var t = 0L
        while (t <= p.lengthMs + step) {
            val x = x0 + t * pxPerMs
            if (x > -dp(40f) && x < width + dp(40f)) { c.drawText(fmt(t), x - dp(12f), dp(12f), ruler); c.drawRect(x, dp(16f), x + 1, dp(20f), tick) }
            t += step
        }
        // clips
        var at = 0L
        p.clips.forEachIndexed { i, clip ->
            val a = x0 + at * pxPerMs; val b = a + clip.lengthMs * pxPerMs
            at += clip.lengthMs
            if (b < 0 || a > width) return@forEachIndexed
            r.set(a + dp(1.5f), clipTop, b - dp(1.5f), clipTop + clipH)
            c.save()
            clipPath.reset(); clipPath.addRoundRect(r, dp(8f), dp(8f), Path.Direction.CW); c.clipPath(clipPath)
            c.drawRect(r, frameBg)
            // film-strip frames
            val fw = clipH * (if (clip.w > 0 && clip.h > 0) min(1.6f, clip.w.toFloat() / clip.h) else 1f)
            var fx = r.left
            while (fx < r.right) {
                if (fx + fw > 0 && fx < width) {
                    val srcMs = clip.startMs + ((fx - r.left + fw / 2) / pxPerMs * clip.speed).toLong()
                    val key = clip.uri + "@" + (srcMs / 1000)
                    val bm = frames[key]
                    if (bm != null) c.drawBitmap(bm, null, RectF(fx, r.top, fx + fw, r.bottom), null) else load(clip, srcMs / 1000, key)
                }
                fx += fw
            }
            c.restore()
            val tags = listOfNotNull(
                when (clip.still) { "photo" -> "photo"; "freeze" -> "freeze"; else -> null },
                if (clip.revOf != null) "reversed" else null,
                if (clip.speed != 1f) "${trim(clip.speed)}×" else null,
                if (clip.volume == 0f && clip.still.isEmpty() && clip.revOf == null) "muted" else null)
            if (tags.isNotEmpty()) {
                val label = tags.joinToString(" · ")
                val tw = badge.measureText(label)
                c.drawRoundRect(r.left + dp(6f), r.bottom - dp(19f), r.left + dp(14f) + tw, r.bottom - dp(5f), dp(4f), dp(4f), badgeBg)
                c.drawText(label, r.left + dp(10f), r.bottom - dp(8.5f), badge)
            }
            if (i == selected) {
                c.drawRoundRect(r, dp(8f), dp(8f), outline)
                c.drawRoundRect(r.left, r.top, r.left + handleW, r.bottom, dp(6f), dp(6f), amber)
                c.drawRoundRect(r.right - handleW, r.top, r.right, r.bottom, dp(6f), dp(6f), amber)
                head.color = 0xFF050505.toInt()
                c.drawRect(r.left + handleW / 2 - dp(1f), r.centerY() - dp(9f), r.left + handleW / 2 + dp(1f), r.centerY() + dp(9f), head)
                c.drawRect(r.right - handleW / 2 - dp(1f), r.centerY() - dp(9f), r.right - handleW / 2 + dp(1f), r.centerY() + dp(9f), head)
                head.color = 0xFFFFFFFF.toInt()
            }
        }
        // joints: a diamond between clips (amber when there's a transition)
        var jt = 0L
        p.clips.forEachIndexed { i, clip ->
            jt += clip.lengthMs
            if (i == p.clips.size - 1) return@forEachIndexed
            val x = x0 + jt * pxPerMs
            if (x < -dp(20f) || x > width + dp(20f)) return@forEachIndexed
            val y = clipTop + clipH / 2; val rr = dp(if (clip.trans != "none") 8f else 6f)
            diamond.reset(); diamond.moveTo(x, y - rr); diamond.lineTo(x + rr, y); diamond.lineTo(x, y + rr); diamond.lineTo(x - rr, y); diamond.close()
            jointFill.color = if (clip.trans != "none") C.AMBER else 0xFFEFE8DF.toInt()
            c.drawPath(diamond, jointShadow); c.drawPath(diamond, jointFill)
        }
        // add button after the last clip
        val ax = x0 + p.lengthMs * pxPerMs + dp(28f); val ay = clipTop + clipH / 2
        c.drawCircle(ax, ay, dp(18f), plus)
        c.drawLine(ax - dp(7f), ay, ax + dp(7f), ay, plusLine); c.drawLine(ax, ay - dp(7f), ax, ay + dp(7f), plusLine)
        // playhead
        val cx = width / 2f
        c.drawRect(cx - dp(1f), dp(20f), cx + dp(1f), clipTop + clipH + dp(10f), head)
        c.drawRoundRect(cx - dp(7f), dp(16f), cx + dp(7f), dp(24f), dp(3f), dp(3f), head)
    }

    private fun trim(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()

    private fun load(clip: Clip, sec: Long, key: String) {
        if (key in loading) return
        loading.add(key)
        val hpx = clipH.toInt()
        pool.execute {
            val bm = try {
                val mr = MediaMetadataRetriever()
                mr.setDataSource(context, Uri.parse(clip.uri))
                val ww = if (clip.w > 0 && clip.h > 0) (hpx * clip.w / clip.h).coerceAtLeast(hpx / 2) else hpx
                val b = mr.getScaledFrameAtTime(sec * 1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, ww, hpx)
                mr.release(); b
            } catch (e: Exception) { null }
            main.post { frames[key] = bm; invalidate() }
        }
    }

    fun release() { pool.shutdownNow() }

    // ---------------------------------------------------------------- touch

    private val scroller = OverScroller(ctx)
    private var trimming = 0          // -1 left end, 1 right end
    private var trimClip = -1
    private var trimOrig = 0L

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (trimming != 0) return true
            moveTo(timeMs + (dx / pxPerMs).toLong()); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (trimming != 0) return true
            val p = project ?: return true
            scroller.fling((timeMs * pxPerMs).toInt(), 0, -vx.toInt(), 0, 0, (p.lengthMs * pxPerMs).toInt(), 0, 0)
            postInvalidateOnAnimation(); return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val p = project ?: return true
            val ax = edgeX + p.lengthMs * pxPerMs + dp(28f)
            if (abs(e.x - ax) < dp(26f) && abs(e.y - (clipTop + clipH / 2)) < dp(30f)) { onAdd?.invoke(); return true }
            // a joint between clips
            if (abs(e.y - (clipTop + clipH / 2)) < dp(22f)) {
                var jt = 0L
                for (i in 0 until p.clips.size - 1) {
                    jt += p.clips[i].lengthMs
                    if (abs(e.x - (edgeX + jt * pxPerMs)) < dp(14f)) { onJoint?.invoke(i); return true }
                }
            }
            val i = clipAt(e.x, e.y)
            onSelect?.invoke(if (i == selected) -1 else i); return true
        }
        override fun onLongPress(e: MotionEvent) {
            val i = clipAt(e.x, e.y); if (i >= 0) { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onHold?.invoke(i) }
        }
    })

    private val pinch = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            pxPerMs = (pxPerMs * det.scaleFactor).coerceIn(dp(8f) / 1000f, dp(400f) / 1000f); invalidate(); return true
        }
    })

    private fun clipAt(x: Float, y: Float): Int {
        val p = project ?: return -1
        if (y < clipTop - dp(8f) || y > clipTop + clipH + dp(8f)) return -1
        val t = ((x - edgeX) / pxPerMs).toLong()
        if (t < 0 || t > p.lengthMs) return -1
        return p.at(t).first
    }

    private fun moveTo(t: Long) {
        val p = project ?: return
        timeMs = t.coerceIn(0, max(0, p.lengthMs - 1)); invalidate(); onScrub?.invoke(timeMs)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) { moveTo((scroller.currX / pxPerMs).toLong()); postInvalidateOnAnimation() }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = project ?: return false
        pinch.onTouchEvent(e)
        if (pinch.isInProgress) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trimming = 0
                if (selected in p.clips.indices && e.y in clipTop..(clipTop + clipH)) {
                    val a = edgeX + p.startOf(selected) * pxPerMs; val b = a + p.clips[selected].lengthMs * pxPerMs
                    if (abs(e.x - a) < handleW * 1.6f) { trimming = -1; trimClip = selected; trimOrig = p.clips[selected].startMs }
                    else if (abs(e.x - b) < handleW * 1.6f) { trimming = 1; trimClip = selected; trimOrig = p.clips[selected].endMs }
                    downX = e.x
                }
            }
            MotionEvent.ACTION_MOVE -> if (trimming != 0) {
                val c = p.clips[trimClip]
                val delta = ((e.x - downX) / pxPerMs * c.speed).toLong()
                if (trimming < 0) c.startMs = (trimOrig + delta).coerceIn(0, c.endMs - 300)
                else c.endMs = (trimOrig + delta).coerceIn(c.startMs + 300, c.sourceMs)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (trimming != 0) { trimming = 0; onTrimmed?.invoke(trimClip); return true }
        }
        if (trimming != 0) return true
        return gestures.onTouchEvent(e)
    }
    private var downX = 0f
}
