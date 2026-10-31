package com.davacada.launcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast

/**
 * "Move to trash" in the share menu. Photos, videos, music and files that Android's media store knows
 * about go to the trash (Android asks to confirm); they can be restored from the launcher's Trash.
 */
class TrashShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 30) { done("The trash needs Android 11 or newer."); return }
        val shared = ArrayList<Uri>()
        @Suppress("DEPRECATION")
        when (intent?.action) {
            Intent.ACTION_SEND -> (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { shared.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { shared.addAll(it) }
        }
        val media = shared.mapNotNull { toMedia(it) }
        if (media.isEmpty()) {
            done(if (shared.isEmpty()) "Nothing to move to the trash." else
                "That app shares a copy, not the file itself, so it can't go to the trash from here. Try sharing it from Files or the gallery.")
            return
        }
        try {
            val pi = MediaStore.createTrashRequest(contentResolver, media, true)
            startIntentSenderForResult(pi.intentSender, 1, null, 0, 0, 0)
        } catch (e: Exception) {
            done("Android didn't allow that: ${e.message}")
        }
        if (media.size < shared.size) Toast.makeText(this, "${shared.size - media.size} item(s) couldn't be moved: the app shared copies.", Toast.LENGTH_LONG).show()
    }

    /** The media-store address of a shared file, if it has one. */
    private fun toMedia(uri: Uri): Uri? {
        if (uri.authority == MediaStore.AUTHORITY) return uri
        if (DocumentsContract.isDocumentUri(this, uri)) {
            return try { MediaStore.getMediaUri(this, uri) } catch (e: Exception) { null }
        }
        return null
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        done(if (resultCode == RESULT_OK) "Moved to the trash" else null)
    }

    private fun done(msg: String?) {
        msg?.let { Toast.makeText(applicationContext, it, Toast.LENGTH_LONG).show() }
        finish()
    }
}
