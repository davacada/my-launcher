package com.davacada.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
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
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : Activity() {

    private class AppEntry(
        val key: String,
        val label: String,
        val info: LauncherActivityInfo,
        val white: Bitmap,
        val colour: Bitmap
    )

    private lateinit var launcherApps: LauncherApps
    private lateinit var userManager: UserManager
    private lateinit var prefs: SharedPreferences

    private lateinit var homeColumn: LinearLayout
    private lateinit var drawer: FrameLayout
    private lateinit var grid: GridView
    private lateinit var homeScroll: ScrollView

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var apps: List<AppEntry> = emptyList()
    private val drawerAdapter = DrawerAdapter()

    private val iconSize by lazy { dp(46) }

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
        if (drawerOpen) closeDrawer(animate = true)
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
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply { marginEnd = dp(22) }
        )

        grid = GridView(this).apply {
            numColumns = 5
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(26)
            gravity = Gravity.CENTER
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            selector = ColorDrawable(Color.TRANSPARENT)
            adapter = drawerAdapter
            setOnTouchListener(EmptySpaceTap { closeDrawer(animate = true) })
        }
        drawer = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
            isClickable = true
            setOnClickListener { closeDrawer(animate = true) }
            addView(grid, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(drawer, FrameLayout.LayoutParams(-1, -1))

        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            grid.setPadding(dp(18), top + dp(40), dp(18), bottom + dp(32))
            homeScroll.setPadding(0, top, 0, bottom)
            insets
        }
        return root
    }

    private fun renderHome() {
        homeColumn.removeAllViews()
        val byKey = apps.associateBy { it.key }
        homeKeys().mapNotNull { byKey[it] }.forEach { entry ->
            homeColumn.addView(ImageView(this).also { bindApp(it, entry, fromHome = true) }, columnSlot())
        }
        homeColumn.addView(drawerButton(), columnSlot())
    }

    private fun columnSlot() = LinearLayout.LayoutParams(iconSize, iconSize).apply {
        topMargin = dp(12)
        bottomMargin = dp(12)
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

    private fun bindApp(view: ImageView, entry: AppEntry, fromHome: Boolean) {
        view.scaleType = ImageView.ScaleType.FIT_CENTER
        view.contentDescription = entry.label // for TalkBack only, never drawn
        view.setImageDrawable(StateListDrawable().apply {
            setExitFadeDuration(140)
            addState(intArrayOf(android.R.attr.state_pressed), BitmapDrawable(resources, entry.colour))
            addState(intArrayOf(), BitmapDrawable(resources, entry.white))
        })
        view.setOnClickListener { launch(entry, it) }
        view.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showMenu(it, entry, fromHome)
            true
        }
    }

    private var drawerOpen = false

    private fun openDrawer() {
        drawerOpen = true
        drawer.animate().cancel()
        grid.setSelection(0)
        drawer.alpha = 0f
        drawer.translationY = dp(40).toFloat()
        drawer.visibility = View.VISIBLE
        drawer.animate().alpha(1f).translationY(0f).setDuration(180).start()
        homeScroll.animate().cancel()
        homeScroll.animate().alpha(0f).setStartDelay(0).setDuration(120)
            .withEndAction { if (drawerOpen) homeScroll.visibility = View.INVISIBLE }.start()
    }

    private fun closeDrawer(animate: Boolean) {
        drawerOpen = false
        drawer.animate().cancel()
        homeScroll.animate().cancel()
        homeScroll.visibility = View.VISIBLE
        if (!animate) {
            drawer.visibility = View.GONE
            homeScroll.alpha = 1f
            return
        }
        homeScroll.animate().alpha(1f).setStartDelay(60).setDuration(180).start()
        drawer.animate().alpha(0f).translationY(dp(40).toFloat()).setDuration(150)
            .withEndAction { if (!drawerOpen) drawer.visibility = View.GONE }.start()
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

    private fun showMenu(anchor: View, entry: AppEntry, fromHome: Boolean) {
        val keys = homeKeys().toMutableList()
        val onHome = entry.key in keys
        val idx = keys.indexOf(entry.key)
        PopupMenu(this, anchor, Gravity.END).apply {
            if (fromHome) {
                if (idx > 0) menu.add(0, 1, 0, "Move up")
                if (idx in 0 until keys.size - 1) menu.add(0, 2, 0, "Move down")
                menu.add(0, 3, 0, "Remove from home")
            } else {
                if (onHome) menu.add(0, 3, 0, "Remove from home") else menu.add(0, 4, 0, "Add to home")
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
                        Intent(Intent.ACTION_DELETE, Uri.fromParts("package", entry.info.componentName.packageName, null))
                    )
                }
                true
            }
            show()
        }
    }

    // ---------------------------------------------------------------- data

    private fun homeKeys(): List<String> =
        prefs.getString("home", "").orEmpty().split("\n").filter { it.isNotEmpty() }

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
        fun appIn(pkg: String?) = mine.firstOrNull { it.info.componentName.packageName == pkg }
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

    private fun loadApps() {
        val size = iconSize
        val density = resources.displayMetrics.densityDpi
        io.execute {
            val list = userManager.userProfiles
                .flatMap { launcherApps.getActivityList(null, it) }
                .filter { it.componentName.packageName != packageName }
                .mapNotNull { info ->
                    try {
                        val raw = info.getIcon(density) ?: return@mapNotNull null
                        val colour = IconFactory.colour(raw, size)
                        val white = IconFactory.white(raw, size)
                        AppEntry(keyOf(info), info.label.toString(), info, white, colour)
                    } catch (e: Exception) {
                        null
                    }
                }
                .sortedBy { it.label.lowercase() }
            main.post {
                apps = list
                if (!prefs.getBoolean("seeded", false) && list.isNotEmpty()) seedHome(list)
                drawerAdapter.notifyDataSetChanged()
                renderHome()
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private inner class DrawerAdapter : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(position: Int): Any = apps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? FrameLayout) ?: FrameLayout(this@MainActivity).apply {
                layoutParams = AbsListView.LayoutParams(-1, dp(54))
                addView(ImageView(context), FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
            }
            bindApp(cell.getChildAt(0) as ImageView, apps[position], fromHome = false)
            return cell
        }
    }

    /** Closes the drawer when the user taps (not scrolls) on empty space in the grid. */
    private inner class EmptySpaceTap(private val onTap: () -> Unit) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@MainActivity).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; moved = false }
                MotionEvent.ACTION_MOVE ->
                    if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) moved = true
                MotionEvent.ACTION_UP ->
                    if (!moved && grid.pointToPosition(e.x.toInt(), e.y.toInt()) == AbsListView.INVALID_POSITION) {
                        onTap()
                    }
            }
            return false
        }
    }

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
}
