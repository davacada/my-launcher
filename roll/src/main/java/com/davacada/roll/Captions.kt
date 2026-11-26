package com.davacada.roll

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Auto captions: the edit's sound (clips as cut, plus voiceovers) is split where people pause, and each
 * phrase is handed to the phone's speech recogniser. The words become caption lines on the timeline,
 * timed to the phrase, with a word-by-word highlight.
 */
object Captions {
    const val RATE = 16_000

    class Style(val id: String, val name: String, val apply: (TextItem) -> Unit)
    val STYLES = listOf(
        Style("clean", "Clean") { it.font = "montserrat"; it.style = "shadow"; it.color = 0xFFFFFFFF.toInt(); it.highlight = 0xFFFFFFFF.toInt(); it.size = 0.045f },
        Style("karaoke", "Karaoke") { it.font = "montserrat"; it.style = "shadow"; it.color = 0xFFFFFFFF.toInt(); it.highlight = 0xFFF2A541.toInt(); it.size = 0.05f },
        Style("bold", "Bold") { it.font = "anton"; it.style = "outline"; it.color = 0xFFFFE14D.toInt(); it.highlight = 0xFFFFFFFF.toInt(); it.size = 0.06f },
        Style("box", "Box") { it.font = "archivo"; it.style = "box"; it.boxColor = 0xD9000000.toInt(); it.color = 0xFFFFFFFF.toInt(); it.highlight = 0xFF6EE7A8.toInt(); it.size = 0.042f },
        Style("pill", "Pill") { it.font = "montserrat"; it.style = "pill"; it.boxColor = 0xF2FFFFFF.toInt(); it.color = 0xFF0A0A0A.toInt(); it.highlight = 0xFFFF5A5A.toInt(); it.size = 0.042f },
        Style("type", "Typewriter") { it.font = "spacemono"; it.style = "shadow"; it.color = 0xFFFFFFFF.toInt(); it.highlight = 0xFFFFE14D.toInt(); it.size = 0.04f; it.animIn = "type" },
    )
    val LANGS = listOf("sl-SI" to "Slovenščina", "en-US" to "English", "es-ES" to "Español", "ru-RU" to "Русский", "de-DE" to "Deutsch", "it-IT" to "Italiano", "hr-HR" to "Hrvatski")

    /** The edit's sound as 16 kHz mono, in edit time. Run off the main thread. */
    fun sound(ctx: Context, p: Project, progress: (Int) -> Unit): ShortArray {
        val out = ShortArray((p.lengthMs * RATE / 1000).toInt())
        var t = 0L
        p.clips.forEachIndexed { i, c ->
            if (c.volume > 0f && c.still.isEmpty() && c.revOf == null) {
                val pcm = Audio.decode(ctx, c.uri, c.startMs, c.lengthMs, mono = true, rate = RATE, speed = c.speed)
                val at = (t * RATE / 1000).toInt()
                for (k in pcm.indices) { val o = at + k; if (o < out.size) out[o] = pcm[k] }
            }
            t += c.lengthMs
            progress((i + 1) * 40 / p.clips.size)
        }
        for (v in p.audio.filter { it.kind == "voice" }) {
            val pcm = Audio.decode(ctx, v.uri, v.inMs, v.lengthMs, mono = true, rate = RATE)
            val at = (v.startMs * RATE / 1000).toInt()
            for (k in pcm.indices) { val o = at + k; if (o < out.size) out[o] = (out[o] + pcm[k]).coerceIn(-32768, 32767).toShort() }
        }
        return out
    }

    /** Where people speak: [start, end) in ms, split at pauses, at most 9 s each. */
    fun phrases(pcm: ShortArray): List<Pair<Long, Long>> {
        val win = RATE / 50                      // 20 ms
        val n = pcm.size / win
        if (n == 0) return emptyList()
        val e = FloatArray(n) { i -> var s = 0.0; for (k in i * win until (i + 1) * win) s += pcm[k].toDouble() * pcm[k]; Math.sqrt(s / win).toFloat() }
        val sorted = e.sorted()
        val floor = sorted[(n * 0.15f).toInt().coerceIn(0, n - 1)]
        val thr = max(floor * 3f, 350f)
        val on = BooleanArray(n) { e[it] > thr }
        // close short gaps, drop tiny blips
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < n) {
            if (!on[i]) { i++; continue }
            var j = i; var gap = 0
            while (j < n && gap < 18) { if (on[j]) gap = 0 else gap++; j++ }
            val end = j - gap
            if (end - i >= 12) out.add(max(0, i - 5) to min(n, end + 6))
            i = j
        }
        // split long phrases at their quietest moment
        val res = ArrayList<Pair<Long, Long>>()
        fun add(a: Int, b: Int) {
            if (b - a <= 450) { res.add(a * 20L to b * 20L); return }
            var q = a + 150; for (k in a + 150 until b - 150) if (e[k] < e[q]) q = k
            add(a, q); add(q, b)
        }
        out.forEach { (a, b) -> add(a, b) }
        return res
    }

    class Run(val cancel: () -> Unit)

    /**
     * Recognises each phrase in turn (must be called on the main thread). [done] gets the captions,
     * or an error message. Needs Android 13 for reading audio that isn't the microphone.
     */
    fun recognise(ctx: Context, pcm: ShortArray, phrases: List<Pair<Long, Long>>, lang: String, progress: (Int) -> Unit, done: (List<TextItem>?, String?) -> Unit): Run {
        val main = Handler(Looper.getMainLooper())
        var cancelled = false
        if (Build.VERSION.SDK_INT < 33) { done(null, "Auto captions need Android 13 or newer"); return Run {} }
        if (phrases.isEmpty()) { done(null, "No speech found in the sound of this edit"); return Run {} }
        val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(ctx)) { done(null, "This phone has no speech recogniser (install or update the Google app)"); return Run {} }
        val sr = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx)
        val items = ArrayList<TextItem>()
        var index = 0; var errors = 0; var lastErr = 0
        fun next() {
            if (cancelled) return
            if (index >= phrases.size) {
                sr.destroy()
                if (items.isEmpty()) done(null, when (lastErr) {
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "The speech recogniser doesn't have this language yet. Try another, or download it in the phone's voice typing settings."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> "The speech recogniser needs the internet for this language"
                    0, SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Couldn't make out any words"
                    else -> "The speech recogniser stopped (error $lastErr)"
                }) else done(items, null)
                return
            }
            val (a, b) = phrases[index]
            val pipe = ParcelFileDescriptor.createPipe()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 400L)
            var finished = false
            fun finish(text: String?) {
                if (finished) return; finished = true
                try { pipe[0].close() } catch (e: Exception) {}
                if (!text.isNullOrBlank()) items.addAll(lines(text.trim(), a, b))
                index++; progress(40 + index * 60 / phrases.size)
                main.post { next() }
            }
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onResults(r: Bundle?) { finish(r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()) }
                override fun onError(error: Int) { lastErr = error; errors++; finish(null) }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(r: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            sr.startListening(intent)
            // feed the phrase (with a little silence after it) into the pipe, then close it
            Thread {
                try {
                    FileOutputStream(pipe[1].fileDescriptor).use { os ->
                        val from = (a * RATE / 1000).toInt().coerceIn(0, pcm.size); val to = (b * RATE / 1000).toInt().coerceIn(from, pcm.size)
                        val bb = ByteBuffer.allocate((to - from + RATE / 2) * 2).order(ByteOrder.LITTLE_ENDIAN)
                        for (k in from until to) bb.putShort(pcm[k])
                        os.write(bb.array())
                    }
                } catch (e: Exception) {}
                try { pipe[1].close() } catch (e: Exception) {}
            }.start()
            main.postDelayed({ if (!finished) { try { sr.stopListening() } catch (e: Exception) {}; main.postDelayed({ if (!finished) { sr.cancel(); finish(null) } }, 4000) } }, (b - a) + 6000)
        }
        next()
        return Run { cancelled = true; try { sr.destroy() } catch (e: Exception) {} }
    }

    /** A phrase's words as caption lines of up to ~5 words, each word given its share of the time. */
    fun lines(text: String, a: Long, b: Long): List<TextItem> {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val weight = words.map { it.length + 2 }; val total = weight.sum().toFloat()
        val starts = ArrayList<Long>(); var acc = 0f
        for (w in weight) { starts.add(a + ((b - a) * acc / total).toLong()); acc += w }
        val out = ArrayList<TextItem>()
        var i = 0
        while (i < words.size) {
            var j = i; var chars = 0
            while (j < words.size && j - i < 6 && chars + words[j].length <= 30) { chars += words[j].length + 1; j++ }
            if (j == i) j = i + 1
            val s = starts[i]; val e = if (j < words.size) starts[j] else b
            out.add(TextItem(words.subList(i, j).joinToString(" "), s, max(e, s + 400)).apply {
                caption = true; y = 0.8f; animIn = "none"; animOut = "none"
                this.words.addAll((i until j).map { starts[it] - s })
            })
            i = j
        }
        return out
    }
}
