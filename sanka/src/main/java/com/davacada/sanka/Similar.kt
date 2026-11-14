package com.davacada.sanka

import android.graphics.Bitmap
import android.graphics.Color
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions

/**
 * Grouping by similarity, entirely on the phone (Sanka has no internet access at all):
 *  - look-alikes: a 64-bit fingerprint of each thumbnail's light-and-dark pattern plus its average colour;
 *  - scenes: a small image-recognition model built into the app names what's in each picture (beach, food, dog…).
 */
object Similar {
    /** Bump to look at every picture again. */
    const val VERSION = 1

    /** Difference hash: shrink to 9×8 greys and note whether each pixel is brighter than its right-hand neighbour. */
    fun dhash(b: Bitmap): Long {
        val s = Bitmap.createScaledBitmap(b, 9, 8, true)
        var h = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val l = grey(s.getPixel(x, y)); val r = grey(s.getPixel(x + 1, y))
            h = (h shl 1) or (if (l > r) 1L else 0L)
        }
        if (s !== b) s.recycle()
        return h
    }

    fun avgColor(b: Bitmap): Int {
        val s = Bitmap.createScaledBitmap(b, 1, 1, true)
        val c = s.getPixel(0, 0) or 0xFF000000.toInt()
        if (s !== b) s.recycle()
        return c
    }

    private fun grey(c: Int) = Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114

    private fun colorGap(a: Int, b: Int) = Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b)) + Math.abs(Color.blue(a) - Color.blue(b))

    fun looksAlike(a: Item, b: Item): Boolean =
        a.seen > 0 && b.seen > 0 && a.ph != 0L && b.ph != 0L &&
            java.lang.Long.bitCount(a.ph xor b.ph) <= 9 &&
            colorGap(a.col, b.col) < 70 &&
            Math.abs(a.aspect - b.aspect) < 0.2f

    /** Sets of pictures that look nearly the same (bursts, retakes, edits), biggest first. */
    fun lookAlikes(items: List<Item>): List<List<Item>> {
        val list = items.filter { it.seen > 0 && it.ph != 0L }
        val parent = IntArray(list.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        for (i in list.indices) for (j in i + 1 until list.size) {
            if (looksAlike(list[i], list[j])) { val a = find(i); val b = find(j); if (a != b) parent[a] = b }
        }
        return list.indices.groupBy { find(it) }.values.filter { it.size > 1 }.map { g -> g.map { list[it] } }.sortedByDescending { it.size }
    }

    // ------------------------------------------------------------------ scenes

    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.5f).build()) }

    /** What the model sees in the picture, most confident first. */
    fun labels(b: Bitmap): List<Pair<String, Float>> = try {
        Tasks.await(labeler.process(InputImage.fromBitmap(b, 0))).map { it.text to it.confidence }.sortedByDescending { it.second }
    } catch (e: Exception) { emptyList() }

    private val GROUPS = linkedMapOf(
        "Beach & sea" to listOf("beach", "sea", "ocean", "surfing", "surfboard", "swimming", "swimwear", "pool", "lake", "river", "waterfall", "boat", "sand", "wave", "coast"),
        "Food & drink" to listOf("food", "cuisine", "dessert", "cake", "pizza", "bread", "fruit", "vegetable", "drink", "coffee", "wine", "cocktail", "juice", "sushi", "pasta", "salad", "cookie", "meal", "cup", "tableware", "ice cream", "chocolate", "burger", "sandwich", "bakery", "dinner", "lunch", "breakfast", "plate", "beer"),
        "Pets & animals" to listOf("dog", "cat", "pet", "bird", "horse", "fish", "animal", "insect", "butterfly", "rabbit", "bear", "duck", "cattle", "reptile", "squirrel"),
        "Nights out & music" to listOf("night", "concert", "party", "fireworks", "bar", "nightclub", "stage", "musical instrument", "musician", "guitar", "piano", "drum", "dance", "event", "neon", "disco", "club", "festival", "christmas"),
        "Nature" to listOf("plant", "flower", "tree", "forest", "garden", "mountain", "sky", "cloud", "sunset", "grass", "leaf", "rainbow", "snow", "field", "rock", "desert", "cliff", "moon", "star", "jungle", "park"),
        "City & places" to listOf("building", "skyscraper", "bridge", "church", "tower", "road", "monument", "castle", "city", "street", "temple", "house", "stadium", "museum", "room", "interior design", "shelf", "couch", "bed", "kitchen", "window", "restaurant"),
        "Cars & travel" to listOf("car", "vehicle", "motorcycle", "bicycle", "airplane", "train", "bus", "wheel", "tire", "truck", "aircraft", "airport", "helmet"),
        "Sport" to listOf("sports", "soccer", "basketball", "tennis", "gym", "skateboard", "ski", "running", "football", "racket", "climbing"),
        "Clothes & style" to listOf("jacket", "jeans", "shoe", "dress", "sunglasses", "hat", "glasses", "jewelry", "handbag", "bag", "sneakers", "cap", "scarf", "watch", "shirt", "fashion"),
        "Documents & text" to listOf("paper", "poster", "text", "menu", "receipt", "document", "book", "newspaper", "handwriting", "screenshot", "computer", "laptop", "mobile phone", "monitor"),
        "People" to listOf("selfie", "smile", "person", "beard", "hair", "eyelash", "skin", "muscle", "lipstick", "tattoo", "baby", "team", "crowd", "flesh", "pajamas", "standing", "sitting"),
    )

    /** A scene name for a picture, from its file name (screenshots) or from what the model saw in it. "" = no idea. */
    fun scene(name: String, labels: List<Pair<String, Float>>): String {
        val n = name.lowercase()
        if ("screenshot" in n || "screen_shot" in n) return "Screenshots"
        if ("screenrecord" in n || "screen_record" in n || "screen recording" in n) return "Screen recordings"
        for ((label, _) in labels) {
            val l = label.lowercase()
            for ((g, words) in GROUPS) if (words.any { w -> l == w || l.startsWith("$w ") || l.endsWith(" $w") }) return g
        }
        // nothing in our groups: use the model's own word when it's sure
        return labels.firstOrNull { it.second >= 0.75f }?.first ?: ""
    }
}
