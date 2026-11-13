package com.davacada.sanka

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File

/**
 * Hands a shared picture to the app you chose (WhatsApp, mail, …). Sanka unlocks a temporary copy into its
 * private cache; only the app you share with is allowed to read it, and Sanka clears these copies again later.
 */
class ShareProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileFor(uri: Uri): File? {
        val base = File(context!!.cacheDir, "share").canonicalFile
        val segs = uri.pathSegments
        if (segs.size != 2) return null
        val f = File(File(base, segs[0]), segs[1]).canonicalFile
        return if (f.path.startsWith(base.path + File.separator) && f.isFile) f else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(uri) ?: throw java.io.FileNotFoundException(uri.toString()), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = fileFor(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        if (f != null) c.addRow(cols.map { when (it) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } })
        return c
    }

    override fun getType(uri: Uri): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase() ?: "") ?: "application/octet-stream"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "com.davacada.sanka.share"

        /** Removes temporary share copies older than [olderThanMs] (all of them with 0). */
        fun clean(ctx: android.content.Context, olderThanMs: Long) {
            val now = System.currentTimeMillis()
            File(ctx.cacheDir, "share").listFiles()?.forEach { d ->
                if (now - d.lastModified() >= olderThanMs) d.deleteRecursively()
            }
        }
    }
}
