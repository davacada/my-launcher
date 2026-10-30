package com.davacada.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.app.AppOpsManager
import android.app.Dialog
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private class AppEntry(
        val key: String,
        val label: String,
        val info: LauncherActivityInfo,
        val white: Bitmap,
        val colour: Bitmap,
        val category: Int,
        val installed: Long
    ) {
        val pkg: String get() = info.componentName.packageName
    }

    private lateinit var launcherApps: LauncherApps
    private lateinit var userManager: UserManager
    private lateinit var prefs: SharedPreferences

    private lateinit var homeColumn: LinearLayout
    private lateinit var homeScroll: ScrollView
    private lateinit var drawer: FrameLayout
    private lateinit var pager: AppPager
    private lateinit var hiddenHint: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var organiserButton: ImageView
    private lateinit var hiddenButton: ImageView
    private lateinit var drawerBg: ImageView
    private lateinit var companion: CompanionView
    private lateinit var companionButton: ImageView

    private var companionOn: Boolean
        get() = prefs.getBoolean("companion", true)
        set(v) { prefs.edit().putBoolean("companion", v).apply() }
    private var drawerPhoto: Bitmap? = null

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var apps: List<AppEntry> = emptyList()
    private var usage: Map<String, Long> = emptyMap()

    private var drawerOpen = false
    private var showHidden = false

    private val renderSize by lazy { dp(46) }   // icon bitmaps are drawn at this size
    private val homeIconSize by lazy { dp(38) }
    private val drawerIconSize by lazy { dp(36) }

    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = loadApps()
        override fun onPackageAdded(packageName: String, user: UserHandle) = loadApps()
        override fun onPackageChanged(packageName: String, user: UserHandle) = loadApps()
        override fun onPackagesAvailable(p: Array<out String>, user: UserHandle, replacing: Boolean) = loadApps()
        override fun onPackagesUnavailable(p: Array<out String>, user: UserHandle, replacing: Boolean) = loadApps()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcherApps = getSystemService(LauncherApps::class.java)
        userManager = getSystemService(UserManager::class.java)
        prefs = getSharedPreferences("launcher", Context.MODE_PRIVATE)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)

        setContentView(buildUi())
        launcherApps.registerCallback(packageCallback, main)
        loadApps()
        loadSavedPhoto()
    }

    override fun onResume() {
        super.onResume()
        // Picks up usage access granted in Settings, and keeps "Most used" fresh.
        if (sort == SORT_USED) refreshUsage()
        // The companion walks in through his door each time you come back home.
        if (::companion.isInitialized && !drawerOpen && companionOn) companion.arrive()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Home button pressed while we're already in front: go back to the home column.
        if (drawerOpen) closeDrawer(animate = true)
    }

    override fun onStop() {
        super.onStop()
        if (drawerOpen) closeDrawer(animate = false)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawerOpen) {
            if (showHidden) setShowHidden(false) else closeDrawer(animate = true)
        }
        // On the home screen itself, Back does nothing.
    }

    override fun onDestroy() {
        launcherApps.unregisterCallback(packageCallback)
        io.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val root = FrameLayout(this)

        // Animated companion, behind the home icons so it never blocks a tap on them.
        companion = CompanionView(this)
        if (!companionOn) companion.visibility = View.GONE
        root.addView(companion, FrameLayout.LayoutParams(-1, -1))

        // Home: one column of icons on the right.
        homeColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        homeScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(homeColumn)
        }
        root.addView(
            homeScroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.BOTTOM
            ).apply { marginEnd = dp(22) }
        )

        // Optional photo behind the drawer (fades in with it).
        drawerBg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        root.addView(drawerBg, FrameLayout.LayoutParams(-1, -1))

        // Drawer: horizontal pages, three icons at the bottom.
        drawer = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = true
            setOnClickListener { closeDrawer(animate = true) }
        }

        hiddenHint = TextView(this).apply {
            text = "Hidden apps · tap one to bring it back"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        drawer.addView(hiddenHint, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        pager = AppPager(this)
        drawer.addView(pager, FrameLayout.LayoutParams(-1, -1))

        organiserButton = barButton(R.drawable.ic_organiser, "Organiser") { showOrganiser(it) }
        hiddenButton = barButton(R.drawable.ic_hidden, "Hidden apps") { setShowHidden(!showHidden) }
        val backgroundButton = barButton(R.drawable.ic_photo, "Drawer background") { showBackgroundMenu(it) }
        companionButton = barButton(R.drawable.ic_companion, "Companion on or off") { toggleCompanion(!companionOn) }
        updateCompanionButton()
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val slot = { LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginStart = dp(8); marginEnd = dp(8) } }
            addView(organiserButton, slot())
            addView(hiddenButton, slot())
            addView(backgroundButton, slot())
            addView(companionButton, slot())
            addView(barButton(R.drawable.ic_edge, "Edge lighting") { showEdgeMenu(it) }, slot())
        }
        drawer.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        root.addView(drawer, FrameLayout.LayoutParams(-1, -1))

        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            // Column sits low on the right, in easy thumb reach, clear of the gesture bar.
            homeScroll.setPadding(0, top, 0, bottom + dp(84))
            (hiddenHint.layoutParams as FrameLayout.LayoutParams).topMargin = top + dp(18)
            (pager.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = top + dp(48)
                bottomMargin = bottom + dp(96)
            }
            (bottomBar.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottom + dp(24)
            drawer.requestLayout()
            insets
        }
        return root
    }

    private fun barButton(icon: Int, description: String, onClick: (View) -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(15), dp(15), dp(15), dp(15))
        contentDescription = description
        background = StateListDrawable().apply {
            val on = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x2EFFFFFF) }
            addState(intArrayOf(android.R.attr.state_selected), on)
            addState(intArrayOf(android.R.attr.state_pressed), on)
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
        setOnClickListener(onClick)
    }

    private fun renderHome() {
        homeColumn.removeAllViews()
        val byKey = apps.associateBy { it.key }
        homeKeys().mapNotNull { byKey[it] }.forEach { entry ->
            homeColumn.addView(ImageView(this).also { bindApp(it, entry, fromHome = true) }, columnSlot())
        }
        homeColumn.addView(drawerButton(), columnSlot())
    }

    private fun columnSlot() = LinearLayout.LayoutParams(homeIconSize, homeIconSize).apply {
        topMargin = dp(11)
        bottomMargin = dp(11)
    }

    private fun drawerButton(): View = ImageView(this).apply {
        setImageDrawable(StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), DotsDrawable(0x80FFFFFF.toInt()))
            addState(intArrayOf(), DotsDrawable(Color.WHITE))
        })
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "All apps"
        setOnClickListener { openDrawer() }
    }

    private fun renderDrawer() {
        val hidden = hiddenKeys()
        val visible = apps.filter { (it.key in hidden) == showHidden }
        val byName = compareBy<AppEntry> { it.label.lowercase() }
        val sorted = when (sort) {
            SORT_USED -> visible.sortedWith(compareByDescending<AppEntry> { usage[it.pkg] ?: 0L }.then(byName))
            SORT_NEW -> visible.sortedWith(compareByDescending<AppEntry> { it.installed }.then(byName))
            else -> visible.sortedWith(compareBy<AppEntry> { typeRank(it.category) }.then(byName))
        }
        val pages = sorted.chunked(COLUMNS * ROWS).ifEmpty { listOf(emptyList()) }.map { chunk ->
            FixedGrid(this, COLUMNS, ROWS, drawerIconSize).apply {
                setPadding(dp(12), 0, dp(12), 0)
                isClickable = true
                setOnClickListener { closeDrawer(animate = true) }
                chunk.forEach { e -> addView(ImageView(context).also { bindApp(it, e, fromHome = false) }) }
            }
        }
        pager.setPages(pages)
        hiddenHint.visibility = if (showHidden) View.VISIBLE else View.GONE
        hiddenButton.isSelected = showHidden
    }

    private fun bindApp(view: ImageView, entry: AppEntry, fromHome: Boolean) {
        view.scaleType = ImageView.ScaleType.FIT_CENTER
        view.contentDescription = entry.label // for TalkBack only, never drawn
        view.setImageDrawable(StateListDrawable().apply {
            setExitFadeDuration(140)
            addState(intArrayOf(android.R.attr.state_pressed), BitmapDrawable(resources, entry.colour))
            addState(intArrayOf(), BitmapDrawable(resources, entry.white))
        })
        val inHiddenView = !fromHome && showHidden
        view.alpha = if (inHiddenView) 0.75f else 1f
        view.setOnClickListener {
            if (inHiddenView) setHidden(entry, false) else launch(entry, it)
        }
        view.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showAppMenu(it, entry, fromHome)
            true
        }
    }

    private fun openDrawer() {
        drawerOpen = true
        showHidden = false
        renderDrawer()
        pager.goTo(0, smooth = false)
        drawer.animate().cancel()
        drawer.alpha = 0f
        drawer.translationY = dp(40).toFloat()
        drawer.visibility = View.VISIBLE
        drawer.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(180).start()
        drawerPhoto?.let {
            drawerBg.animate().cancel()
            drawerBg.alpha = 0f
            drawerBg.visibility = View.VISIBLE
            drawerBg.animate().alpha(1f).setDuration(180).start()
        }
        homeScroll.animate().cancel()
        homeScroll.animate().alpha(0f).setStartDelay(0).setDuration(120)
            .withEndAction { if (drawerOpen) homeScroll.visibility = View.INVISIBLE }.start()
        companion.animate().cancel()
        companion.animate().alpha(0f).setStartDelay(0).setDuration(120)
            .withEndAction { if (drawerOpen) companion.visibility = View.INVISIBLE }.start()
    }

    private fun closeDrawer(animate: Boolean) {
        drawerOpen = false
        drawer.animate().cancel()
        homeScroll.animate().cancel()
        homeScroll.visibility = View.VISIBLE
        companion.animate().cancel()
        companion.visibility = if (companionOn) View.VISIBLE else View.GONE
        drawerBg.animate().cancel()
        if (!animate) {
            drawer.visibility = View.GONE
            drawerBg.visibility = View.GONE
            homeScroll.alpha = 1f
            companion.alpha = 1f
            return
        }
        companion.animate().alpha(1f).setStartDelay(60).setDuration(180).start()
        drawerBg.animate().alpha(0f).setDuration(150)
            .withEndAction { if (!drawerOpen) drawerBg.visibility = View.GONE }.start()
        homeScroll.animate().alpha(1f).setStartDelay(60).setDuration(180).start()
        drawer.animate().alpha(0f).translationY(dp(40).toFloat()).setStartDelay(0).setDuration(150)
            .withEndAction { if (!drawerOpen) drawer.visibility = View.GONE }.start()
    }

    /** Turns the home-screen companion on or off; he walks in next time you close the drawer. */
    private fun toggleCompanion(on: Boolean) {
        companionOn = on
        updateCompanionButton()
        if (on) {
            companion.alpha = 0f
            companion.visibility = View.INVISIBLE // shown again when the drawer closes
            companion.post { companion.arrive() }
        } else {
            companion.visibility = View.GONE
        }
    }

    private fun updateCompanionButton() {
        companionButton.setImageResource(if (companionOn) R.drawable.ic_companion else R.drawable.ic_companion_off)
        companionButton.isSelected = companionOn
        companionButton.contentDescription = if (companionOn) "Companion on" else "Companion off"
    }

    private fun setShowHidden(on: Boolean) {
        showHidden = on
        pager.goTo(0, smooth = false)
        renderDrawer()
    }

    // ---------------------------------------------------------------- organiser

    private class MenuItem(val icon: Int, val label: String, val selected: Boolean, val action: () -> Unit)

    private fun showOrganiser(anchor: View) {
        showGlassMenu(anchor as ImageView, listOf(
            MenuItem(R.drawable.ic_sort_type, "Type", sort == SORT_TYPE) { chooseSort(SORT_TYPE) },
            MenuItem(R.drawable.ic_sort_used, "Most used", sort == SORT_USED) { chooseSort(SORT_USED) },
            MenuItem(R.drawable.ic_sort_new, "Newest", sort == SORT_NEW) { chooseSort(SORT_NEW) }
        ))
    }

    // ---------------------------------------------------------------- edge lighting

    private var edgeOn: Boolean
        get() = prefs.getBoolean("edge", true)
        set(v) { prefs.edit().putBoolean("edge", v).apply() }

    private fun hasNotificationAccess(): Boolean =
        getSystemService(android.app.NotificationManager::class.java)
            .isNotificationListenerAccessGranted(android.content.ComponentName(this, EdgeListenerService::class.java))

    /** Edge lighting: on/off, the two permissions it needs, and previews of each glow. */
    private fun showEdgeMenu(anchor: View) {
        val items = ArrayList<MenuItem>()
        items.add(MenuItem(R.drawable.ic_edge, "Edge lighting", edgeOn && hasNotificationAccess() && Settings.canDrawOverlays(this)) {
            edgeOn = !edgeOn
            if (edgeOn && !hasNotificationAccess()) openSafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            else if (edgeOn && !Settings.canDrawOverlays(this)) openSafely(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        items.add(MenuItem(R.drawable.ic_bell, "Notification access", hasNotificationAccess()) {
            openSafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        items.add(MenuItem(R.drawable.ic_layers, "Display over apps", Settings.canDrawOverlays(this)) {
            openSafely(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        listOf(EdgeGlowView.Kind.NOTIFICATION to "Preview notification", EdgeGlowView.Kind.MUSIC to "Preview music", EdgeGlowView.Kind.CALL to "Preview call")
            .forEach { (kind, label) ->
                items.add(MenuItem(R.drawable.ic_play, label, false) {
                    startActivity(Intent(this, EdgeGlowActivity::class.java).putExtra(EdgeGlowActivity.EXTRA_KIND, kind.name))
                })
            }
        showGlassMenu(anchor as ImageView, items)
    }

    private fun openSafely(intent: Intent) {
        try { startActivity(intent) } catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun showBackgroundMenu(anchor: View) {
        val hasPhoto = drawerPhoto != null
        showGlassMenu(anchor as ImageView, listOf(
            MenuItem(R.drawable.ic_photo, "Choose photo", hasPhoto) { pickPhoto() },
            MenuItem(R.drawable.ic_wallpaper, "Wallpaper", !hasPhoto) { clearPhoto() }
        ))
    }

    /** Frosted-glass menu that opens just above [anchor]. */
    private fun showGlassMenu(anchor: ImageView, items: List<MenuItem>) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val menuW = dp(220)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        items.forEach { item ->
            panel.addView(menuRow(item.icon, item.label, item.selected) {
                dialog.dismiss()
                item.action()
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(1); bottomMargin = dp(1) })
        }
        dialog.setContentView(panel, ViewGroup.LayoutParams(menuW, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setCanceledOnTouchOutside(true)

        panel.measure(
            View.MeasureSpec.makeMeasureSpec(menuW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val loc = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val screenW = resources.displayMetrics.widthPixels

        dialog.window?.let { w ->
            w.setFormat(PixelFormat.TRANSLUCENT)
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            val blur = Build.VERSION.SDK_INT >= 31 && windowManager.isCrossWindowBlurEnabled
            w.setBackgroundDrawable(GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(if (blur) 0x8C121A2A.toInt() else 0xD9121A2A.toInt())
                setStroke(dp(1), 0x38FFFFFF)
            })
            if (blur && Build.VERSION.SDK_INT >= 31) w.setBackgroundBlurRadius(dp(24))
            w.attributes = w.attributes.apply {
                gravity = Gravity.TOP or Gravity.START
                width = menuW
                height = WindowManager.LayoutParams.WRAP_CONTENT
                x = (loc[0] + anchor.width / 2 - menuW / 2).coerceIn(dp(8), screenW - menuW - dp(8))
                y = loc[1] - dp(12) - panel.measuredHeight
            }
        }
        anchor.isSelected = true
        dialog.setOnDismissListener { anchor.isSelected = false }
        dialog.show()
    }

    private fun menuRow(icon: Int, label: String, selected: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            isClickable = true
            val shape = { color: Int ->
                GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(color) }
            }
            background = RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF),
                if (selected) shape(0x24FFFFFF) else null,
                shape(Color.WHITE)
            )
            setOnClickListener { onClick() }
        }
        row.addView(ImageView(this).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(20), dp(20)))
        row.addView(TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
        if (selected) {
            row.addView(ImageView(this).apply { setImageResource(R.drawable.ic_check) }, LinearLayout.LayoutParams(dp(18), dp(18)))
        }
        return row
    }

    private var sort: String
        get() = prefs.getString("sort", SORT_TYPE) ?: SORT_TYPE
        set(v) { prefs.edit().putString("sort", v).apply() }

    private fun chooseSort(key: String) {
        sort = key
        pager.goTo(0, smooth = false)
        if (key == SORT_USED && !hasUsageAccess()) {
            renderDrawer()
            // "Most used" needs the phone's usage data; Android asks for it once in Settings.
            try {
                startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            return
        }
        if (key == SORT_USED) refreshUsage() else renderDrawer()
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun refreshUsage() {
        if (!hasUsageAccess()) return
        io.execute {
            val usm = getSystemService(UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            val map = try {
                usm.queryAndAggregateUsageStats(now - 30L * 24 * 60 * 60 * 1000, now)
                    .mapValues { it.value.totalTimeInForeground }
            } catch (e: Exception) {
                emptyMap()
            }
            main.post {
                usage = map
                if (drawerOpen) renderDrawer()
            }
        }
    }

    private fun typeRank(category: Int): Int {
        val i = TYPE_ORDER.indexOf(category)
        return when {
            i >= 0 -> i
            category == ApplicationInfo.CATEGORY_UNDEFINED -> TYPE_ORDER.size + 1
            else -> TYPE_ORDER.size
        }
    }

    // ---------------------------------------------------------------- drawer photo

    private val photoFile by lazy { java.io.File(filesDir, "drawer_background.jpg") }

    private fun pickPhoto() {
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_PHOTO)
        } catch (e: Exception) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQUEST_PHOTO)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_PHOTO || resultCode != RESULT_OK || uri == null) return
        val metrics = resources.displayMetrics
        val target = maxOf(metrics.widthPixels, metrics.heightPixels)
        io.execute {
            val bmp = try { decodeScaled(uri, target) } catch (e: Exception) { null } ?: return@execute
            try {
                photoFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            } catch (e: Exception) { /* still show it this session */ }
            main.post { showPhoto(bmp) }
        }
    }

    /** Decodes a picked image, shrunk so its longest side is about [target] px. */
    private fun decodeScaled(uri: Uri, target: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = android.graphics.ImageDecoder.createSource(contentResolver, uri)
            return android.graphics.ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > target) {
                    val scale = target.toFloat() / longest
                    decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                }
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun showPhoto(bmp: Bitmap?) {
        drawerPhoto = bmp
        drawerBg.setImageBitmap(bmp)
        if (bmp == null) {
            drawerBg.visibility = View.GONE
        } else if (drawerOpen) {
            drawerBg.alpha = 1f
            drawerBg.visibility = View.VISIBLE
        }
    }

    private fun clearPhoto() {
        photoFile.delete()
        showPhoto(null)
    }

    private fun loadSavedPhoto() {
        io.execute {
            val bmp = if (photoFile.exists()) android.graphics.BitmapFactory.decodeFile(photoFile.path) else null
            if (bmp != null) main.post { showPhoto(bmp) }
        }
    }

    // ---------------------------------------------------------------- actions

    private fun launch(entry: AppEntry, from: View) {
        val bounds = Rect().also { from.getGlobalVisibleRect(it) }
        val opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height).toBundle()
        try {
            launcherApps.startMainActivity(entry.info.componentName, entry.info.user, bounds, opts)
        } catch (e: Exception) {
            loadApps() // app probably vanished; refresh
        }
    }

    private fun showAppMenu(anchor: View, entry: AppEntry, fromHome: Boolean) {
        val keys = homeKeys().toMutableList()
        val onHome = entry.key in keys
        val idx = keys.indexOf(entry.key)
        val isHidden = entry.key in hiddenKeys()
        PopupMenu(this, anchor, Gravity.END).apply {
            if (fromHome) {
                if (idx > 0) menu.add(0, 1, 0, "Move up")
                if (idx in 0 until keys.size - 1) menu.add(0, 2, 0, "Move down")
                menu.add(0, 3, 0, "Remove from home")
            } else {
                if (onHome) menu.add(0, 3, 0, "Remove from home") else menu.add(0, 4, 0, "Add to home")
                if (isHidden) menu.add(0, 8, 0, "Unhide") else menu.add(0, 7, 0, "Hide")
            }
            menu.add(0, 5, 0, "App info")
            if (entry.info.user == Process.myUserHandle()) menu.add(0, 6, 0, "Uninstall")

            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> { keys.removeAt(idx); keys.add(idx - 1, entry.key); saveHome(keys) }
                    2 -> { keys.removeAt(idx); keys.add(idx + 1, entry.key); saveHome(keys) }
                    3 -> { keys.remove(entry.key); saveHome(keys) }
                    4 -> { keys.add(entry.key); saveHome(keys) }
                    5 -> launcherApps.startAppDetailsActivity(
                        entry.info.componentName, entry.info.user, null, null
                    )
                    6 -> startActivity(
                        Intent(Intent.ACTION_DELETE, Uri.fromParts("package", entry.pkg, null))
                    )
                    7 -> setHidden(entry, true)
                    8 -> setHidden(entry, false)
                }
                true
            }
            show()
        }
    }

    // ---------------------------------------------------------------- data

    private fun homeKeys(): List<String> =
        prefs.getString("home", "").orEmpty().split("\n").filter { it.isNotEmpty() }

    private fun hiddenKeys(): Set<String> = prefs.getStringSet("hidden", emptySet()) ?: emptySet()

    private fun setHidden(entry: AppEntry, hide: Boolean) {
        val set = hiddenKeys().toMutableSet()
        if (hide) set.add(entry.key) else set.remove(entry.key)
        prefs.edit().putStringSet("hidden", set).apply()
        if (drawerOpen) renderDrawer()
    }

    /** First run: put the phone's default Phone, Messages and Browser apps in the column. */
    private fun seedHome(list: List<AppEntry>) {
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
        )
        val keys = (intents.mapNotNull { defaultAppFor(it, list)?.key } + homeKeys()).distinct()
        prefs.edit().putString("home", keys.joinToString("\n")).putBoolean("seeded", true).apply()
    }

    private fun defaultAppFor(intent: Intent, list: List<AppEntry>): AppEntry? {
        val mine = list.filter { it.info.user == Process.myUserHandle() }
        fun appIn(pkg: String?) = mine.firstOrNull { it.pkg == pkg }
        // The app the user picked as default for this action...
        @Suppress("DEPRECATION")
        val preferred = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        appIn(preferred?.activityInfo?.packageName)?.let { return it }
        // ...or, if no default is set yet, the first installed app that can handle it.
        @Suppress("DEPRECATION")
        return packageManager.queryIntentActivities(intent, 0)
            .firstNotNullOfOrNull { appIn(it.activityInfo.packageName) }
    }

    private fun saveHome(keys: List<String>) {
        prefs.edit().putString("home", keys.joinToString("\n")).apply()
        renderHome()
    }

    private fun keyOf(info: LauncherActivityInfo): String =
        info.componentName.flattenToString() + "#" + userManager.getSerialNumberForUser(info.user)

    private fun installTime(pkg: String): Long = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(pkg, 0).firstInstallTime
    } catch (e: Exception) {
        0L
    }

    /** Which app fills which everyday role on this phone (its dialer, camera, gallery…), mapped to a line icon. */
    private fun roleGlyphs(): Map<String, Int> {
        val out = HashMap<String, Int>()
        fun add(intent: Intent, glyph: Int) {
            try {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, 0)
                    .forEach { out.putIfAbsent(it.activityInfo.packageName, glyph) }
            } catch (e: Exception) { }
        }
        fun category(c: String, glyph: Int) = add(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, c), glyph)

        add(Intent(Intent.ACTION_DIAL), R.drawable.g_phone)
        add(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")), R.drawable.g_message)
        category(Intent.CATEGORY_APP_MESSAGING, R.drawable.g_message)
        category(Intent.CATEGORY_APP_CONTACTS, R.drawable.g_people)
        category(Intent.CATEGORY_APP_EMAIL, R.drawable.g_mail)
        category(Intent.CATEGORY_APP_CALENDAR, R.drawable.g_calendar)
        category(Intent.CATEGORY_APP_GALLERY, R.drawable.g_photos)
        category(Intent.CATEGORY_APP_CALCULATOR, R.drawable.g_calc)
        category(Intent.CATEGORY_APP_MAPS, R.drawable.g_map)
        category(Intent.CATEGORY_APP_MUSIC, R.drawable.g_music)
        category(Intent.CATEGORY_APP_MARKET, R.drawable.g_bag)
        category(Intent.CATEGORY_APP_FITNESS, R.drawable.g_heart)
        if (Build.VERSION.SDK_INT >= 29) category(Intent.CATEGORY_APP_FILES, R.drawable.g_folder)
        if (Build.VERSION.SDK_INT >= 31) category(Intent.CATEGORY_APP_WEATHER, R.drawable.g_cloud)
        add(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS), R.drawable.g_clock)
        add(Intent(Settings.ACTION_SETTINGS), R.drawable.g_settings)
        add(Intent(MediaStore.ACTION_IMAGE_CAPTURE), R.drawable.g_camera)
        add(Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION), R.drawable.g_mic)
        add(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE), R.drawable.g_globe)
        return out
    }

    private fun loadApps() {
        val size = renderSize
        val density = resources.displayMetrics.densityDpi
        io.execute {
            val roles = roleGlyphs()
            val list = userManager.userProfiles
                .flatMap { launcherApps.getActivityList(null, it) }
                .filter { it.componentName.packageName != packageName }
                .mapNotNull { info ->
                    try {
                        val raw = info.getIcon(density) ?: return@mapNotNull null
                        val colour = IconFactory.colour(raw, size)
                        val pkg = info.componentName.packageName
                        val white = IconFactory.white(raw, size)
                            ?: (roles[pkg] ?: PACKAGE_GLYPHS[pkg])?.let { res ->
                                getDrawable(res)?.let { IconFactory.fromVector(it, size) }
                            }
                            ?: IconFactory.monogram(info.label.toString(), size)
                        AppEntry(
                            keyOf(info), info.label.toString(), info, white, colour,
                            info.applicationInfo.category, installTime(info.componentName.packageName)
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                .sortedBy { it.label.lowercase() }
            main.post {
                apps = list
                if (!prefs.getBoolean("seeded", false) && list.isNotEmpty()) seedHome(list)
                renderHome()
                if (drawerOpen) renderDrawer()
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Simple 3x3 dot grid used for the app-drawer button. */
    private class DotsDrawable(color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val s = minOf(b.width(), b.height())
            val r = s * 0.065f
            val gap = s * 0.22f
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            for (i in -1..1) for (j in -1..1) canvas.drawCircle(cx + i * gap, cy + j * gap, r, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val REQUEST_PHOTO = 41

        // Line icons for popular apps that don't ship a white icon of their own.
        val PACKAGE_GLYPHS = mapOf(
            "com.whatsapp" to R.drawable.g_chat,
            "com.whatsapp.w4b" to R.drawable.g_chat,
            "org.telegram.messenger" to R.drawable.g_send,
            "org.thoughtcrime.securesms" to R.drawable.g_message,
            "com.facebook.orca" to R.drawable.g_chat,
            "com.facebook.katana" to R.drawable.g_people,
            "com.instagram.android" to R.drawable.g_camera,
            "com.snapchat.android" to R.drawable.g_camera,
            "com.discord" to R.drawable.g_headphones,
            "com.spotify.music" to R.drawable.g_music,
            "com.soundcloud.android" to R.drawable.g_headphones,
            "com.shazam.android" to R.drawable.g_mic,
            "com.google.android.youtube" to R.drawable.g_play,
            "com.google.android.apps.youtube.music" to R.drawable.g_music,
            "com.netflix.mediaclient" to R.drawable.g_film,
            "com.amazon.avod.thirdpartyclient" to R.drawable.g_film,
            "com.disney.disneyplus" to R.drawable.g_film,
            "com.crunchyroll.crunchyroid" to R.drawable.g_tv,
            "tv.twitch.android.app" to R.drawable.g_tv,
            "com.microsoft.xboxone.smartglass" to R.drawable.g_game,
            "com.valvesoftware.android.steam.community" to R.drawable.g_game,
            "com.playstation.mobile2ndscreen" to R.drawable.g_game,
            "com.amazon.mShop.android.shopping" to R.drawable.g_cart,
            "com.android.vending" to R.drawable.g_bag,
            "com.google.android.apps.maps" to R.drawable.g_map,
            "com.ubercab" to R.drawable.g_car,
            "ee.mtakso.client" to R.drawable.g_car,
            "com.paypal.android.p2pmobile" to R.drawable.g_card,
            "com.revolut.revolut" to R.drawable.g_card,
            "com.google.android.apps.walletnfcrel" to R.drawable.g_card,
            "com.duolingo" to R.drawable.g_book,
            "com.amazon.kindle" to R.drawable.g_book,
            "com.audible.application" to R.drawable.g_headphones,
            "com.strava" to R.drawable.g_heart,
            "com.google.android.apps.fitness" to R.drawable.g_heart,
            "com.google.android.keep" to R.drawable.g_notes,
            "com.google.android.apps.docs" to R.drawable.g_folder,
            "com.google.android.apps.photos" to R.drawable.g_photos,
            "com.google.android.gm" to R.drawable.g_mail,
            "com.microsoft.office.outlook" to R.drawable.g_mail,
            "com.google.android.apps.authenticator2" to R.drawable.g_shield,
            "com.android.chrome" to R.drawable.g_globe,
            "org.mozilla.firefox" to R.drawable.g_globe
        )
        const val COLUMNS = 6
        const val ROWS = 8
        const val SORT_TYPE = "type"
        const val SORT_USED = "used"
        const val SORT_NEW = "new"

        // Order apps appear in when sorted by type; apps with no category go last.
        val TYPE_ORDER = listOf(
            ApplicationInfo.CATEGORY_SOCIAL,
            ApplicationInfo.CATEGORY_IMAGE,
            ApplicationInfo.CATEGORY_VIDEO,
            ApplicationInfo.CATEGORY_AUDIO,
            ApplicationInfo.CATEGORY_GAME,
            ApplicationInfo.CATEGORY_NEWS,
            ApplicationInfo.CATEGORY_MAPS,
            ApplicationInfo.CATEGORY_PRODUCTIVITY
        )
    }
}
