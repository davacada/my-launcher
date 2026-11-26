package com.davacada.letra

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.Normalizer
import java.util.Locale

/**
 * Clocks you in and out in Urnik.NET when you answer a Work reminder: Letra opens Urnik.NET and taps
 * its clock-in (Prihod) or clock-out (Odhod) button for you, through an Accessibility service.
 *
 * Urnik.NET always records the moment the button is tapped, so only answers close to "now" are sent there.
 * If the phone is locked, the tap waits until you unlock it.
 */
object Urnik {
    private const val PREFS = "urnik"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val IN_WORDS = listOf("prihod", "prijava", "zacetek", "zacni", "zacetek dela", "vhod", "check in", "check-in", "clock in", "start", "in")
    val OUT_WORDS = listOf("odhod", "odjava", "konec", "zakljuci", "konec dela", "izhod", "check out", "check-out", "clock out", "stop", "out")
    val CONFIRM_WORDS = listOf("potrdi", "da", "ok", "v redu", "shrani", "confirm", "yes", "save")
    /** Never tapped, whatever they say: breaks, absences and the like. */
    val NEVER = listOf("malic", "odmor", "pavza", "dopust", "bolni", "sluzben", "odsot", "potni", "break", "lunch", "vacation", "sick", "trip", "izbris", "brisi", "delete", "preklic", "cancel")

    fun enabled(c: Context) = prefs(c).getBoolean("on", false)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("on", on).apply()

    fun norm(s: CharSequence?): String = Normalizer.normalize(s?.toString() ?: "", Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

    /** Button text learned from your own taps in Urnik.NET (more exact than the word lists). */
    fun learned(c: Context, kind: String): String? = prefs(c).getString("btn_$kind", null)
    fun learn(c: Context, kind: String, text: String) = prefs(c).edit().putString("btn_$kind", text).apply()

    /** Urnik.NET's package, found by its name in the app list. */
    fun pkg(c: Context): String? {
        prefs(c).getString("pkg", null)?.let { p -> if (c.packageManager.getLaunchIntentForPackage(p) != null) return p }
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val hit = c.packageManager.queryIntentActivities(i, 0).firstOrNull { ri ->
            val label = norm(ri.loadLabel(c.packageManager))
            label.contains("urnik") || ri.activityInfo.packageName.lowercase().contains("urnik")
        } ?: return null
        val p = hit.activityInfo.packageName
        prefs(c).edit().putString("pkg", p).apply()
        return p
    }

    fun serviceOn(c: Context): Boolean {
        val want = ComponentName(c, UrnikService::class.java).flattenToString()
        val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { it.equals(want, true) }
    }

    /** Queue a punch; the service does it now, or as soon as the phone is unlocked. */
    fun punch(c: Context, kind: String) {
        prefs(c).edit().putString("pending", kind).putLong("pendingAt", System.currentTimeMillis()).apply()
        UrnikService.instance?.kick()
    }
    fun pending(c: Context): String? {
        val p = prefs(c)
        val k = p.getString("pending", null) ?: return null
        // a punch that's been waiting more than 2 hours is stale: better to do nothing than to clock the wrong time
        if (System.currentTimeMillis() - p.getLong("pendingAt", 0) > 2 * 3600_000L) { clear(c); Places.confirm(c, "Didn't clock " + (if (k == Places.ASK_IN) "in" else "out") + " on Urnik.NET: the phone stayed locked too long."); return null }
        return k
    }
    fun clear(c: Context) = prefs(c).edit().remove("pending").apply()
}

class UrnikService : AccessibilityService() {
    companion object { var instance: UrnikService? = null }
    private val main = Handler(Looper.getMainLooper())
    private var running: String? = null           // kind being punched right now
    private var startedAt = 0L
    private var tapped = false
    private var tappedAt = 0L

    override fun onServiceConnected() { instance = this; kick() }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onInterrupt() {}

    private fun locked() = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    /** Start a waiting punch if the phone is unlocked. */
    fun kick() {
        if (running != null || !Urnik.enabled(this)) return
        val kind = Urnik.pending(this) ?: return
        if (locked()) return
        val pkg = Urnik.pkg(this) ?: run { Urnik.clear(this); Places.confirm(this, "Couldn't find Urnik.NET on this phone."); return }
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return
        running = kind; startedAt = System.currentTimeMillis(); tapped = false
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        main.postDelayed({ step() }, 1200)
        main.postDelayed({ if (running == kind) finish(false, "Couldn't find the " + (if (kind == Places.ASK_IN) "clock-in (Prihod)" else "clock-out (Odhod)") + " button in Urnik.NET. Tap it yourself once, and Letra will learn it.") }, 25_000)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = Urnik.pkg(this)
        // unlocking, or anything happening: a waiting punch can go now
        if (running == null) {
            if (Urnik.pending(this) != null && !locked()) kick()
            // learn the buttons from your own taps in Urnik.NET
            if (e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && e.packageName == pkg) {
                val t = Urnik.norm(e.text?.joinToString(" ") ?: e.contentDescription)
                    .ifEmpty { Urnik.norm(e.source?.let { labelOf(it) }) }
                when {
                    t.isEmpty() || Urnik.NEVER.any { t.contains(it) } -> {}
                    matches(t, Urnik.IN_WORDS) -> Urnik.learn(this, Places.ASK_IN, t)
                    matches(t, Urnik.OUT_WORDS) -> Urnik.learn(this, Places.ASK_OUT, t)
                }
            }
            return
        }
        if (e.packageName == pkg) { main.removeCallbacks(stepper); main.postDelayed(stepper, 350) }
    }

    private val stepper = Runnable { step() }

    private fun step() {
        val kind = running ?: return
        val root = rootInActiveWindow ?: return
        if (root.packageName != Urnik.pkg(this)) return
        val nodes = all(root)
        if (!tapped) {
            val mine = find(nodes, kind)
            val other = find(nodes, if (kind == Places.ASK_IN) Places.ASK_OUT else Places.ASK_IN)
            if (mine != null) {
                if (click(mine)) { tapped = true; tappedAt = System.currentTimeMillis(); main.postDelayed(stepper, 900) }
            } else if (other != null && System.currentTimeMillis() - startedAt > 4000) {
                // only the opposite button is there: you're already clocked in (or out)
                finish(true, "Urnik.NET already shows you clocked " + (if (kind == Places.ASK_IN) "in" else "out") + ".")
            }
            return
        }
        // after the tap: answer a confirmation dialog if one appears, then we're done
        val confirm = nodes.firstOrNull { n -> n.isVisibleToUser && clickableOf(n) != null && Urnik.norm(labelOf(n)).let { t -> t.isNotEmpty() && Urnik.CONFIRM_WORDS.any { w -> t == w } } }
        if (confirm != null && System.currentTimeMillis() - tappedAt < 6000) { click(confirm); tappedAt = 0L; main.postDelayed(stepper, 900); return }
        if (System.currentTimeMillis() - tappedAt > 1500 || tappedAt == 0L) finish(true, "Clocked " + (if (kind == Places.ASK_IN) "in" else "out") + " on Urnik.NET at " + Work.clock(Work.nowMin()) + ".")
        else main.postDelayed(stepper, 700)
    }

    private fun finish(ok: Boolean, msg: String) {
        running = null
        Urnik.clear(this)
        main.removeCallbacksAndMessages(null)
        if (ok) main.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 600)
        Places.confirm(this, msg)
    }

    private fun matches(t: String, words: List<String>) = words.any { w -> t == w || t.startsWith("$w ") || t.endsWith(" $w") || (w.length > 3 && t.contains(w)) }

    private fun find(nodes: List<AccessibilityNodeInfo>, kind: String): AccessibilityNodeInfo? {
        val learned = Urnik.learned(this, kind)
        val words = if (kind == Places.ASK_IN) Urnik.IN_WORDS else Urnik.OUT_WORDS
        val cands = nodes.filter { it.isVisibleToUser && it.isEnabled && clickableOf(it) != null }
            .map { it to Urnik.norm(labelOf(it)) }
            .filter { (_, t) -> t.isNotEmpty() && t.length < 40 && Urnik.NEVER.none { t.contains(it) } }
        if (learned != null) cands.firstOrNull { it.second == learned }?.let { return it.first }
        return cands.firstOrNull { (_, t) -> matches(t, words) }?.first
    }

    private fun labelOf(n: AccessibilityNodeInfo): String =
        listOfNotNull(n.text, n.contentDescription).joinToString(" ").ifEmpty {
            (0 until n.childCount).mapNotNull { n.getChild(it) }.joinToString(" ") { c -> listOfNotNull(c.text, c.contentDescription).joinToString(" ") }
        }

    private fun clickableOf(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var x: AccessibilityNodeInfo? = n; var d = 0
        while (x != null && d < 4) { if (x.isClickable) return x; x = x.parent; d++ }
        return null
    }

    private fun click(n: AccessibilityNodeInfo) = clickableOf(n)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

    private fun all(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>(); val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(root)
        while (q.isNotEmpty() && out.size < 600) { val n = q.removeFirst(); out.add(n); for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) } }
        return out
    }
}
