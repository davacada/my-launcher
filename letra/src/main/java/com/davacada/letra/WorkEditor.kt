package com.davacada.letra

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** One working day: the date, and the minutes after midnight you came in and left (null until written). */
class Shift(var day: String, var inMin: Int?, var outMin: Int?) {
    /** Minutes worked; a shift that ends before it starts ran past midnight. */
    fun minutes(): Int? {
        val i = inMin ?: return null; val o = outMin ?: return null
        return if (o >= i) o - i else o + 1440 - i
    }
}

object Work {
    /** Told when something outside the editor (a reminder answer) changed the timesheet. */
    val changed = mutableListOf<() -> Unit>()

    /** The day still waiting for "out": today's, or last night's if it ran past midnight. */
    fun openShift(n: Note): Shift? {
        val yesterday = key.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time)
        return n.shifts.filter { it.inMin != null && it.outMin == null && (it.day == today() || it.day == yesterday) }.maxByOrNull { it.day }
    }
    private val key = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    fun today(): String = key.format(Calendar.getInstance().time)
    fun cal(day: String): Calendar = Calendar.getInstance().apply { time = key.parse(day)!! }
    fun nowMin(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
    fun clock(m: Int?) = if (m == null) "--:--" else "%02d:%02d".format(m / 60, m % 60)
    fun dur(m: Int) = if (m % 60 == 0) "${m / 60} h" else "${m / 60} h ${m % 60} min"
    fun dayLabel(day: String): String = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(cal(day).time).replaceFirstChar { it.uppercase() }
    fun monthKey(day: String) = day.substring(0, 7)
    fun monthLabel(month: String): String = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal("$month-01").time).replaceFirstChar { it.uppercase() }

    /** The timesheet as text, month by month (newest first). */
    fun text(n: Note): String = buildString {
        append(n.displayTitle()).append("\n")
        n.shifts.sortedByDescending { it.day }.groupBy { monthKey(it.day) }.forEach { (m, list) ->
            val total = list.sumOf { it.minutes() ?: 0 }
            append("\n").append(monthLabel(m)).append("  ·  ").append(dur(total)).append("  ·  ").append(list.size).append(if (list.size == 1) " day" else " days").append("\n")
            list.sortedBy { it.day }.forEach { s ->
                append(dayLabel(s.day)).append("\n")
                append("   in ").append(clock(s.inMin)).append("   out ").append(clock(s.outMin))
                s.minutes()?.let { append("   ").append(dur(it)) }
                append("\n")
            }
        }
    }
}

/**
 * Work: a timesheet. "Came in" writes today's date and the time; "Leaving" writes the time you left.
 * Days are grouped by month with the month's total. Tap any time or date to change it.
 */
class WorkEditor(a: LetraActivity, n: Note) : Editor(a, n) {
    private val color = Look.color(Note.WORK)
    private val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(16), a.dp(4), a.dp(16), a.dp(40)) }
    private val bottom = FrameLayout(a)
    private var showAll = mutableSetOf<String>()

    private val reload: () -> Unit = {
        a.store.all().firstOrNull { it.id == n.id }?.let { fresh -> n.shifts.clear(); n.shifts.addAll(fresh.shifts); render(); renderBottom() }
    }
    init { Work.changed.add(reload) }
    override fun release() { Work.changed.remove(reload); super.release() }

    override fun topActions(): List<View> = listOf(a.iconButton(R.drawable.i_pin, "Location reminders", if (Places.enabled(a)) color else Look.UMBER) { placesSheet() })

    override fun view(): View { render(); return ScrollView(a).apply { isVerticalScrollBarEnabled = false; addView(box) } }
    override fun tools(): View { renderBottom(); return bottom }

    private fun open(): Shift? = Work.openShift(n)

    private fun renderBottom() {
        bottom.removeAllViews()
        val o = open()
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(12)); setBackgroundColor(Look.CARD) }
        if (o == null) {
            row.addView(a.bigButton("Came in", R.drawable.i_in, color, true) { cameIn() }, LinearLayout.LayoutParams(0, a.dp(54), 1f))
        } else {
            row.addView(a.text("In since " + Work.clock(o.inMin), 14f, Look.SUB, true).apply { setPadding(a.dp(4), 0, a.dp(12), 0) })
            row.addView(a.bigButton("Leaving", R.drawable.i_out, color, true) { leaving(o) }, LinearLayout.LayoutParams(0, a.dp(54), 1f))
        }
        row.addView(a.iconButton(R.drawable.i_add, "Add a day by hand", color) { addDay() }, LinearLayout.LayoutParams(a.dp(48), a.dp(48)).apply { marginStart = a.dp(8) })
        bottom.addView(row)
    }

    private fun cameIn() {
        val s = Shift(Work.today(), Work.nowMin(), null)
        n.shifts.add(s); changed()
        a.toast("Came in at " + Work.clock(s.inMin))
    }

    private fun leaving(s: Shift) {
        s.outMin = Work.nowMin(); changed()
        s.minutes()?.let { a.toast("Left at ${Work.clock(s.outMin)} · ${Work.dur(it)}") }
    }

    private fun addDay() {
        val c = Calendar.getInstance()
        DatePickerDialog(a, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert, { _, y, m, d ->
            val day = "%04d-%02d-%02d".format(y, m + 1, d)
            val s = Shift(day, null, null); n.shifts.add(s); changed()
            pickTime(s, true)
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun pickTime(s: Shift, isIn: Boolean, thenOut: Boolean = true) {
        val cur = (if (isIn) s.inMin else s.outMin) ?: if (isIn) 8 * 60 else 16 * 60
        TimePickerDialog(a, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert, { _, h, m ->
            if (isIn) s.inMin = h * 60 + m else s.outMin = h * 60 + m
            changed()
            if (isIn && thenOut && s.outMin == null && s.day != Work.today()) pickTime(s, false)
        }, cur / 60, cur % 60, true).apply { setTitle(if (isIn) "Came in at" else "Left at") }.show()
    }

    private fun pickDate(s: Shift) {
        val c = Work.cal(s.day)
        DatePickerDialog(a, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert, { _, y, m, d ->
            s.day = "%04d-%02d-%02d".format(y, m + 1, d); changed()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun changed() { dirty(); render(); renderBottom() }

    private fun render() {
        box.removeAllViews()
        if (n.shifts.isEmpty()) {
            box.addView(a.text("Tap “Came in” when you get to work, and “Leaving” when you go. The date writes itself.", 15f, Look.SUB).apply { setLineSpacing(0f, 1.2f); setPadding(a.dp(6), a.dp(18), a.dp(6), 0) })
            return
        }
        val months = n.shifts.sortedByDescending { it.day }.groupBy { Work.monthKey(it.day) }
        months.entries.forEachIndexed { mi, (m, list) ->
            val total = list.sumOf { it.minutes() ?: 0 }
            // month heading with the total
            box.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
                setPadding(a.dp(6), a.dp(if (mi == 0) 8 else 26), a.dp(6), a.dp(8))
                addView(a.text(Work.monthLabel(m), 20f, Look.UMBER, true).apply { typeface = Typeface.create("serif", Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(a.text(Work.dur(total), 15f, color, true))
            })
            box.addView(a.text("${list.size} " + (if (list.size == 1) "day" else "days") + (if (total > 0 && list.size > 0) "  ·  avg " + Work.dur(total / list.count { it.minutes() != null }.coerceAtLeast(1)) else ""), 12.5f, Look.SUB).apply { setPadding(a.dp(6), 0, 0, a.dp(8)) })
            // days in order through the month, like a written page
            list.sortedBy { it.day }.forEach { s -> box.addView(dayRow(s)) }
        }
    }

    private fun dayRow(s: Shift): View {
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12))
            background = pill(Look.PAPER, a.dpf(16f))
            setOnLongClickListener { remove(s); true }
        }
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(a.text(Work.dayLabel(s.day), 16f, Look.UMBER, true).apply {
            typeface = Typeface.create("serif", Typeface.BOLD)
            if (s.day == Work.today()) setTextColor(color)
            setOnClickListener { pickDate(s) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        s.minutes()?.let { top.addView(a.text(Work.dur(it), 14f, Look.SOFT, true)) }
        card.addView(top)
        val times = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, a.dp(8), 0, 0) }
        times.addView(timeChip("in", s.inMin) { pickTime(s, true, thenOut = false) })
        times.addView(a.text("→", 15f, Look.FAINT).apply { setPadding(a.dp(10), 0, a.dp(10), 0) })
        times.addView(timeChip("out", s.outMin) { pickTime(s, false) })
        card.addView(times)
        return FrameLayout(a).apply { setPadding(0, 0, 0, a.dp(8)); addView(card) }
    }

    private fun timeChip(label: String, m: Int?, onClick: () -> Unit) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(a.dp(12), a.dp(6), a.dp(14), a.dp(6))
        background = ripple(pill(Look.TINT, a.dpf(14f)), pill(0xFFFFFFFF.toInt(), a.dpf(14f)))
        addView(a.text(label, 12f, Look.SUB, true).apply { setPadding(0, 0, a.dp(8), 0) })
        addView(a.text(Work.clock(m), 18f, if (m == null) Look.FAINT else Look.UMBER, true).apply { typeface = Typeface.create("serif", Typeface.BOLD) })
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------ location reminders

    private fun placesSheet() {
        val places = Places.load(a)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        box.addView(a.text("When you've been at work for a few minutes, Letra asks “At work?”. When you leave, or get home with the day still open, it asks “Done with work?”. Tap Yes, type a time, or say it.", 13.5f, Look.SUB).apply { setLineSpacing(0f, 1.15f); setPadding(a.dp(6), 0, a.dp(6), a.dp(12)) })
        val status = a.text("", 13f, Look.SUB).apply { setPadding(a.dp(6), a.dp(6), a.dp(6), 0) }
        val edits = places.map { p ->
            box.addView(a.text(if (p.work) "Work" else "Home", 12f, if (p.work) color else Look.SOFT, true).apply { letterSpacing = 0.06f; setPadding(a.dp(6), a.dp(8), 0, a.dp(2)) })
            val e = android.widget.EditText(a).apply {
                setText(p.address); textSize = 15f; setTextColor(Look.UMBER); background = pill(Look.PAPER, a.dpf(14f))
                setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10)); isSingleLine = true
            }
            box.addView(e, LinearLayout.LayoutParams(-1, -2))
            box.addView(a.text(if (p.found) "✓ found on the map" else "not looked up yet", 11.5f, if (p.found) color else Look.FAINT).apply { setPadding(a.dp(10), a.dp(3), 0, 0) })
            e
        }
        box.addView(status)
        val on = Places.enabled(a)
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, a.dp(14), 0, 0) }
        row.addView(a.bigButton(if (on) "Save" else "Turn on", R.drawable.i_pin, color, true) {
            places.forEachIndexed { i, p -> val t = edits[i].text.toString().trim(); if (t != p.address) { p.address = t; p.lat = 0.0; p.lng = 0.0 } }
            status.text = "Finding the addresses…"
            Thread {
                val missing = Places.geocode(a, places)
                Places.save(a, places)
                a.main.post {
                    if (missing.size == places.size) { status.text = "Couldn't find these addresses. Check the internet and the spelling."; return@post }
                    askPermissions {
                        Places.setEnabled(a, true)
                        Places.arm(a) { err ->
                            a.main.post {
                                if (err != null) { status.text = err; Places.setEnabled(a, false) }
                                else { a.closeSheet(); a.toast(if (missing.isEmpty()) "Location reminders are on" else "On, but couldn't find: " + missing.joinToString { it.address }); a.open(n) }
                            }
                        }
                    }
                }
            }.start()
        }, LinearLayout.LayoutParams(0, a.dp(52), 1f))
        if (on) row.addView(a.bigButton("Turn off", null, color, false) {
            Places.setEnabled(a, false); Places.arm(a); a.closeSheet(); a.toast("Location reminders are off"); a.open(n)
        }, LinearLayout.LayoutParams(-2, a.dp(52)).apply { marginStart = a.dp(10) })
        box.addView(row)
        box.addView(urnikSection())
        a.showSheet("Location reminders", ScrollView(a).apply { addView(box) })
    }

    /** Urnik.NET: clock in and out there too when you answer Yes. */
    private fun urnikSection(): View {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, a.dp(22), 0, 0) }
        box.addView(a.text("Urnik.NET", 17f, Look.UMBER, true).apply { typeface = Typeface.create("serif", Typeface.BOLD); setPadding(a.dp(6), 0, 0, a.dp(4)) })
        val pkg = Urnik.pkg(a)
        val svc = Urnik.serviceOn(a)
        val on = Urnik.enabled(a) && svc
        val learnedIn = Urnik.learned(a, Places.ASK_IN); val learnedOut = Urnik.learned(a, Places.ASK_OUT)
        val lines = buildList {
            add(if (pkg != null) "✓ Urnik.NET is installed" else "✗ Urnik.NET isn't installed on this phone")
            add(if (svc) "✓ Letra may tap buttons in it" else "✗ Letra isn't allowed to tap buttons yet (Accessibility)")
            add("Clock-in button: " + (learnedIn ?: "looks for Prihod / Prijava / Začetek"))
            add("Clock-out button: " + (learnedOut ?: "looks for Odhod / Odjava / Konec"))
        }
        box.addView(a.text("When you answer Yes, Letra opens Urnik.NET and taps the clock-in or clock-out button for you, then goes back to the home screen. If the phone is locked, it happens when you unlock it. Urnik.NET records the moment of the tap, so a time you type that's more than 15 minutes off isn't sent there.", 13.5f, Look.SUB).apply { setLineSpacing(0f, 1.15f); setPadding(a.dp(6), 0, a.dp(6), a.dp(8)) })
        box.addView(a.text(lines.joinToString("\n"), 13f, Look.SOFT).apply { setLineSpacing(0f, 1.25f); setPadding(a.dp(6), 0, a.dp(6), a.dp(10)) })
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        when {
            pkg == null -> {}
            !svc -> row.addView(a.bigButton("Allow in Accessibility", null, color, true) {
                AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                    .setTitle("Let Letra tap Urnik.NET")
                    .setMessage("On the next screen open “Letra · Urnik.NET clock in/out” and turn it on.\n\nIf it's greyed out: go to Settings › Apps › Letra, tap ⋮ (top right) › Allow restricted settings, then try again.")
                    .setPositiveButton("Open settings") { _, _ -> Urnik.setEnabled(a, true); a.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    .setNegativeButton("Cancel", null).show()
            }, LinearLayout.LayoutParams(0, a.dp(52), 1f))
            on -> {
                row.addView(a.bigButton("Clock in/out is on", null, color, true) {}, LinearLayout.LayoutParams(0, a.dp(52), 1f))
                row.addView(a.bigButton("Turn off", null, color, false) { Urnik.setEnabled(a, false); a.closeSheet(); placesSheet() }, LinearLayout.LayoutParams(-2, a.dp(52)).apply { marginStart = a.dp(10) })
            }
            else -> row.addView(a.bigButton("Clock in/out on Urnik.NET", null, color, true) { Urnik.setEnabled(a, true); a.closeSheet(); placesSheet() }, LinearLayout.LayoutParams(0, a.dp(52), 1f))
        }
        box.addView(row)
        return box
    }

    /** Location while using the app, then "Allow all the time" (Android asks that one separately), then notifications. */
    private fun askPermissions(then: () -> Unit) {
        if (!Places.hasLocation(a)) {
            a.permissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.POST_NOTIFICATIONS)) {
                if (Places.hasLocation(a)) askPermissions(then) else a.toast("Letra needs your location for this")
            }
            return
        }
        if (!Places.hasBackground(a)) {
            AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("Allow all the time")
                .setMessage("So Letra can notice when you get to work and leave, even when it's closed, choose “Allow all the time” on the next screen.")
                .setPositiveButton("Continue") { _, _ ->
                    a.permissions(arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                        if (Places.hasBackground(a)) then() else a.toast("Choose “Allow all the time” for Letra's location")
                    }
                }
                .setNegativeButton("Cancel", null).show()
            return
        }
        then()
    }

    private fun remove(s: Shift) {
        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("Remove ${Work.dayLabel(s.day)}?")
            .setPositiveButton("Remove") { _, _ ->
                val i = n.shifts.indexOf(s); n.shifts.remove(s); changed()
                a.undoBar("Day removed") { n.shifts.add(i.coerceIn(0, n.shifts.size), s); changed() }
            }
            .setNegativeButton("Cancel", null).show()
    }
}
