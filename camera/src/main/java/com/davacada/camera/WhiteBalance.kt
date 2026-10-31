package com.davacada.camera

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/**
 * The white-balance slider's scale: 4000K is neutral (the camera's daylight balance). Lower numbers
 * make the picture cooler, higher numbers warmer, like the kelvin dial on a cinema camera.
 */
object WhiteBalance {
    const val MIN = 2500
    const val MAX = 6500
    const val STEP = 50
    const val NEUTRAL = 4000
    private const val DAYLIGHT = 5500.0

    /** Converts the slider value to a real light temperature, keeping equal steps in mireds. */
    fun realKelvin(k: Int): Double = 1e6 / (1e6 / DAYLIGHT + 1e6 / k - 1e6 / NEUTRAL)

    /** Red and blue gain multipliers (relative to daylight) for light of temperature [t]. */
    fun factors(t: Double): Pair<Float, Float> {
        val d = rgb(DAYLIGHT); val w = rgb(t)
        val r = (d[0] / w[0]) / (d[1] / w[1])
        val b = (d[2] / w[2]) / (d[1] / w[1])
        return r.toFloat() to b.toFloat()
    }

    /** Approximate colour of a black body at [t] kelvin (Tanner Helland's fit), 0..255 per channel. */
    private fun rgb(t: Double): DoubleArray {
        val x = t / 100.0
        val r = if (x <= 66) 255.0 else (329.698727446 * (x - 60).pow(-0.1332047592)).coerceIn(1.0, 255.0)
        val g = if (x <= 66) (99.4708025861 * ln(x) - 161.1195681661).coerceIn(1.0, 255.0)
                else (288.1221695283 * (x - 60).pow(-0.0755148492)).coerceIn(1.0, 255.0)
        val b = if (x >= 66) 255.0 else if (x <= 19) 1.0 else (138.5177312231 * ln(x - 10) - 305.0447927307).coerceIn(1.0, 255.0)
        return doubleArrayOf(r, g, b)
    }
}

/** Slow motion: a clip shot at a high frame rate is re-timed so it plays back at [playFps]. */
object SlowMo {
    fun save(ctx: Context, src: File, shotFps: Int, playFps: Int): Uri? {
        val factor = shotFps.toDouble() / playFps
        val ex = MediaExtractor().apply { setDataSource(src.absolutePath) }
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ?: run { ex.release(); return null }
        val fmt = ex.getTrackFormat(track)
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, playFps)
        ex.selectTrack(track)
        val rotation = try {
            MediaMetadataRetriever().run { setDataSource(src.absolutePath); val r = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION); release(); r?.toIntOrNull() ?: 0 }
        } catch (e: Exception) { 0 }

        val name = "PRO_SLOMO_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ProCamera")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: run { ex.release(); return null }
        try {
            ctx.contentResolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
                val mux = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                mux.setOrientationHint(rotation)
                val out = mux.addTrack(fmt)
                mux.start()
                val cap = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 8 * 1024 * 1024
                val buf = ByteBuffer.allocateDirect(cap.coerceAtLeast(2 * 1024 * 1024))
                val info = MediaCodec.BufferInfo()
                var first = -1L
                while (true) {
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    val t = ex.sampleTime
                    if (first < 0) first = t
                    info.set(0, n, ((t - first) * factor).toLong(), if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    mux.writeSampleData(out, buf, info)
                    ex.advance()
                }
                mux.stop(); mux.release()
            }
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            ctx.contentResolver.delete(uri, null, null)
            throw e
        } finally { ex.release() }
    }
}
