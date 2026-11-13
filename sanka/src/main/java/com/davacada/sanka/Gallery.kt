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
        var taken = 0L; var duration = 0L; var w = 0; var h = 0
        if (mime.startsWith("video/")) {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, uri)
                duration = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val vw = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val vh = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot % 180 != 0) { w = vh; h = vw } else { w = vw; h = vh }
                r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DATE)?.let { d ->
                    taken = try { java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(d.take(15))?.time ?: 0L } catch (e: Exception) { 0L }
                }
            } catch (e: Exception) { } finally { r.release() }
        } else {
            try {
                ctx.contentResolver.openInputStream(uri)?.use { s ->
                    val ex = android.media.ExifInterface(s)
                    val dt = ex.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL) ?: ex.getAttribute(android.media.ExifInterface.TAG_DATETIME)
                    if (dt != null) taken = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).parse(dt)?.time ?: 0L
                    val iw = ex.getAttributeInt(android.media.ExifInterface.TAG_IMAGE_WIDTH, 0)
                    val ih = ex.getAttributeInt(android.media.ExifInterface.TAG_IMAGE_LENGTH, 0)
                    val o = ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
                    val turned = o == 5 || o == 6 || o == 7 || o == 8
                    if (turned) { w = ih; h = iw } else { w = iw; h = ih }
                }
            } catch (e: Exception) { }
        }
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
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED,
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
