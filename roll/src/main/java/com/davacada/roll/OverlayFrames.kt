package com.davacada.roll

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * Export: hands out the frames of the V2 / V3 clips, in order, a small batch at a time
 * (decoding forwards is fast; jumping around is slow). One per export, shared by every V1 clip.
 */
class OverlayFrames(private val ctx: Context, val clips: List<Clip>) {
    private class Stream(val clip: Clip) {
        var mr: MediaMetadataRetriever? = null
        var count = 0; var dur = 1L; var rotation = 0
        var batch: List<Bitmap> = emptyList(); var batchStart = -1
        var last: Bitmap? = null; var lastIndex = -1
    }
    private val streams = HashMap<String, Stream>()

    /** The frame of [c] at [srcMs] (its own source time), at most 1280 on the long side. */
    fun frame(c: Clip, srcMs: Long): Bitmap? {
        val s = streams.getOrPut(c.id) { Stream(c) }
        try {
            if (s.mr == null) {
                val mr = MediaMetadataRetriever(); mr.setDataSource(ctx, Uri.parse(c.uri)); s.mr = mr
                s.dur = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1L
                s.count = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
                s.rotation = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            }
            val mr = s.mr!!
            if (s.count <= 0) return s.last ?: mr.getScaledFrameAtTime(srcMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 1280, 720)?.also { s.last = it }
            val idx = (srcMs * s.count / s.dur).toInt().coerceIn(0, s.count - 1)
            if (idx == s.lastIndex) return s.last
            if (idx < s.batchStart || idx >= s.batchStart + s.batch.size || s.batchStart < 0) {
                s.batch.forEach { if (it !== s.last) it.recycle() }
                val n = minOf(if (maxOf(c.w, c.h) > 2000) 4 else 8, s.count - idx)
                s.batch = mr.getFramesAtIndex(idx, n); s.batchStart = idx
            }
            var f = s.batch[idx - s.batchStart]
            if ((f.width > f.height) != (c.w > c.h) && s.rotation != 0) f = Bitmap.createBitmap(f, 0, 0, f.width, f.height, Matrix().apply { postRotate(s.rotation.toFloat()) }, true)
            val k = minOf(1f, 1280f / maxOf(f.width, f.height))
            if (k < 1f) f = Bitmap.createScaledBitmap(f, (f.width * k).toInt().coerceAtLeast(2), (f.height * k).toInt().coerceAtLeast(2), true)
            s.last = f; s.lastIndex = idx
            return f
        } catch (e: Exception) { return s.last }
    }

    fun release() { streams.values.forEach { try { it.mr?.release() } catch (e: Exception) {} }; streams.clear() }
}
