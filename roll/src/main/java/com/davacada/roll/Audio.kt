package com.davacada.roll

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** Music or a voiceover on the edit: [lengthMs] of the file from [inMs], placed at [startMs] (edit time). */
class AudioItem(
    var uri: String, var name: String, var kind: String,     // "music" or "voice"
    var startMs: Long, var inMs: Long, var lengthMs: Long, var sourceMs: Long,
    val id: String = UUID.randomUUID().toString(),
) {
    var volume = 1f
    var fadeIn = 0L; var fadeOut = 0L
    val endMs get() = startMs + lengthMs

    fun json(): JSONObject = JSONObject().put("id", id).put("u", uri).put("n", name).put("k", kind).put("s", startMs).put("in", inMs)
        .put("len", lengthMs).put("src", sourceMs).put("v", volume.toDouble()).put("fi", fadeIn).put("fo", fadeOut)

    companion object {
        fun from(o: JSONObject) = AudioItem(o.getString("u"), o.optString("n"), o.optString("k", "music"), o.optLong("s"), o.optLong("in"),
            o.optLong("len"), o.optLong("src"), o.optString("id", UUID.randomUUID().toString())).apply {
            volume = o.optDouble("v", 1.0).toFloat(); fadeIn = o.optLong("fi"); fadeOut = o.optLong("fo")
        }
    }
}

/**
 * Sound: reads audio files to plain samples, mixes music and voiceovers into one soundtrack for the whole
 * edit (with fades, volume, and music ducking under the voice), and writes WAV files.
 */
object Audio {
    const val RATE = 48_000

    /** [lenMs] of [uri] from [fromMs], as interleaved stereo 16-bit at 48 kHz ([mono]: one channel at [rate]). */
    fun decode(ctx: Context, uri: String, fromMs: Long, lenMs: Long, mono: Boolean = false, rate: Int = RATE, speed: Float = 1f): ShortArray {
        val ex = MediaExtractor()
        try { ex.setDataSource(ctx, Uri.parse(uri), null) } catch (e: Exception) { ex.release(); return ShortArray(0) }
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        if (track == null) { ex.release(); return ShortArray(0) }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        ex.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(fmt, null, null, 0); dec.start()
        var enc = if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
        val outCh = if (mono) 1 else 2
        val endUs = (fromMs + (lenMs * speed).toLong()) * 1000
        // gather the decoded samples (as floats, per output channel), then resample
        val raw = FloatArrayList()
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false
        try {
            while (!outDone) {
                if (!inDone) {
                    val i = dec.dequeueInputBuffer(5000)
                    if (i >= 0) {
                        val n = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                        if (n < 0 || ex.sampleTime > endUs + 200_000) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 5000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = dec.outputFormat
                    ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) enc = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else if (o >= 0) {
                    val buf = dec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    // 16-bit, float, 24-bit (some phones record it) or 32-bit samples
                    val bps = when (enc) { AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4; AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3; else -> 2 }
                    fun sample(): Float = when (enc) {
                        AudioFormat.ENCODING_PCM_FLOAT -> buf.getFloat()
                        AudioFormat.ENCODING_PCM_32BIT -> buf.getInt() / 2147483648f
                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> { val b0 = buf.get().toInt() and 255; val b1 = buf.get().toInt() and 255; val b2 = buf.get().toInt(); ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f }
                        else -> buf.getShort() / 32768f
                    }
                    val frames = info.size / (bps * ch)
                    var t = info.presentationTimeUs
                    val step = 1_000_000.0 / inRate
                    for (k in 0 until frames) {
                        val l = sample(); val r = if (ch > 1) sample() else l
                        for (x in 2 until ch) sample()
                        val ts = t + (k * step).toLong()
                        if (ts >= fromMs * 1000 && ts < endUs) { if (outCh == 1) raw.add((l + r) / 2) else { raw.add(l); raw.add(r) } }
                    }
                    dec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
        } catch (e: Exception) {
        } finally { try { dec.stop() } catch (e: Exception) {}; dec.release(); ex.release() }
        // resample (and apply speed) by straight-line interpolation
        val inFrames = raw.size / outCh
        val ratio = inRate.toDouble() * speed / rate
        val outFrames = min((lenMs * rate / 1000).toInt(), (inFrames / ratio).toInt())
        val out = ShortArray(max(0, outFrames) * outCh)
        for (k in 0 until outFrames) {
            val pos = k * ratio; val a = pos.toInt(); val f = (pos - a).toFloat()
            val b = min(a + 1, inFrames - 1)
            for (c in 0 until outCh) {
                val v = raw.get(a * outCh + c) * (1 - f) + raw.get(b * outCh + c) * f
                out[k * outCh + c] = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return out
    }

    /** How long an audio file is. */
    fun lengthOf(ctx: Context, uri: String): Long = try {
        val mr = android.media.MediaMetadataRetriever(); mr.setDataSource(ctx, Uri.parse(uri))
        val d = mr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        mr.release(); d
    } catch (e: Exception) { 0L }

    fun key(p: Project) = p.audio.joinToString("|") { it.json().toString() } + "#" + p.lengthMs + p.duck

    /**
     * The soundtrack for the whole edit: every music and voice item mixed, as a 48 kHz stereo WAV.
     * Music dips under voiceovers when [Project.duck] is on. Returns null when there's nothing to mix.
     */
    fun mix(ctx: Context, p: Project): File? {
        if (p.audio.isEmpty() || p.lengthMs <= 0) return null
        val out = File(File(ctx.cacheDir, "mix").apply { mkdirs() }, "mix-" + key(p).hashCode().toUInt().toString(16) + ".wav")
        if (out.exists() && out.length() > 44) return out
        File(ctx.cacheDir, "mix").listFiles()?.forEach { if (it != out) it.delete() }
        val n = (p.lengthMs * RATE / 1000).toInt()
        val acc = FloatArray(n * 2)
        val voices = p.audio.filter { it.kind == "voice" }.map { it.startMs to it.endMs }
        fun duckAt(ms: Long): Float {
            if (!p.duck || voices.isEmpty()) return 1f
            var g = 1f
            for ((a, b) in voices) {
                val d = when { ms in a..b -> 0f; ms < a -> (a - ms).toFloat(); else -> (ms - b).toFloat() }
                g = min(g, 0.28f + 0.72f * (d / 350f).coerceIn(0f, 1f))
            }
            return g
        }
        for (it in p.audio) {
            val len = min(it.lengthMs, p.lengthMs - it.startMs)
            if (len <= 0) continue
            val pcm = decode(ctx, it.uri, it.inMs, len)
            android.util.Log.i("ROLLTEST", "mix ${it.name}: ${pcm.size / 2} frames, peak ${pcm.maxOfOrNull { s -> kotlin.math.abs(s.toInt()) } ?: 0}")
            val at = (it.startMs * RATE / 1000).toInt()
            val frames = pcm.size / 2
            val fi = it.fadeIn * RATE / 1000; val fo = it.fadeOut * RATE / 1000
            for (k in 0 until frames) {
                val o = at + k; if (o >= n) break
                var g = it.volume
                if (fi > 0 && k < fi) g *= k.toFloat() / fi
                if (fo > 0 && k > frames - fo) g *= (frames - k).toFloat() / fo
                if (it.kind == "music" && k % 480 == 0) lastDuck = duckAt(o * 1000L / RATE)
                if (it.kind == "music") g *= lastDuck
                acc[o * 2] += pcm[k * 2] / 32768f * g; acc[o * 2 + 1] += pcm[k * 2 + 1] / 32768f * g
            }
        }
        // a soft limiter so loud overlaps don't crackle
        val pcm = ShortArray(n * 2) { i -> val v = acc[i]; val s = if (v > 0.9f) 0.9f + (v - 0.9f) / (1 + (v - 0.9f) * 10) else if (v < -0.9f) -0.9f + (v + 0.9f) / (1 - (v + 0.9f) * 10) else v; (s * 32767).toInt().coerceIn(-32768, 32767).toShort() }
        writeWav(out, pcm, 2, RATE)
        return out
    }
    private var lastDuck = 1f

    fun writeWav(f: File, pcm: ShortArray, channels: Int, rate: Int) {
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(0)
            val data = pcm.size * 2
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt(36 + data); h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(channels.toShort()); h.putInt(rate); h.putInt(rate * channels * 2); h.putShort((channels * 2).toShort()); h.putShort(16)
            h.put("data".toByteArray()); h.putInt(data)
            raf.write(h.array())
            val b = ByteBuffer.allocate(data).order(ByteOrder.LITTLE_ENDIAN)
            b.asShortBuffer().put(pcm)
            raf.write(b.array())
        }
    }

    /** A growable float list without boxing. */
    class FloatArrayList {
        private var a = FloatArray(1 shl 16); var size = 0; private set
        fun add(v: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
        fun get(i: Int) = a[i]
    }
}

/** Records a voiceover from the microphone to a WAV file (48 kHz mono). */
class VoiceRecorder(private val out: File) {
    @Volatile private var running = false
    private var thread: Thread? = null
    var level = 0f; private set

    @android.annotation.SuppressLint("MissingPermission")
    fun start(): Boolean {
        val min = android.media.AudioRecord.getMinBufferSize(Audio.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try { android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, Audio.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, 8192) * 2) } catch (e: Exception) { return false }
        if (rec.state != android.media.AudioRecord.STATE_INITIALIZED) return false
        running = true
        thread = Thread {
            val chunks = ArrayList<ShortArray>()
            val buf = ShortArray(2400)
            rec.startRecording()
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    chunks.add(buf.copyOf(n))
                    var peak = 0; for (i in 0 until n) peak = max(peak, kotlin.math.abs(buf[i].toInt()))
                    level = peak / 32768f
                }
            }
            rec.stop(); rec.release()
            val all = ShortArray(chunks.sumOf { it.size }); var o = 0
            for (c in chunks) { c.copyInto(all, o); o += c.size }
            Audio.writeWav(out, all, 1, Audio.RATE)
        }.apply { start() }
        return true
    }

    /** Stops and finishes the file (waits for it). */
    fun stop() { running = false; thread?.join(3000) }
}
