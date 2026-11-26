package com.davacada.roll

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.SingleColorLut
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** Turns a project into files: MP4 video, GIF, or a Telegram video sticker (WEBM, VP9). */
@UnstableApi
object Render {

    /** Width and height of the edit for an aspect choice and a short side of [short] pixels. */
    fun size(p: Project, short: Int): Pair<Int, Int> {
        val first = p.clips.firstOrNull()
        val ratio = when (p.aspect) {
            "9:16" -> 9f / 16f; "1:1" -> 1f; "4:5" -> 4f / 5f; "16:9" -> 16f / 9f; "4:3" -> 4f / 3f; "3:4" -> 3f / 4f; "21:9" -> 2.39f
            else -> if (first != null && first.w > 0 && first.h > 0) first.w.toFloat() / first.h else 16f / 9f
        }
        fun even(x: Float) = (x.toInt() / 2) * 2
        return if (ratio >= 1f) even(short * ratio) to short else short to even(short / ratio)
    }

    fun ratio(p: Project): Float { val (w, h) = size(p, 1080); return w.toFloat() / h }

    /** The grade as video effects: one colour lookup table, then grain and vignette. */
    fun gradeEffects(ctx: Context, g: Grade, lutDir: File): List<Effect> {
        val out = ArrayList<Effect>()
        if (!g.neutral) out.add(SingleColorLut.createFromCube(Looks.lut(g, lutDir)))
        // overlays (Media3's own overlay drawing): the chosen overlay, then vignette, then grain on top
        val layers = ArrayList<androidx.media3.effect.TextureOverlay>()
        if (g.overlay != "none" && Overlays.byId(g.overlay) != null && g.overlayAmount > 0.01f) layers.add(FrameOverlay(g.overlay, g.overlayAmount))
        val vi = Looks.vignetteOf(g); if (vi > 0.01f) layers.add(FrameOverlay("vignette", vi))
        val gr = Looks.grainOf(g); if (gr > 0.01f) layers.add(FrameOverlay("grain", gr))
        if (layers.isNotEmpty()) out.add(androidx.media3.effect.OverlayEffect(layers))
        return out
    }

    private class Constant(private val s: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long) = s
        override fun getNextSpeedChangeTimeUs(timeUs: Long) = androidx.media3.common.C.TIME_UNSET
    }

    /** Every clip's sound becomes stereo at the same volume scale, so clips from different sources join cleanly. */
    private fun volume(v: Float): AudioProcessor = ChannelMixingAudioProcessor().apply {
        putChannelMixingMatrix(ChannelMixingMatrix.create(1, 2).scaleBy(v))
        putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2).scaleBy(v))
    }

    /** Same sample rate for every clip (phones record 44.1 or 48 kHz). */
    private fun resample(): AudioProcessor = androidx.media3.common.audio.SonicAudioProcessor().apply { setOutputSampleRateHz(48000) }

    /** The part of the edit between [fromMs] and [toMs] (timeline time), as clips. */
    fun range(p: Project, fromMs: Long, toMs: Long): List<Clip> {
        val out = ArrayList<Clip>(); var t = 0L
        for (c in p.clips) {
            val a = t; val b = t + c.lengthMs; t = b
            if (b <= fromMs || a >= toMs) continue
            val cc = Clip.from(c.json())   // same id
            cc.startMs = c.startMs + ((maxOf(fromMs, a) - a) * c.speed).toLong()
            cc.endMs = c.startMs + ((minOf(toMs, b) - a) * c.speed).toLong()
            if (cc.endMs - cc.startMs > 40) out.add(cc)
        }
        return out
    }

    /**
     * [level] makes the export more forgiving after a failure:
     * 0 normal · 1 no duration hint, no forced audio, MediaCodec tone-mapping · 2 also without sound.
     */
    /** The last frame of each clip that dissolves into the next one (clip id → frame). Run off the main thread. */
    fun dissolveFrames(ctx: Context, clips: List<Clip>): Map<String, Bitmap> {
        val out = HashMap<String, Bitmap>()
        clips.forEachIndexed { i, c ->
            if (i < clips.size - 1 && c.trans == "dissolve") lastFrame(ctx, c, 1280)?.let { out[c.id] = it }
        }
        return out
    }

    /** The frame at the very end of a clip, upright, at most [long] pixels on its long side. */
    fun lastFrame(ctx: Context, c: Clip, long: Int): Bitmap? = try {
        val mr = MediaMetadataRetriever(); mr.setDataSource(ctx, Uri.parse(c.uri))
        val k = minOf(1f, long.toFloat() / maxOf(c.w, c.h, 1))
        val b = mr.getScaledFrameAtTime(maxOf(0, c.endMs - 40) * 1000, MediaMetadataRetriever.OPTION_CLOSEST, maxOf(2, (c.w * k).toInt()), maxOf(2, (c.h * k).toInt()))
        mr.release(); b
    } catch (e: Exception) { null }

    fun composition(ctx: Context, p: Project, clips: List<Clip>, w: Int, h: Int, fps: Int, withSoundAsked: Boolean, lutDir: File, level: Int = 0,
                    dissolve: Map<String, Bitmap> = emptyMap(), fromMs: Long = 0, soundtrack: File? = null): Composition {
        val withSound = withSoundAsked && level < 2
        val grade = gradeEffects(ctx, p.grade, lutDir)
        val items = clips.mapIndexed { idx, c ->
            val mi = MediaItem.Builder().setUri(Uri.parse(c.uri)).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.startMs).setEndPositionMs(c.endMs).build()).build()
            val audio = ArrayList<AudioProcessor>(); val video = ArrayList<Effect>()
            if (c.speed != 1f) {
                if (withSound) { val pair = Effects.createExperimentalSpeedChangingEffect(Constant(c.speed)); audio.add(pair.first); video.add(pair.second) }
                else video.add(SpeedChangeEffect(c.speed))
            }
            // Phones like the Red Magic record 24-bit sound; the mixers only take 16-bit, so convert first.
            if (withSound) { audio.add(0, androidx.media3.common.audio.ToInt16PcmAudioProcessor()); audio.add(volume(c.volume)); audio.add(resample()) }
            video.add(RollFrame(clips, idx, w, h, clips.getOrNull(idx - 1)?.let { dissolve[it.id] }, p.grade.fx()))
            video.add(FrameDropEffect.createDefaultFrameDropEffect(fps.toFloat()))
            video.addAll(grade)
            // titles and captions go on top of the grade
            if (p.texts.isNotEmpty()) video.add(androidx.media3.effect.OverlayEffect(listOf(TextOverlay(ctx, p.texts.map { TextItem.from(it.json()) }, fromMs + clips.take(idx).sumOf { it.lengthMs }))))
            val b = EditedMediaItem.Builder(mi).setEffects(Effects(audio, video)).setRemoveAudio(!withSound)
            if (level == 0 && c.sourceMs > 0) b.setDurationUs(c.sourceMs * 1000)
            b.build()
        }
        val sequences = arrayListOf(EditedMediaItemSequence(items))
        // music and voiceovers: one mixed soundtrack alongside the clips
        if (withSound && soundtrack != null && soundtrack.exists()) {
            val st = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(soundtrack))).setRemoveVideo(true)
                .setEffects(Effects(listOf(androidx.media3.common.audio.ToInt16PcmAudioProcessor(), resample()), emptyList())).build()
            sequences.add(EditedMediaItemSequence(listOf(st)))
        }
        return Composition.Builder(sequences)
            .setHdrMode(if (level == 0) Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL else Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC)
            .experimentalSetForceAudioTrack(withSound && level == 0)
            .build()
    }

    class Job(var cancel: () -> Unit)

    /** A readable reason, from the deepest cause. */
    fun reason(e: Throwable): String {
        var t: Throwable? = e
        val parts = ArrayList<String>()
        while (t != null) { parts.add((t.javaClass.simpleName + ": " + (t.message ?: "")).trim()); t = t.cause }
        val all = parts.joinToString(" · ")
        return when {
            Regex("(?i)SecurityException|Permission").containsMatchIn(all) -> "Roll lost access to a clip. Remove it from the timeline and add it again."
            Regex("(?i)FileNotFound|No such file|ENOENT").containsMatchIn(all) -> "A clip was moved or deleted from the phone."
            Regex("(?i)Decoder|MediaCodec|codec").containsMatchIn(all) -> "The phone couldn't decode one of the clips (${parts.last().take(90)})."
            else -> parts.last().take(140)
        }
    }

    /**
     * Export with automatic retries: if the first try fails (e.g. "Asset loader error"),
     * try again more forgivingly, and finally without sound. [done] gets (error, note).
     */
    fun export(ctx: Context, make: (Int) -> Composition, out: File, mime: String, bitrate: Int, progress: (Int) -> Unit,
               done: (String?, String?) -> Unit): Job {
        val job = Job {}
        var cancelled = false
        var firstError: String? = null
        fun attempt(level: Int) {
            val comp = try { make(level) } catch (e: Exception) { done(reason(e), null); return }
            val inner = exportOnce(ctx, comp, out, mime, bitrate, progress) { err ->
                if (cancelled) return@exportOnce
                when {
                    err == null -> done(null, if (level == 2) "Saved WITHOUT sound. The sound failed: ${firstError ?: "unknown"}" else null)
                    level < 2 -> { if (firstError == null) firstError = err; attempt(level + 1) }
                    else -> done(err, null)
                }
            }
            job.cancel = { cancelled = true; inner.cancel() }
        }
        attempt(0)
        return job
    }

    /** Runs the export to [out]; [progress] gets 0..100, [done] gets null on success or the error. */
    private fun exportOnce(ctx: Context, comp: Composition, out: File, mime: String, bitrate: Int, progress: (Int) -> Unit, done: (String?) -> Unit): Job {
        val main = Handler(Looper.getMainLooper())
        val enc = DefaultEncoderFactory.Builder(ctx)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .setEnableFallback(true).build()
        var finished = false
        // no "nothing written for a while" abort: heavy looks on a slow phone are fine, just slower
        val tr = Transformer.Builder(ctx).setVideoMimeType(mime).setEncoderFactory(enc).setMaxDelayBetweenMuxerSamplesMs(androidx.media3.common.C.TIME_UNSET)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { finished = true; done(null) }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { finished = true; done(reason(exportException)) }
            }).build()
        out.parentFile?.mkdirs(); out.delete()
        tr.start(comp, out.path)
        val holder = ProgressHolder()
        val tick = object : Runnable {
            override fun run() {
                if (finished) return
                if (tr.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress)
                main.postDelayed(this, 250)
            }
        }
        main.post(tick)
        return Job { finished = true; tr.cancel() }
    }

    // ---------------------------------------------------------------- saving

    /** Puts a finished file into the gallery (Movies/Roll or Pictures/Roll). Returns its address. */
    fun saveToGallery(ctx: Context, f: File, name: String, mime: String): Uri? {
        val image = mime.startsWith("image/")
        val coll = if (image) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (image) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES) + "/Roll")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(coll, v) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    // ---------------------------------------------------------------- GIF

    /** Reads every frame of [video] and writes an animated GIF. [loop]: "forever", "once" or "bounce". */
    fun gif(video: File, out: File, fps: Int, loop: String, progress: (Int) -> Unit) {
        val r = MediaMetadataRetriever(); r.setDataSource(video.path)
        val count = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
        val frames = ArrayList<Bitmap>()
        for (i in 0 until count) { r.getFrameAtIndex(i)?.let { frames.add(it) }; progress(i * 60 / maxOf(count, 1)) }
        r.release()
        val seq = if (loop == "bounce") frames + frames.reversed().drop(1).dropLast(1) else frames
        FileOutputStream(out).use { os ->
            val g = GifWriter(os, maxOf(2, Math.round(100f / fps)), if (loop == "once") -1 else 0)
            seq.forEachIndexed { i, b -> g.frame(b); progress(60 + i * 40 / maxOf(seq.size, 1)) }
            g.finish()
        }
        frames.forEach { it.recycle() }
    }

    // ---------------------------------------------------------------- Telegram sticker (WEBM, VP9)

    /** Re-encodes [src] (small H.264) to a VP9 WEBM with no sound. Returns false if the phone can't make VP9. */
    fun webm(src: File, dst: File, bitrate: Int): Boolean {
        val ex = MediaExtractor(); ex.setDataSource(src.path)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return false
        ex.selectTrack(track)
        val inFmt = ex.getTrackFormat(track)
        val w = inFmt.getInteger(MediaFormat.KEY_WIDTH); val h = inFmt.getInteger(MediaFormat.KEY_HEIGHT)
        val fps = if (inFmt.containsKey(MediaFormat.KEY_FRAME_RATE)) inFmt.getInteger(MediaFormat.KEY_FRAME_RATE) else 30
        val encFmt = MediaFormat.createVideoFormat(MimeTypes.VIDEO_VP9, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate); setInteger(MediaFormat.KEY_FRAME_RATE, fps); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(encFmt) ?: run { ex.release(); return false }
        val enc = MediaCodec.createByCodecName(name)
        enc.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface(); enc.start()
        val dec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(inFmt, surface, null, 0); dec.start()
        dst.delete()
        val mux = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)
        var muxTrack = -1; var inDone = false; var decDone = false; var encDone = false
        val info = MediaCodec.BufferInfo()
        try {
            while (!encDone) {
                if (!inDone) {
                    val i = dec.dequeueInputBuffer(5000)
                    if (i >= 0) {
                        val n = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                        if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                if (!decDone) {
                    val o = dec.dequeueOutputBuffer(info, 5000)
                    if (o >= 0) {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        dec.releaseOutputBuffer(o, info.presentationTimeUs * 1000)
                        if (eos) { decDone = true; enc.signalEndOfInputStream() }
                    }
                }
                val e = enc.dequeueOutputBuffer(info, 5000)
                when {
                    e == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { muxTrack = mux.addTrack(enc.outputFormat); mux.start() }
                    e >= 0 -> {
                        val buf = enc.getOutputBuffer(e)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && muxTrack >= 0) mux.writeSampleData(muxTrack, buf, info)
                        enc.releaseOutputBuffer(e, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encDone = true
                    }
                }
            }
        } finally {
            try { dec.stop() } catch (e: Exception) {}; dec.release()
            try { enc.stop() } catch (e: Exception) {}; enc.release(); surface.release()
            try { if (muxTrack >= 0) mux.stop() } catch (e: Exception) {}; mux.release()
            ex.release()
        }
        return muxTrack >= 0
    }
}

/**
 * A small animated-GIF writer: each frame is reduced to a fixed 252-colour palette with ordered
 * dithering (smooth gradients, no banding) and LZW-compressed.
 */
class GifWriter(private val os: OutputStream, private val delayCs: Int, private val loops: Int) {
    private var started = false
    private var w = 0; private var h = 0
    private val palette = ByteArray(256 * 3).also { p ->
        var k = 0
        for (r in 0 until 6) for (g in 0 until 7) for (b in 0 until 6) {
            p[k * 3] = (r * 255 / 5).toByte(); p[k * 3 + 1] = (g * 255 / 6).toByte(); p[k * 3 + 2] = (b * 255 / 5).toByte(); k++
        }
    }
    private val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    private fun short(v: Int) { os.write(v and 0xff); os.write(v shr 8 and 0xff) }

    fun frame(b: Bitmap) {
        if (!started) {
            w = b.width; h = b.height
            os.write("GIF89a".toByteArray()); short(w); short(h)
            os.write(0xF7); os.write(0); os.write(0)          // global palette of 256 colours
            os.write(palette)
            if (loops >= 0) { os.write(byteArrayOf(0x21, 0xFF.toByte(), 11)); os.write("NETSCAPE2.0".toByteArray()); os.write(3); os.write(1); short(loops); os.write(0) }
            started = true
        }
        val px = IntArray(w * h)
        val bm = if (b.width == w && b.height == h) b else Bitmap.createScaledBitmap(b, w, h, true)
        bm.getPixels(px, 0, w, 0, 0, w, h)
        val idx = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val c = px[y * w + x]
            val d = (bayer[(y and 3) * 4 + (x and 3)] - 7.5f) / 16f
            val r = (((c shr 16 and 255) / 255f * 5f) + d).toInt().coerceIn(0, 5)
            val g = (((c shr 8 and 255) / 255f * 6f) + d).toInt().coerceIn(0, 6)
            val bl = (((c and 255) / 255f * 5f) + d).toInt().coerceIn(0, 5)
            idx[y * w + x] = ((r * 7 + g) * 6 + bl).toByte()
        }
        os.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0)); short(delayCs); os.write(0); os.write(0)  // frame delay
        os.write(0x2C); short(0); short(0); short(w); short(h); os.write(0)
        lzw(idx)
    }

    private fun lzw(data: ByteArray) {
        val minCode = 8
        os.write(minCode)
        val out = ByteArrayOutputStream()
        var cur = 0; var bits = 0
        var codeSize = minCode + 1
        val clear = 1 shl minCode; val eoi = clear + 1
        var next = eoi + 1
        val dict = HashMap<Int, Int>()
        fun emit(code: Int) { cur = cur or (code shl bits); bits += codeSize; while (bits >= 8) { out.write(cur and 0xff); cur = cur ushr 8; bits -= 8 } }
        emit(clear)
        var prefix = data[0].toInt() and 0xff
        for (i in 1 until data.size) {
            val k = data[i].toInt() and 0xff
            val key = (prefix shl 8) or k
            val found = dict[key]
            if (found != null) { prefix = found; continue }
            emit(prefix)
            if (next < 4096) { dict[key] = next++; if (next > (1 shl codeSize) && codeSize < 12) codeSize++ }
            else { emit(clear); dict.clear(); next = eoi + 1; codeSize = minCode + 1 }
            prefix = k
        }
        emit(prefix); emit(eoi)
        if (bits > 0) out.write(cur and 0xff)
        val bytes = out.toByteArray()
        var o = 0
        while (o < bytes.size) { val n = minOf(255, bytes.size - o); os.write(n); os.write(bytes, o, n); o += n }
        os.write(0)
    }

    fun finish() { os.write(0x3B); os.flush() }
}
