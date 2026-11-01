package com.davacada.launcher

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Android's own trash, in the launcher's style. Apps that delete through Android's trash (Files by Google,
 * many galleries, this launcher's "Move to trash" share option) put things here; Android removes them for
 * good after about 30 days. Tap items to select them, then restore them or delete them forever, or empty it all.
 */
class TrashActivity : Activity() {

    private data class Item(val id: Long, val uri: Uri, val name: String, val mime: String, val size: Long, val expires: Long, val kind: Int)

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(3)
    private val thumbs = LruCache<Long, Bitmap>(80)
    private var items: List<Item> = emptyList()
    private val selected = LinkedHashSet<Long>()

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var grid: GridView
    private lateinit var message: LinearLayout
    private lateinit var messageText: TextView
    private lateinit var messageButtons: LinearLayout
    private lateinit var actions: LinearLayout
    private lateinit var emptyButton: TextView
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(build())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun pill(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r }

    private fun build(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(0xD90B0F16.toInt()) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(14), dp(6))
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = TextView(this).apply {
            text = "Trash"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); typeface = Typeface.DEFAULT_BOLD
        }
        subtitle = TextView(this).apply { setTextColor(0xB3FFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) }
        titles.addView(title); titles.addView(subtitle)
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        emptyButton = TextView(this).apply {
            text = "Empty"; setTextColor(0xFFFF6B6B.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), 0); background = pill(0x1AFFFFFF, dp(18).toFloat())
            setOnClickListener { deleteForever(items) }
        }
        header.addView(emptyButton, LinearLayout.LayoutParams(-2, dp(36)))
        col.addView(header)

        // Shortcuts to the other trashes, which other apps can't read: Google Photos (in your Google account)
        // and the phone's gallery ("Recently deleted", kept in the gallery's private storage).
        val shortcuts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(4), dp(16), dp(6))
        }
        shortcuts.addView(shortcut("Google Photos trash") { openPhotosTrash() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) })
        shortcuts.addView(shortcut("Gallery · Recently deleted") { openGalleryTrash() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4) })
        col.addView(shortcuts)

        val body = FrameLayout(this)
        grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(6); verticalSpacing = dp(6)
            setPadding(dp(12), dp(8), dp(12), dp(110)); clipToPadding = false
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = pill(Color.TRANSPARENT, 0f)
            adapter = this@TrashActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> toggle(items[pos].id) }
        }
        body.addView(grid, FrameLayout.LayoutParams(-1, -1))

        message = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(32), 0, dp(32), dp(60)) }
        message.addView(ImageView(this).apply { setImageResource(R.drawable.ic_trash); alpha = 0.5f }, LinearLayout.LayoutParams(dp(56), dp(56)))
        messageText = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f)
        }
        message.addView(messageText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        messageButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        message.addView(messageButtons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
        body.addView(message, FrameLayout.LayoutParams(-1, -1))
        col.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        // Restore / delete forever, shown while something is selected.
        actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = pill(0xE6151A22.toInt(), dp(30).toFloat()); setPadding(dp(8), dp(8), dp(8), dp(8)); visibility = View.GONE
        }
        actions.addView(actionButton(R.drawable.ic_restore, "Restore") { restore(selectedItems()) })
        actions.addView(actionButton(R.drawable.ic_trash, "Delete forever", 0xFFFF6B6B.toInt()) { deleteForever(selectedItems()) })
        root.addView(actions, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(36) })

        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            col.setPadding(0, insets.systemWindowInsetTop, 0, 0)
            @Suppress("DEPRECATION")
            (actions.layoutParams as FrameLayout.LayoutParams).bottomMargin = insets.systemWindowInsetBottom + dp(20)
            insets
        }
        return root
    }

    private fun shortcut(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = "$label  ›"; gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(10), 0, dp(10), 0); background = pill(0x1FFFFFFF, dp(20).toFloat())
        setOnClickListener { onClick() }
    }

    private fun tryStart(i: Intent): Boolean = try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }

    /** Google Photos' trash: its own app if installed (straight to Trash where it supports the link), otherwise the website. */
    private fun openPhotosTrash() {
        val link = Uri.parse("https://photos.google.com/trash")
        if (tryStart(Intent(Intent.ACTION_VIEW, link).setPackage(PHOTOS))) return
        packageManager.getLaunchIntentForPackage(PHOTOS)?.let {
            if (tryStart(it)) { Toast.makeText(this, "Open Library → Trash", Toast.LENGTH_LONG).show(); return }
        }
        tryStart(Intent(Intent.ACTION_VIEW, link))
    }

    /** The phone's own gallery (RedMagic's, not Google Photos); Android doesn't let apps open its Recently deleted album directly. */
    private fun openGalleryTrash() {
        val galleries = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY), 0).map { it.activityInfo.packageName }.distinct()
        val pkg = galleries.firstOrNull { it != PHOTOS }
            ?: packageManager.getInstalledApplications(0).map { it.packageName }
                .firstOrNull { (it.contains("gallery", true) || it.contains("album", true)) && packageManager.getLaunchIntentForPackage(it) != null }
        val launch = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
        if (launch != null && tryStart(launch)) {
            Toast.makeText(this, "Open Albums → Recently deleted", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Couldn't find the phone's gallery app.", Toast.LENGTH_LONG).show()
        }
    }

    private fun actionButton(icon: Int, label: String, color: Int = Color.WHITE, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), 0, dp(20), 0); background = pill(0x14FFFFFF, dp(24).toFloat())
        addView(ImageView(context).apply { setImageResource(icon); setColorFilter(color) }, LinearLayout.LayoutParams(dp(22), dp(22)))
        addView(TextView(context).apply {
            text = label; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(4); marginEnd = dp(4) }
    }

    private fun showMessage(text: String, vararg buttons: Pair<String, () -> Unit>) {
        grid.visibility = View.GONE
        message.visibility = View.VISIBLE
        messageText.text = text
        messageButtons.removeAllViews()
        buttons.forEachIndexed { i, (label, action) ->
            messageButtons.addView(TextView(this).apply {
                this.text = label; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(if (i == 0) 0xFF0D1117.toInt() else Color.WHITE)
                background = pill(if (i == 0) Color.WHITE else 0x1FFFFFFF, dp(22).toFloat())
                setPadding(dp(22), 0, dp(22), 0)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-2, dp(44)).apply { topMargin = dp(8) })
        }
    }

    private fun render() {
        val total = items.sumOf { it.size }
        subtitle.text = if (items.isEmpty()) "Nothing here" else
            "${items.size} item${if (items.size == 1) "" else "s"} · ${Formatter.formatShortFileSize(this, total)} · deleted for good after 30 days"
        emptyButton.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        if (items.isEmpty()) showMessage("The trash is empty.\n\nThings deleted through Android's trash land here, and you can share any file to \"Move to trash\".")
        else { message.visibility = View.GONE; grid.visibility = View.VISIBLE }
        selected.retainAll(items.map { it.id }.toSet())
        actions.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        if (selected.isNotEmpty()) title.text = "${selected.size} selected" else title.text = "Trash"
        adapter.notifyDataSetChanged()
    }

    private fun toggle(id: Long) {
        if (!selected.remove(id)) selected.add(id)
        render()
    }

    private fun selectedItems() = items.filter { it.id in selected }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selected.isNotEmpty()) { selected.clear(); render() } else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ------------------------------------------------------------------ access

    private val mediaPerms get() = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun allFiles() = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()
    private fun mediaOnly() = mediaPerms.any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun refresh() {
        if (Build.VERSION.SDK_INT < 30) { showMessage("The trash needs Android 11 or newer."); return }
        if (!allFiles() && !mediaOnly()) {
            subtitle.text = ""
            emptyButton.visibility = View.GONE
            showMessage("To show what's in the trash, the launcher needs to see your files.",
                "Allow access to all files" to {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                    } catch (e: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                },
                "Photos, videos and music only" to { requestPermissions(mediaPerms, 7) })
            return
        }
        io.execute {
            val list = try { query() } catch (e: Exception) { emptyList() }
            main.post { items = list; render() }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = refresh()

    private fun query(): List<Item> {
        val out = LinkedHashMap<Long, Item>()
        val collections = if (allFiles()) listOf(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL))
        else listOf(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))
        val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_EXPIRES, MediaStore.Files.FileColumns.MEDIA_TYPE)
        for (coll in collections) {
            val args = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_EXPIRES} DESC")
            }
            contentResolver.query(coll, cols, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val mime = c.getString(2) ?: continue // folders have no type
                    val kind = c.getInt(5)
                    val name = (c.getString(1) ?: "File").removePrefix(".trashed-").replace(Regex("^\\d+-"), "")
                    out[id] = Item(id, uriFor(id, kind), name, mime, c.getLong(3), c.getLong(4), kind)
                }
            }
        }
        return out.values.sortedByDescending { it.expires }
    }

    private fun uriFor(id: Long, kind: Int): Uri = ContentUris.withAppendedId(when (kind) {
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    }, id)

    // ------------------------------------------------------------------ restore / delete (Android asks to confirm)

    private fun restore(list: List<Item>) {
        if (list.isEmpty() || Build.VERSION.SDK_INT < 30) return
        ask(MediaStore.createTrashRequest(contentResolver, list.map { it.uri }, false), REQ_RESTORE)
    }

    private fun deleteForever(list: List<Item>) {
        if (list.isEmpty() || Build.VERSION.SDK_INT < 30) return
        ask(MediaStore.createDeleteRequest(contentResolver, list.map { it.uri }), REQ_DELETE)
    }

    private fun ask(pi: android.app.PendingIntent, req: Int) {
        try { startIntentSenderForResult(pi.intentSender, req, null, 0, 0, 0) }
        catch (e: Exception) { Toast.makeText(this, "Android didn't allow that: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == RESULT_OK) {
            Toast.makeText(this, if (requestCode == REQ_RESTORE) "Restored" else "Deleted for good", Toast.LENGTH_SHORT).show()
            selected.clear()
        }
        refresh()
    }

    // ------------------------------------------------------------------ grid

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? Cell) ?: Cell()
            cell.bind(items[position])
            return cell
        }
    }

    private inner class Cell : FrameLayout(this@TrashActivity) {
        val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val glyph = TextView(context).apply {
            gravity = Gravity.CENTER; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }
        val label = TextView(context).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setShadowLayer(4f, 0f, 1f, Color.BLACK); setPadding(dp(6), 0, dp(6), dp(5))
        }
        val days = TextView(context).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), dp(2), dp(6), dp(2)); background = pill(0x99000000.toInt(), dp(8).toFloat())
        }
        val check = View(context)
        var boundId = -1L

        init {
            clipToOutline = true
            background = pill(0x1FFFFFFF, dp(12).toFloat())
            addView(glyph, LayoutParams(-1, -1))
            addView(image, LayoutParams(-1, -1))
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xAA000000.toInt(), 0))
            }, LayoutParams(-1, dp(36), Gravity.BOTTOM))
            addView(label, LayoutParams(-1, -2, Gravity.BOTTOM))
            addView(days, LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(5), dp(5), 0, 0) })
            addView(check, LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(widthMeasureSpec, widthMeasureSpec)

        fun bind(item: Item) {
            boundId = item.id
            label.text = item.name
            val left = ((item.expires * 1000 - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)
            days.text = if (left <= 1) "today" else "$left d"
            glyph.text = item.name.substringAfterLast('.', "").uppercase().take(4).ifEmpty { item.mime.substringBefore('/').uppercase().take(5) }
            val on = item.id in selected
            check.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (on) setColor(Color.WHITE) else { setColor(0x33000000); setStroke(dp(2), Color.WHITE) }
            }
            alpha = if (selected.isNotEmpty() && !on) 0.6f else 1f
            foreground = if (on) GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setStroke(dp(3), Color.WHITE); setColor(0x22FFFFFF) } else null
            val cached = thumbs.get(item.id)
            image.setImageBitmap(cached)
            if (cached == null && Build.VERSION.SDK_INT >= 29 &&
                (item.kind == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE || item.kind == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)) {
                val id = item.id; val uri = item.uri
                io.execute {
                    val bmp = try { contentResolver.loadThumbnail(uri, Size(320, 320), null) } catch (e: Exception) { null }
                    if (bmp != null) {
                        thumbs.put(id, bmp)
                        main.post { if (boundId == id) image.setImageBitmap(bmp) }
                    }
                }
            }
        }
    }

    companion object {
        private const val REQ_RESTORE = 41
        private const val REQ_DELETE = 42
        private const val PHOTOS = "com.google.android.apps.photos"
    }
}
