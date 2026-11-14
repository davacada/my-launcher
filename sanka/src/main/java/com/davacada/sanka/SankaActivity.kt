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

    private enum class Screen { SETUP, LOCK, VAULT, FOLDER, ADD, VIEWER, RESULT, DUPES }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(3)
    private val importer = Executors.newSingleThreadExecutor() // imports one at a time, so nothing is copied twice
    private var importing = false
    private var pickingLockBg = false
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
    private var folderId: String? = null      // the open folder (FOLDER screen)
    private var viewList: List<Item> = emptyList()
    private var addTarget: String? = null     // where new hides go; null = ask
    private var pickingCover = false
    private var scroll: android.widget.ScrollView? = null
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
        takeShared(intent)
        io.execute { ShareProvider.clean(this, 60 * 60_000L) } // old temporary share copies
        show(if (pin.isSet) Screen.LOCK else Screen.SETUP)
    }

    // ------------------------------------------------------------------ shared in from other apps

    /** Photos and videos another app shared to Sanka; brought in once Sanka is unlocked. */
    private val incoming = ArrayList<Uri>()
    private var incomingType: String? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (takeShared(intent) && unlocked && vault != null) importShared()
    }

    private fun takeShared(i: Intent?): Boolean {
        i ?: return false
        val uris = ArrayList<Uri>()
        @Suppress("DEPRECATION")
        when (i.action) {
            Intent.ACTION_SEND -> (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        }
        // Some apps also (or only) list the files in the clip data; take those too.
        if (i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_SEND_MULTIPLE) i.clipData?.let { clip ->
            for (k in 0 until clip.itemCount) clip.getItemAt(k).uri?.let { uris.add(it) }
        }
        if (uris.isEmpty()) return false
        incoming.clear(); incoming.addAll(uris.distinct()); incomingType = i.type
        i.action = Intent.ACTION_MAIN // don't bring them in twice
        return true
    }

    /** Asks which folder, then copies the shared files in, encrypted. The other app keeps its copies. */
    private fun importShared() {
        val v = vault ?: return
        if (incoming.isEmpty()) return
        val uris = ArrayList(incoming.distinct()); incoming.clear()
        val go = { folder: String -> bringIn(uris, folder) }
        val choices = mutableListOf<Pair<String, () -> Unit>>(
            "New folder…" to { newFolderDialog { f -> go(f.id) } },
            "Not in a folder" to { go("") })
        v.folders.forEach { f -> choices.add(f.name to { go(f.id) }) }
        dialog().setTitle("Add ${uris.size} to").setItems(choices.map { it.first }.toTypedArray()) { _, which -> choices[which].second() }
            .setOnCancelListener { incoming.addAll(uris) }
            .show()
    }

    /** Copies shared files in one by one (each gets a second try), then says exactly what happened. */
    private fun bringIn(uris: List<Uri>, folder: String) {
        val v = vault ?: return
        importing = true
        busy("Bringing in 0 of ${uris.size}…")
        importer.execute {
            val before = v.items.size
            val brought = ArrayList<String>()
            val log = ArrayList<String>() // one line per file, for the Details list
            val failed = ArrayList<Uri>()
            val reasons = ArrayList<String>()
            var already = 0
            uris.forEachIndexed { i, u ->
                main.post { progress?.text = "Bringing in ${i + 1} of ${uris.size}…" }
                var ok = false
                var name = u.lastPathSegment ?: "file ${i + 1}"
                var why = ""
                for (attempt in 0..1) {
                    try {
                        v.duplicatesSkipped = 0
                        val m = Gallery.describe(this, u, incomingType)
                        name = m.name
                        val got = v.add(m, folder)
                        if (got != null) {
                            ok = true; brought.add(got.id)
                            why = "added (${android.text.format.Formatter.formatShortFileSize(this, m.size)})"
                            break
                        } else { why = "couldn't be read: " + v.lastError.ifEmpty { "unknown reason" }; if (attempt == 1) reasons.add(v.lastError) }
                    } catch (e: SecurityException) { reasons.add("no permission to read"); why = "no permission to read"; break }
                    catch (e: Exception) { val r = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""); why = r; if (attempt == 1) reasons.add(r) }
                    if (attempt == 0) Thread.sleep(400)
                }
                if (!ok) failed.add(u)
                log.add("${i + 1}. $name — $why")
            }
            val added = v.items.size - before
            main.post {
                busy(null); importing = false
                lastBrought = brought.distinct()
                if (folder.isNotEmpty() && v.folders.any { it.id == folder }) { folderId = folder; show(Screen.FOLDER) } else { folderId = null; show(Screen.VAULT) }
                val msg = StringBuilder("Received ${uris.size} from the other app.\n\n")
                msg.append("• $added new in Sanka\n")
                val dupes = dupesTouching(brought)
                if (dupes.isNotEmpty()) msg.append("• ${dupes.sumOf { it.size - 1 }} look identical to other pictures in Sanka; you'll get to check them next\n")
                if (failed.isNotEmpty()) msg.append("• ${failed.size} couldn't be read" + (reasons.distinct().firstOrNull()?.let { " ($it)" } ?: "") + "\n")
                if (failed.isEmpty()) msg.append("\nOnce you've checked them, you can delete them in the other app.")
                val d = dialog().setTitle(if (failed.isEmpty()) "Done" else "Some didn't come through").setMessage(msg.toString())
                    .setPositiveButton("OK", null)
                    .setNegativeButton("Details") { _, _ -> dialog().setTitle("Each file").setMessage(log.joinToString("\n")).setPositiveButton("OK", null).show() }
                if (failed.isNotEmpty()) d.setNeutralButton("Try those again") { _, _ -> bringIn(failed, folder) }
                else if (lastBrought.isNotEmpty()) d.setNeutralButton("Show them") { _, _ -> resultOpen = true; selected.clear(); show(Screen.RESULT) }
                d.setOnDismissListener { if (dupes.isNotEmpty()) askAboutDupes(dupes, true) }
                d.show()
            }
        }
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
        selected.clear(); picked.clear(); pickingLockBg = false; pickingCover = false; resultOpen = false; dupesOpen = false
        if (pin.isSet) show(Screen.LOCK)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            Screen.VIEWER -> { releasePlayer(); show(back()) }
            Screen.ADD -> { picked.clear(); show(back()) }
            Screen.DUPES -> { dupesOpen = false; show(Screen.VAULT) }
            Screen.RESULT -> { resultOpen = false; selected.clear(); show(if (folderId != null && vault?.folders?.any { it.id == folderId } == true) Screen.FOLDER else Screen.VAULT) }
            Screen.FOLDER -> if (selected.isNotEmpty() || pickingCover) { selected.clear(); pickingCover = false; rerender() } else { folderId = null; show(Screen.VAULT) }
            Screen.VAULT -> if (selected.isNotEmpty()) { selected.clear(); rerender() } else { lock(); moveTaskToBack(true) }
            else -> moveTaskToBack(true)
        }
    }

    // ------------------------------------------------------------------ screens

    /** Where Back from a viewer or the picker goes. */
    private var lastBrought: List<String> = emptyList() // what the last import brought in (new + already there)
    private var resultOpen = false

    private fun back() = if (dupesOpen) Screen.DUPES else if (resultOpen) Screen.RESULT else if (folderId != null && vault?.folders?.any { it.id == folderId } == true) Screen.FOLDER else Screen.VAULT

    /** Redraws the current screen and keeps the scroll position (after selecting, moving, …). */
    private fun rerender() {
        val y = scroll?.scrollY ?: 0
        show(screen)
        scroll?.let { sv -> sv.post { sv.scrollTo(0, y) } }
    }

    private fun show(s: Screen) {
        screen = s
        val r = root ?: return
        scroll = null
        r.removeAllViews()
        if (s == Screen.SETUP || s == Screen.LOCK) r.addView(lockBackground(), FrameLayout.LayoutParams(-1, -1))
        r.addView(when (s) {
            Screen.SETUP -> setupScreen()
            Screen.LOCK -> lockScreen()
            Screen.VAULT -> homeScreen()
            Screen.FOLDER -> folderScreen()
            Screen.RESULT -> resultScreen()
            Screen.DUPES -> dupesScreen()
            Screen.ADD -> addScreen()
            Screen.VIEWER -> viewerScreen()
        }, FrameLayout.LayoutParams(-1, -1))
    }

    /** Behind the PIN pad: the moving aurora, or a picture you chose, heavily blurred and dimmed. */
    private fun lockBackground(): View {
        val id = prefs.getString("lockBg", null) ?: return AuroraView(this)
        val frame = FrameLayout(this).apply { setBackgroundColor(BG) }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f
            setRenderEffect(android.graphics.RenderEffect.createBlurEffect(dpf(28f), dpf(28f), android.graphics.Shader.TileMode.CLAMP))
        }
        frame.addView(img, FrameLayout.LayoutParams(-1, -1))
        frame.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x660B0F16, 0x8C0B0F16.toInt(), 0xE60B0F16.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))
        io.execute {
            val v = vault ?: try { Vault(this).also { vault = it } } catch (e: Exception) { null }
            val item = v?.items?.firstOrNull { it.id == id }
            val b = item?.let { v.thumb(it) }
            main.post {
                if (b != null) { img.setImageBitmap(b); img.animate().alpha(1f).setDuration(400).start() }
                else { prefs.edit().remove("lockBg").apply() } // picture no longer in Sanka: back to the aurora next time
            }
        }
        return frame
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
        if (incoming.isNotEmpty()) main.post { importShared() }
        // Items hidden before folders existed don't know their shape yet; work it out once.
        importer.execute {
            val v = vault ?: return@execute
            val sized = v.fillInSizes()
            val changed = v.checkUp() >= 0 // real creation dates and fingerprints for older items
            val dupes = v.duplicateGroups().filter { it.first().hash !in keptHashes() }
            main.post {
                if ((sized || changed) && (screen == Screen.VAULT || screen == Screen.FOLDER)) rerender()
                if (dupes.isNotEmpty() && unlocked && !importing) askAboutDupes(dupes, false)
            }
        }
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

    private fun section(title: String, right: View?): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(14), dp(16), dp(10))
        addView(label(title, 12.5f, 0xFFA9B2C0.toInt(), bold = true).apply { letterSpacing = 0.08f }, LinearLayout.LayoutParams(0, -2, 1f))
        if (right != null) addView(right)
    }

    private fun smallPill(icon: Int, text: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), 0, dp(14), 0); background = pill(0x29C9A2FF, dpf(17f))
        addView(ImageView(context).apply { setImageResource(icon); setColorFilter(ACCENT) }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6) })
        addView(label(text, 13f, ACCENT, bold = true))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, dp(34))
    }

    private fun count(list: List<Item>): String {
        val photos = list.count { !it.isVideo }; val videos = list.size - photos
        return listOfNotNull(if (photos > 0) "$photos photo${if (photos == 1) "" else "s"}" else null,
            if (videos > 0) "$videos video${if (videos == 1) "" else "s"}" else null).joinToString(" · ").ifEmpty { "Empty" }
    }

    /** Photos and videos as a collage that keeps each one's shape. */
    // ------------------------------------------------------------------ order: by date created, newest added, or by shape

    private val prefs get() = getSharedPreferences("sanka", MODE_PRIVATE)
    /** "date" (when the file was created), "newest" (when it came into Sanka) or "format" (vertical / horizontal). */
    private var sortMode: String
        get() = prefs.getString("sort", "date") ?: "date"
        set(v) { prefs.edit().putString("sort", v).apply() }
    private val byDate get() = sortMode != "newest"

    /**
     * When the file itself was created: the date written inside it (or in its name). Failing that, the gallery's date,
     * but only if it's clearly older than the import (otherwise it's just the day the file was copied). 0 = unknown.
     */
    private fun createdOf(it: Item): Long = when {
        it.made > 0 -> it.made
        it.taken > 0 && it.taken < it.added - 6 * 3_600_000L -> it.taken
        else -> 0L
    }
    private fun whenOf(it: Item) = createdOf(it).takeIf { t -> t > 0 } ?: it.added

    /** Newest created first; things whose files carry no date at all go last (newest added first among them). */
    private fun byCreated(list: List<Item>) = list.sortedWith(compareByDescending<Item> { createdOf(it) > 0 }.thenByDescending { createdOf(it) }.thenByDescending { it.added })

    private fun shape(it: Item) = when { it.aspect < 0.95f -> 0; it.aspect > 1.05f -> 1; else -> 2 }

    private fun ordered(list: List<Item>) = when (sortMode) {
        "newest" -> list.sortedByDescending { it.added }
        "format" -> byCreated(list).sortedBy { shape(it) } // stable: keeps date order inside each shape
        else -> byCreated(list)
    }

    private fun sortMenu() {
        val modes = listOf("date" to "By date created (newest first)", "newest" to "Newest added to Sanka first", "format" to "By format: vertical, then horizontal")
        val labels = modes.map { (k, l) -> (if (sortMode == k) "✓  " else "     ") + l }.toTypedArray()
        dialog().setTitle("Order").setItems(labels) { _, which -> sortMode = modes[which].first; rerender() }.show()
    }

    /** The collage in the chosen order. By format it's split into Vertical / Horizontal / Square; by date, files without a date come last. */
    private fun collage(list: List<Item>, targetDp: Int): View {
        val all = ordered(list)
        val parts: List<Pair<String, List<Item>>> = when (sortMode) {
            "format" -> listOf("VERTICAL", "HORIZONTAL", "SQUARE").mapIndexed { i, t -> t to all.filter { shape(it) == i } }.filter { it.second.isNotEmpty() }
            "date" -> {
                val undated = all.filter { createdOf(it) == 0L }
                if (undated.isEmpty() || undated.size == all.size) listOf("" to all)
                else listOf("" to all.filter { createdOf(it) > 0 }, "NO CREATION DATE IN THE FILE" to undated)
            }
            else -> listOf("" to all)
        }
        if (parts.size == 1 && parts[0].first.isEmpty()) return collageRows(all, all, 0, targetDp)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var offset = 0
        parts.forEach { (title, items) ->
            if (title.isNotEmpty()) col.addView(section(title, label("${items.size}", 12.5f, 0xFFA9B2C0.toInt())))
            col.addView(collageRows(items, all, offset, targetDp))
            offset += items.size
        }
        return col
    }

    /** One justified block of [part]; [all] and [offset] let the viewer swipe through everything in order. */
    private fun collageRows(part: List<Item>, all: List<Item>, offset: Int, targetDp: Int): View {
        val list = part
        val j = JustifiedLayout(this, targetDp).apply { aspects = list.map { it.aspect }; setPadding(dp(8), 0, dp(8), 0) }
        list.forEachIndexed { i, item ->
            val cell = Cell(this, square = false, radius = 6f)
            cell.set(item.id, item.duration, item.isVideo, selected.isNotEmpty(), item.id in selected)
            thumbInto(cell, item.id) { vault?.thumb(item) }
            cell.setOnClickListener {
                when {
                    pickingLockBg -> {
                        prefs.edit().putString("lockBg", item.id).apply()
                        pickingLockBg = false
                        Toast.makeText(this, "Lock screen background set", Toast.LENGTH_SHORT).show()
                        rerender()
                    }
                    pickingCover -> {
                        folderId?.let { f -> vault?.setCover(f, item.id) }
                        pickingCover = false
                        Toast.makeText(this, "Cover changed", Toast.LENGTH_SHORT).show()
                        show(Screen.FOLDER)
                    }
                    selected.isNotEmpty() -> { if (!selected.remove(item.id)) selected.add(item.id); rerender() }
                    else -> { viewList = all; viewIndex = offset + i; show(Screen.VIEWER) }
                }
            }
            cell.setOnLongClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                pickingCover = false
                if (!selected.remove(item.id)) selected.add(item.id); rerender(); true
            }
            j.addView(cell)
        }
        return j
    }

    private fun bottomBar(frame: FrameLayout, buttons: List<View>) {
        frame.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(BG, BG, 0))
        }, FrameLayout.LayoutParams(-1, insetBottom + dp(110), Gravity.BOTTOM))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = pill(0xEB151A22.toInt(), dpf(32f)); setPadding(dp(8), dp(8), dp(8), dp(8)); elevation = dpf(8f)
        }
        buttons.forEach { bar.addView(it, LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(4); marginEnd = dp(4) }) }
        frame.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = insetBottom + dp(20) })
    }

    private fun selectionButtons(items: List<Item>, inFolder: Boolean): List<View> {
        val sel = items.filter { it.id in selected }
        val list = mutableListOf<View>(
            compactButton(R.drawable.ic_share, "Share") { share(sel) },
            compactButton(R.drawable.ic_folder_move, "Move") { moveTo(sel) },
            compactButton(R.drawable.ic_unhide, "Unhide") { unhide(sel) },
            compactButton(R.drawable.ic_trash, "Delete", 0xFFFF6B6B.toInt()) { confirmDelete(sel) })
        if (inFolder && sel.size == 1) list.add(1, compactButton(R.drawable.ic_image, "Cover") {
            folderId?.let { f -> vault?.setCover(f, sel[0].id) }; selected.clear(); show(Screen.FOLDER)
        })
        return list
    }

    // ------------------------------------------------------------------ home: folders + loose items

    private fun homeScreen(): View {
        val v = vault ?: return View(this)
        val frame = FrameLayout(this)
        val sv = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
        scroll = sv
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, insetBottom + dp(110)) }
        sv.addView(col)
        val loose = v.inFolder("")
        // Folders follow the same order: the one with the latest picture (taken or added) first.
        val folders = v.folders.sortedByDescending { f ->
            v.inFolder(f.id).maxOfOrNull { if (byDate) whenOf(it) else it.added } ?: f.created
        }
        val sub = "${folders.size} folder${if (folders.size == 1) "" else "s"} · ${v.items.size} item${if (v.items.size == 1) "" else "s"}"
        col.addView(header(if (selected.isEmpty()) "Sanka" else "${selected.size} selected", sub,
            if (selected.isEmpty()) null else iconButton(R.drawable.ic_close, "Cancel selection") { selected.clear(); rerender() },
            listOf(iconButton(R.drawable.ic_sort, "Order") { sortMenu() }, iconButton(R.drawable.ic_lock, "Lock now") { lock() }, iconButton(R.drawable.ic_more, "Settings") { settingsMenu(it) })))

        if (v.items.isEmpty() && folders.isEmpty()) {
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(32), dp(120), dp(32), 0)
                addView(ImageView(context).apply { setImageResource(R.drawable.ic_mono); alpha = 0.35f }, LinearLayout.LayoutParams(dp(96), dp(96)))
                addView(label("Nothing hidden yet", 17f, bold = true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
                addView(label("Tap Hide to choose photos and videos.\nThey're moved here, encrypted, and taken out of your gallery.", 14f, 0x99FFFFFF.toInt()).apply {
                    gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f)
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
                addView(smallPill(R.drawable.ic_add, "New folder") { newFolderDialog { show(Screen.VAULT) } }, LinearLayout.LayoutParams(-2, dp(34)).apply { topMargin = dp(20) })
            })
        } else {
            col.addView(section("FOLDERS", if (selected.isEmpty()) smallPill(R.drawable.ic_add, "New folder") { newFolderDialog { show(Screen.VAULT) } } else null))
            if (folders.isEmpty()) col.addView(label("No folders yet. Make one to sort what you hide.", 13.5f, 0x99FFFFFF.toInt()).apply { setPadding(dp(20), 0, dp(20), dp(6)) })
            val tileW = (resources.displayMetrics.widthPixels - dp(16) * 2 - dp(12)) / 2
            folders.chunked(2).forEach { pair ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), 0, dp(16), dp(16)) }
                pair.forEachIndexed { i, f -> row.addView(folderTile(f), LinearLayout.LayoutParams(tileW, -2).apply { if (i == 1) marginStart = dp(12) }) }
                col.addView(row)
            }
            if (loose.isNotEmpty()) {
                col.addView(section("NOT IN A FOLDER", label("${loose.size}", 12.5f, 0xFFA9B2C0.toInt())))
                col.addView(collage(loose, 130))
            }
        }
        frame.addView(sv, FrameLayout.LayoutParams(-1, -1))
        bottomBar(frame, if (pickingLockBg) listOf(glassButton(R.drawable.ic_close, "Cancel choosing background") { pickingLockBg = false; rerender() })
            else if (selected.isEmpty()) listOf(glassButton(R.drawable.ic_add, "Hide photos & videos", filled = true) { addTarget = null; openAdd() })
            else selectionButtons(loose, false))
        return frame
    }

    private fun folderTile(f: Folder): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val v = vault!!
        val inside = v.inFolder(f.id)
        val cover = v.coverOf(f)
        val cell = Cell(context, square = true, radius = 20f)
        cell.set("cover:" + (cover?.id ?: f.id), 0, false, false, false)
        if (cover != null) thumbInto(cell, cover.id) { v.thumb(cover) }
        else cell.addView(ImageView(context).apply { setImageResource(R.drawable.ic_folder_move); alpha = 0.3f },
            FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        addView(cell)
        addView(label(f.name, 15f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(4), dp(8), dp(4), 0) })
        addView(label(if (inside.isEmpty()) "Empty" else "${inside.size} item${if (inside.size == 1) "" else "s"}", 12f, 0xFFA9B2C0.toInt()).apply { setPadding(dp(4), dp(1), dp(4), 0) })
        setOnClickListener { selected.clear(); folderId = f.id; show(Screen.FOLDER) }
        setOnLongClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); folderMenu(f); true }
    }

    // ------------------------------------------------------------------ inside a folder

    private fun folderScreen(): View {
        val v = vault ?: return View(this)
        val f = v.folders.firstOrNull { it.id == folderId } ?: run { main.post { show(Screen.VAULT) }; return View(this) }
        val items = v.inFolder(f.id)
        val cover = v.coverOf(f)
        val frame = FrameLayout(this)
        val sv = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        scroll = sv
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, insetBottom + dp(110)) }
        sv.addView(col)

        // Cover across the top, fading into the background, with the name over it.
        val hero = FrameLayout(this)
        val heroImg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        hero.addView(heroImg, FrameLayout.LayoutParams(-1, -1))
        if (cover != null) {
            thumbs.get(cover.id)?.let { heroImg.setImageBitmap(it) } ?: io.execute { v.thumb(cover)?.let { b -> main.post { if (heroImg.drawable == null) heroImg.setImageBitmap(b) } } }
            if (!cover.isVideo) io.execute { v.photo(cover, 1600)?.let { b -> main.post { heroImg.setImageBitmap(b) } } }
        }
        hero.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x8C0B0F16.toInt(), 0x000B0F16, 0x330B0F16, BG))
        }, FrameLayout.LayoutParams(-1, -1))
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), insetTop + dp(4), dp(8), 0)
            addView(iconButton(if (selected.isEmpty()) R.drawable.ic_back else R.drawable.ic_close, if (selected.isEmpty()) "Back to folders" else "Cancel selection") {
                if (selected.isNotEmpty() || pickingCover) { selected.clear(); pickingCover = false; rerender() } else { folderId = null; show(Screen.VAULT) }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(iconButton(R.drawable.ic_sort, "Order") { sortMenu() }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(iconButton(R.drawable.ic_more, "Folder options") { folderMenu(f) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        hero.addView(topRow, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        hero.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(14))
            addView(label(if (selected.isEmpty()) f.name else "${selected.size} selected", 30f, bold = true).apply { maxLines = 2 })
            addView(label(if (pickingCover) "Tap the picture you want as the cover" else count(items), 13.5f, if (pickingCover) ACCENT else 0xFFCFD5DE.toInt()))
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        col.addView(hero, LinearLayout.LayoutParams(-1, insetTop + dp(if (cover != null) 260 else 170)))

        if (items.isEmpty()) {
            col.addView(label("This folder is empty.\nTap Add to hide photos and videos straight into it.", 14f, 0x99FFFFFF.toInt()).apply {
                gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f); setPadding(dp(32), dp(40), dp(32), 0)
            })
        } else col.addView(collage(items, 160))
        frame.addView(sv, FrameLayout.LayoutParams(-1, -1))

        bottomBar(frame, when {
            pickingLockBg -> listOf(glassButton(R.drawable.ic_close, "Cancel choosing background") { pickingLockBg = false; rerender() })
            selected.isNotEmpty() -> selectionButtons(items, true)
            pickingCover -> listOf(glassButton(R.drawable.ic_close, "Cancel") { pickingCover = false; rerender() })
            else -> listOfNotNull(
                glassButton(R.drawable.ic_add, "Add", filled = true) { addTarget = f.id; openAdd() },
                if (items.isNotEmpty()) glassButton(R.drawable.ic_image, "Change cover") {
                    pickingCover = true; selected.clear(); rerender()
                } else null)
        })
        return frame
    }

    // ------------------------------------------------------------------ identical pictures: you decide

    private var pendingDupes: List<List<Item>> = emptyList()
    private var dupeGroups: List<List<String>> = emptyList()
    private val dupeKeep = HashSet<String>()
    private var dupesOpen = false

    /** Hashes you've already said to keep both copies of, so Sanka doesn't ask again. */
    private fun keptHashes(): Set<String> = prefs.getStringSet("keptDupes", emptySet()) ?: emptySet()
    private fun keepHashes(h: Collection<String>) = prefs.edit().putStringSet("keptDupes", keptHashes() + h).apply()

    /** Groups of identical pictures that include any of [ids]. */
    private fun dupesTouching(ids: Collection<String>): List<List<Item>> {
        val set = ids.toSet()
        return vault?.duplicateGroups()?.filter { g -> g.any { it.id in set } } ?: emptyList()
    }

    private fun flushDupes() {
        val d = pendingDupes; pendingDupes = emptyList()
        if (d.isNotEmpty()) askAboutDupes(d, true)
    }

    private fun askAboutDupes(groups: List<List<Item>>, justAdded: Boolean) {
        val extra = groups.sumOf { it.size - 1 }
        val text = if (justAdded) "$extra of what you just added ${if (extra == 1) "is" else "are"} identical to other pictures in Sanka (same contents)."
                   else "Sanka found ${groups.size} set${if (groups.size == 1) "" else "s"} of identical pictures ($extra extra cop${if (extra == 1) "y" else "ies"})."
        dialog().setTitle("Possible duplicates").setMessage("$text\n\nWhat would you like to do?")
            .setPositiveButton("Look at them") { _, _ -> openDupes(groups) }
            .setNeutralButton("Keep everything") { _, _ -> keepHashes(groups.map { it.first().hash }) }
            .setNegativeButton("Keep one of each") { _, _ ->
                val v = vault ?: return@setNegativeButton
                groups.forEach { g -> g.drop(1).forEach { v.remove(it) } } // keeps the one that was there first
                thumbs.evictAll(); rerender()
                Toast.makeText(this, "Removed $extra extra cop${if (extra == 1) "y" else "ies"}", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun openDupes(groups: List<List<Item>>) {
        dupeGroups = groups.map { g -> g.map { it.id } }
        dupeKeep.clear(); dupeKeep.addAll(groups.map { it.first().id }) // first guess: keep the one that was there first
        dupesOpen = true; selected.clear()
        show(Screen.DUPES)
    }

    private fun dupesScreen(): View {
        val v = vault ?: return View(this)
        val byId = v.items.associateBy { it.id }
        val groups = dupeGroups.map { g -> g.mapNotNull { byId[it] } }.filter { it.size > 1 }
        val frame = FrameLayout(this)
        val sv = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        scroll = sv
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, insetBottom + dp(110)) }
        sv.addView(col)
        col.addView(header("Possible duplicates", "Tap to choose which to keep · hold to look closer",
            iconButton(R.drawable.ic_back, "Back") { onBackPressed() }, emptyList()))
        if (groups.isEmpty()) col.addView(label("No duplicates left.", 14f, 0x99FFFFFF.toInt()).apply { setPadding(dp(24), dp(24), dp(24), 0) })
        val tileW = dp(150)
        val fmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        groups.forEachIndexed { gi, g ->
            col.addView(section("SET ${gi + 1} · ${g.size} IDENTICAL", null))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), 0, dp(14), dp(6)) }
            g.forEach { item ->
                val keep = item.id in dupeKeep
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    val cell = Cell(context, square = true, radius = 14f)
                    cell.set("d:" + item.id, item.duration, item.isVideo, true, keep)
                    thumbInto(cell, item.id) { v.thumb(item) }
                    addView(cell, LinearLayout.LayoutParams(tileW, tileW))
                    val where = v.folders.firstOrNull { it.id == item.folder }?.name ?: "Not in a folder"
                    addView(label(item.name, 12.5f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(dp(2), dp(6), dp(2), 0) },
                        LinearLayout.LayoutParams(tileW, -2))
                    addView(label("${android.text.format.Formatter.formatShortFileSize(context, item.size)} · $where\nadded ${fmt.format(Date(item.added))}", 11.5f, 0xFFA9B2C0.toInt()).apply { setPadding(dp(2), dp(1), dp(2), 0) },
                        LinearLayout.LayoutParams(tileW, -2))
                    addView(label(if (keep) "Keep" else "Delete", 12f, if (keep) ACCENT else 0xFFFF6B6B.toInt(), bold = true).apply { setPadding(dp(2), dp(3), dp(2), 0) })
                    setOnClickListener {
                        if (keep) { if (g.count { it.id in dupeKeep } > 1) dupeKeep.remove(item.id) else Toast.makeText(context, "At least one copy stays", Toast.LENGTH_SHORT).show() }
                        else dupeKeep.add(item.id)
                        rerender()
                    }
                    setOnLongClickListener { viewList = g; viewIndex = g.indexOf(item); show(Screen.VIEWER); true }
                }
                row.addView(tile, LinearLayout.LayoutParams(tileW, -2).apply { marginEnd = dp(12) })
            }
            col.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        }
        frame.addView(sv, FrameLayout.LayoutParams(-1, -1))
        val toDelete = groups.flatten().filter { it.id !in dupeKeep }
        bottomBar(frame, listOf(
            glassButton(null, "Keep all") { keepHashes(groups.map { it.first().hash }); dupesOpen = false; show(Screen.VAULT) },
            glassButton(R.drawable.ic_trash, if (toDelete.isEmpty()) "Done" else "Delete ${toDelete.size}", if (toDelete.isEmpty()) Color.WHITE else 0xFFFF6B6B.toInt()) {
                toDelete.forEach { v.remove(it) }
                // sets where you kept more than one copy on purpose won't be asked about again
                keepHashes(groups.filter { g -> g.count { it.id in dupeKeep } > 1 }.map { it.first().hash })
                thumbs.evictAll(); dupesOpen = false
                if (toDelete.isNotEmpty()) Toast.makeText(this, "Deleted ${toDelete.size}", Toast.LENGTH_SHORT).show()
                show(Screen.VAULT)
            }))
        return frame
    }

    // ------------------------------------------------------------------ what the last import brought in

    private fun resultScreen(): View {
        val v = vault ?: return View(this)
        val ids = lastBrought.toSet()
        val items = v.items.filter { it.id in ids }
        val frame = FrameLayout(this)
        val sv = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        scroll = sv
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, insetBottom + dp(110)) }
        sv.addView(col)
        col.addView(header(if (selected.isEmpty()) "Just brought in" else "${selected.size} selected",
            count(items) + " · new and already here",
            iconButton(if (selected.isEmpty()) R.drawable.ic_back else R.drawable.ic_close, "Back") {
                if (selected.isNotEmpty()) { selected.clear(); rerender() } else onBackPressed()
            }, listOf(iconButton(R.drawable.ic_sort, "Order") { sortMenu() })))
        if (items.isEmpty()) col.addView(label("Nothing to show.", 14f, 0x99FFFFFF.toInt()).apply { setPadding(dp(24), dp(24), dp(24), 0) })
        else col.addView(collage(items, 150))
        frame.addView(sv, FrameLayout.LayoutParams(-1, -1))
        if (selected.isNotEmpty()) bottomBar(frame, selectionButtons(items, false))
        return frame
    }

    // ------------------------------------------------------------------ folder dialogs

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun nameDialog(title: String, start: String, ok: String, done: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            setText(start); setSelection(start.length); setSingleLine(true); hint = "Folder name"
            setTextColor(Color.WHITE); setHintTextColor(0x80FFFFFF.toInt())
        }
        val box = FrameLayout(this).apply { setPadding(dp(22), dp(8), dp(22), 0); addView(input) }
        val d = dialog().setTitle(title).setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(ok) { _, _ -> done(input.text.toString()) }
            .create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        input.requestFocus()
    }

    private fun newFolderDialog(then: (Folder) -> Unit) =
        nameDialog("New folder", "", "Create") { name -> vault?.newFolder(name)?.let(then) }

    private fun folderMenu(f: Folder) {
        dialog().setTitle(f.name).setItems(arrayOf("Rename", "Change cover", "Delete folder")) { _, which ->
            when (which) {
                0 -> nameDialog("Rename folder", f.name, "Save") { n -> vault?.renameFolder(f.id, n); rerender() }
                1 -> { folderId = f.id; selected.clear(); pickingCover = vault?.inFolder(f.id)?.isNotEmpty() == true; show(Screen.FOLDER) }
                2 -> dialog().setTitle("Delete \"${f.name}\"?")
                    .setMessage(vault?.inFolder(f.id)?.size.let { n -> if (n == null || n == 0) "The folder is empty." else "The folder and the $n item${if (n == 1) "" else "s"} in it will be deleted for good. They can't be recovered; unhide anything you want to keep first." })
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete folder") { _, _ -> thumbs.evictAll(); vault?.deleteFolder(f.id); folderId = null; selected.clear(); show(Screen.VAULT) }
                    .show()
            }
        }.show()
    }

    /** Asks which folder (or none, or a new one), then calls [done] with its id ("" = not in a folder). */
    private fun chooseFolder(title: String, exclude: String?, done: (String) -> Unit) {
        val v = vault ?: return
        val choices = mutableListOf<Pair<String, String?>>("New folder…" to null)
        if (exclude != "") choices.add("Not in a folder" to "")
        v.folders.filter { it.id != exclude }.forEach { choices.add(it.name to it.id) }
        dialog().setTitle(title).setItems(choices.map { it.first }.toTypedArray()) { _, which ->
            val id = choices[which].second
            if (id == null) newFolderDialog { f -> done(f.id) } else done(id)
        }.show()
    }

    private fun moveTo(list: List<Item>) {
        if (list.isEmpty()) return
        val here = list.first().folder.takeIf { s -> list.all { it.folder == s } }
        chooseFolder("Move ${list.size} to", here) { id ->
            vault?.move(list.map { it.id }, id)
            selected.clear()
            Toast.makeText(this, "Moved", Toast.LENGTH_SHORT).show()
            rerender()
        }
    }

    private fun settingsMenu(anchor: View) {
        val fpOk = getSystemService(BiometricManager::class.java)
            ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        val labels = mutableListOf("Change PIN", "Lock screen background", "Find duplicates")
        if (fpOk) labels.add(if (pin.fingerprint) "Fingerprint unlock: on" else "Fingerprint unlock: off")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> { unlocked = true; show(Screen.SETUP) }
                    1 -> dialog().setTitle("Lock screen background").setItems(arrayOf("Aurora (moving glow)", "One of my pictures, blurred")) { _, w ->
                        if (w == 0) { prefs.edit().remove("lockBg").apply(); Toast.makeText(this, "Aurora it is", Toast.LENGTH_SHORT).show() }
                        else { pickingLockBg = true; selected.clear(); rerender(); Toast.makeText(this, "Tap the picture you want behind the lock screen", Toast.LENGTH_LONG).show() }
                    }.show()
                    2 -> { val g = vault?.duplicateGroups().orEmpty(); if (g.isEmpty()) Toast.makeText(this, "No duplicates", Toast.LENGTH_SHORT).show() else openDupes(g) }
                    3 -> { pin.fingerprint = !pin.fingerprint; Toast.makeText(this, if (pin.fingerprint) "Fingerprint on" else "Fingerprint off", Toast.LENGTH_SHORT).show() }
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

    private var album: String? = null          // album filter on the hide screen; null = all
    private var addTitle: TextView? = null
    private var hideButton: View? = null
    private var allButton: TextView? = null

    private fun shown() = if (album == null) gallery else gallery.filter { it.album == album }

    /** Updates the title, the Hide button and the ticks without rebuilding (so the list keeps its place). */
    private fun refreshPicks() {
        addTitle?.text = if (picked.isEmpty()) "Choose what to hide" else "${picked.size} chosen"
        (hideButton as? LinearLayout)?.let { b -> ((b.getChildAt(b.childCount - 1)) as? TextView)?.text = "Hide ${picked.size}" }
        hideButton?.visibility = if (picked.isEmpty()) View.GONE else View.VISIBLE
        val list = shown()
        allButton?.text = if (list.isNotEmpty() && list.all { it.uri in picked }) "Clear" else "Select all"
        addAdapter?.notifyDataSetChanged()
    }

    private fun addScreen(): View {
        val frame = FrameLayout(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val partial = Build.VERSION.SDK_INT >= 34 && readPerms.none { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        val head = header("Choose what to hide",
            if (partial) "Only the photos you allowed are shown" else "",
            iconButton(R.drawable.ic_back, "Back") { picked.clear(); album = null; show(back()) },
            if (partial) listOf(iconButton(R.drawable.ic_add, "Allow more photos") { askAccess() }) else emptyList()) as LinearLayout
        addTitle = (head.getChildAt(1) as LinearLayout).getChildAt(0) as TextView
        // Select all / clear, for the album shown
        allButton = label("Select all", 13f, ACCENT, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0); background = pill(0x29C9A2FF, dpf(17f))
            setOnClickListener {
                val list = shown()
                if (list.isNotEmpty() && list.all { it.uri in picked }) list.forEach { picked.remove(it.uri) } else list.forEach { picked.add(it.uri) }
                refreshPicks()
            }
        }
        head.addView(allButton, LinearLayout.LayoutParams(-2, dp(34)).apply { marginStart = dp(4) })
        col.addView(head)

        if (gallery.isEmpty()) {
            col.addView(FrameLayout(this).apply {
                addView(ProgressBar(context), FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            // Albums as chips, biggest first, so a whole album (e.g. what you exported from another app) is two taps.
            val albums = gallery.groupBy { it.album }.filterKeys { it.isNotEmpty() }.toList().sortedByDescending { it.second.size }
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(2), dp(14), dp(8)) }
            fun chip(text: String, key: String?) = label(text, 13.5f, if (album == key) BG else Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0)
                background = pill(if (album == key) Color.WHITE else 0x1FFFFFFF, dpf(17f))
                setOnClickListener { album = key; show(Screen.ADD) }
            }
            chips.addView(chip("All  ${gallery.size}", null), LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) })
            albums.forEach { (name, list) -> chips.addView(chip("$name  ${list.size}", name), LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) }) }
            col.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

            val adapter = AddAdapter().also { addAdapter = it }
            col.addView(GridView(this).apply {
                numColumns = 4; stretchMode = GridView.STRETCH_COLUMN_WIDTH
                horizontalSpacing = dp(3); verticalSpacing = dp(3)
                setPadding(dp(6), dp(4), dp(6), insetBottom + dp(96)); clipToPadding = false
                selector = pill(Color.TRANSPARENT, 0f)
                this.adapter = adapter
                setOnItemClickListener { _, _, pos, _ ->
                    val m = shown()[pos]
                    if (!picked.remove(m.uri)) picked.add(m.uri)
                    refreshPicks()
                }
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        frame.addView(col, FrameLayout.LayoutParams(-1, -1))
        hideButton = glassButton(R.drawable.ic_lock, "Hide ${picked.size}", filled = true) { hidePicked() }
        frame.addView(hideButton, FrameLayout.LayoutParams(-2, dp(52), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = insetBottom + dp(24) })
        refreshPicks()
        return frame
    }

    private inner class AddAdapter : BaseAdapter() {
        override fun getCount() = shown().size
        override fun getItem(p: Int) = shown()[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val cell = (convert as? Cell) ?: Cell(this@SankaActivity)
            val m = shown()[p]
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
        if (importing) return
        val target = addTarget
        if (target != null) { hideInto(target); return }
        val v = vault ?: return
        val chosen = gallery.filter { it.uri in picked }
        val albums = chosen.map { it.album }.filter { it.isNotEmpty() }.distinct()
        // Offer to keep the albums they came from as Sanka folders (handy when moving everything over from another app).
        val choices = mutableListOf<Pair<String, () -> Unit>>()
        if (albums.isNotEmpty()) choices.add((if (albums.size == 1) "Into a folder called \"${albums[0]}\"" else "Keep their ${albums.size} albums as folders") to { hideInto(null) })
        choices.add("New folder…" to { newFolderDialog { f -> hideInto(f.id) } })
        choices.add("Not in a folder" to { hideInto("") })
        v.folders.forEach { f -> choices.add(f.name to { hideInto(f.id) }) }
        dialog().setTitle("Hide ${chosen.size} in").setItems(choices.map { it.first }.toTypedArray()) { _, which -> choices[which].second() }.show()
    }

    /** [folder] = folder id ("" = none), or null to put each item in a folder named after its album. */
    private fun hideInto(folder: String?) {
        val v = vault ?: return
        val chosen = gallery.filter { it.uri in picked }
        val byAlbum = HashMap<String, String>()
        if (folder == null) chosen.map { it.album }.filter { it.isNotEmpty() }.distinct().forEach { name ->
            byAlbum[name] = (v.folders.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: v.newFolder(name)).id
        }
        if (chosen.isEmpty() || importing) return
        importing = true
        busy("Hiding 0 of ${chosen.size}…")
        importer.execute {
            val done = ArrayList<Uri>()
            val newIds = ArrayList<String>()
            chosen.forEachIndexed { i, m ->
                main.post { progress?.text = "Hiding ${i + 1} of ${chosen.size}…" }
                v.add(m, folder ?: byAlbum[m.album] ?: "")?.let { done.add(m.uri); newIds.add(it.id) }
            }
            main.post {
                busy(null); importing = false
                pendingDupes = dupesTouching(newIds) // asked about once the gallery question is answered
                picked.clear(); album = null
                if (!folder.isNullOrEmpty() && v.folders.any { it.id == folder }) { folderId = folder; show(Screen.FOLDER) } else { folderId = null; show(Screen.VAULT) }
                if (done.size < chosen.size) Toast.makeText(this, "${chosen.size - done.size} couldn't be read and stayed in the gallery.", Toast.LENGTH_LONG).show()
                if (done.isNotEmpty()) {
                    try {
                        awayForSystem = true
                        startIntentSenderForResult(MediaStore.createDeleteRequest(contentResolver, done).intentSender, REQ_DELETE, null, 0, 0, 0)
                    } catch (e: Exception) {
                        awayForSystem = false
                        Toast.makeText(this, "Hidden. Remove the originals from your gallery yourself.", Toast.LENGTH_LONG).show()
                        flushDupes()
                    }
                } else flushDupes()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_DELETE) {
            Toast.makeText(this, if (resultCode == RESULT_OK) "Hidden and removed from the gallery"
                else "Hidden, but the originals are still in the gallery", Toast.LENGTH_LONG).show()
            flushDupes()
        }
    }

    // ------------------------------------------------------------------ share

    /**
     * Unlocks temporary copies into Sanka's private cache and opens Android's share sheet. Only the app you pick
     * can read them; the copies are cleared again within the hour. What you send leaves Sanka unencrypted.
     */
    private fun share(list: List<Item>) {
        val v = vault ?: return
        if (list.isEmpty()) return
        busy(if (list.size == 1) "Getting it ready…" else "Getting ${list.size} ready…")
        io.execute {
            val uris = ArrayList<Uri>()
            val base = java.io.File(cacheDir, "share").apply { mkdirs() }
            for (it in list) {
                try {
                    val dir = java.io.File(base, it.id).apply { mkdirs() }
                    val safe = it.name.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "picture" }
                        .let { n -> if (n.contains('.')) n else n + if (it.isVideo) ".mp4" else ".jpg" }
                    val out = java.io.File(dir, safe)
                    if (!out.exists() || out.length() != it.size) {
                        java.io.FileOutputStream(out).use { o -> EncFile(this, v.dataFile(it)).use { e -> e.stream().copyTo(o, 256 * 1024) } }
                    }
                    uris.add(Uri.Builder().scheme("content").authority(ShareProvider.AUTHORITY).appendPath(it.id).appendPath(safe).build())
                } catch (e: Exception) { }
            }
            main.post {
                busy(null)
                if (uris.isEmpty()) { Toast.makeText(this, "Couldn't get that ready to share.", Toast.LENGTH_LONG).show(); return@post }
                val types = list.map { it.mime }.distinct()
                val type = when {
                    types.size == 1 -> types[0]
                    types.all { it.startsWith("image/") } -> "image/*"
                    types.all { it.startsWith("video/") } -> "video/*"
                    else -> "*/*"
                }
                val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                           else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                send.type = type
                send.clipData = android.content.ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { u -> addItem(android.content.ClipData.Item(u)) } }
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try { startActivity(Intent.createChooser(send, if (uris.size == 1) "Share" else "Share ${uris.size}")) }
                catch (e: Exception) { Toast.makeText(this, "No app to share with.", Toast.LENGTH_LONG).show() }
            }
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
                show(back())
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
                show(back())
            }.show()
    }

    // ------------------------------------------------------------------ viewer

    private fun viewerScreen(): View {
        val v = vault ?: return View(this)
        val gone = v.items.map { it.id }.toSet()
        val items = viewList.filter { it.id in gone }.also { viewList = it }
        if (items.isEmpty()) { main.post { show(back()) }; return View(this) }
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

        val date = createdOf(item).let { c -> if (c > 0) SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault()).format(Date(c))
            else "No date in the file · added " + SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(item.added)) }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), insetTop + dp(6), dp(16), dp(10))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xAA000000.toInt(), 0))
            addView(iconButton(R.drawable.ic_back, "Back") { releasePlayer(); show(back()) }, LinearLayout.LayoutParams(dp(44), dp(44)))
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
            addView(compactButton(R.drawable.ic_share, "Share") { share(listOf(item)) }, lp())
            addView(compactButton(R.drawable.ic_unhide, "Unhide") { unhide(listOf(item)) }, lp())
            addView(compactButton(R.drawable.ic_trash, "Delete", 0xFFFF6B6B.toInt()) { confirmDelete(listOf(item)) }, lp())
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
