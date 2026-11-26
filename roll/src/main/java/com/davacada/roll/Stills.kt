package com.davacada.roll

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.util.UUID

/**
 * Roll's own little videos: a photo or a frozen frame becomes a still clip, and a clip can be reversed.
 * They're plain H.264 files in the app's storage, so everything else (trim, speed, looks, export) just works.
 */
object Stills {
    const val STILL_MS = 10_000L          // a still clip can be up to 10 s long
    private const val FPS = 30

    fun dir(ctx: Context) = File(ctx.filesDir, "media").apply { mkdirs() }

    /** Even dimensions, multiples of 16 (every encoder takes them), at most [long] on the long side. */
    private fun encSize(w: Int, h: Int, long: Int): Pair<Int, Int> {
        val k = minOf(1f, long.toFloat() / maxOf(w, h))
        fun r16(x: Float) = maxOf(16, (x / 16f).toInt() * 16)
        return r16(w * k) to r16(h * k)
    }

    /** A photo from the gallery, upright, at most 1920 on its long side. */
    fun decodePhoto(ctx: Context, uri: Uri): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
        val k = minOf(1f, 1920f / maxOf(info.size.width, info.size.height))
        d.setTargetSize(maxOf(2, (info.size.width * k).toInt()), maxOf(2, (info.size.height * k).toInt()))
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

    /** A still clip from [bm]: [STILL_MS] long, shown for [showMs] at first. */
    fun stillClip(ctx: Context, bm: Bitmap, kind: String, showMs: Long): Clip {
        val (w, h) = encSize(bm.width, bm.height, 1920)
        val frame = if (bm.width == w && bm.height == h) bm else Bitmap.createScaledBitmap(bm, w, h, true)
        val out = File(dir(ctx), "still-" + UUID.randomUUID() + ".mp4")
        val yuv = Yuv(w, h).apply { set(frame) }
        encode(out, w, h, (STILL_MS * FPS / 1000).toInt(), 4_000_000) { _ -> yuv }
        return Clip(Uri.fromFile(out).toString(), STILL_MS, 0, showMs.coerceAtMost(STILL_MS), w = w, h = h).apply { still = kind; if (kind == "photo") kenBurns = true }
    }

    /**
     * A reversed copy of the part of [c] that's in the edit (no sound). [progress] gets 0..100.
     * Returns the new clip, keeping the old one in revOf so it can be turned back.
     */
    fun reverse(ctx: Context, c: Clip, progress: (Int) -> Unit): Clip? {
        val mr = MediaMetadataRetriever(); mr.setDataSource(ctx, Uri.parse(c.uri))
        try {
            val dur = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            val count = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: return null
            val rotation = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val fps = (count * 1000f / dur).coerceIn(10f, 60f)
            val a = (c.startMs * count / dur).toInt().coerceIn(0, count - 1)
            val b = (c.endMs * count / dur).toInt().coerceIn(a + 1, count)
            val n = b - a
            val (w, h) = encSize(c.w, c.h, 1920)
            val out = File(dir(ctx), "rev-" + UUID.randomUUID() + ".mp4")
            val yuv = Yuv(w, h)
            val chunk = if (maxOf(c.w, c.h) > 2000) 6 else 15
            var batch: List<Bitmap> = emptyList(); var batchStart = -1
            encode(out, w, h, n, (w.toLong() * h * fps * 0.12f).toLong().coerceIn(2_000_000, 40_000_000).toInt(), fps) { k ->
                // frame k of the output is source frame b - 1 - k
                val src = b - 1 - k
                if (src < batchStart || batchStart < 0) {
                    batch.forEach { it.recycle() }
                    batchStart = maxOf(a, src - chunk + 1)
                    batch = mr.getFramesAtIndex(batchStart, src - batchStart + 1)
                }
                var f = batch[src - batchStart]
                // some phones hand frames over un-rotated
                if ((f.width > f.height) != (c.w > c.h) && rotation != 0) f = Bitmap.createBitmap(f, 0, 0, f.width, f.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
                yuv.set(if (f.width == w && f.height == h) f else Bitmap.createScaledBitmap(f, w, h, true))
                progress(k * 100 / n)
                yuv
            }
            batch.forEach { it.recycle() }
            val len = n * 1000L / fps.toLong().coerceAtLeast(1)
            return Clip(Uri.fromFile(out).toString(), len, 0, len, c.speed, c.volume, w, h).apply {
                framingFrom(c); trans = c.trans; transMs = c.transMs; kenBurns = c.kenBurns
                revOf = c.json().toString()
            }
        } finally { mr.release() }
    }

    /** Reversed back: the clip as it was before. */
    fun unreverse(ctx: Context, c: Clip): Clip? {
        val o = c.revOf ?: return null
        try { Uri.parse(c.uri).path?.let { File(it).delete() } } catch (e: Exception) {}
        return Clip.from(org.json.JSONObject(o)).apply { framingFrom(c); trans = c.trans; transMs = c.transMs }
    }

    /** A frame as YUV 4:2:0 (what video encoders take). */
    class Yuv(val w: Int, val h: Int) {
        val y = ByteArray(w * h); val u = ByteArray(w * h / 4); val v = ByteArray(w * h / 4)
        private val px = IntArray(w * h)
        fun set(b: Bitmap) {
            b.getPixels(px, 0, w, 0, 0, w, h)
            for (j in 0 until h) {
                val row = j * w
                for (i in 0 until w) {
                    val c = px[row + i]
                    val r = c shr 16 and 255; val g = c shr 8 and 255; val bl = c and 255
                    y[row + i] = (((66 * r + 129 * g + 25 * bl + 128) shr 8) + 16).toByte()
                    if (j and 1 == 0 && i and 1 == 0) {
                        val k = (j / 2) * (w / 2) + i / 2
                        u[k] = (((-38 * r - 74 * g + 112 * bl + 128) shr 8) + 128).toByte()
                        v[k] = (((112 * r - 94 * g - 18 * bl + 128) shr 8) + 128).toByte()
                    }
                }
            }
        }
    }

    /** Encodes [frames] pictures (asked for one by one) to an H.264 MP4. */
    private fun encode(out: File, w: Int, h: Int, frames: Int, bitrate: Int, fps: Float = FPS.toFloat(), frame: (Int) -> Yuv) {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate); setInteger(MediaFormat.KEY_FRAME_RATE, Math.round(fps)); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); enc.start()
        out.delete()
        val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1; var next = 0; var inDone = false; var outDone = false
        val info = MediaCodec.BufferInfo()
        try {
            while (!outDone) {
                if (!inDone) {
                    val i = enc.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val pts = (next * 1_000_000L / fps).toLong()
                        if (next >= frames) { enc.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else {
                            val yuv = frame(next)
                            val img = enc.getInputImage(i)!!
                            val pl = img.planes
                            put(pl[0], yuv.y, w, h); put(pl[1], yuv.u, w / 2, h / 2); put(pl[2], yuv.v, w / 2, h / 2)
                            enc.queueInputBuffer(i, 0, w * h * 3 / 2, pts, 0)
                            next++
                        }
                    }
                }
                val o = enc.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = mux.addTrack(enc.outputFormat); mux.start() }
                    o >= 0 -> {
                        val buf = enc.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) mux.writeSampleData(track, buf, info)
                        enc.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
            }
        } finally {
            try { enc.stop() } catch (e: Exception) {}; enc.release()
            try { if (track >= 0) mux.stop() } catch (e: Exception) {}; mux.release()
        }
    }

    private fun put(plane: android.media.Image.Plane, data: ByteArray, w: Int, h: Int) {
        val buf = plane.buffer; val rs = plane.rowStride; val ps = plane.pixelStride
        if (ps == 1 && rs == w) { buf.put(data, 0, w * h); return }
        for (j in 0 until h) {
            if (ps == 1) { buf.position(j * rs); buf.put(data, j * w, w) }
            else for (i in 0 until w) { val at = j * rs + i * ps; if (at < buf.limit()) buf.put(at, data[j * w + i]) }
        }
    }
}
