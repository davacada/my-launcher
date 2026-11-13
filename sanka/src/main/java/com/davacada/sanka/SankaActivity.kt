package com.davacada.sanka

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Sanka: a locked place for photos and videos. Hidden items are copied in encrypted and removed from the
 * gallery; they can be viewed here, put back in the gallery, or deleted. Locks with a PIN or fingerprint,
 * and locks again as soon as you leave the app. Screenshots and the recent-apps preview are blocked.
 */
class SankaActivity : Activity() {

    private enum class Screen { SETUP, LOCK, VAULT, ADD, VIEWER }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(3)
    private lateinit var pin: Pin
    private var vault: Vault? = null
    private var screen = Screen.LOCK
    private var unlocked = false
    private var awayForSystem = false // our own permission/delete/fingerprint screens shouldn't lock the app
    private var failures = 0
    private var lockedUntil = 0L
    private var insetTop = 0
    private var insetBottom = 0

    private val thumbs = LruCache<String, Bitmap>(240)
    private var root: FrameLayout? = null

    // vault grid
    private val selected = LinkedHashSet<String>()
    private var vaultAdapter: VaultAdapter? = null
    // add screen
    private var gallery: List<Media> = emptyList()
    private val picked = LinkedHashSet<Uri>()
    private var addAdapter: AddAdapter? = null
    // viewer
    private var viewIndex = 0
    private var player: MediaPlayer? = null
    private var playerSource: EncDataSource? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) // no screenshots, blank preview in recent apps
        window.setDecorFitsSystemWindows(false)
        pin = Pin(this)
        root = FrameLayout(this).apply { setBackgroundColor(BG) }
        root!!.setOnApplyWindowInsetsListener { _, ins ->
            val bars = ins.getInsets(android.view.WindowInsets.Type.systemBars())
            if (bars.top != insetTop || bars.bottom != insetBottom) { insetTop = bars.top; insetBottom = bars.bottom; show(screen) }
            ins
        }
        setContentView(root)
        show(if (pin.isSet) Screen.LOCK else Screen.SETUP)
    }

    override fun onResume() {
        super.onResume()
        awayForSystem = false
        if (pin.isSet && !unlocked && screen != Screen.LOCK) show(Screen.LOCK)
        if (screen == Screen.LOCK) main.postDelayed({ if (screen == Screen.LOCK && !unlocked) askFingerprint() }, 250)
    }

    override fun onStop() {
        super.onStop()
        if (!awayForSystem && pin.isSet) lock()
    }

    override fun onDestroy() { releasePlayer(); io.shutdownNow(); super.onDestroy() }

    private fun lock() {
        unlocked = false
        releasePlayer()
        thumbs.evictAll()
        selected.clear(); picked.clear()
        if (pin.isSet) show(Screen.LOCK)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            Screen.VIEWER -> { releasePlayer(); show(Screen.VAULT) }
            Screen.ADD -> { picked.clear(); show(Screen.VAULT) }
            Screen.VAULT -> if (selected.isNotEmpty()) { selected.clear(); show(Screen.VAULT) } else { lock(); moveTaskToBack(true) }
            else -> moveTaskToBack(true)
        }
    }

    // ------------------------------------------------------------------ screens

    private fun show(s: Screen) {
        screen = s
        val r = root ?: return
        r.removeAllViews()
        r.addView(when (s) {
            Screen.SETUP -> setupScreen()
            Screen.LOCK -> lockScreen()
            Screen.VAULT -> vaultScreen()
            Screen.ADD -> addScreen()
            Screen.VIEWER -> viewerScreen()
        }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun brand(sub: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(ImageView(context).apply { setImageResource(R.drawable.ic_mono) }, LinearLayout.LayoutParams(dp(84), dp(84)))
        addView(label("Sanka", 26f, bold = true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        addView(label(sub, 14f, 0xB3FFFFFF.toInt()).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
    }

    private fun setupScreen(): View {
        var first: String? = null
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val title = brand("Choose a PIN (4 to 8 digits)")
        col.addView(title, LinearLayout.LayoutParams(-2, -2).apply { topMargin = insetTop + dp(40); bottomMargin = dp(30) })
        lateinit var pad: PinPad
        pad = PinPad(this, false, {}) { entered ->
            val sub = (title as LinearLayout).getChildAt(2) as TextView
            if (first == null) { first = entered; pad.clear(); sub.text = "Type it again to confirm" }
            else if (first == entered) {
                pin.set(entered); unlocked = true
                Toast.makeText(this, "PIN set", Toast.LENGTH_SHORT).show()
                openVault()
            } else { first = null; sub.text = "Didn't match. Choose a PIN (4 to 8 digits)"; pad.shake() }
        }
        col.addView(pad)
        return col
    }

    private fun fingerprintAvailable(): Boolean =
        pin.fingerprint && getSystemService(BiometricManager::class.java)
            ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    private fun lockScreen(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val title = brand("Enter your PIN")
        col.addView(title, LinearLayout.LayoutParams(-2, -2).apply { topMargin = insetTop + dp(40); bottomMargin = dp(30) })
        lateinit var pad: PinPad
        pad = PinPad(this, fingerprintAvailable(), { askFingerprint() }) { entered ->
            val sub = (title as LinearLayout).getChildAt(2) as TextView
            val now = SystemClock.elapsedRealtime()
            if (now < lockedUntil) { sub.text = "Too many tries. Wait ${(lockedUntil - now) / 1000 + 1} s"; pad.shake(); return@PinPad }
            if (pin.check(entered)) { failures = 0; unlocked = true; openVault() }
            else {
                failures++
                if (failures % 5 == 0) lockedUntil = now + 30_000L * (failures / 5)
                sub.text = if (now < lockedUntil) "Too many tries. Wait ${(lockedUntil - now) / 1000} s" else "Wrong PIN"
                pad.shake()
            }
        }
        col.addView(pad)
        return col
    }

    private fun askFingerprint() {
        if (!fingerprintAvailable() || unlocked) return
        val exec = mainExecutor
        try {
            BiometricPrompt.Builder(this)
                .setTitle("Unlock Sanka")
                .setNegativeButton("Use PIN", exec) { _, _ -> }
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
                .authenticate(CancellationSignal(), exec, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        awayForSystem = false; failures = 0; unlocked = true; openVault()
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { awayForSystem = false }
                })
            awayForSystem = true
        } catch (e: Exception) { }
    }

    private fun openVault() {
        if (vault == null) {
            // Unwrapping the key can take a moment the first time.
            vault = try { Vault(this) } catch (e: Exception) {
                Toast.makeText(this, "Couldn't open the vault: ${e.message}", Toast.LENGTH_LONG).show(); return
            }
        }
        show(Screen.VAULT)
    }

    // ------------------------------------------------------------------ vault grid

    private fun header(title: String, sub: String, left: View?, right: List<View>): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(if (left == null) 20 else 6), insetTop + dp(10), dp(10), dp(8))
        if (left != null) addView(left, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 24f, bold = true))
            if (sub.isNotEmpty()) addView(label(sub, 13f, 0xB3FFFFFF.toInt()))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(if (left == null) 0 else 4) })
        right.forEach { addView(it, LinearLayout.LayoutParams(dp(44), dp(44))) }
    }

    private fun vaultScreen(): View {
        val v = vault ?: return View(this)
        val items = v.items
        val frame = FrameLayout(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val photos = items.count { !it.isVideo }; val videos = items.size - photos
        val sub = if (items.isEmpty()) "" else listOfNotNull(
            if (photos > 0) "$photos photo${if (photos == 1) "" else "s"}" else null,
            if (videos > 0) "$videos video${if (videos == 1) "" else "s"}" else null).joinToString(" · ")
        val title = if (selected.isEmpty()) "Sanka" else "${selected.size} selected"
        col.addView(header(title, sub,
            if (selected.isEmpty()) null else iconButton(R.drawable.ic_close, "Cancel selection") { selected.clear(); show(Screen.VAULT) },
            listOf(iconButton(R.drawable.ic_lock, "Lock now") { lock() }, iconButton(R.drawable.ic_more, "Settings") { settingsMenu(it) })))

        if (items.isEmpty()) {
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(ImageView(context).apply { setImageResource(R.drawable.ic_mono); alpha = 0.35f }, LinearLayout.LayoutParams(dp(96), dp(96)))
                addView(label("Nothing hidden yet", 17f, bold = true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
                addView(label("Tap Hide to choose photos and videos.\nThey're moved here, encrypted, and taken out of your gallery.", 14f, 0x99FFFFFF.toInt()).apply {
                    gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f)
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            }, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(80) })
        } else {
            val adapter = VaultAdapter().also { vaultAdapter = it }
            col.addView(GridView(this).apply {
                numColumns = 3; stretchMode = GridView.STRETCH_COLUMN_WIDTH
                horizontalSpacing = dp(4); verticalSpacing = dp(4)
                setPadding(dp(8), dp(4), dp(8), insetBottom + dp(96)); clipToPadding = false
                selector = pill(Color.TRANSPARENT, 0f)
                this.adapter = adapter
                setOnItemClickListener { _, _, pos, _ ->
                    val tapped = items[pos]
                    if (selected.isNotEmpty()) { if (!selected.remove(tapped.id)) selected.add(tapped.id); show(Screen.VAULT) }
                    else { viewIndex = pos; show(Screen.VIEWER) }
                }
                setOnItemLongClickListener { _, view, pos, _ ->
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    selected.add(items[pos].id); show(Screen.VAULT); true
                }
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        frame.addView(col, FrameLayout.LayoutParams(-1, -1))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = pill(0xE6151A22.toInt(), dpf(32f)); setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val lp = { LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(4); marginEnd = dp(4) } }
        if (selected.isEmpty()) {
            bar.addView(glassButton(R.drawable.ic_add, "Hide photos & videos", filled = true) { openAdd() }, lp())
        } else {
            val sel = items.filter { it.id in selected }
            bar.addView(glassButton(R.drawable.ic_unhide, "Unhide") { unhide(sel) }, lp())
            bar.addView(glassButton(R.drawable.ic_trash, "Delete", 0xFFFF6B6B.toInt()) { confirmDelete(sel) }, lp())
        }
        frame.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = insetBottom + dp(20) })
        return frame
    }

    private fun settingsMenu(anchor: View) {
        val fpOk = getSystemService(BiometricManager::class.java)
            ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        val labels = mutableListOf("Change PIN")
        if (fpOk) labels.add(if (pin.fingerprint) "Fingerprint unlock: on" else "Fingerprint unlock: off")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> { unlocked = true; show(Screen.SETUP) }
                    1 -> { pin.fingerprint = !pin.fingerprint; Toast.makeText(this, if (pin.fingerprint) "Fingerprint on" else "Fingerprint off", Toast.LENGTH_SHORT).show() }
                }
            }.show()
    }

    private fun thumbInto(cell: Cell, key: String, load: () -> Bitmap?) {
        val cached = thumbs.get(key)
        if (cached != null) { cell.image.setImageBitmap(cached); return }
        io.execute {
            val b = try { load() } catch (e: Exception) { null } ?: return@execute
            thumbs.put(key, b)
            main.post { if (cell.boundKey == key) cell.image.setImageBitmap(b) }
        }
    }

    private inner class VaultAdapter : BaseAdapter() {
        override fun getCount() = vault?.items?.size ?: 0
        override fun getItem(p: Int) = vault!!.items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val cell = (convert as? Cell) ?: Cell(this@SankaActivity)
            val item = vault!!.items[p]
            cell.set(item.id, item.duration, item.isVideo, selected.isNotEmpty(), item.id in selected)
            thumbInto(cell, item.id) { vault?.thumb(item) }
            return cell
        }
    }

    // ------------------------------------------------------------------ hide (add from gallery)

    private val readPerms get() = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun hasAnyAccess(): Boolean {
        val perms = readPerms.toMutableList()
        if (Build.VERSION.SDK_INT >= 34) perms.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        return perms.any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun askAccess() {
        awayForSystem = true
        val perms = readPerms.toMutableList()
        if (Build.VERSION.SDK_INT >= 34) perms.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        requestPermissions(perms.toTypedArray(), REQ_PERMS)
    }

    private fun openAdd() {
        picked.clear()
        if (!hasAnyAccess()) { askAccess(); return }
        gallery = emptyList()
        show(Screen.ADD)
        io.execute {
            val list = Gallery.recent(this)
            main.post { if (screen == Screen.ADD) { gallery = list; show(Screen.ADD) } }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQ_PERMS) return
        if (hasAnyAccess()) openAdd()
        else Toast.makeText(this, "Sanka needs access to your photos and videos to hide them.", Toast.LENGTH_LONG).show()
    }

    private fun addScreen(): View {
        val frame = FrameLayout(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val partial = Build.VERSION.SDK_INT >= 34 && readPerms.none { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        col.addView(header(if (picked.isEmpty()) "Choose what to hide" else "${picked.size} chosen",
            if (partial) "Only the photos you allowed are shown" else "",
            iconButton(R.drawable.ic_back, "Back") { picked.clear(); show(Screen.VAULT) },
            if (partial) listOf(iconButton(R.drawable.ic_add, "Allow more photos") { askAccess() }) else emptyList()))
        if (gallery.isEmpty()) {
            col.addView(FrameLayout(this).apply {
                addView(ProgressBar(context), FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val adapter = AddAdapter().also { addAdapter = it }
            col.addView(GridView(this).apply {
                numColumns = 4; stretchMode = GridView.STRETCH_COLUMN_WIDTH
                horizontalSpacing = dp(3); verticalSpacing = dp(3)
                setPadding(dp(6), dp(4), dp(6), insetBottom + dp(96)); clipToPadding = false
                selector = pill(Color.TRANSPARENT, 0f)
                this.adapter = adapter
                setOnItemClickListener { _, _, pos, _ ->
                    val m = gallery[pos]
                    if (!picked.remove(m.uri)) picked.add(m.uri)
                    show(Screen.ADD)
                }
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        frame.addView(col, FrameLayout.LayoutParams(-1, -1))
        if (picked.isNotEmpty()) {
            frame.addView(glassButton(R.drawable.ic_lock, "Hide ${picked.size}", filled = true) { hidePicked() },
                FrameLayout.LayoutParams(-2, dp(52), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = insetBottom + dp(24) })
        }
        return frame
    }

    private inner class AddAdapter : BaseAdapter() {
        override fun getCount() = gallery.size
        override fun getItem(p: Int) = gallery[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val cell = (convert as? Cell) ?: Cell(this@SankaActivity)
            val m = gallery[p]
            val key = "g:" + m.uri
            cell.set(key, m.duration, m.isVideo, true, m.uri in picked)
            thumbInto(cell, key) { contentResolver.loadThumbnail(m.uri, Size(300, 300), null) }
            return cell
        }
    }

    private var progress: TextView? = null
    private var progressShade: View? = null

    private fun busy(text: String?) {
        val r = root ?: return
        progressShade?.let { r.removeView(it) }
        progress = null; progressShade = null
        if (text == null) return
        val shade = FrameLayout(this).apply { setBackgroundColor(0xCC0B0F16.toInt()); isClickable = true }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = pill(0xFF151A22.toInt(), dpf(22f)); setPadding(dp(28), dp(24), dp(28), dp(24))
            addView(ProgressBar(context), LinearLayout.LayoutParams(dp(36), dp(36)))
        }
        val t = label(text, 15f).apply { gravity = Gravity.CENTER }
        box.addView(t, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        shade.addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        r.addView(shade, FrameLayout.LayoutParams(-1, -1))
        progress = t; progressShade = shade
    }

    /** Copies the chosen items in (encrypted), then asks Android to remove the originals from the gallery. */
    private fun hidePicked() {
        val v = vault ?: return
        val chosen = gallery.filter { it.uri in picked }
        if (chosen.isEmpty()) return
        busy("Hiding 0 of ${chosen.size}…")
        io.execute {
            val done = ArrayList<Uri>()
            chosen.forEachIndexed { i, m ->
                main.post { progress?.text = "Hiding ${i + 1} of ${chosen.size}…" }
                if (v.add(m) != null) done.add(m.uri)
            }
            main.post {
                busy(null)
                picked.clear(); thumbs.evictAll()
                show(Screen.VAULT)
                if (done.size < chosen.size) Toast.makeText(this, "${chosen.size - done.size} couldn't be read and stayed in the gallery.", Toast.LENGTH_LONG).show()
                if (done.isNotEmpty()) {
                    try {
                        awayForSystem = true
                        startIntentSenderForResult(MediaStore.createDeleteRequest(contentResolver, done).intentSender, REQ_DELETE, null, 0, 0, 0)
                    } catch (e: Exception) {
                        awayForSystem = false
                        Toast.makeText(this, "Hidden. Remove the originals from your gallery yourself.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_DELETE) {
            Toast.makeText(this, if (resultCode == RESULT_OK) "Hidden and removed from the gallery"
                else "Hidden, but the originals are still in the gallery", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ unhide / delete

    private fun unhide(list: List<Item>) {
        val v = vault ?: return
        if (list.isEmpty()) return
        busy("Putting back 0 of ${list.size}…")
        io.execute {
            var ok = 0
            list.forEachIndexed { i, item ->
                main.post { progress?.text = "Putting back ${i + 1} of ${list.size}…" }
                if (v.restore(item)) ok++
            }
            main.post {
                busy(null); selected.clear(); releasePlayer()
                show(Screen.VAULT)
                Toast.makeText(this, if (ok == list.size) "Back in the gallery" else "$ok of ${list.size} put back", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDelete(list: List<Item>) {
        if (list.isEmpty()) return
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (list.size == 1) "Delete this for good?" else "Delete ${list.size} items for good?")
            .setMessage("They're only in Sanka, so they can't be recovered.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val v = vault ?: return@setPositiveButton
                releasePlayer()
                list.forEach { v.remove(it) }
                selected.clear()
                show(Screen.VAULT)
            }.show()
    }

    // ------------------------------------------------------------------ viewer

    private fun viewerScreen(): View {
        val v = vault ?: return View(this)
        val items = v.items
        if (items.isEmpty()) { main.post { show(Screen.VAULT) }; return View(this) }
        viewIndex = viewIndex.coerceIn(0, items.lastIndex)
        val item = items[viewIndex]
        releasePlayer()

        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val chrome = ArrayList<View>()
        val toggleChrome = { chrome.forEach { c -> c.visibility = if (c.visibility == View.VISIBLE) View.GONE else View.VISIBLE } }
        val swipe = { dir: Int ->
            val n = viewIndex + dir
            if (n in items.indices) { viewIndex = n; show(Screen.VIEWER) }
        }

        if (item.isVideo) frame.addView(videoView(item, chrome, toggleChrome, swipe), FrameLayout.LayoutParams(-1, -1))
        else {
            val img = ZoomImageView(this, toggleChrome, swipe)
            frame.addView(img, FrameLayout.LayoutParams(-1, -1))
            thumbs.get(item.id)?.let { img.show(it) }
            val maxSide = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 2
            io.execute {
                val b = v.photo(item, maxSide)
                main.post { if (screen == Screen.VIEWER && items.getOrNull(viewIndex)?.id == item.id && b != null) img.show(b) }
            }
        }

        val date = SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault()).format(Date(if (item.taken > 0) item.taken else item.added))
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), insetTop + dp(6), dp(16), dp(10))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xAA000000.toInt(), 0))
            addView(iconButton(R.drawable.ic_back, "Back") { releasePlayer(); show(Screen.VAULT) }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(date, 15f, bold = true))
                addView(label("${viewIndex + 1} of ${items.size}", 12f, 0xB3FFFFFF.toInt()))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        }
        frame.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP)); chrome.add(top)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = pill(0xCC151A22.toInt(), dpf(32f)); setPadding(dp(8), dp(8), dp(8), dp(8))
            val lp = { LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(4); marginEnd = dp(4) } }
            addView(glassButton(R.drawable.ic_unhide, "Unhide") { unhide(listOf(item)) }, lp())
            addView(glassButton(R.drawable.ic_trash, "Delete", 0xFFFF6B6B.toInt()) { confirmDelete(listOf(item)) }, lp())
        }
        frame.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = insetBottom + dp(20) })
        chrome.add(bar)
        return frame
    }

    private fun videoView(item: Item, chrome: MutableList<View>, toggleChrome: () -> Unit, swipe: (Int) -> Unit): View {
        val frame = FrameLayout(this)
        val surface = SurfaceView(this)
        frame.addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        val playBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_pause_fill); background = oval(0x66000000); setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        frame.addView(playBtn, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER)); chrome.add(playBtn)
        val time = label("0:00", 12f)
        val seek = SeekBar(this).apply { max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(ACCENT); thumbTintList = progressTintList }
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(20), 0)
            addView(seek, LinearLayout.LayoutParams(0, -2, 1f)); addView(time)
        }
        frame.addView(seekRow, FrameLayout.LayoutParams(-1, dp(40), Gravity.BOTTOM).apply { bottomMargin = insetBottom + dp(88) })
        chrome.add(seekRow)

        fun fmt(ms: Int) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
        val tick = object : Runnable {
            override fun run() {
                val p = player ?: return
                try {
                    if (p.duration > 0) { seek.progress = (1000L * p.currentPosition / p.duration).toInt(); time.text = "${fmt(p.currentPosition)} / ${fmt(p.duration)}" }
                } catch (e: Exception) { }
                main.postDelayed(this, 250)
            }
        }
        fun setPlaying(on: Boolean) {
            val p = player ?: return
            if (on) p.start() else p.pause()
            playBtn.setImageResource(if (on) R.drawable.ic_pause_fill else R.drawable.ic_play_fill)
        }
        playBtn.setOnClickListener { player?.let { setPlaying(!it.isPlaying) } }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                val p = player ?: return
                if (fromUser && p.duration > 0) p.seekTo((p.duration.toLong() * v / 1000).toInt())
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        val gestures = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent) = true
            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean { toggleChrome(); return true }
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                if (kotlin.math.abs(vx) > kotlin.math.abs(vy) * 1.5f && kotlin.math.abs(vx) > 600) swipe(if (vx < 0) 1 else -1)
                return true
            }
        })
        surface.setOnTouchListener { _, e -> gestures.onTouchEvent(e) }

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                val v = vault ?: return
                try {
                    val src = EncDataSource(this@SankaActivity, v.dataFile(item))
                    val mp = MediaPlayer()
                    mp.setDataSource(src)
                    mp.setDisplay(h)
                    mp.isLooping = true
                    mp.setOnVideoSizeChangedListener { _, w, hgt ->
                        if (w == 0 || hgt == 0) return@setOnVideoSizeChangedListener
                        val fw = frame.width.toFloat(); val fh = frame.height.toFloat()
                        val s = minOf(fw / w, fh / hgt)
                        surface.layoutParams = FrameLayout.LayoutParams((w * s).toInt(), (hgt * s).toInt(), Gravity.CENTER)
                    }
                    mp.setOnPreparedListener { it.start(); main.post(tick) }
                    mp.setOnErrorListener { _, _, _ -> Toast.makeText(this@SankaActivity, "This video can't be played here. Unhide it to open it in the gallery.", Toast.LENGTH_LONG).show(); true }
                    mp.prepareAsync()
                    player = mp; playerSource = src
                } catch (e: Exception) {
                    Toast.makeText(this@SankaActivity, "Couldn't play this video.", Toast.LENGTH_LONG).show()
                }
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hgt: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) { releasePlayer() }
        })
        return frame
    }

    private fun releasePlayer() {
        player?.let { try { it.release() } catch (e: Exception) { } }
        player = null
        playerSource?.let { try { it.close() } catch (e: Exception) { } }
        playerSource = null
    }

    companion object {
        private const val REQ_PERMS = 11
        private const val REQ_DELETE = 12
    }
}
