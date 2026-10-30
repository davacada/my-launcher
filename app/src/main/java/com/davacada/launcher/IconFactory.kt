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
     * The app's own white glyph, from its monochrome or foreground layer.
     * Returns null when the app doesn't provide one that works as a white icon.
     */
    fun white(src: Drawable, size: Int): Bitmap? {
        if (src is AdaptiveIconDrawable) {
            val layer: Drawable? =
                if (Build.VERSION.SDK_INT >= 33 && src.monochrome != null) src.monochrome
                else src.foreground
            if (layer != null) {
                val glyph = render(size) { c ->
                    val d = copyOf(layer)
                    val pad = (size * LAYER_OUTSET).toInt()
                    d.setBounds(-pad, -pad, size + pad, size + pad)
                    d.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                    d.draw(c)
                }
                // A foreground that covers (almost) the whole tile would just be a white
                // square, so in that case fall through to the greyscale version instead.
                if (opaqueShare(glyph) < 0.85f) return glyph
                glyph.recycle()
            }
        }
        return null
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
    private fun opaqueShare(bmp: Bitmap): Float {
        val step = maxOf(1, bmp.width / 24)
        var total = 0
        var opaque = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                total++
                if (Color.alpha(bmp.getPixel(x, y)) > 200) opaque++
                x += step
            }
            y += step
        }
        return if (total == 0) 0f else opaque.toFloat() / total
    }
}
