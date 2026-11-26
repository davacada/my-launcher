package com.davacada.roll

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The sound for an export with music or voiceovers, done by Roll itself: every clip's own sound (at its
 * speed, pitch kept), the music and the voice, mixed into one track, encoded as AAC and put together
 * with the picture (which Media3 exports without sound).
 */
@UnstableApi
object Mux {
    /** Everything you'd hear, for the whole edit: 48 kHz stereo. Run off the main thread. */
    fun fullMix(ctx: Context, p: Project, progress: (Int) -> Unit): ShortArray {
        val rate = Audio.RATE
        val n = (p.lengthMs * rate / 1000).toInt()
        val acc = FloatArray(n * 2)
        var t = 0L
        p.clips.forEachIndexed { i, c ->
            if (p.clipSound() && c.volume > 0f && c.still.isEmpty() && c.revOf == null) {
                var pcm = Audio.decode(ctx, c.uri, c.startMs, (c.endMs - c.startMs))     // source time, speed 1
                if (c.speed != 1f && pcm.isNotEmpty()) pcm = stretch(pcm, c.speed)
                val at = (t * rate / 1000).toInt()
                val frames = minOf(pcm.size / 2, (c.lengthMs * rate / 1000).toInt())
                for (k in 0 until frames) {
                    val o = at + k; if (o >= n) break
                    acc[o * 2] += pcm[k * 2] / 32768f * c.volume; acc[o * 2 + 1] += pcm[k * 2 + 1] / 32768f * c.volume
                }
            }
            t += c.lengthMs
            progress((i + 1) * 60 / maxOf(1, p.clips.size))
        }
        // V2 / V3 clips' own sound, where they sit
        if (p.clipSound()) for (o in p.overlays) {
            if (o.volume <= 0f || o.still.isNotEmpty() || o.revOf != null || p.vTracks.getOrNull(o.track)?.hide == true) continue
            var pcm = Audio.decode(ctx, o.uri, o.startMs, o.endMs - o.startMs)
            if (o.speed != 1f && pcm.isNotEmpty()) pcm = stretch(pcm, o.speed)
            val at0 = (o.at * rate / 1000).toInt()
            for (k in 0 until pcm.size / 2) { val q = at0 + k; if (q >= n) break; acc[q * 2] += pcm[k * 2] / 32768f * o.volume; acc[q * 2 + 1] += pcm[k * 2 + 1] / 32768f * o.volume }
        }
        val clipsOnly = acc.copyOf()
        if (p.musicSound()) Audio.mix(ctx, p)?.also { android.util.Log.i("ROLLTEST", "soundtrack ${it.length()} bytes") }?.let { f ->
            // music + voice (already mixed with fades and ducking)
            val bytes = f.readBytes()
            val bb = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            var k = 0
            while (bb.hasRemaining() && k < acc.size) { acc[k] += bb.get() / 32768f; k++ }
        }
        progress(70)
        // loudness per second (for the self-test log)
        val secs = n / rate
        android.util.Log.i("ROLLTEST", "rms/s clips: " + (0 until secs).joinToString(" ") { sec -> var e = 0.0; for (k in sec * rate * 2 until (sec + 1) * rate * 2) e += clipsOnly[k] * clipsOnly[k]; "%.3f".format(Math.sqrt(e / (rate * 2))) })
        android.util.Log.i("ROLLTEST", "rms/s all:   " + (0 until secs).joinToString(" ") { sec -> var e = 0.0; for (k in sec * rate * 2 until (sec + 1) * rate * 2) e += acc[k] * acc[k]; "%.3f".format(Math.sqrt(e / (rate * 2))) })
        return ShortArray(acc.size) { i -> val v = acc[i]; val s = if (v > 0.9f) 0.9f + (v - 0.9f) / (1 + (v - 0.9f) * 10) else if (v < -0.9f) -0.9f + (v + 0.9f) / (1 - (v + 0.9f) * 10) else v; (s * 32767).toInt().coerceIn(-32768, 32767).toShort() }
    }

    /** Plays [pcm] (stereo 48 kHz) [speed] times faster without changing its pitch. */
    fun stretch(pcm: ShortArray, speed: Float): ShortArray {
        val sp = SonicAudioProcessor(); sp.setSpeed(speed); sp.setPitch(1f)
        sp.configure(AudioProcessor.AudioFormat(Audio.RATE, 2, C.ENCODING_PCM_16BIT)); sp.flush()
        val out = ArrayList<Short>(pcm.size)
        val bytes = ByteBuffer.allocateDirect(pcm.size * 2).order(ByteOrder.nativeOrder())
        bytes.asShortBuffer().put(pcm); bytes.position(0); bytes.limit(pcm.size * 2)
        fun drain() { val o = sp.output.order(ByteOrder.nativeOrder()); while (o.remaining() >= 2) out.add(o.short) }
        while (bytes.hasRemaining()) { val before = bytes.position(); sp.queueInput(bytes); drain(); if (bytes.position() == before) break }
        sp.queueEndOfStream()
        var guard = 0
        while (!sp.isEnded && guard++ < 1000) drain()
        drain()
        return ShortArray(out.size) { out[it] }
    }

    /** Puts the picture of [video] and [pcm] (as AAC) together in [out]. */
    fun join(video: File, pcm: ShortArray, out: File, progress: (Int) -> Unit) {
        val vex = MediaExtractor(); vex.setDataSource(video.path)
        val vt = (0 until vex.trackCount).first { vex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        vex.selectTrack(vt)
        val vfmt = vex.getTrackFormat(vt)
        val rotation = try { MediaMetadataRetriever().run { setDataSource(video.path); val r = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0; release(); r } } catch (e: Exception) { 0 }
        out.delete()
        val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        mux.setOrientationHint(rotation)
        val vOut = mux.addTrack(vfmt)
        // AAC encoder
        val afmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, Audio.RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC); setInteger(MediaFormat.KEY_BIT_RATE, 192_000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(afmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); enc.start()
        val info = MediaCodec.BufferInfo()
        var aOut = -1; var started = false
        val pending = ArrayList<Pair<ByteBuffer, MediaCodec.BufferInfo>>()   // audio written before the muxer starts
        var pos = 0; var inDone = false; var outDone = false
        try {
            // encode the sound first (the muxer starts once the AAC format is known)
            while (!outDone) {
                if (!inDone) {
                    val i = enc.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = enc.getInputBuffer(i)!!.order(ByteOrder.LITTLE_ENDIAN); buf.clear()
                        val count = minOf(buf.capacity() / 2, pcm.size - pos) and 1.inv()
                        val pts = pos / 2 * 1_000_000L / Audio.RATE
                        if (count <= 0) { enc.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { buf.asShortBuffer().put(pcm, pos, count); enc.queueInputBuffer(i, 0, count * 2, pts, 0); pos += count }
                        progress(pos * 50 / maxOf(1, pcm.size))
                    }
                }
                val o = enc.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { aOut = mux.addTrack(enc.outputFormat); mux.start(); started = true }
                else if (o >= 0) {
                    val b = enc.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val copy = ByteBuffer.allocate(info.size); b.position(info.offset); b.limit(info.offset + info.size); copy.put(b); copy.flip()
                        val ci = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                        if (started) mux.writeSampleData(aOut, copy, ci) else pending.add(copy to ci)
                    }
                    enc.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            for ((b, i) in pending) mux.writeSampleData(aOut, b, i)
            // then copy the picture across untouched
            val buf = ByteBuffer.allocate(4 * 1024 * 1024)
            val vi = MediaCodec.BufferInfo()
            val dur = if (vfmt.containsKey(MediaFormat.KEY_DURATION)) vfmt.getLong(MediaFormat.KEY_DURATION) else 1L
            while (true) {
                val n = vex.readSampleData(buf, 0)
                if (n < 0) break
                vi.set(0, n, vex.sampleTime, if (vex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                mux.writeSampleData(vOut, buf, vi)
                progress(50 + (vex.sampleTime * 50 / maxOf(1L, dur)).toInt().coerceIn(0, 50))
                vex.advance()
            }
        } finally {
            try { enc.stop() } catch (e: Exception) {}; enc.release()
            try { mux.stop() } catch (e: Exception) {}; mux.release()
            vex.release()
        }
    }
}
