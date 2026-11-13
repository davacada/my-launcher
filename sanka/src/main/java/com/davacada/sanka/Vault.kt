package com.davacada.sanka

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaDataSource
import android.net.Uri
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption for everything Sanka keeps. Each file is AES-256 in counter mode (so videos can be read
 * from any point while playing) with its own random IV at the start of the file. The AES key itself is
 * stored wrapped by a key that lives in the phone's secure hardware (Android Keystore) and never leaves it.
 */
object Crypto {
    private const val MASTER = "sanka_master"
    private val rnd = SecureRandom()
    @Volatile private var key: SecretKey? = null

    fun key(ctx: Context): SecretKey {
        key?.let { return it }
        synchronized(this) {
            key?.let { return it }
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!ks.containsAlias(MASTER)) {
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                    init(KeyGenParameterSpec.Builder(MASTER, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256).build())
                }.generateKey()
            }
            val master = ks.getKey(MASTER, null) as SecretKey
            val f = File(ctx.filesDir, "key.bin")
            val raw: ByteArray = if (f.exists()) {
                val b = f.readBytes()
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, master, GCMParameterSpec(128, b.copyOfRange(0, 12)))
                    doFinal(b, 12, b.size - 12)
                }
            } else {
                val k = ByteArray(32).also { rnd.nextBytes(it) }
                val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, master) }
                val tmp = File(ctx.filesDir, "key.tmp")
                tmp.writeBytes(c.iv + c.doFinal(k)); tmp.renameTo(f)
                k
            }
            return SecretKeySpec(raw, "AES").also { key = it }
        }
    }

    /** Wraps [out]: writes a fresh IV, then everything written is encrypted. */
    fun encrypting(ctx: Context, out: OutputStream): OutputStream {
        val iv = ByteArray(16).also { rnd.nextBytes(it) }
        out.write(iv)
        return CipherOutputStream(out, Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(ctx), IvParameterSpec(iv))
        })
    }

    /** A decrypting cipher positioned at byte [pos] of the plain data. */
    fun cipherAt(ctx: Context, iv: ByteArray, pos: Long): Cipher {
        val ctr = iv.copyOf()
        var add = pos / 16
        var i = 15
        while (add > 0 && i >= 0) {
            val sum = (ctr[i].toInt() and 0xFF) + (add and 0xFF).toInt()
            ctr[i] = sum.toByte()
            add = (add ushr 8) + (sum ushr 8)
            i--
        }
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(ctx), IvParameterSpec(ctr))
        val skip = (pos % 16).toInt()
        if (skip > 0) c.update(ByteArray(skip))
        return c
    }

    fun writeBytes(ctx: Context, f: File, data: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { fo -> encrypting(ctx, fo).use { it.write(data) } }
        tmp.renameTo(f)
    }

    fun readBytes(ctx: Context, f: File): ByteArray = EncFile(ctx, f).use { e ->
        val out = ByteArray(e.length.toInt()); e.read(0, out, 0, out.size); out
    }
}

/** Random-access reading of an encrypted file. */
class EncFile(private val ctx: Context, file: File) : AutoCloseable {
    private val raf = RandomAccessFile(file, "r")
    private val iv = ByteArray(16).also { raf.readFully(it) }
    val length: Long = raf.length() - 16
    private var cipher: javax.crypto.Cipher? = null
    private var nextPos = -1L

    @Synchronized
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= length) return -1
        val n = minOf(len.toLong(), length - pos).toInt()
        raf.seek(16 + pos)
        raf.readFully(buf, off, n)
        val c = if (pos == nextPos && cipher != null) cipher!! else Crypto.cipherAt(ctx, iv, pos)
        val plain = c.update(buf, off, n)
        if (plain != null) System.arraycopy(plain, 0, buf, off, plain.size)
        cipher = c; nextPos = pos + n
        return n
    }

    fun stream(): InputStream = object : InputStream() {
        var p = 0L
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF }
        override fun read(b: ByteArray, off: Int, len: Int): Int { val n = this@EncFile.read(p, b, off, len); if (n > 0) p += n; return n }
    }

    override fun close() = raf.close()
}

/** Lets the video player read straight from an encrypted file. */
class EncDataSource(ctx: Context, file: File) : MediaDataSource() {
    private val enc = EncFile(ctx, file)
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = enc.read(position, buffer, offset, size)
    override fun getSize(): Long = enc.length
    override fun close() = enc.close()
}

data class Item(
    val id: String, val name: String, val mime: String, val size: Long,
    val taken: Long, val added: Long, val duration: Long, val path: String,
    val folder: String = "", val w: Int = 0, val h: Int = 0,
    val hash: String = "",          // fingerprint of the file's contents, to spot duplicates
    val dated: Int = 0              // how thoroughly the creation date was checked (2 = inside the file, then its name)
) {
    val isVideo get() = mime.startsWith("video/")
    /** Width / height as shown (1 when unknown). */
    val aspect: Float get() = if (w > 0 && h > 0) (w.toFloat() / h).coerceIn(0.3f, 3.5f) else 1f
    fun json() = JSONObject().put("id", id).put("name", name).put("mime", mime).put("size", size)
        .put("taken", taken).put("added", added).put("duration", duration).put("path", path)
        .put("folder", folder).put("w", w).put("h", h).put("hash", hash).put("dated", dated)
    companion object {
        fun from(o: JSONObject) = Item(o.getString("id"), o.optString("name"), o.optString("mime"), o.optLong("size"),
            o.optLong("taken"), o.optLong("added"), o.optLong("duration"), o.optString("path"),
            o.optString("folder"), o.optInt("w"), o.optInt("h"), o.optString("hash"), o.optInt("dated", 0))
    }
}

/** A folder; its cover is [cover] if that item is still in it, otherwise the first thing added to it. */
data class Folder(val id: String, val name: String, val cover: String = "", val created: Long = System.currentTimeMillis()) {
    fun json() = JSONObject().put("id", id).put("name", name).put("cover", cover).put("created", created)
    companion object {
        fun from(o: JSONObject) = Folder(o.getString("id"), o.optString("name"), o.optString("cover"), o.optLong("created"))
    }
}

/** What's hidden: encrypted files in the app's private storage plus an encrypted list. */
class Vault(private val ctx: Context) {
    private companion object { const val DATED = 3 // 3: better video dates }

    private val dir = File(ctx.filesDir, "vault").apply { mkdirs() }
    private val indexFile = File(dir, "index.bin")
    var items: MutableList<Item> = mutableListOf()
        private set
    var folders: MutableList<Folder> = mutableListOf()
        private set

    init { load() }

    private fun load() {
        try {
            if (!indexFile.exists()) return
            val text = String(Crypto.readBytes(ctx, indexFile)).trim()
            if (text.startsWith("[")) { // first version: just a list of items
                val a = JSONArray(text)
                items = (0 until a.length()).map { Item.from(a.getJSONObject(it)) }.toMutableList()
            } else {
                val o = JSONObject(text)
                val a = o.optJSONArray("items") ?: JSONArray()
                val f = o.optJSONArray("folders") ?: JSONArray()
                items = (0 until a.length()).map { Item.from(a.getJSONObject(it)) }.toMutableList()
                folders = (0 until f.length()).map { Folder.from(f.getJSONObject(it)) }.toMutableList()
            }
        } catch (e: Exception) { }
    }

    @Synchronized private fun save() {
        val a = JSONArray(); items.forEach { a.put(it.json()) }
        val f = JSONArray(); folders.forEach { f.put(it.json()) }
        Crypto.writeBytes(ctx, indexFile, JSONObject().put("v", 2).put("items", a).put("folders", f).toString().toByteArray())
    }

    // ------------------------------------------------------------------ folders

    fun inFolder(folder: String) = items.filter { it.folder == folder }

    fun coverOf(f: Folder): Item? {
        val list = inFolder(f.id)
        return list.firstOrNull { it.id == f.cover } ?: list.lastOrNull() // items are newest first, so last = first added
    }

    @Synchronized fun newFolder(name: String): Folder {
        val f = Folder(UUID.randomUUID().toString(), name.trim().ifEmpty { "New folder" })
        folders.add(0, f); save(); return f
    }

    @Synchronized fun renameFolder(id: String, name: String) {
        folders = folders.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it }.toMutableList(); save()
    }

    /** Removes the folder and everything in it, for good. */
    @Synchronized fun deleteFolder(id: String) {
        items.filter { it.folder == id }.forEach { dataFile(it).delete(); thumbFile(it).delete() }
        items = items.filter { it.folder != id }.toMutableList()
        folders.removeAll { it.id == id }
        save()
    }

    @Synchronized fun move(ids: Collection<String>, folder: String) {
        items = items.map { if (it.id in ids) it.copy(folder = folder) else it }.toMutableList(); save()
    }

    @Synchronized fun setCover(folder: String, item: String) {
        folders = folders.map { if (it.id == folder) it.copy(cover = item) else it }.toMutableList(); save()
    }

    /**
     * One-off tidy-up for items hidden by older versions: reads each file's own creation date and fingerprint,
     * then removes exact duplicates (same contents), keeping the copy that's in a folder or was added first.
     * Returns how many duplicates were removed, or -1 if nothing changed.
     */
    fun checkUp(): Int {
        val todo = items.filter { it.hash.isEmpty() || it.dated < DATED }
        var changed = false
        for (m in todo) {
            val hash = if (m.hash.isEmpty()) try { EncFile(ctx, dataFile(m)).use { e -> fingerprint(e.stream()) } } catch (e: Exception) { "" } else m.hash
            val made = if (m.dated < DATED) createdAt(m).takeIf { it > 0 } ?: Dates.fromName(m.name) else 0L
            synchronized(this) {
                items = items.map { x -> if (x.id != m.id) x else x.copy(hash = hash.ifEmpty { x.hash }, dated = DATED,
                    taken = if (made > 0) made else x.taken) }.toMutableList()
            }
            changed = true
        }
        if (changed) synchronized(this) { save() }
        return if (changed) 0 else -1
    }

    /** Sets of items whose contents are identical (oldest first). Nothing is removed; you decide. */
    fun duplicateGroups(): List<List<Item>> = synchronized(this) {
        items.filter { it.hash.isNotEmpty() }.groupBy { it.hash }.values.filter { it.size > 1 }.map { g -> g.sortedBy { it.added } }
    }

    /** When the photo or video was made, from inside the file itself (EXIF / video header); 0 if it doesn't say. */
    private fun createdAt(m: Item): Long = try {
        if (m.isVideo) {
            val r = android.media.MediaMetadataRetriever()
            try {
                EncDataSource(ctx, dataFile(m)).use { src -> r.setDataSource(src); Dates.video(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DATE)) }
            } finally { r.release() }
        } else EncFile(ctx, dataFile(m)).use { e -> Dates.exif(e.stream()) }
    } catch (e: Exception) { 0L }

    /** SHA-256 of a whole stream, as hex. */
    private fun fingerprint(input: java.io.InputStream): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(256 * 1024)
        while (true) { val n = input.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Older items were saved without their size: work it out from the thumbnail so the collage gets their shape. */
    fun fillInSizes(): Boolean {
        val missing = items.filter { it.w == 0 || it.h == 0 }
        if (missing.isEmpty()) return false
        val sizes = HashMap<String, Pair<Int, Int>>()
        for (m in missing) {
            try {
                val b = Crypto.readBytes(ctx, thumbFile(m))
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(b, 0, b.size, o)
                if (o.outWidth > 0) sizes[m.id] = o.outWidth to o.outHeight
            } catch (e: Exception) { }
        }
        synchronized(this) {
            items = items.map { x -> sizes[x.id]?.let { (w, h) -> x.copy(w = w, h = h) } ?: x }.toMutableList(); save()
        }
        return sizes.isNotEmpty()
    }

    fun dataFile(it: Item) = File(dir, it.id + ".bin")
    private fun thumbFile(it: Item) = File(dir, it.id + ".thumb")

    fun thumb(it: Item): Bitmap? = try {
        val b = Crypto.readBytes(ctx, thumbFile(it)); BitmapFactory.decodeByteArray(b, 0, b.size)
    } catch (e: Exception) { null }

    /** Full photo, scaled down to at most [maxSide] pixels on its longest side. */
    fun photo(it: Item, maxSide: Int): Bitmap? = try {
        val bytes = Crypto.readBytes(ctx, dataFile(it))
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { d, info, _ ->
            val s = info.size
            val longest = maxOf(s.width, s.height)
            if (longest > maxSide) d.setTargetSampleSize(Integer.highestOneBit((longest + maxSide - 1) / maxSide).coerceAtLeast(1))
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: Exception) { null }

    /** Copies a gallery item in, encrypted. Returns the new item, or null if it couldn't be read. */
    /** How many of the last adds were already in Sanka (same contents) and so weren't copied again. */
    var duplicatesSkipped = 0

    /** Why the last [add] failed, in plain words (for the import summary). */
    var lastError = ""

    /**
     * Reads the source exactly once into an encrypted copy. Some apps (vaults especially) hand out files that can
     * only be read once, or only as a stream, so everything else (size, length, thumbnail, date) comes from Sanka's
     * own copy afterwards. Tries the direct file first, then the stream, a few times.
     */
    private fun copyIn(uri: Uri, tmp: File, md: java.security.MessageDigest): Long {
        var last: Exception? = null
        for (attempt in 0 until 3) {
            for (direct in listOf(true, false)) {
                md.reset(); tmp.delete()
                try {
                    val raw: InputStream = if (direct)
                        ctx.contentResolver.openFileDescriptor(uri, "r")?.let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it) } ?: continue
                    else ctx.contentResolver.openInputStream(uri) ?: continue
                    var n = 0L
                    raw.use { r ->
                        val input = java.security.DigestInputStream(r, md)
                        FileOutputStream(tmp).use { fo -> Crypto.encrypting(ctx, fo).use { out ->
                            val buf = ByteArray(256 * 1024)
                            while (true) { val k = input.read(buf); if (k < 0) break; out.write(buf, 0, k); n += k }
                        } }
                    }
                    if (n > 0) return n
                    last = java.io.IOException("the other app sent an empty file")
                } catch (e: Exception) { last = e }
            }
            Thread.sleep(600L * (attempt + 1))
        }
        throw last ?: java.io.IOException("the other app didn't hand over the file")
    }

    fun add(m: Media, folder: String = ""): Item? {
        val id = UUID.randomUUID().toString()
        var item = Item(id, m.name, m.mime, m.size, m.taken, System.currentTimeMillis(), m.duration, m.path, folder, m.w, m.h)
        val tmp = File(dir, "$id.part")
        lastError = ""
        try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = copyIn(m.uri, tmp, md)
            if (item.size <= 0) item = item.copy(size = bytes)
            val hash = md.digest().joinToString("") { "%02x".format(it) }
            // Identical copies are kept for now; Sanka shows them afterwards so you can decide.
            if (!tmp.renameTo(dataFile(item))) { lastError = "couldn't save the copy"; return null }
            if (item.isVideo) videoInfo(item)?.let { (d, w, h) -> item = item.copy(duration = if (item.duration > 0) item.duration else d, w = if (item.w > 0) item.w else w, h = if (item.h > 0) item.h else h) }
            // When it was made: from inside the file, else from a date in its name (IMG_20230512…), else what the gallery said.
            val made = createdAt(item).takeIf { it > 0 } ?: Dates.fromName(item.name)
            item = item.copy(hash = hash, dated = DATED, taken = if (made > 0) made else item.taken)
            // Gallery items have a ready thumbnail; anything else gets one made from Sanka's own copy.
            val th = (if (m.uri.authority == MediaStore.AUTHORITY) try { ctx.contentResolver.loadThumbnail(m.uri, Size(640, 640), null) } catch (e: Exception) { null } else null)
                ?: makeThumb(item)
            var saved = item
            if (th != null) {
                val bo = ByteArrayOutputStream(); th.compress(Bitmap.CompressFormat.JPEG, 85, bo)
                Crypto.writeBytes(ctx, thumbFile(item), bo.toByteArray())
                if (item.w == 0 || item.h == 0) saved = item.copy(w = th.width, h = th.height) // thumbnail keeps the shape
            }
            synchronized(this) { items.add(0, saved); save() }
            return saved
        } catch (e: Exception) {
            lastError = when (e) {
                is SecurityException -> "the other app didn't give permission to read it"
                is java.io.FileNotFoundException -> "the other app said the file isn't there"
                else -> e.message ?: e.javaClass.simpleName
            }
            tmp.delete(); dataFile(item).delete(); thumbFile(item).delete()
            return null
        }
    }

    /** Length (ms), width and height of a hidden video, read from Sanka's copy. */
    private fun videoInfo(it: Item): Triple<Long, Int, Int>? = try {
        val r = android.media.MediaMetadataRetriever()
        try {
            EncDataSource(ctx, dataFile(it)).use { src ->
                r.setDataSource(src)
                val d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val vw = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val vh = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot % 180 != 0) Triple(d, vh, vw) else Triple(d, vw, vh)
            }
        } finally { r.release() }
    } catch (e: Exception) { null }

    /** A thumbnail made from Sanka's own (encrypted) copy. */
    private fun makeThumb(it: Item): Bitmap? = try {
        if (it.isVideo) {
            val r = android.media.MediaMetadataRetriever()
            try {
                EncDataSource(ctx, dataFile(it)).use { src ->
                    r.setDataSource(src)
                    r.getScaledFrameAtTime(1_000_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640)
                        ?: r.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640)
                }
            } finally { r.release() }
        } else photo(it, 640)
    } catch (e: Exception) { null }

    /** Puts an item back in the gallery (in its original folder where possible) and removes it from Sanka. */
    fun restore(it: Item): Boolean {
        val video = it.isVideo
        val coll = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                   else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val fallback = if (video) "Movies/Sanka/" else "Pictures/Sanka/"
        for (path in listOf(it.path.ifEmpty { fallback }, fallback).distinct()) {
            var uri: Uri? = null
            try {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, it.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, it.mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                    if (it.taken > 0) put(MediaStore.MediaColumns.DATE_TAKEN, it.taken)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                uri = ctx.contentResolver.insert(coll, v) ?: continue
                ctx.contentResolver.openOutputStream(uri)!!.use { out -> EncFile(ctx, dataFile(it)).use { e -> e.stream().copyTo(out, 256 * 1024) } }
                ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                remove(it)
                return true
            } catch (e: Exception) {
                uri?.let { u -> try { ctx.contentResolver.delete(u, null, null) } catch (x: Exception) { } }
            }
        }
        return false
    }

    /** Gone for good. */
    fun remove(it: Item) {
        dataFile(it).delete(); thumbFile(it).delete()
        synchronized(this) { items.removeAll { x -> x.id == it.id }; save() }
    }
}
