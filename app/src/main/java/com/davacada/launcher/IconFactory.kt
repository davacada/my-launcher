package com.davacada.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build

/**
 * Turns an app's real icon into two bitmaps:
 *  - colour: the icon exactly as the app ships it (shown while pressed)
 *  - white:  a plain white glyph of the app's logo (shown the rest of the time)
 */
object IconFactory {

    // Adaptive icon layers are drawn on a 108dp canvas of which only the inner 72dp
    // is visible, so each layer extends 18/72 = 25% past the visible bounds on every side.
    private const val LAYER_OUTSET = 0.25f

    fun colour(src: Drawable, size: Int): Bitmap = render(size) { c ->
        val d = copyOf(src)
        d.setBounds(0, 0, size, size)
        d.draw(c)
    }

    /**
     * The app's own logo in white.
     *  1. its monochrome (themed) icon, when it ships one;
     *  2. otherwise its foreground layer: a one-colour logo becomes a white silhouette, and a logo printed
     *     on a solid shape (text on a label, a letter on a disc) keeps its inner details;
     *  3. otherwise the full icon, with everything that differs from its background turned white.
     * Returns null only when nothing readable comes out.
     */
    fun white(src: Drawable, size: Int): Bitmap? {
        if (src is AdaptiveIconDrawable) {
            if (Build.VERSION.SDK_INT >= 33 && src.monochrome != null) {
                val glyph = layer(src.monochrome!!, size, tint = true)
                if (opaqueShare(glyph) in 0.01f..0.85f) return glyph
                glyph.recycle()
            }
            src.foreground?.let { fg ->
                val layer = layer(fg, size, tint = false)
                val glyph = fromLayer(layer)
                layer.recycle()
                if (glyph != null) return glyph
            }
        }
        val full = colour(src, size)
        return againstBackground(full).also { full.recycle() }
    }

    private fun layer(d0: Drawable, size: Int, tint: Boolean): Bitmap = render(size) { c ->
        val d = copyOf(d0)
        val pad = (size * LAYER_OUTSET).toInt()
        d.setBounds(-pad, -pad, size + pad, size + pad)
        if (tint) d.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        d.draw(c)
    }

    /** A foreground layer → white glyph. */
    private fun fromLayer(layer: Bitmap): Bitmap? {
        val cover = opaqueShare(layer)
        if (cover < 0.01f) return null
        if (cover > 0.85f) return againstBackground(layer)        // fills the tile: treat like a full icon
        // colours used by the logo, roughly
        val counts = HashMap<Int, Int>()
        var total = 0
        forEachSample(layer) { px ->
            if (Color.alpha(px) > 128) { counts[bucket(px)] = (counts[bucket(px)] ?: 0) + 1; total++ }
        }
        if (total == 0) return null
        val sorted = counts.entries.sortedByDescending { it.value }
        val main = sorted[0]
        val mainShare = main.value.toFloat() / total
        val others = 1f - mainShare
        return if (mainShare > 0.5f && others > 0.06f && cover * mainShare > 0.10f) {
            // a solid shape with a logo on it: keep what differs from the shape
            val g = map(layer) { px, a -> (a * clamp01((dist(px, unbucket(main.key)) - 40f) / 70f)).toInt() }
            if (opaqueShare(g, 120) > 0.005f) g else { g.recycle(); map(layer) { _, a -> a } }
        } else {
            map(layer) { _, a -> a }                                  // one-colour logo: plain silhouette
        }
    }

    /** Everything that differs from the icon's background colour becomes white. */
    private fun againstBackground(bmp: Bitmap): Bitmap? {
        val w = bmp.width
        // background = most common colour on a ring just inside the icon's edge
        val counts = HashMap<Int, Int>()
        val r = w * 0.36f; val cx = w / 2f
        for (i in 0 until 72) {
            val ang = Math.PI * 2 * i / 72
            val x = (cx + r * Math.cos(ang)).toInt().coerceIn(0, w - 1)
            val y = (cx + r * Math.sin(ang)).toInt().coerceIn(0, w - 1)
            val px = bmp.getPixel(x, y)
            if (Color.alpha(px) > 128) counts[bucket(px)] = (counts[bucket(px)] ?: 0) + 1
        }
        val bg = counts.maxByOrNull { it.value }?.key?.let { unbucket(it) } ?: return null
        val g = map(bmp) { px, a -> (a * clamp01((dist(px, bg) - 45f) / 70f)).toInt() }
        val share = opaqueShare(g, 120)
        if (share < 0.005f || share > 0.75f) { g.recycle(); return null }
        return g
    }

    private fun map(src: Bitmap, alpha: (Int, Int) -> Int): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val a = Color.alpha(px[i])
            px[i] = if (a == 0) 0 else Color.argb(alpha(px[i], a).coerceIn(0, 255), 255, 255, 255)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun bucket(px: Int) = ((Color.red(px) shr 4) shl 8) or ((Color.green(px) shr 4) shl 4) or (Color.blue(px) shr 4)
    private fun unbucket(b: Int) = Color.rgb(((b shr 8) and 15) * 17, ((b shr 4) and 15) * 17, (b and 15) * 17)
    private fun dist(a: Int, b: Int): Float {
        val dr = Color.red(a) - Color.red(b); val dg = Color.green(a) - Color.green(b); val db = Color.blue(a) - Color.blue(b)
        return Math.sqrt((dr * dr + dg * dg + db * db).toDouble()).toFloat()
    }
    private fun clamp01(v: Float) = v.coerceIn(0f, 1f)

    private inline fun forEachSample(bmp: Bitmap, f: (Int) -> Unit) {
        val step = maxOf(1, bmp.width / 48)
        var y = 0
        while (y < bmp.height) { var x = 0; while (x < bmp.width) { f(bmp.getPixel(x, y)); x += step }; y += step }
    }

    /** One of the launcher's own line icons, drawn white at about the same size as app glyphs. */
    fun fromVector(glyph: Drawable, size: Int): Bitmap = render(size) { c ->
        val d = copyOf(glyph)
        val inset = (size * 0.2f).toInt()
        d.setBounds(inset, inset, size - inset, size - inset)
        d.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        d.draw(c)
    }

    /** A clean white initial, for apps with no usable glyph and no matching line icon. */
    fun monogram(label: String, size: Int): Bitmap = render(size) { c ->
        val letter = label.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        c.drawText(letter, size / 2f, y, paint)
    }

    private fun copyOf(d: Drawable): Drawable =
        d.constantState?.newDrawable()?.mutate() ?: d.mutate()

    private inline fun render(size: Int, block: (Canvas) -> Unit): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        block(Canvas(bmp))
        return bmp
    }

    /** Fraction of sampled pixels that are mostly opaque. */
    private fun opaqueShare(bmp: Bitmap, min: Int = 200): Float {
        val step = maxOf(1, bmp.width / 24)
        var total = 0
        var opaque = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                total++
                if (Color.alpha(bmp.getPixel(x, y)) > min) opaque++
                x += step
            }
            y += step
        }
        return if (total == 0) 0f else opaque.toFloat() / total
    }
}
