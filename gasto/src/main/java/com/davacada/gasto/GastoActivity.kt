package com.davacada.gasto

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class GastoActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var pull: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var content: LinearLayout
    private lateinit var nav: LinearLayout
    private var tab = 0
    private var filter = "all"
    private var topInset = 0
    private var bottomInset = 0
    private val onChange: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Store.init(this)
        window.statusBarColor = C.WHITE
        window.navigationBarColor = C.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.setDecorFitsSystemWindows(false)

        root = FrameLayout(this).apply { background = grain() }
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        content = vbox()
        scroll.addView(content)
        pull = androidx.swiperefreshlayout.widget.SwipeRefreshLayout(this).apply {
            setColorSchemeColors(C.G1)
            setProgressBackgroundColorSchemeColor(0xFFFFFFFF.toInt())
            addView(scroll)
            setOnRefreshListener { refresh() }
        }
        root.addView(pull, FrameLayout.LayoutParams(-1, -1))
        nav = hbox()
        root.addView(nav, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, ins ->
            val bars = ins.getInsets(WindowInsets.Type.systemBars())
            topInset = bars.top; bottomInset = bars.bottom
            pull.setProgressViewOffset(false, topInset, topInset + dp(64))
            render(); ins
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Alerts.schedule(this)
        Store.listen(onChange)
        render()
    }

    override fun onDestroy() { Store.unlisten(onChange); super.onDestroy() }

    override fun onResume() {
        super.onResume()
        render()
        Alerts.checkRenewals(this)
        // refresh Gmail at most every 3 hours when the app opens
        if (Gmail.connected(this) && System.currentTimeMillis() - Prefs.long(this, "gmailSynced") > 3 * 3600_000L) {
            Thread { Gmail.syncInBackground(this) }.start()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CSV && resultCode == RESULT_OK) {
            data?.data?.let { importStatement(it) }
            return
        }
        if (requestCode == Gmail.REQ) {
            if (resultCode == RESULT_OK) Gmail.onResult(this, data) { err -> gmailDone(err) }
            else { Prefs.setStr(this, "gmailError", Gmail.SETUP); gmailDone(Gmail.SETUP) }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tab != 0) { tab = 0; render() } else super.onBackPressed()
    }

    /** Pull down: look again at notifications in the shade and, if connected, at Gmail. */
    private fun refresh() {
        val before = Store.all().size
        Thread {
            PayListener.scanActive(this)
            if (Gmail.connected(this)) Gmail.syncInBackground(this)
            runOnUiThread {
                val n = Store.all().size - before
                pull.isRefreshing = false
                render()
                toast(if (n > 0) "$n new payment${if (n == 1) "" else "s"}" else if (!listenerOn()) "Turn on bank notifications in Settings to catch payments" else "Up to date")
            }
        }.start()
    }

    private fun gmailDone(err: String?) {
        when {
            err == null -> toast("Gmail: ${Prefs.str(this, "gmailAdded") ?: "0"} new receipts")
            err == Gmail.SETUP -> gmailSetupHelp()
            else -> toast(err)
        }
        render()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun listenerOn(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(ComponentName(this, PayListener::class.java).flattenToString())
    }

    // MARK: layout

    private fun render() {
        val y = scroll.scrollY
        content.removeAllViews()
        content.setPadding(dp(20), topInset + dp(14), dp(20), bottomInset + dp(110))
        when (tab) {
            0 -> month()
            1 -> subscriptions()
            2 -> daily()
            else -> settings()
        }
        renderNav()
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun renderNav() {
        nav.removeAllViews()
        nav.background = round(0xF0F2F1EA.toInt(), 0f, 1, C.LINE)
        nav.setPadding(0, dp(10), 0, bottomInset + dp(10))
        val items = listOf("Month" to R.drawable.i_home, "Subscriptions" to R.drawable.i_subs, "Daily" to R.drawable.i_daily, "Settings" to R.drawable.i_settings)
        items.forEachIndexed { i, (name, icon) ->
            val on = i == tab
            val col = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; background = ripple() }
            val pillBox = FrameLayout(this).apply {
                background = if (on) round(C.MINT, dpf(16f)) else null
                setPadding(dp(18), dp(4), dp(18), dp(4))
                addView(ImageView(context).apply { setImageResource(icon); setColorFilter(if (on) C.G1 else C.SUB) }, FrameLayout.LayoutParams(dp(22), dp(22)))
            }
            col.addView(pillBox, LinearLayout.LayoutParams(-2, -2))
            col.addView(text(name, 11f, if (on) C.G1 else C.SUB, 600).apply { setPadding(0, dp(5), 0, 0); maxLines = 1 },
                LinearLayout.LayoutParams(-2, -2))
            col.contentDescription = name
            col.setOnClickListener { tab = i; scroll.scrollTo(0, 0); render() }
            nav.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun eyebrow(s: String) = text(s.uppercase(), 12f, C.G1, 700).apply { letterSpacing = 0.08f }

    private fun when_(t: Long): String {
        val c = Calendar.getInstance(); val today = c.get(Calendar.DAY_OF_YEAR); val year = c.get(Calendar.YEAR)
        c.timeInMillis = t
        val d = c.get(Calendar.DAY_OF_YEAR)
        return when {
            c.get(Calendar.YEAR) == year && d == today -> "today"
            c.get(Calendar.YEAR) == year && d == today - 1 -> "yesterday"
            System.currentTimeMillis() - t < 6 * 86_400_000L -> SimpleDateFormat("EEE", Locale.ENGLISH).format(t)
            else -> SimpleDateFormat("d MMM", Locale.ENGLISH).format(t)
        }
    }

    private fun sourceName(p: Payment) = when (p.source) {
        "gmail" -> "Gmail"; "play" -> "Google Play"; "wallet" -> "Google Wallet"; "manual" -> "added by you"; else -> p.app.ifEmpty { "bank" }
    }

    private fun catName(c: String) = when (c) { "food" -> "food"; "transport" -> "transport"; "out" -> "going out"; else -> "other" }

    private fun paymentRow(p: Payment, last: Boolean, sub: String) =
        payRow(p.merchant, sub, (if (p.amount < 0) "+" else "−") + eur(p.amount), last, onClick = { editPayment(p) }, onLong = { editPayment(p) })

    // MARK: Month

    private fun month() {
        val base = Prefs.base(this)
        val ps = Period.payments(this)
        val spent = ps.sumOf { it.amount }
        val left = base - spent
        val days = Period.daysLeft(this)

        val top = hbox()
        top.addView(text("", 26f).apply {
            val b = android.text.SpannableStringBuilder("gasto.")
            b.setSpan(android.text.style.ForegroundColorSpan(C.SAGE), 5, 6, 0)
            text = b; setTextColor(C.G1); typeface = Fonts.get(context, 800); letterSpacing = -0.04f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(text(Period.monthName(this), 13f, C.SUB, 600))
        content.addView(top)

        content.addView(hbox().apply {
            gravity = Gravity.CENTER
            background = round(C.G1, dpf(18f)).let { android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), it, null) }
            setPadding(0, dp(13), 0, dp(13))
            addView(ImageView(context).apply { setImageResource(R.drawable.i_scan); setColorFilter(C.WHITE) }, LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(text("Scan for receipts", 15f, C.WHITE, 700).apply { setPadding(dp(10), 0, 0, 0) })
            contentDescription = "Scan email and bank for receipts"
            setOnClickListener { scan() }
        }, lp(top = 16))

        if (!listenerOn() && !Gmail.connected(this)) {
            content.addView(cardBox(16, 14).apply {
                background = card(C.WARN)
                addView(text("Let Gasto see your spending", 15f, C.INK, 700))
                addView(text("Turn on bank notifications and connect Gmail in Settings. Until then you can add payments by hand.", 13f, C.SUB).apply { setPadding(0, dp(4), 0, 0) })
                setOnClickListener { tab = 3; render() }
            }, lp(top = 14))
        }

        val big = hbox().apply { background = card(); setPadding(dp(18), dp(18), dp(16), dp(18)) }
        val ringBox = FrameLayout(this)
        ringBox.addView(Ring(this).apply { fraction = (spent / base).toFloat() }, FrameLayout.LayoutParams(-1, -1))
        ringBox.addView(vbox().apply {
            gravity = Gravity.CENTER
            addView(text("SPENT", 11f, C.SUB, 600), LinearLayout.LayoutParams(-2, -2))
            addView(text("${Math.round(spent / base * 100)}%", 17f, C.INK, 800).apply { setPadding(0, dp(2), 0, 0) }, LinearLayout.LayoutParams(-2, -2))
        }, FrameLayout.LayoutParams(-1, -1))
        big.addView(ringBox, LinearLayout.LayoutParams(dp(124), dp(124)))
        big.addView(vbox().apply {
            setPadding(dp(16), 0, 0, 0)
            addView(text(if (left >= 0) "LEFT THIS MONTH" else "OVER THIS MONTH", 12f, C.SUB, 700).apply { letterSpacing = 0.06f })
            addView(money(Math.abs(left), 36f, if (left >= 0) C.INK else C.RED).apply { setPadding(0, dp(4), 0, 0) })
            addView(text("of €${base.toInt()} · $days days to go", 13f, C.SUB).apply { setPadding(0, dp(4), 0, 0) })
            addView(text("✎ Update amount", 12.5f, C.G1, 700).apply {
                background = round(C.MINT, dpf(12f)); setPadding(dp(10), dp(5), dp(10), dp(5))
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        big.background = card()
        big.setOnClickListener { editLeft(left) }
        big.contentDescription = "Left this month. Tap to update the amount"
        content.addView(big, lp(top = 16))

        val stats = hbox()
        val subs = ps.filter { it.kind == "sub" }
        val daily = ps.filter { it.kind == "daily" }
        listOf(
            Triple("PER DAY", eur(Math.max(0.0, left) / days), "to stay on ${base.toInt()}"),
            Triple("SUBS", eur(subs.sumOf { it.amount }), "${Subs.list().size} active"),
            Triple("DAILY", eur(daily.sumOf { it.amount }), "${daily.size} payments"),
        ).forEachIndexed { i, (a, b, c) ->
            stats.addView(vbox().apply {
                background = card(); setPadding(dp(12), dp(12), dp(8), dp(12))
                addView(text(a, 11f, C.SUB, 700).apply { letterSpacing = 0.04f })
                addView(text(b, 17f, C.INK, 800).apply { setPadding(0, dp(4), 0, dp(1)); maxLines = 1 })
                addView(text(c, 11f, C.SUB).apply { maxLines = 1 })
                setOnClickListener { tab = if (i == 1) 1 else 2; render() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
        content.addView(stats, lp(top = 10))

        content.addView(label("Latest", "see all ›") { tab = 2; render() })
        val latest = ps.take(5)
        if (latest.isEmpty()) {
            content.addView(cardBox(16, 16).apply { addView(text("No payments yet this month.", 14f, C.SUB)) })
        } else {
            val box = cardBox()
            latest.forEachIndexed { i, p ->
                box.addView(paymentRow(p, i == latest.size - 1, "${when_(p.time)} · ${when (p.kind) { "sub" -> "subscription"; "adjust" -> "your update"; else -> catName(p.category) }}${if (p.kind == "adjust") "" else " · " + sourceName(p)}"))
            }
            content.addView(box)
        }
    }

    // MARK: Subscriptions

    private fun subscriptions() {
        val list = Subs.list()
        val total = list.sumOf { it.amount }
        val base = Prefs.base(this)
        content.addView(eyebrow("Subscriptions"))
        content.addView(hbox().apply {
            gravity = Gravity.BOTTOM
            addView(money(total, 30f))
            addView(text(" / month", 15f, C.SUB, 600).apply { setPadding(0, 0, 0, dp(4)) })
        }, lp(top = 4))
        content.addView(text("${Math.round(total / base * 100)}% of your €${base.toInt()} · ${eur(total * 12).substringBefore('.')} a year", 13f, C.SUB), lp(top = 4))

        val now = System.currentTimeMillis()
        list.filter { it.next - now in 0..(3 * 86_400_000L) }.forEach { s ->
            val days = Math.max(0, ((s.next - now) / 86_400_000L).toInt())
            content.addView(hbox().apply {
                background = card(C.WARN); setPadding(dp(16), dp(12), dp(16), dp(12))
                addView(View(context).apply { background = round(C.RED, dpf(4f)) }, LinearLayout.LayoutParams(dp(8), dp(8)))
                addView(text("${s.merchant} renews ${if (days == 0) "today" else "in $days day${if (days == 1) "" else "s"}"} · ${eur(s.amount)}", 13.5f, C.INK, 500)
                    .apply { setPadding(dp(10), 0, dp(8), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(text("Cancel ›", 13f, C.G1, 700).apply { setOnClickListener { openCancel(s.merchant) } })
            }, lp(top = 14))
        }

        content.addView(label("Active · ${list.size}", "next charge"))
        if (list.isEmpty()) {
            content.addView(cardBox(16, 16).apply {
                addView(text("No subscriptions found yet. They show up from Gmail receipts, Google Play and your bank. You can also mark any payment as a subscription by tapping it.", 13.5f, C.SUB).apply { setLineSpacing(0f, 1.2f) })
            })
            return
        }
        val box = cardBox()
        list.forEachIndexed { i, s ->
            val src = when (s.source) { "play" -> "Google Play"; "gmail" -> "Gmail receipt"; "wallet" -> "Google Wallet"; "manual" -> "added by you"; else -> "bank" }
            box.addView(payRow(s.merchant, "$src · renews ${SimpleDateFormat("d MMM", Locale.ENGLISH).format(s.next)}", eur(s.amount), i == list.size - 1,
                onClick = { Store.all().firstOrNull { it.merchant == s.merchant && it.kind == "sub" }?.let { editPayment(it) } }))
        }
        content.addView(box)
    }

    private fun openCancel(merchant: String) {
        // Google Play subscriptions can be managed directly; others: search how to cancel.
        val play = Store.all().any { it.merchant == merchant && it.source == "play" }
        val uri = if (play) Uri.parse("https://play.google.com/store/account/subscriptions")
        else Uri.parse("https://www.google.com/search?q=" + Uri.encode("cancel $merchant subscription"))
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

    // MARK: Daily

    private fun daily() {
        val all = Period.payments(this).filter { it.kind == "daily" }
        val shown = if (filter == "all") all else all.filter { it.category == filter }
        val head = hbox()
        head.addView(eyebrow("Daily payments"), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(text("+ Add", 13f, C.G1, 700).apply {
            background = round(C.MINT, dpf(14f)); setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { addPayment() }
        })
        content.addView(head)
        content.addView(money(all.sumOf { it.amount }, 30f), lp(top = 4))
        val sources = all.map { sourceName(it) }.distinct().joinToString(", ").ifEmpty { "nothing yet" }
        content.addView(text("${all.size} payments in ${Period.monthName(this)} · from $sources", 13f, C.SUB), lp(top = 4))

        val chips = hbox()
        listOf("all" to "All", "food" to "Food", "transport" to "Transport", "out" to "Going out", "other" to "Other").forEach { (k, n) ->
            val sum = all.filter { it.category == k }.sumOf { it.amount }
            val label = if (k == "all" || sum == 0.0) n else "$n €${Math.round(sum)}"
            chips.addView(chip(label, filter == k) { filter = k; render() }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        }
        content.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, lp(top = 12))

        if (shown.isEmpty()) {
            content.addView(cardBox(16, 16).apply { addView(text("Nothing here yet.", 14f, C.SUB)) }, lp(top = 16))
            return
        }
        val byDay = shown.groupBy { Calendar.getInstance().apply { timeInMillis = it.time }.let { c -> c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR) } }
        for ((_, ps) in byDay.entries.sortedByDescending { it.key }) {
            val w = when_(ps.first().time)
            val title = when (w) { "today", "yesterday" -> w; else -> SimpleDateFormat("EEEE d MMM", Locale.ENGLISH).format(ps.first().time) }
            content.addView(label(title, "−" + eur(ps.sumOf { it.amount })))
            val box = cardBox()
            ps.forEachIndexed { i, p ->
                box.addView(paymentRow(p, i == ps.size - 1, SimpleDateFormat("HH:mm", Locale.ENGLISH).format(p.time) + " · " + catName(p.category) + " · " + sourceName(p)))
            }
            content.addView(box)
        }
    }

    // MARK: Settings

    private fun settings() {
        content.addView(eyebrow("Settings"))
        content.addView(text("Your money", 30f, C.INK, 800).apply { letterSpacing = -0.02f }, lp(top = 4))

        content.addView(label("Monthly base"))
        val baseBox = cardBox()
        baseBox.addView(hbox().apply {
            setPadding(0, dp(14), 0, dp(14))
            addView(vbox().apply {
                addView(text("€${Prefs.base(context).let { if (it % 1.0 == 0.0) it.toInt().toString() else String.format("%.2f", it) }}", 34f, C.INK, 800).apply { letterSpacing = -0.03f })
                addView(text("every month starts here", 12f, C.SUB).apply { setPadding(0, dp(2), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("Edit", 13f, C.G2, 700).apply {
                background = round(C.MINT, dpf(14f)); setPadding(dp(14), dp(9), dp(14), dp(9))
                setOnClickListener { editBase() }
            })
        })
        baseBox.addView(divider(), LinearLayout.LayoutParams(-1, 1))
        baseBox.addView(settingRow("Month starts on", "the 1st, or your payday", text("${ordinal(Prefs.startDay(this))} ›", 14f, C.SUB), true) { pickStartDay() })
        content.addView(baseBox)

        content.addView(label("Where spending comes from", "stays on your phone"))
        val src = cardBox()
        val gmailState = when {
            Gmail.connected(this) -> text("Connected ✓", 12.5f, C.G1, 700)
            Gmail.lastError(this) != null -> text("Set up ›", 12.5f, C.RED, 700)
            else -> text("Connect ›", 12.5f, C.G1, 700)
        }
        val gmailSub = when {
            Gmail.connected(this) -> "receipts, invoices, Google Play · " + (Prefs.long(this, "gmailSynced").takeIf { it > 0 }?.let { "checked " + when_(it) } ?: "checking…")
            Gmail.lastError(this) != null -> Gmail.lastError(this)!!
            else -> "receipts, invoices, Google Play purchases"
        }
        src.addView(settingRow("Gmail", gmailSub, gmailState) {
            if (Gmail.connected(this)) {
                AlertDialog.Builder(this).setTitle("Gmail").setItems(arrayOf("Check now", "Disconnect")) { _, w ->
                    if (w == 0) { toast("Checking Gmail…"); Gmail.connect(this) { gmailDone(it) } } else { Gmail.disconnect(this); render() }
                }.show()
            } else if (Gmail.lastError(this) == Gmail.SETUP) gmailSetupHelp()
            else Gmail.connect(this) { gmailDone(it) }
        })
        src.addView(settingRow("New receipts from Gmail's notifications", "works without the Google setup, from today on",
            toggle(Prefs.bool(this, "gmailNotes", true)) { Prefs.setBool(this, "gmailNotes", it) }))
        val on = listenerOn()
        val apps = Prefs.apps(this)
        src.addView(settingRow("Bank notifications",
            if (on) "card payments as they happen" + (if (apps.isNotEmpty()) " · " + apps.joinToString(", ") { PayListener.appName(this, it) } else "") else "lets Gasto read payment notifications",
            if (on) text("Connected ✓", 12.5f, C.G1, 700) else text("Turn on ›", 12.5f, C.G1, 700), true) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        content.addView(src)

        val seen = Prefs.seen(this)
        if (seen.isNotEmpty()) {
            content.addView(label("Apps that showed a payment"))
            val box = cardBox()
            seen.sorted().forEachIndexed { i, pkg ->
                box.addView(settingRow(PayListener.appName(this, pkg), pkg, toggle(pkg in apps) { v ->
                    val s = Prefs.apps(this); if (v) s.add(pkg) else s.remove(pkg); Prefs.setApps(this, s)
                }, i == seen.size - 1))
            }
            content.addView(box)
        }

        content.addView(label("Alerts"))
        val al = cardBox()
        al.addView(settingRow("When I pass 80% of €${Prefs.base(this).toInt()}", null, toggle(Prefs.bool(this, "alert80", true)) { Prefs.setBool(this, "alert80", it) }))
        al.addView(settingRow("Before a subscription renews", "2 days before", toggle(Prefs.bool(this, "alertRenew", true)) { Prefs.setBool(this, "alertRenew", it) }, true))
        content.addView(al)

        content.addView(text("Gasto only reads receipts and payment messages. Nothing is uploaded anywhere. Tap any payment to fix its name, amount or type.", 12f, C.SUB).apply {
            setLineSpacing(0f, 1.25f); setPadding(dp(4), dp(14), dp(4), 0)
        })
    }

    private fun ordinal(d: Int) = d.toString() + when { d in 11..13 -> "th"; d % 10 == 1 -> "st"; d % 10 == 2 -> "nd"; d % 10 == 3 -> "rd"; else -> "th" }

    private fun gmailSetupHelp() {
        AlertDialog.Builder(this).setTitle("One-time Gmail setup")
            .setMessage("Google asks every app that reads Gmail to be registered once.\n\n" +
                "1. Open console.cloud.google.com and create a project called Gasto.\n" +
                "2. APIs & Services › Library › enable \"Gmail API\".\n" +
                "3. OAuth consent screen: External, add your own Gmail as a test user.\n" +
                "4. Credentials › Create OAuth client ID › Android:\n" +
                "   package  com.davacada.gasto\n" +
                "   SHA-1  DA:8D:57:04:EE:F1:D6:81:89:B1:A4:E0:04:4A:4C:A3:3A:2D:62:20\n\n" +
                "Then come back and tap Connect.")
            .setPositiveButton("Try again") { _, _ -> Prefs.setStr(this, "gmailError", null); Gmail.connect(this) { gmailDone(it) } }
            .setNeutralButton("Open Google Cloud") { _, _ -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.cloud.google.com/apis/credentials"))) } }
            .setNegativeButton("Close", null).show()
    }

    // MARK: dialogs

    private fun field(hint: String, value: String, number: Boolean) = EditText(this).apply {
        setText(value); this.hint = hint
        typeface = Fonts.get(context, 600); setTextColor(C.INK)
        inputType = if (number) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
    }

    private fun dialogBox(vararg views: View) = vbox().apply { setPadding(dp(22), dp(8), dp(22), 0); views.forEach { addView(it) } }

    private fun editBase() {
        val f = field("1270", Prefs.base(this).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }, true)
        AlertDialog.Builder(this).setTitle("Monthly base (€)").setView(dialogBox(f))
            .setPositiveButton("Save") { _, _ -> Parse.number(f.text.toString())?.takeIf { it > 0 }?.let { Prefs.setBase(this, it); render() } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun pickStartDay() {
        val days = (1..28).map { ordinal(it) }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Month starts on").setItems(days) { _, i -> Prefs.setStartDay(this, i + 1); render() }.show()
    }

    /** She types what she really has left; the difference is saved as an update so the month stays right. */
    private fun editLeft(left: Double) {
        val f = field("What you have left (€)", String.format(Locale.US, "%.2f", left), true)
        val hint = text("Check your bank app and type what's left for this month. Gasto keeps the difference as an update, so everything adds up.", 12.5f, C.SUB).apply {
            setPadding(0, dp(10), 0, 0); setLineSpacing(0f, 1.2f)
        }
        AlertDialog.Builder(this).setTitle("Update amount left").setView(dialogBox(f, hint))
            .setPositiveButton("Save") { _, _ ->
                val v = Parse.number(f.text.toString()) ?: return@setPositiveButton
                val diff = left - v      // positive: spent more than Gasto saw
                if (Math.abs(diff) < 0.005) return@setPositiveButton
                Store.add(this, Payment("adj:${System.currentTimeMillis()}", diff, "Balance update", System.currentTimeMillis(),
                    "adjust", "adjust", "other", note = "Set what's left to ${eur(v)}"))
                toast(if (diff > 0) "${eur(diff)} more counted as spent" else "${eur(-diff)} given back to this month")
                Alerts.checkBudget(this)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun addPayment() {
        val name = field("Where (e.g. Market)", "", false)
        val amt = field("Amount €", "", true)
        var kind = "daily"
        val kinds = hbox().apply { setPadding(0, dp(10), 0, 0) }
        fun drawKinds() {
            kinds.removeAllViews()
            listOf("daily" to "Daily", "sub" to "Subscription").forEach { (k, n) ->
                kinds.addView(chip(n, kind == k) { kind = k; drawKinds() }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
            }
        }
        drawKinds()
        AlertDialog.Builder(this).setTitle("Add a payment").setView(dialogBox(name, amt, kinds))
            .setPositiveButton("Add") { _, _ ->
                val v = Parse.number(amt.text.toString()) ?: return@setPositiveButton
                val n = name.text.toString().trim().ifEmpty { "Cash" }
                Store.add(this, Payment("m:${System.currentTimeMillis()}", Math.abs(v), n, System.currentTimeMillis(), "manual", kind, Parse.category(n)))
                Alerts.checkBudget(this)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun editPayment(p: Payment) {
        val name = field("Name", p.merchant, false)
        val amt = field("Amount €", String.format(Locale.US, "%.2f", p.amount), true)
        var kind = p.kind
        var cat = p.category
        val kinds = hbox().apply { setPadding(0, dp(10), 0, 0) }
        val cats = hbox().apply { setPadding(0, dp(8), 0, 0) }
        fun draw() {
            kinds.removeAllViews(); cats.removeAllViews()
            listOf("daily" to "Daily", "sub" to "Subscription").forEach { (k, n) ->
                kinds.addView(chip(n, kind == k) { kind = k; draw() }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
            }
            listOf("food" to "Food", "transport" to "Transport", "out" to "Going out", "other" to "Other").forEach { (k, n) ->
                cats.addView(chip(n, cat == k) { cat = k; draw() }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
            }
        }
        draw()
        val info = text("${sourceName(p)} · ${SimpleDateFormat("d MMM, HH:mm", Locale.ENGLISH).format(p.time)}" +
            (if (p.note.isNotBlank()) "\n“${p.note.take(140)}”" else ""), 12f, C.SUB).apply { setPadding(0, dp(12), 0, 0) }
        AlertDialog.Builder(this).setTitle("Payment").setView(dialogBox(name, amt, kinds, HorizontalScrollView(this).apply { addView(cats) }, info))
            .setPositiveButton("Save") { _, _ ->
                p.merchant = name.text.toString().trim().ifEmpty { p.merchant }
                Parse.number(amt.text.toString())?.let { p.amount = if (p.kind == "adjust") it else Math.abs(it) }
                p.kind = kind; p.category = cat
                Store.update(this, p)
            }
            .setNeutralButton("Remove") { _, _ ->
                AlertDialog.Builder(this).setMessage("Remove ${p.merchant} (${eur(p.amount)})? It won't count this month.")
                    .setPositiveButton("Remove") { _, _ -> Store.remove(this, p) }.setNegativeButton("Keep", null).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // MARK: Scan

    private val REQ_CSV = 5301

    private fun scanRow(title: String): Pair<LinearLayout, TextView> {
        val status = text("…", 12.5f, C.SUB)
        val row = vbox().apply {
            setPadding(0, dp(12), 0, dp(12))
            addView(text(title, 15f, C.INK, 600))
            addView(status.apply { setPadding(0, dp(3), 0, 0) })
        }
        return row to status
    }

    private fun scan() {
        val box = vbox().apply { setPadding(dp(22), dp(6), dp(22), dp(4)) }
        val (gRow, gStatus) = scanRow("Gmail receipts")
        val (bRow, bStatus) = scanRow("Bank & Wallet notifications")
        val (sRow, sStatus) = scanRow("Bank statement (CSV)")
        box.addView(gRow); box.addView(divider(), LinearLayout.LayoutParams(-1, 1))
        box.addView(bRow); box.addView(divider(), LinearLayout.LayoutParams(-1, 1))
        box.addView(sRow)
        val dlg = AlertDialog.Builder(this).setTitle("Scanning").setView(box).setPositiveButton("Done", null).show()

        // bank notifications still in the shade
        if (!listenerOn()) {
            bStatus.text = "Off · tap to turn on"; bStatus.setTextColor(C.G1)
            bRow.setOnClickListener { dlg.dismiss(); startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        } else {
            val n = PayListener.scanActive(this)
            bStatus.text = when {
                n < 0 -> "Starting up · new payments are caught as they come"
                n == 0 -> "Nothing new in your notifications · new payments are caught as they come"
                else -> "$n new payment${if (n == 1) "" else "s"} found"
            }
        }

        // Gmail: everything from the last two months
        when {
            Gmail.connected(this) -> {
                gStatus.text = "Reading your inbox…"
                Gmail.connect(this) { err ->
                    gStatus.text = err ?: "${Prefs.str(this, "gmailAdded") ?: "0"} new receipts"
                    render()
                }
            }
            Gmail.lastError(this) == Gmail.SETUP -> {
                gStatus.text = "Needs the one-time Google setup · tap"; gStatus.setTextColor(C.RED)
                gRow.setOnClickListener { dlg.dismiss(); gmailSetupHelp() }
            }
            else -> {
                gStatus.text = "Not connected · tap to connect"; gStatus.setTextColor(C.G1)
                gRow.setOnClickListener { dlg.dismiss(); Gmail.connect(this) { gmailDone(it) } }
            }
        }

        // older bank payments: from an exported statement
        sStatus.text = "For older payments: export a CSV from your bank app, then tap to pick it"
        sStatus.setTextColor(C.G1)
        sRow.setOnClickListener {
            dlg.dismiss()
            runCatching {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel", "application/octet-stream"))
                }, REQ_CSV)
            }
        }
    }

    private fun importStatement(uri: android.net.Uri) {
        toast("Reading the statement…")
        Thread {
            val msg = runCatching { Statement.import(this, uri) }.fold(
                { (a, s) -> "$a payment${if (a == 1) "" else "s"} added" + (if (s > 0) " · $s skipped (income, duplicates or unreadable)" else "") },
                { it.message ?: "Couldn't read that file." })
            runOnUiThread { toast(msg); render() }
        }.start()
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
}
