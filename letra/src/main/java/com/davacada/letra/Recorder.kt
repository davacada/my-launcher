package com.davacada.letra

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlin.math.sqrt

/**
 * Records a take (48 kHz WAV) and, at the same time, writes out the words it hears.
 * The microphone is opened once; the speech recogniser is fed a copy of the same sound
 * (Android 13+), so recording and transcribing never fight over the mic.
 */
class Recorder(private val ctx: Context, private val out: File, private var language: String) {
    interface Listener {
        fun level(rms: Float)
        fun partial(text: String)
        fun heard(atMs: Long, text: String)
        fun transcribing(ok: Boolean, why: String)
    }

    var listener: Listener? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var paused = false
    private var thread: Thread? = null
    private var rec: AudioRecord? = null
    private var raf: RandomAccessFile? = null
    private var bytes = 0L
    @Volatile var elapsedMs = 0L; private set

    // speech
    private var sr: SpeechRecognizer? = null
    @Volatile private var pipe: OutputStream? = null
    private var sessionStart = 0L
    private var lastPartial = ""
    private val canFeed = Build.VERSION.SDK_INT >= 33
    private var speechOn = true

    companion object { const val RATE = 48000 }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE / 5 * 2))
        } catch (e: Exception) { return false }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        rec = r
        raf = RandomAccessFile(out, "rw").apply { setLength(0); write(ByteArray(44)) }
        running = true
        r.startRecording()
        thread = Thread({ loop() }, "letra-rec").apply { start() }
        startSpeech()
        return true
    }

    private fun loop() {
        val buf = ShortArray(RATE / 20)       // 50 ms
        val bb = ByteArray(buf.size * 2)
        val down = ByteArray(buf.size / 3 * 2) // 16 kHz copy for the recogniser
        while (running) {
            val n = rec?.read(buf, 0, buf.size) ?: break
            if (n <= 0) continue
            if (paused) continue
            var sum = 0.0
            for (i in 0 until n) {
                val s = buf[i].toInt()
                bb[2 * i] = (s and 0xff).toByte(); bb[2 * i + 1] = (s shr 8 and 0xff).toByte()
                sum += (s * s).toDouble()
            }
            try { raf?.write(bb, 0, n * 2) } catch (e: Exception) {}
            bytes += n * 2
            elapsedMs = bytes * 1000 / (RATE * 2)
            val rms = (sqrt(sum / n) / 32768.0).toFloat()
            main.post { listener?.level(rms) }
            // average every 3 samples -> 16 kHz
            val m = n / 3
            for (i in 0 until m) {
                val s = (buf[3 * i] + buf[3 * i + 1] + buf[3 * i + 2]) / 3
                down[2 * i] = (s and 0xff).toByte(); down[2 * i + 1] = (s shr 8 and 0xff).toByte()
            }
            try { pipe?.write(down, 0, m * 2) } catch (e: Exception) { pipe = null }
        }
    }

    /** Switches the language words are written out in (takes effect straight away). */
    fun setLanguage(l: String) {
        language = l
        if (!running || paused) return
        speechOn = true; errors = 0
        val p = pipe; pipe = null
        try { p?.close() } catch (e: Exception) {}
        main.post { try { sr?.cancel() } catch (e: Exception) {}; startSpeech() }
    }

    fun pause(p: Boolean) { paused = p; if (p) stopSpeechSession() else startSpeech() }
    val isPaused get() = paused

    /** Stops and finishes the WAV file. Returns its length in ms. */
    fun stop(): Long {
        running = false
        thread?.join(800)
        try { rec?.stop() } catch (e: Exception) {}
        rec?.release(); rec = null
        speechOn = false
        stopSpeechSession()
        main.post { sr?.destroy(); sr = null }
        raf?.let { f ->
            try {
                f.seek(0); f.write(header(bytes)); f.close()
            } catch (e: Exception) {}
        }
        raf = null
        return elapsedMs
    }

    private fun header(data: Long): ByteArray {
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt((36 + data).toInt()); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(RATE); h.putInt(RATE * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(data.toInt())
        return h.array()
    }

    // ---------------- speech ----------------

    private fun startSpeech() {
        if (!speechOn || !running) return
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { main.post { listener?.transcribing(false, "No speech recogniser on this phone") }; speechOn = false; return }
        if (!canFeed) { main.post { listener?.transcribing(false, "Writing out words while recording needs Android 13") }; speechOn = false; return }
        main.post {
            if (!running || paused) return@post
            if (sr == null) sr = SpeechRecognizer.createSpeechRecognizer(ctx).apply { setRecognitionListener(rl) }
            val p = try { ParcelFileDescriptor.createPipe() } catch (e: Exception) { return@post }
            val old = pipe
            pipe = ParcelFileDescriptor.AutoCloseOutputStream(p[1])
            try { old?.close() } catch (e: Exception) {}
            sessionStart = elapsedMs
            lastPartial = ""
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, p[0])
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
            }
            try { sr?.startListening(i) } catch (e: Exception) { listener?.transcribing(false, "Couldn't start writing out words") }
        }
    }

    private fun stopSpeechSession() {
        val p = pipe; pipe = null
        try { p?.close() } catch (e: Exception) {}
        main.post { try { sr?.stopListening() } catch (e: Exception) {} }
    }

    private var errors = 0
    private val rl = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { errors = 0; listener?.transcribing(true, "") }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(b: Bundle?) {
            val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            lastPartial = t; listener?.partial(t)
        }
        override fun onResults(b: Bundle?) {
            val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.ifBlank { null } ?: lastPartial.ifBlank { null }
            if (t != null) listener?.heard(sessionStart, t)
            listener?.partial("")
            if (running && !paused && speechOn) startSpeech()
        }
        override fun onError(error: Int) {
            if (lastPartial.isNotBlank()) { listener?.heard(sessionStart, lastPartial); listener?.partial("") }
            if (!running || paused || !speechOn) return
            if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                listener?.transcribing(false, "This language isn't downloaded for offline speech"); speechOn = false; return
            }
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { listener?.transcribing(false, "Speech needs the microphone permission"); speechOn = false; return }
            if (++errors > 6) { listener?.transcribing(false, "Speech stopped; the recording goes on"); speechOn = false; return }
            main.postDelayed({ startSpeech() }, if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 600L else 150L)
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}

fun fmtTime(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

/** A short wooden click for the metronome. */
object Click {
    private var track: android.media.AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var on = false
    var onBeat: ((Int) -> Unit)? = null

    fun start(bpm: Int) {
        stop(); if (bpm <= 0) return
        on = true
        val rate = 44100
        val len = rate / 30
        fun tone(f: Double, amp: Double) = ShortArray(len) { i -> val e = Math.exp(-i / (len / 6.0)); (Math.sin(2 * Math.PI * f * i / rate) * e * amp * 32767).toInt().toShort() }
        val hi = tone(1600.0, 0.6); val lo = tone(1000.0, 0.45)
        val t = android.media.AudioTrack.Builder()
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(rate).setTransferMode(android.media.AudioTrack.MODE_STREAM).build()
        track = t; t.play()
        val beat = rate * 60 / bpm
        val main = Handler(Looper.getMainLooper())
        thread = Thread {
            var n = 0
            val silence = ShortArray(beat)
            while (on) {
                val b = n % 4
                val c = if (b == 0) hi else lo
                System.arraycopy(c, 0, silence, 0, minOf(c.size, beat))
                java.util.Arrays.fill(silence, minOf(c.size, beat), beat, 0)
                main.post { onBeat?.invoke(b) }
                t.write(silence, 0, beat)
                n++
            }
        }.apply { start() }
    }

    fun stop() { on = false; thread?.join(300); thread = null; try { track?.stop(); track?.release() } catch (e: Exception) {}; track = null }
    val running get() = on
}

