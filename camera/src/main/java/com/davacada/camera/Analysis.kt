package com.davacada.camera

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Histogram, zebras, focus peaking and false colour worked out from a small copy of the
 * on-screen picture, so the camera never has to run an extra stream for them.
 */
class Analysis(val w: Int = 160, val h: Int = 90) {
    val bitmap: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(w * h)
    private val luma = IntArray(w * h)
    private val smooth = FloatArray(64)
    val hist = IntArray(64)
    var mean = 0.5f
        private set

    /** Reads [bitmap] (already filled from the preview) and returns overlay pixels, or null if no overlay is on. */
    fun run(flags: OverlayFlags): IntArray? {
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val raw = IntArray(64)
        var sum = 0L
        for (i in pixels.indices) {
            val p = pixels[i]
            val y = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
            luma[i] = y
            raw[y shr 2]++
            sum += y
        }
        mean = sum / (w * h).toFloat() / 255f
        // Ease towards the new frame so the graph glides instead of jittering.
        for (i in 0 until 64) {
            smooth[i] = smooth[i] * 0.55f + raw[i] * 0.45f
            hist[i] = smooth[i].toInt()
        }

        if (!flags.zebras && !flags.peaking && !flags.falseColor) return null
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val v = luma[i]
            var c = 0
            if (flags.falseColor) c = falseColor(v)
            if (flags.peaking && x in 1 until w - 1 && y in 1 until h - 1) {
                val g = abs(luma[i + 1] - luma[i - 1]) + abs(luma[i + w] - luma[i - w])
                if (g > 70) c = 0xFF33E07A.toInt()
            }
            if (flags.zebras && v >= 242 && ((x + y) / 2) % 3 == 0) c = 0xD0FFFFFF.toInt()
            out[i] = c
        }
        return out
    }

    private fun falseColor(v: Int): Int = when {
        v < 10 -> 0xC0702090.toInt()        // crushed blacks: purple
        v < 40 -> 0xA02F6FD6.toInt()        // shadows: blue
        v in 110..135 -> 0xA04CAF50.toInt() // mid grey: green
        v in 165..185 -> 0xA0F48FB1.toInt() // a stop over: pink
        v > 248 -> 0xC0E0453A.toInt()       // clipped: red
        v > 230 -> 0xA0F2C94C.toInt()       // near clipping: yellow
        else -> 0
    }
}
