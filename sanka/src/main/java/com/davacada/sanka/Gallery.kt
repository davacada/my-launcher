package com.davacada.sanka

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** A photo or video in the phone's gallery. */
data class Media(
    val uri: Uri, val name: String, val mime: String, val size: Long,
    val taken: Long, val duration: Long, val path: String,
    val w: Int = 0, val h: Int = 0, val album: String = ""
) {
    val isVideo get() = mime.startsWith("video/")
}

/** Reading "when was this made" from inside photo and video files. */
object Dates {
    fun exif(input: java.io.InputStream): Long {
        val ex = android.media.ExifInterface(input)
        for (tag in listOf(android.media.ExifInterface.TAG_DATETIME_ORIGINAL, android.media.ExifInterface.TAG_DATETIME_DIGITIZED, android.media.ExifInterface.TAG_DATETIME)) {
            val v = ex.getAttribute(tag) ?: continue
            if (v.startsWith("0000")) continue
            val t = try { java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).parse(v)?.time } catch (e: Exception) { null }
            if (t != null && t > 0) return t
        }
        return 0L
    }

    /**
     * A date written in a file name: IMG_20230512_141500, VID-20210301-WA0003, Screenshot_2022-11-03-18-22-10,
     * PXL_20240107_093012345, signal-2023-04-01-120000, or a timestamp like FB_IMG_1678901234567. 0 if none.
     */
    fun fromName(name: String): Long {
        val now = System.currentTimeMillis()
        val ymd = Regex("(?<!\\d)((?:19|20)\\d{2})[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\\d|3[01])(?:[-_ T.]?([01]\\d|2[0-3])[-_.:]?([0-5]\\d)(?:[-_.:]?([0-5]\\d))?)?")
        ymd.find(name)?.let { m ->
            val g = m.groupValues
            val c = java.util.Calendar.getInstance()
            c.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toIntOrNull() ?: 12, g[5].toIntOrNull() ?: 0, g[6].toIntOrNull() ?: 0)
            c.set(java.util.Calendar.MILLISECOND, 0)
            val t = c.timeInMillis
            if (g[1].toInt() >= 1990 && t <= now + 86_400_000L) return t
        }
        // 13-digit millisecond timestamps (Facebook, Messenger, many apps), between 2005 and now
        Regex("(?<!\\d)(1[1-9]\\d{11})(?!\\d)").find(name)?.let { m ->
            val t = m.value.toLong()
            if (t in 1_104_537_600_000L..now) return t
        }
        return 0L
    }

    /** Video headers store e.g. "20240312T101530.000Z" (UTC); 1904/1970 placeholders mean "unknown". */
    fun video(v: String?): Long {
        if (v.isNullOrBlank()) return 0L
        // Formats vary ("20240312T101530.000Z", "2024-03-12T10:15:30Z", "2024 03 12 10:15:30"): use the digits.
        val d = v.filter { it.isDigit() }
        if (d.length < 8) return 0L
        val t = try {
            java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .parse(d.take(14).padEnd(14, '0'))?.time
        } catch (e: Exception) { null } ?: return 0L
        // 1904 / 1970 placeholders (and anything in the future) mean "unknown"
        return if (t > 86_400_000L * 365 * 2 && t < System.currentTimeMillis() + 86_400_000L) t else 0L
    }
}

object Gallery {
    /** Details of a file another app shared with Sanka (name, type, size, length and, for photos, when it was taken). */
    fun describe(ctx: Context, uri: Uri, typeHint: String?): Media {
        var name = "file"; var size = 0L
        try {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { name = c.getString(0) ?: name; size = c.getLong(1) }
            }
        } catch (e: Exception) { }
        val mime = ctx.contentResolver.getType(uri)?.takeIf { it.startsWith("image/") || it.startsWith("video/") }
            ?: typeHint?.takeIf { (it.startsWith("image/") || it.startsWith("video/")) && !it.endsWith("*") }
            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "image/jpeg"
        // Nothing is read from the file here: some apps only let it be read once, and Sanka needs that read
        // for the copy. Length, size, shape and date all come from Sanka's own copy afterwards.
        // As a last resort for the date, the other app may tell us when the file was taken or last changed.
        var taken = 0L
        for (col in listOf(MediaStore.MediaColumns.DATE_TAKEN, android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED, MediaStore.MediaColumns.DATE_MODIFIED)) {
            if (taken > 0) break
            try {
                ctx.contentResolver.query(uri, arrayOf(col), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) {
                        val v = c.getLong(0)
                        taken = if (col == MediaStore.MediaColumns.DATE_MODIFIED && v < 100_000_000_000L) v * 1000 else v
                    }
                }
            } catch (e: Exception) { }
        }
        if (taken > System.currentTimeMillis() - 10 * 60_000L) taken = 0L // "just now" means a fresh temporary copy, not the real date
        val duration = 0L; val w = 0; val h = 0
        return Media(uri, name, mime, size, taken, duration, "", w, h)
    }

    /** The newest photos and videos on the phone (only the ones Android lets Sanka see). */
    fun recent(ctx: Context, limit: Int = 4000): List<Media> {
        val out = ArrayList<Media>()
        val sets = listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) to false,
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) to true,
        )
        for ((coll, video) in sets) {
            val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.ORIENTATION,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            try {
                ctx.contentResolver.query(coll, cols, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                    var n = 0
                    while (c.moveToNext() && n++ < limit) {
                        val taken = c.getLong(4).takeIf { it > 0 } ?: (c.getLong(5) * 1000)
                        // Width and height as the picture is shown: swap them when it's stored turned on its side.
                        val turned = c.getInt(10) % 180 != 0
                        val w = c.getInt(if (turned) 9 else 8); val h = c.getInt(if (turned) 8 else 9)
                        out.add(Media(ContentUris.withAppendedId(coll, c.getLong(0)), c.getString(1) ?: "file",
                            c.getString(2) ?: if (video) "video/mp4" else "image/jpeg", c.getLong(3), taken,
                            if (video) c.getLong(6) else 0L, c.getString(7) ?: "", w, h, c.getString(11) ?: ""))
                    }
                }
            } catch (e: Exception) { }
        }
        return out.sortedByDescending { it.taken }.take(limit)
    }
}

/** The PIN, stored only as a salted, slow hash. */
class Pin(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("sanka", Context.MODE_PRIVATE)
    val isSet get() = prefs.contains("pinHash")
    var fingerprint: Boolean
        get() = prefs.getBoolean("fingerprint", true)
        set(v) { prefs.edit().putBoolean("fingerprint", v).apply() }

    fun set(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString("pinSalt", b64(salt)).putString("pinHash", b64(hash(pin, salt))).apply()
    }

    fun check(pin: String): Boolean {
        val salt = prefs.getString("pinSalt", null)?.let { unb64(it) } ?: return false
        val want = prefs.getString("pinHash", null)?.let { unb64(it) } ?: return false
        return java.security.MessageDigest.isEqual(want, hash(pin, salt))
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, 60000, 256)).encoded

    private fun b64(b: ByteArray) = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
    private fun unb64(s: String) = android.util.Base64.decode(s, android.util.Base64.NO_WRAP)
}
