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
 * The timeline, laid out like DaVinci Resolve: a timecode ruler, then V1 (the clips), T1 (titles and
 * captions), A1 (the clips' own sound, with its waveform) and A2, A3 … (music and voiceovers).
 * Each track has a header on the left: its name and its controls. The red playhead stays in the middle.
 *
 * Swipe sideways to scrub, up and down to see more tracks, pinch to zoom. Tap to select; drag a selected
 * bar to move it (along, or to another audio track), drag its ends to trim; things snap to the playhead,
 * clip edges and each other.
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
    /** Tapped a title or caption bar (its id). */
    var onText: ((String) -> Unit)? = null
    /** A bar was moved or trimmed (then the edit is saved). */
    var onLaneMoved: (() -> Unit)? = null
    var onLaneDown: (() -> Unit)? = null
    /** The selected title or sound (its id). */
    var selectedText: String? = null
        set(v) { field = v; invalidate() }
    /** Tapped a music / voice bar (its id). */
    var onAudio: ((String) -> Unit)? = null
    /** A track control: vH vL vM vS (V1 / A1) · tH tL t+ · a0M a0S a0L (A2 …) · v+ a+ */
    var onTrack: ((String) -> Unit)? = null
    /** Held an audio track's header (A2 = 0, …). */
    var onTrackHold: ((Int) -> Unit)? = null
    /** Tried to change something on a locked track. */
    var onLocked: ((String) -> Unit)? = null
    /** Tapped a selected V2 / V3 clip again (its id). */
    var onOverlay: ((String) -> Unit)? = null
    /** Things snap to the playhead, edges and each other. */
    var snapOn = true

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    var pxPerMs = dp(60f) / 1000f
        private set
    private var timeMs = 0L
    private var scrollY = 0f

    // ---------------------------------------------------------------- layout
    private val rulerH get() = dp(24f)
    private val headW get() = dp(96f)
    private val vH get() = dp(54f)
    private val tH get() = dp(26f)
    private val aH get() = dp(30f)
    private val gap get() = dp(3f)
    private fun vCount() = project?.vTracks?.size ?: 0
    /** V2 is just above V1, V3 above V2 … */
    private fun ovTop(k: Int) = rulerH + dp(4f) - scrollY + (vCount() - 1 - k) * (vH + gap)
    private val vTop get() = rulerH + dp(4f) - scrollY + vCount() * (vH + gap)
    private val tTop get() = vTop + vH + gap
    private val a1Top get() = tTop + tH + gap
    private fun aTop(i: Int) = a1Top + (i + 1) * (aH + gap)
    private fun addTop(p: Project) = aTop(p.aTracks.size) + dp(2f)
    private fun contentH(p: Project) = addTop(p) + scrollY + dp(28f)
    private val handleW get() = dp(12f)
    private val midX get() = headW + (width - headW) / 2f
    private val edgeX get() = midX - timeMs * pxPerMs

    // ---------------------------------------------------------------- colours (Resolve-ish greys, blue video, green audio)
    private val panel = Paint().apply { color = 0xFF1A1B1E.toInt() }
    private val trackBg = Paint().apply { color = 0xFF222327.toInt() }
    private val trackBg2 = Paint().apply { color = 0xFF1F2023.toInt() }
    private val rulerBg = Paint().apply { color = 0xFF151619.toInt() }
    private val ruler = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9A9CA3.toInt(); textSize = dp(9.5f); typeface = Typeface.MONOSPACE }
    private val tick = Paint().apply { color = 0x66A0A3AA }
    private val videoBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3D5A86.toInt() }
    private val videoTop = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5677AB.toInt() }
    private val audioBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E6A47.toInt() }
    private val voiceBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A7466.toInt() }
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8EE0A8.toInt(); strokeWidth = dp(1f) }
    private val titleBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7A56B5.toInt() }
    private val captionBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3F8F7A.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2F2F2.toInt(); textSize = dp(10f); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }
    private val sel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF29B38.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2.2f) }
    private val amber = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF29B38.toInt() }
    private val playhead = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE5483B.toInt() }
    private val plus = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
    private val plusLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND }
    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x669A9CA3; textSize = dp(10f) }
    private val headBg = Paint().apply { color = 0xFF2A2B2F.toInt() }
    private val headLine = Paint().apply { color = 0xFF111214.toInt() }
    private val headName = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD8D9DD.toInt(); textSize = dp(11f); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    private val btnBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val btnText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = dp(9.5f); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    private val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val jointFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val jointShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(3f) }
    private val clipPath = Path(); private val r = RectF(); private val diamond = Path()
    private val icons = HashMap<Int, android.graphics.drawable.Drawable>()

    // thumbnails (one per second of source) and waveforms (peak per 25 ms), loaded in the background
    private val frames = HashMap<String, Bitmap?>()
    private val loading = HashSet<String>()
    private val peaks = HashMap<String, FloatArray?>()
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun setTime(t: Long) { timeMs = t; invalidate() }
    fun time() = timeMs
    fun release() { pool.shutdownNow() }

    /** Frames per second used for the timecode. */
    var fps = 25

    private fun timecode(ms: Long): String {
        val f = ((ms % 1000) * fps / 1000).toInt(); val s = ms / 1000
        return "%02d:%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60, f)
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(c: Canvas) {
        val p = project ?: return
        scrollY = scrollY.coerceIn(0f, max(0f, contentH(p) - height))
        val x0 = edgeX
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), panel)
        c.save(); c.clipRect(0f, rulerH, width.toFloat(), height.toFloat())
        // track backgrounds
        c.drawRect(0f, vTop, width.toFloat(), vTop + vH, trackBg)
        for (k in 0 until vCount()) c.drawRect(0f, ovTop(k), width.toFloat(), ovTop(k) + vH, if (k % 2 == 0) trackBg2 else trackBg)
        c.drawRect(0f, tTop, width.toFloat(), tTop + tH, trackBg2)
        c.drawRect(0f, a1Top, width.toFloat(), a1Top + aH, trackBg)
        for (i in p.aTracks.indices) c.drawRect(0f, aTop(i), width.toFloat(), aTop(i) + aH, if (i % 2 == 0) trackBg2 else trackBg)

        // V1 and A1: the clips, and their sound underneath
        var at = 0L
        p.clips.forEachIndexed { i, clip ->
            val a = x0 + at * pxPerMs; val b = a + clip.lengthMs * pxPerMs
            at += clip.lengthMs
            if (b < headW || a > width) return@forEachIndexed
            drawClip(c, clip, i, a, b)
            drawClipSound(c, clip, a, b)
        }
        // V2, V3 …: clips over V1, wherever they were put
        for (o in p.overlays) {
            val a = x0 + o.at * pxPerMs; val b = x0 + o.endAt * pxPerMs
            if (b < headW || a > width) continue
            drawClipAt(c, o, a, b, ovTop(o.track), o.id == selectedText)
        }
        for (k in 0 until vCount()) if (p.vTracks[k].hide) c.drawRect(headW, ovTop(k), width.toFloat(), ovTop(k) + vH, dim)
        // joints between clips (orange when there's a transition)
        var jt = 0L
        p.clips.forEachIndexed { i, clip ->
            jt += clip.lengthMs
            if (i == p.clips.size - 1) return@forEachIndexed
            val x = x0 + jt * pxPerMs
            if (x < headW - dp(10f) || x > width + dp(20f)) return@forEachIndexed
            val y = vTop + vH / 2; val rr = dp(if (clip.trans != "none") 7f else 5f)
            diamond.reset(); diamond.moveTo(x, y - rr); diamond.lineTo(x + rr, y); diamond.lineTo(x, y + rr); diamond.lineTo(x - rr, y); diamond.close()
            jointFill.color = if (clip.trans != "none") 0xFFF29B38.toInt() else 0xFFE8E8EA.toInt()
            c.drawPath(diamond, jointShadow); c.drawPath(diamond, jointFill)
        }
        // add after the last clip
        val ax = x0 + p.lengthMs * pxPerMs + dp(24f); val ay = vTop + vH / 2
        c.drawCircle(ax, ay, dp(15f), plus)
        c.drawLine(ax - dp(6f), ay, ax + dp(6f), ay, plusLine); c.drawLine(ax, ay - dp(6f), ax, ay + dp(6f), plusLine)

        // T1: titles and captions
        if (p.texts.isEmpty()) c.drawText("titles and captions", headW + dp(10f), tTop + tH * 0.66f, hint)
        for (it in p.texts) bar(c, x0 + it.startMs * pxPerMs, x0 + it.endMs * pxPerMs, tTop, tH, if (it.caption) captionBg else titleBg, it.text.replace('\n', ' '), it.id == selectedText, null)
        // A2 …: music and voiceovers
        for (it in p.audio) {
            val top = aTop(it.track)
            bar(c, x0 + it.startMs * pxPerMs, x0 + it.endMs * pxPerMs, top, aH, if (it.kind == "voice") voiceBg else audioBg,
                (if (it.kind == "voice") "● " else "♪ ") + it.name, it.id == selectedText) { rr -> drawWave(c, it.uri, it.sourceMs, rr, it.inMs, 1f, it.volume) }
        }
        if (p.audio.isEmpty()) c.drawText("music and voiceovers", headW + dp(10f), aTop(0) + aH * 0.62f, hint)

        // dim what you can't see or hear
        if (p.vHide) c.drawRect(headW, vTop, width.toFloat(), vTop + vH, dim)
        if (p.tHide) c.drawRect(headW, tTop, width.toFloat(), tTop + tH, dim)
        if (!p.clipSound()) c.drawRect(headW, a1Top, width.toFloat(), a1Top + aH, dim)
        for (i in p.aTracks.indices) if (!p.trackSound(i)) c.drawRect(headW, aTop(i), width.toFloat(), aTop(i) + aH, dim)
        c.restore()

        // ruler (timecode)
        c.drawRect(0f, 0f, width.toFloat(), rulerH, rulerBg)
        val steps = longArrayOf(1000, 2000, 5000, 10000, 30000, 60000)
        val step = steps.firstOrNull { it * pxPerMs > dp(86f) } ?: 60000L
        var t = 0L
        while (t <= p.lengthMs + step) {
            val x = x0 + t * pxPerMs
            if (x > headW - dp(10f) && x < width + dp(60f)) { c.drawText(timecode(t), x + dp(3f), dp(11f), ruler); c.drawRect(x, dp(2f), x + 1, rulerH, tick) }
            // small ticks
            for (k in 1 until 5) { val xx = x + k * step * pxPerMs / 5; if (xx > headW && xx < width) c.drawRect(xx, rulerH - dp(5f), xx + 1, rulerH, tick) }
            t += step
        }
        // playhead: red, full height
        val cx = midX
        c.drawRect(cx - dp(0.8f), dp(10f), cx + dp(0.8f), height.toFloat(), playhead)
        val ph = Path().apply { moveTo(cx - dp(6f), dp(12f)); lineTo(cx + dp(6f), dp(12f)); lineTo(cx + dp(6f), dp(18f)); lineTo(cx, dp(23f)); lineTo(cx - dp(6f), dp(18f)); close() }
        c.drawPath(ph, playhead)

        drawHeaders(c, p)
    }

    private fun drawClip(c: Canvas, clip: Clip, i: Int, a: Float, b: Float) = drawClipAt(c, clip, a, b, vTop, i == selected)

    private fun drawClipAt(c: Canvas, clip: Clip, a: Float, b: Float, top: Float, isSel: Boolean) {
        r.set(a + dp(1f), top + dp(1f), b - dp(1f), top + vH - dp(1f))
        c.save()
        clipPath.reset(); clipPath.addRoundRect(r, dp(4f), dp(4f), Path.Direction.CW); c.clipPath(clipPath)
        c.drawRect(r, videoBg)
        // a name strip on top, film frames below (like Resolve's thumbnails)
        val strip = dp(13f)
        c.drawRect(r.left, r.top, r.right, r.top + strip, videoTop)
        val name = when { clip.still == "photo" -> "Photo"; clip.still == "freeze" -> "Freeze frame"; clip.revOf != null -> "Reversed"; else -> Uri.parse(clip.uri).lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "Clip" }
        c.drawText(name.take(40), r.left + dp(5f), r.top + dp(10f), label)
        val fh = r.height() - strip
        val fw = fh * (if (clip.w > 0 && clip.h > 0) min(1.6f, clip.w.toFloat() / clip.h) else 1f)
        var fx = r.left
        while (fx < r.right) {
            if (fx + fw > headW && fx < width) {
                val srcMs = clip.startMs + ((fx - r.left + fw / 2) / pxPerMs * clip.speed).toLong()
                val key = clip.uri + "@" + (srcMs / 1000)
                val bm = frames[key]
                if (bm != null) c.drawBitmap(bm, null, RectF(fx, r.top + strip, fx + fw, r.bottom), null) else load(clip, srcMs / 1000, key)
            }
            fx += fw
        }
        c.restore()
        val tags = listOfNotNull(if (clip.speed != 1f) "${trim(clip.speed)}×" else null, if (clip.kenBurns) "KB" else null, if (clip.fit) "fit" else null)
        if (tags.isNotEmpty()) {
            val t = tags.joinToString(" "); val tw = badge.measureText(t)
            c.drawRoundRect(r.right - tw - dp(10f), r.bottom - dp(16f), r.right - dp(3f), r.bottom - dp(3f), dp(3f), dp(3f), badgeBg)
            c.drawText(t, r.right - tw - dp(6.5f), r.bottom - dp(6f), badge)
        }
        if (isSel) {
            c.drawRoundRect(r, dp(4f), dp(4f), sel)
            c.drawRect(r.left, r.top, r.left + handleW, r.bottom, amber)
            c.drawRect(r.right - handleW, r.top, r.right, r.bottom, amber)
        }
    }

    /** A1: the clip's own sound, its waveform at its volume. */
    private fun drawClipSound(c: Canvas, clip: Clip, a: Float, b: Float) {
        r.set(a + dp(1f), a1Top + dp(1f), b - dp(1f), a1Top + aH - dp(1f))
        if (clip.still.isNotEmpty() || clip.revOf != null) {
            c.drawRoundRect(r, dp(3f), dp(3f), trackBg2)
            c.save(); c.clipRect(r); c.drawText("no sound", r.left + dp(5f), r.top + dp(18f), hint); c.restore()
            return
        }
        c.drawRoundRect(r, dp(3f), dp(3f), audioBg)
        drawWave(c, clip.uri, clip.sourceMs, RectF(r), clip.startMs, clip.speed, clip.volume)
        if (selected >= 0 && project?.clips?.getOrNull(selected) === clip) c.drawRoundRect(r, dp(3f), dp(3f), sel)
    }

    private fun drawWave(c: Canvas, uri: String, sourceMs: Long, rr: RectF, fromMs: Long, speed: Float, vol: Float) {
        val pk = peaks[uri]
        if (pk == null) { loadPeaks(uri, sourceMs); return }
        c.save(); c.clipRect(rr)
        val mid = rr.centerY(); val half = rr.height() / 2 - dp(2f)
        var x = max(rr.left, headW)
        val end = min(rr.right, width.toFloat())
        while (x < end) {
            val ms = fromMs + ((x - rr.left) / pxPerMs * speed).toLong()
            val v = (pk.getOrNull((ms / 25).toInt()) ?: 0f) * vol
            val h = min(1f, v) * half
            c.drawLine(x, mid - h, x, mid + h, wave)
            x += dp(2f)
        }
        c.restore()
    }

    /** A bar on T1 or an audio track; [extra] draws inside it (a waveform). */
    private fun bar(c: Canvas, a: Float, b: Float, top: Float, h: Float, bg: Paint, text: String, on: Boolean, extra: ((RectF) -> Unit)?) {
        if (b < headW || a > width) return
        r.set(a + dp(1f), top + dp(1f), b - dp(1f), top + h - dp(1f))
        c.drawRoundRect(r, dp(3f), dp(3f), bg)
        val box = RectF(r)
        extra?.invoke(RectF(box.left, box.top + dp(10f), box.right, box.bottom))
        c.save(); c.clipRect(box); c.drawText(text, box.left + dp(5f), box.top + dp(10.5f), label); c.restore()
        if (on) {
            c.drawRoundRect(box, dp(3f), dp(3f), sel)
            c.drawRect(box.left, box.top, box.left + dp(5f), box.bottom, amber); c.drawRect(box.right - dp(5f), box.top, box.right, box.bottom, amber)
        }
    }

    // ---------------------------------------------------------------- track headers

    private class Btn(val rect: RectF, val key: String, val on: Boolean)

    private fun buttons(p: Project): List<Btn> {
        val out = ArrayList<Btn>()
        val cw = dp(19f); val x0 = dp(28f)
        fun row(top: Float, h: Float, keys: List<Pair<String, Boolean>>) {
            val bh = min(h - dp(6f), dp(20f)); val y = top + (h - bh) / 2
            keys.forEachIndexed { i, (k, on) -> val x = x0 + i * (cw + dp(2f)); out.add(Btn(RectF(x, y, x + cw, y + bh), k, on)) }
        }
        out.add(Btn(RectF(dp(4f), dp(3f), headW - dp(8f), rulerH - dp(3f)), "v+", false))            // add above the video
        row(vTop, vH, listOf("vH" to p.vHide, "vL" to p.vLock))
        for (k in 0 until p.vTracks.size) row(ovTop(k), vH, listOf("o${k}H" to p.vTracks[k].hide, "o${k}L" to p.vTracks[k].lock))
        row(tTop, tH, listOf("tH" to p.tHide, "tL" to p.tLock, "t+" to false))
        row(a1Top, aH, listOf("vM" to p.vMute, "vS" to p.vSolo))
        for (i in p.aTracks.indices) { val t = p.aTracks[i]; row(aTop(i), aH, listOf("a${i}M" to t.mute, "a${i}S" to t.solo, "a${i}L" to t.lock)) }
        out.add(Btn(RectF(dp(4f), addTop(p), headW - dp(8f), addTop(p) + dp(20f)), "a+", false))      // add under the audio
        return out
    }

    private fun icon(res: Int) = icons.getOrPut(res) { context.getDrawable(res)!!.mutate() }

    private fun drawHeaders(c: Canvas, p: Project) {
        c.drawRect(0f, 0f, headW, height.toFloat(), headBg)
        c.drawRect(headW - dp(1f), 0f, headW, height.toFloat(), headLine)
        c.save(); c.clipRect(0f, rulerH, headW, height.toFloat())
        fun name(n: String, top: Float, h: Float) { c.drawText(n, dp(5f), top + h / 2 + dp(4f), headName); c.drawRect(0f, top + h + gap / 2, headW, top + h + gap / 2 + 1, headLine) }
        name("V1", vTop, vH); name("T1", tTop, tH); name("A1", a1Top, aH)
        for (k in 0 until p.vTracks.size) name("V${k + 2}", ovTop(k), vH)
        for (i in p.aTracks.indices) name("A${i + 2}", aTop(i), aH)
        c.restore()
        for (b in buttons(p)) {
            if (b.key != "v+" && b.rect.bottom < rulerH) continue
            val k = b.key
            if (k == "v+" || k == "a+") {
                btnBg.color = 0xFF34363B.toInt(); c.drawRoundRect(b.rect, dp(4f), dp(4f), btnBg)
                btnText.color = 0xFFF29B38.toInt(); btnText.textSize = dp(10f)
                c.drawText(if (k == "v+") "+ video" else "+ audio", b.rect.centerX(), b.rect.centerY() + dp(3.5f), btnText)
                continue
            }
            val kind = k.last()
            val active = when (kind) { 'M' -> 0xFFE5483B.toInt(); 'S' -> 0xFFE8C547.toInt(); 'L' -> 0xFFD8D9DD.toInt(); 'H' -> 0xFF8E6BD0.toInt(); else -> 0xFFF29B38.toInt() }
            btnBg.color = if (b.on) active else 0xFF3A3C41.toInt()
            c.drawRoundRect(b.rect, dp(3f), dp(3f), btnBg)
            val fg = if (b.on) 0xFF111111.toInt() else 0xFFC9CACF.toInt()
            when (kind) {
                'M', 'S' -> { btnText.color = fg; btnText.textSize = dp(10.5f); c.drawText(kind.toString(), b.rect.centerX(), b.rect.centerY() + dp(3.8f), btnText) }
                else -> {
                    val dr = icon(when (kind) { 'L' -> R.drawable.i_lock; 'H' -> if (b.on) R.drawable.i_eye_off else R.drawable.i_eye; else -> R.drawable.i_add })
                    val s = dp(12f).toInt(); val cx = b.rect.centerX().toInt(); val cy = b.rect.centerY().toInt()
                    dr.setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2); dr.setTint(if (kind == '+') 0xFFF29B38.toInt() else fg); dr.draw(c)
                }
            }
        }
    }

    private fun trim(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()

    // ---------------------------------------------------------------- loading

    private fun load(clip: Clip, sec: Long, key: String) {
        if (key in loading) return
        loading.add(key)
        val hpx = (vH - dp(14f)).toInt()
        try {
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
        } catch (e: Exception) {}
    }

    private fun loadPeaks(uri: String, sourceMs: Long) {
        val key = "wave:$uri"
        if (key in loading) return
        loading.add(key)
        try {
            pool.execute {
                val pcm = try { Audio.decode(context, uri, 0, if (sourceMs > 0) sourceMs else 600_000, mono = true, rate = 1000) } catch (e: Throwable) { ShortArray(0) }
                val n = pcm.size / 25
                val pk = FloatArray(n) { i -> var m = 0; for (k in i * 25 until (i + 1) * 25) m = max(m, abs(pcm[k].toInt())); m / 32768f }
                // a little louder, so quiet recordings still show
                val top = pk.maxOrNull()?.takeIf { it > 0.01f } ?: 1f
                for (i in pk.indices) pk[i] = min(1f, pk[i] / top * 0.95f)
                main.post { peaks[uri] = pk; invalidate() }
            }
        } catch (e: Exception) {}
    }

    // ---------------------------------------------------------------- touch

    private val scroller = OverScroller(ctx)
    private var trimming = 0; private var trimClip = -1; private var trimOrig = 0L
    private var downX = 0f; private var downY = 0f
    private var headTouch = false; private var headScroll = false
    private var moving: Any? = null; private var moveMode = 0          // 0 move, -1 trim start, 1 trim end
    private var moveOrigStart = 0L; private var moveOrigEnd = 0L; private var moveOrigIn = 0L; private var moveOrigSrcEnd = 0L
    private var dir = 0                                                  // 0 undecided, 1 sideways, 2 up/down

    /** Times things snap to: the playhead, the start, the clip edges, other bars' edges. */
    private fun snapPoints(p: Project, except: Any?): List<Long> {
        val out = arrayListOf(0L, timeMs, p.lengthMs)
        p.overlays.forEach { if (it !== except) { out.add(it.at); out.add(it.endAt) } }
        var t = 0L; p.clips.forEach { t += it.lengthMs; out.add(t) }
        p.texts.forEach { if (it !== except) { out.add(it.startMs); out.add(it.endMs) } }
        p.audio.forEach { if (it !== except) { out.add(it.startMs); out.add(it.endMs) } }
        return out
    }
    private fun snap(p: Project, t: Long, except: Any?): Long {
        if (!snapOn) return t
        val tol = (dp(10f) / pxPerMs).toLong()
        return snapPoints(p, except).minByOrNull { abs(it - t) }?.takeIf { abs(it - t) <= tol } ?: t
    }

    private fun ovTrackAt(y: Float): Int = (0 until vCount()).firstOrNull { y >= ovTop(it) && y < ovTop(it) + vH } ?: -1
    private fun trackAt(p: Project, y: Float): Int = (0 until p.aTracks.size).firstOrNull { y >= aTop(it) - gap && y < aTop(it) + aH + gap } ?: -1

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); dir = 0; return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (trimming != 0) return true
            if (dir == 0) dir = if (abs(dy) > abs(dx)) 2 else 1
            if (dir == 2) { scrollY += dy; invalidate() } else moveTo(timeMs + (dx / pxPerMs).toLong())
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (trimming != 0 || dir == 2) return true
            val p = project ?: return true
            scroller.fling((timeMs * pxPerMs).toInt(), 0, -vx.toInt(), 0, 0, (p.lengthMs * pxPerMs).toInt(), 0, 0)
            postInvalidateOnAnimation(); return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val p = project ?: return true
            val t = ((e.x - edgeX) / pxPerMs).toLong()
            val ax = edgeX + p.lengthMs * pxPerMs + dp(24f)
            if (abs(e.x - ax) < dp(22f) && abs(e.y - (vTop + vH / 2)) < dp(26f)) { if (p.vLock) onLocked?.invoke("V1") else onAdd?.invoke(); return true }
            val ok = ovTrackAt(e.y)
            if (ok >= 0) {
                p.overlayAt(ok, t)?.let {
                    if (p.vTracks[ok].lock) onLocked?.invoke("V${ok + 2}") else if (it.id != selectedText) { selectedText = it.id; onSelect?.invoke(-1); selectedText = it.id; performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) } else onOverlay?.invoke(it.id)
                    return true }
                selectedText = null; return true
            }
            if (e.y in tTop..(tTop + tH)) {
                // first tap selects (then drag it, or its ends); tap again to open it
                p.texts.lastOrNull { t in it.startMs..it.endMs }?.let {
                    if (p.tLock) onLocked?.invoke("T1") else if (it.id != selectedText) { selectedText = it.id; performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) } else onText?.invoke(it.id)
                    return true }
                selectedText = null; return true
            }
            val tr = trackAt(p, e.y)
            if (tr >= 0) {
                p.audio.lastOrNull { it.track == tr && t in it.startMs..it.endMs }?.let {
                    if (p.trackLocked(tr)) onLocked?.invoke("A${tr + 2}") else if (it.id != selectedText) { selectedText = it.id; performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) } else onAudio?.invoke(it.id)
                    return true }
                selectedText = null; return true
            }
            if (!p.vLock && abs(e.y - (vTop + vH / 2)) < dp(20f)) {
                var jt = 0L
                for (i in 0 until p.clips.size - 1) { jt += p.clips[i].lengthMs; if (abs(e.x - (edgeX + jt * pxPerMs)) < dp(12f)) { onJoint?.invoke(i); return true } }
            }
            val i = clipAt(e.x, e.y)
            selectedText = null
            onSelect?.invoke(if (i == selected) -1 else i); return true
        }
        override fun onLongPress(e: MotionEvent) {
            val i = clipAt(e.x, e.y)
            if (i >= 0) { if (project?.vLock == true) { onLocked?.invoke("V1"); return }; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onHold?.invoke(i) }
        }
    })

    private val pinch = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            pxPerMs = (pxPerMs * det.scaleFactor).coerceIn(dp(4f) / 1000f, dp(400f) / 1000f); invalidate(); return true
        }
    })

    /** A clip on V1 or its sound on A1. */
    private fun clipAt(x: Float, y: Float): Int {
        val p = project ?: return -1
        val onV = y in (vTop - dp(4f))..(vTop + vH + dp(4f)); val onA1 = y in a1Top..(a1Top + aH)
        if (!onV && !onA1) return -1
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
        // track headers: buttons, or drag up and down to see more tracks
        if (e.actionMasked == MotionEvent.ACTION_DOWN && e.x < headW) {
            headTouch = true; headScroll = false; downY = e.y; return true
        }
        if (headTouch) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (abs(e.y - downY) > dp(8f) || headScroll) { headScroll = true; scrollY -= e.y - downY; downY = e.y; invalidate() }
                MotionEvent.ACTION_UP -> {
                    headTouch = false
                    if (!headScroll) buttons(p).firstOrNull { RectF(it.rect).apply { inset(-dp(2f), -dp(4f)) }.contains(e.x, e.y) }?.let {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onTrack?.invoke(it.key); invalidate()
                    } ?: run {
                        // a held / tapped audio track name
                        val tr = trackAt(p, e.y)
                        if (!headScroll && tr >= 0 && e.x < dp(26f) && e.eventTime - e.downTime > 450) onTrackHold?.invoke(tr)
                    }
                }
                MotionEvent.ACTION_CANCEL -> headTouch = false
            }
            return true
        }
        pinch.onTouchEvent(e)
        if (pinch.isInProgress) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trimming = 0; moving = null; downX = e.x; downY = e.y
                val t0 = ((e.x - edgeX) / pxPerMs).toLong()
                val edge = (dp(14f) / pxPerMs).toLong()
                // press on the selected bar: move it, or trim it by an end
                val selT = p.texts.firstOrNull { it.id == selectedText }
                val selA = p.audio.firstOrNull { it.id == selectedText }
                fun grab(item: Any, start: Long, end: Long) {
                    moving = item; moveOrigStart = start; moveOrigEnd = end; moveOrigIn = (item as? AudioItem)?.inMs ?: 0
                    moveMode = when { abs(t0 - start) <= edge -> -1; abs(t0 - end) <= edge -> 1; else -> 0 }
                    onLaneDown?.invoke()
                }
                val selO = p.overlays.firstOrNull { it.id == selectedText }
                if (selO != null && p.vTracks.getOrNull(selO.track)?.lock != true && ovTrackAt(e.y) == selO.track && t0 in (selO.at - edge)..(selO.endAt + edge)) {
                    moving = selO; moveOrigStart = selO.at; moveOrigEnd = selO.endAt; moveOrigIn = selO.startMs; moveOrigSrcEnd = selO.endMs
                    moveMode = when { abs(t0 - selO.at) <= edge -> -1; abs(t0 - selO.endAt) <= edge -> 1; else -> 0 }
                    onLaneDown?.invoke(); return true
                }
                if (selT != null && !p.tLock && e.y in tTop..(tTop + tH) && t0 in (selT.startMs - edge)..(selT.endMs + edge)) { grab(selT, selT.startMs, selT.endMs); return true }
                if (selA != null && !p.trackLocked(selA.track) && trackAt(p, e.y) == selA.track && t0 in (selA.startMs - edge)..(selA.endMs + edge)) { grab(selA, selA.startMs, selA.endMs); return true }
                if (selected in p.clips.indices && !p.vLock && e.y in vTop..(vTop + vH)) {
                    val a = edgeX + p.startOf(selected) * pxPerMs; val b = a + p.clips[selected].lengthMs * pxPerMs
                    if (abs(e.x - a) < handleW * 1.6f) { trimming = -1; trimClip = selected; trimOrig = p.clips[selected].startMs }
                    else if (abs(e.x - b) < handleW * 1.6f) { trimming = 1; trimClip = selected; trimOrig = p.clips[selected].endMs }
                }
            }
            MotionEvent.ACTION_MOVE -> if (moving != null) {
                val dt = ((e.x - downX) / pxPerMs).toLong()
                when (val m = moving) {
                    is TextItem -> when (moveMode) {
                        0 -> { val len = moveOrigEnd - moveOrigStart; var s = (moveOrigStart + dt).coerceAtLeast(0); val se = snap(p, s + len, m); val ss = snap(p, s, m)
                            s = if (ss != s) ss else if (se != s + len) se - len else s; m.startMs = s.coerceAtLeast(0); m.endMs = m.startMs + len }
                        -1 -> m.startMs = snap(p, moveOrigStart + dt, m).coerceIn(0, m.endMs - 300)
                        else -> m.endMs = snap(p, moveOrigEnd + dt, m).coerceAtLeast(m.startMs + 300)
                    }
                    is Clip -> when (moveMode) {
                        0 -> {
                            val len = moveOrigEnd - moveOrigStart; var s = (moveOrigStart + dt).coerceAtLeast(0); val se = snap(p, s + len, m); val ss = snap(p, s, m)
                            s = if (ss != s) ss else if (se != s + len) se - len else s; m.at = s.coerceIn(0, max(0, p.lengthMs - 200))
                            val k = ovTrackAt(e.y); if (k >= 0 && p.vTracks[k].lock != true) m.track = k
                        }
                        -1 -> { val na = snap(p, moveOrigStart + dt, m).coerceAtLeast(0)
                            val src = (moveOrigIn + ((na - moveOrigStart) * m.speed).toLong()).coerceIn(0, moveOrigSrcEnd - 300)
                            m.startMs = src; m.at = moveOrigStart + ((src - moveOrigIn) / m.speed).toLong() }
                        else -> { val ne = snap(p, moveOrigEnd + dt, m); m.endMs = (moveOrigSrcEnd + ((ne - moveOrigEnd) * m.speed).toLong()).coerceIn(m.startMs + 300, m.sourceMs) }
                    }
                    is AudioItem -> when (moveMode) {
                        0 -> {
                            val len = moveOrigEnd - moveOrigStart; var s = (moveOrigStart + dt).coerceAtLeast(0); val se = snap(p, s + len, m); val ss = snap(p, s, m)
                            s = if (ss != s) ss else if (se != s + len) se - len else s; m.startMs = s.coerceAtLeast(0)
                            // drag up or down onto another audio track
                            val tr = trackAt(p, e.y); if (tr >= 0 && !p.trackLocked(tr)) m.track = tr
                        }
                        -1 -> { val ns = snap(p, moveOrigStart + dt, m).coerceIn(moveOrigStart - moveOrigIn, moveOrigEnd - 300).coerceAtLeast(0)
                            m.inMs = moveOrigIn + (ns - moveOrigStart); m.startMs = ns; m.lengthMs = moveOrigEnd - ns }
                        else -> { val ne = snap(p, moveOrigEnd + dt, m).coerceIn(m.startMs + 300, m.startMs + (m.sourceMs - m.inMs)); m.lengthMs = ne - m.startMs }
                    }
                }
                invalidate(); return true
            } else if (trimming != 0) {
                val c = p.clips[trimClip]
                val delta = ((e.x - downX) / pxPerMs * c.speed).toLong()
                if (trimming < 0) c.startMs = (trimOrig + delta).coerceIn(0, c.endMs - 300)
                else c.endMs = (trimOrig + delta).coerceIn(c.startMs + 300, c.sourceMs)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (moving != null) {
                    val m = moving; val moved = abs(e.x - downX) > dp(4f) || abs(e.y - downY) > dp(8f); moving = null
                    if (moved) onLaneMoved?.invoke() else selectedText?.let { when (m) { is AudioItem -> onAudio?.invoke(it); is Clip -> onOverlay?.invoke(it); else -> onText?.invoke(it) } }
                    return true
                }
                if (trimming != 0) { trimming = 0; onTrimmed?.invoke(trimClip); return true }
            }
        }
        if (trimming != 0) return true
        return gestures.onTouchEvent(e)
    }
}
