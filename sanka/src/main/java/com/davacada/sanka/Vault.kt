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
    val taken: Long, val added: Long, val duration: Long, val path: String
) {
    val isVideo get() = mime.startsWith("video/")
    fun json() = JSONObject().put("id", id).put("name", name).put("mime", mime).put("size", size)
        .put("taken", taken).put("added", added).put("duration", duration).put("path", path)
    companion object {
        fun from(o: JSONObject) = Item(o.getString("id"), o.optString("name"), o.optString("mime"), o.optLong("size"),
            o.optLong("taken"), o.optLong("added"), o.optLong("duration"), o.optString("path"))
    }
}

/** What's hidden: encrypted files in the app's private storage plus an encrypted list. */
class Vault(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "vault").apply { mkdirs() }
    private val indexFile = File(dir, "index.bin")
    var items: MutableList<Item> = load()
        private set

    private fun load(): MutableList<Item> = try {
        if (!indexFile.exists()) mutableListOf() else {
            val a = JSONArray(String(Crypto.readBytes(ctx, indexFile)))
            (0 until a.length()).map { Item.from(a.getJSONObject(it)) }.toMutableList()
        }
    } catch (e: Exception) { mutableListOf() }

    @Synchronized private fun save() {
        val a = JSONArray(); items.forEach { a.put(it.json()) }
        Crypto.writeBytes(ctx, indexFile, a.toString().toByteArray())
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
    fun add(m: Media): Item? {
        val id = UUID.randomUUID().toString()
        val item = Item(id, m.name, m.mime, m.size, m.taken, System.currentTimeMillis(), m.duration, m.path)
        val tmp = File(dir, "$id.part")
        try {
            ctx.contentResolver.openInputStream(m.uri)?.use { input ->
                FileOutputStream(tmp).use { fo -> Crypto.encrypting(ctx, fo).use { input.copyTo(it, 256 * 1024) } }
            } ?: return null
            if (!tmp.renameTo(dataFile(item))) return null
            val th = try { ctx.contentResolver.loadThumbnail(m.uri, Size(420, 420), null) } catch (e: Exception) { null }
            if (th != null) {
                val bo = ByteArrayOutputStream(); th.compress(Bitmap.CompressFormat.JPEG, 85, bo)
                Crypto.writeBytes(ctx, thumbFile(item), bo.toByteArray())
            }
            synchronized(this) { items.add(0, item); save() }
            return item
        } catch (e: Exception) {
            tmp.delete(); dataFile(item).delete(); thumbFile(item).delete()
            return null
        }
    }

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
