package com.davacada.cuerda

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

const val RATE = 44100

/** The microphone: a background thread that hands overlapping blocks of samples to [onBlock]. */
class Mic(private val size: Int, private val hop: Int, private val onBlock: (FloatArray) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, max(min, size * 4))
                    .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                    ?: AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, max(min, size * 4))
            } catch (e: Exception) { running = false; return@Thread }
            if (rec.state != AudioRecord.STATE_INITIALIZED) { running = false; return@Thread }
            val ring = FloatArray(size)
            val chunk = FloatArray(hop)
            rec.startRecording()
            try {
                while (running) {
                    var got = 0
                    while (got < hop && running) {
                        val n = rec.read(chunk, got, hop - got, AudioRecord.READ_BLOCKING)
                        if (n <= 0) break
                        got += n
                    }
                    if (!running) break
                    System.arraycopy(ring, hop, ring, 0, size - hop)
                    System.arraycopy(chunk, 0, ring, size - hop, hop)
                    onBlock(ring.copyOf())
                }
            } finally {
                runCatching { rec.stop() }; rec.release()
            }
        }.apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop() { running = false; thread = null }
    val isRunning get() = running
}

/** YIN pitch detection: returns (frequency, clarity 0..1) or null for silence/noise. */
object Yin {
    fun detect(x: FloatArray, minHz: Double, maxHz: Double): Pair<Double, Double>? {
        var rms = 0.0
        for (v in x) rms += v * v
        rms = sqrt(rms / x.size)
        if (rms < 0.004) return null
        val tauMin = max(2, (RATE / maxHz).toInt())
        val tauMax = min(x.size / 2 - 2, (RATE / minHz).toInt())
        val w = x.size / 2
        val d = FloatArray(tauMax + 2)
        for (tau in 1..tauMax + 1) {
            var s = 0f
            var i = 0
            while (i < w) { val df = x[i] - x[i + tau]; s += df * df; i++ }
            d[tau] = s
        }
        // cumulative mean normalised difference
        val c = FloatArray(tauMax + 2); c[0] = 1f
        var run = 0f
        for (tau in 1..tauMax + 1) { run += d[tau]; c[tau] = if (run == 0f) 1f else d[tau] * tau / run }
        var tau = -1
        var t = tauMin
        while (t <= tauMax) {
            if (c[t] < 0.12f) {
                while (t + 1 <= tauMax && c[t + 1] < c[t]) t++
                tau = t; break
            }
            t++
        }
        if (tau < 0) {
            // no dip under the threshold: take the global minimum if it is reasonably clear
            var best = tauMin
            for (k in tauMin..tauMax) if (c[k] < c[best]) best = k
            if (c[best] > 0.3f) return null
            tau = best
        }
        // parabolic interpolation
        val betterTau = if (tau in 1 until tauMax) {
            val s0 = c[tau - 1]; val s1 = c[tau]; val s2 = c[tau + 1]
            val den = s0 + s2 - 2 * s1
            if (den != 0f) tau + (s0 - s2) / (2 * den) else tau.toFloat()
        } else tau.toFloat()
        val f = RATE / betterTau.toDouble()
        if (f < minHz || f > maxHz) return null
        return f to (1.0 - c[tau]).coerceIn(0.0, 1.0)
    }
}

object Fft {
    /** In-place radix-2 FFT on re/im (length power of two). */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * Listens to music and works out the chord, the key, the tempo, and (for a lone voice) chords that fit the melody.
 * Feed it blocks of 8192 samples every 2048 samples.
 */
class ChordEar {
    data class Result(
        val chord: Chords.Chord?, val confidence: Double, val suggested: Boolean,
        val key: Triple<Int, Boolean, Double>?, val bpm: Int?, val loud: Boolean, val melodyNote: Int?,
        val level: Double = 0.0,
    )

    private val n = 8192
    private val window = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
    private val smooth = DoubleArray(12)
    private val keyProfile = DoubleArray(12)
    private var lastMag: DoubleArray? = null
    private val flux = ArrayDeque<Double>()
    private var current: Chords.Chord? = null
    private var candidate: Chords.Chord? = null
    private var candidateCount = 0
    private val melody = ArrayDeque<Pair<Int, Long>>()   // pitch class, time
    private var frames = 0

    fun reset() {
        smooth.fill(0.0); keyProfile.fill(0.0); lastMag = null; flux.clear()
        current = null; candidate = null; candidateCount = 0; melody.clear(); frames = 0
    }

    fun feed(x: FloatArray): Result {
        frames++
        var rms = 0.0
        for (v in x) rms += v * v
        rms = sqrt(rms / x.size)
        val loud = rms > 0.006

        val re = DoubleArray(n) { x[it] * window[it] }
        val im = DoubleArray(n)
        Fft.fft(re, im)
        val half = n / 2
        val mag = DoubleArray(half) { sqrt(re[it] * re[it] + im[it] * im[it]) }

        // onset strength (spectral flux) for tempo
        lastMag?.let { lm ->
            var f = 0.0
            for (i in 2 until 700) { val dlt = ln(1 + mag[i]) - ln(1 + lm[i]); if (dlt > 0) f += dlt }
            flux.addLast(f); while (flux.size > 200) flux.removeFirst()
        }
        lastMag = mag

        if (!loud) return Result(current, 0.0, false, keyOrNull(), tempo(), false, null, rms)

        // chroma: 55 Hz – 2 kHz, log-compressed, peaks only
        val chroma = DoubleArray(12)
        val binHz = RATE.toDouble() / n
        val lo = (55 / binHz).toInt(); val hi = (2000 / binHz).toInt()
        for (i in lo..hi) {
            val m = mag[i]
            if (m <= mag[i - 1] || m < mag[i + 1]) continue
            // refine peak position
            val a = mag[i - 1]; val c = mag[i + 1]
            val p = 0.5 * (a - c) / (a - 2 * m + c).let { if (it == 0.0) 1e-9 else it }
            val f = (i + p) * binHz
            val midi = 69 + 12 * log2(f / Notes.a4)
            val pc = ((Math.round(midi).toInt() % 12) + 12) % 12
            val dev = abs(midi - Math.round(midi))
            val w = ln(1 + 50 * m) * (1 - dev) * (if (f < 120) 0.6 else 1.0)
            chroma[pc] += w
        }
        val sum = chroma.sum()
        if (sum <= 0) return Result(current, 0.0, false, keyOrNull(), tempo(), true, null, rms)
        for (i in 0 until 12) chroma[i] /= sum
        for (i in 0 until 12) { smooth[i] = smooth[i] * 0.55 + chroma[i] * 0.45; keyProfile[i] = keyProfile[i] * 0.995 + chroma[i] }

        // a single voice: one pitch class clearly on top → melody mode
        val sorted = chroma.sortedDescending()
        val mono = sorted[0] > 0.42 && sorted[0] > 2.6 * sorted[1]
        val now = System.currentTimeMillis()
        if (mono) {
            val pc = chroma.indices.maxBy { chroma[it] }
            melody.addLast(pc to now)
        }
        while (melody.isNotEmpty() && now - melody.first().second > 2600) melody.removeFirst()
        val melodyNote = if (mono) chroma.indices.maxBy { chroma[it] } else null

        val key = keyOrNull()
        if (mono || (melody.size > 8 && sorted[0] > 0.33)) {
            // suggest a chord that fits the recent melody notes, from the key
            val k = key ?: return Result(current, 0.0, true, null, tempo(), true, melodyNote, rms)
            val counts = DoubleArray(12); melody.forEach { counts[it.first] += 1.0 }
            var bestC: Chords.Chord? = null; var bestS = -1.0
            for ((idx, ch) in Chords.diatonic(k.first, k.second).withIndex()) {
                val notes = ch.notes()
                var s = notes.sumOf { counts[it] } - 0.4 * (counts.sum() - notes.sumOf { counts[it] })
                if (idx == 0) s += 0.6          // home chord breaks ties
                if (s > bestS) { bestS = s; bestC = ch }
            }
            settle(bestC, 3)
            val conf = (bestS / max(1.0, counts.sum())).coerceIn(0.0, 1.0) * 0.8
            return Result(current, conf, true, key, tempo(), true, melodyNote, rms)
        }

        // template matching on the smoothed chroma
        var best: Chords.Chord? = null; var bestScore = -1.0
        val norm = sqrt(smooth.sumOf { it * it })
        for (q in Chords.qualities) for (root in 0 until 12) {
            val t = DoubleArray(12)
            q.intervals.forEachIndexed { i, iv -> t[(root + iv) % 12] = when (i) { 0 -> 1.0; 1, 2 -> 0.85; else -> 0.6 } }
            val tn = sqrt(t.sumOf { it * it })
            var dot = 0.0; for (i in 0 until 12) dot += smooth[i] * t[i]
            var s = dot / (norm * tn) - q.penalty
            // chords from the key are a little more likely
            if (key != null && Chords.diatonic(key.first, key.second).any { it.root == root && it.q.id == q.id }) s += 0.03
            if (s > bestScore) { bestScore = s; best = Chords.Chord(root, q) }
        }
        if (bestScore > 0.55) settle(best, 2)
        val conf = ((bestScore - 0.5) / 0.4).coerceIn(0.0, 1.0)
        return Result(current, conf, false, key, tempo(), true, null, rms)
    }

    /** Only change chord once the new one has won a few frames in a row. */
    private fun settle(c: Chords.Chord?, need: Int) {
        if (c == null) return
        if (current != null && c == current) { candidate = null; candidateCount = 0; return }
        if (c == candidate) candidateCount++ else { candidate = c; candidateCount = 1 }
        if (candidateCount >= need || current == null) { current = c; candidate = null; candidateCount = 0 }
    }

    private fun keyOrNull(): Triple<Int, Boolean, Double>? {
        if (keyProfile.sum() < 20) return null
        val k = Chords.key(keyProfile)
        return if (k.third > 0.45) k else null
    }

    /** Tempo from the autocorrelation of onset strength (hop = 2048 samples ≈ 46 ms). */
    private fun tempo(): Int? {
        if (flux.size < 120) return null
        val f = flux.toDoubleArray()
        val mean = f.average()
        for (i in f.indices) f[i] = max(0.0, f[i] - mean)
        val hopSec = 2048.0 / RATE
        var bestLag = 0; var best = 0.0
        val minLag = (60.0 / 180 / hopSec).toInt(); val maxLag = (60.0 / 60 / hopSec).toInt()
        for (lag in minLag..maxLag) {
            var s = 0.0
            for (i in lag until f.size) s += f[i] * f[i - lag]
            // prefer tempos around 90–130
            val bpm = 60 / (lag * hopSec)
            s *= 1.0 - 0.25 * abs(ln(bpm / 110)).coerceAtMost(1.0)
            if (s > best) { best = s; bestLag = lag }
        }
        if (bestLag == 0 || best <= 0) return null
        return Math.round(60 / (bestLag * hopSec)).toInt()
    }
}

/** A gentle reference tone. */
class Tone {
    @Volatile private var playing = false
    private var thread: Thread? = null
    @Volatile var freq = 440.0

    fun toggle(f: Double): Boolean { if (playing) stop() else start(f); return playing }

    fun start(f: Double) {
        freq = f
        if (playing) return
        playing = true
        thread = Thread {
            val buf = android.media.AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val track = android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(max(buf, 4096 * 4)).build()
            track.play()
            val out = FloatArray(1024)
            var phase = 0.0
            var amp = 0.0
            while (playing) {
                val step = 2 * PI * freq / RATE
                for (i in out.indices) {
                    amp += (0.32 - amp) * 0.002
                    // warm tone: fundamental plus soft harmonics
                    out[i] = (amp * (sin(phase) + 0.35 * sin(2 * phase) + 0.12 * sin(3 * phase)) / 1.47).toFloat()
                    phase += step; if (phase > 2 * PI) phase -= 2 * PI
                }
                track.write(out, 0, out.size, android.media.AudioTrack.WRITE_BLOCKING)
            }
            // fade out
            for (i in out.indices) { amp *= 0.995; out[i] = (amp * sin(phase)).toFloat(); phase += 2 * PI * freq / RATE }
            track.write(out, 0, out.size, android.media.AudioTrack.WRITE_BLOCKING)
            track.stop(); track.release()
        }.apply { start() }
    }

    fun stop() { playing = false; thread = null }
    val isPlaying get() = playing
}

val mainHandler = Handler(Looper.getMainLooper())
