package com.davacada.letra

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** A place that matters for the timesheet: somewhere you work, or home. */
class Place(var name: String, var address: String, var work: Boolean, var lat: Double = 0.0, var lng: Double = 0.0) {
    val found get() = lat != 0.0 || lng != 0.0
    fun json() = JSONObject().put("n", name).put("a", address).put("w", work).put("lat", lat).put("lng", lng)
}

/**
 * Location reminders for Work: when you've been at a work address for a few minutes Letra asks
 * "At work?", when you walk away (or get home with the day still open) it asks "Done with work?".
 * Answer Yes, type a time, or say it; the time goes into today's line of the timesheet.
 */
object Places {
    private const val PREFS = "work_places"
    const val CH = "work"
    const val NOTE_ID = 4100
    const val RADIUS = 130f

    fun defaults() = mutableListOf(
        Place("Work", "Ulica Dušana Kvedra 46, 3230 Šentjur, Slovenia", true),
        Place("Work", "Mestni trg 5b, 3230 Šentjur, Slovenia", true),
        Place("Home", "Gajstova pot 12, 3230 Šentjur, Slovenia", false),
    )

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("on", false)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("on", on).apply()

    fun load(c: Context): MutableList<Place> {
        val s = prefs(c).getString("places", null) ?: return defaults()
        return try {
            val a = JSONArray(s)
            MutableList(a.length()) { i -> a.getJSONObject(i).let { Place(it.optString("n"), it.optString("a"), it.optBoolean("w"), it.optDouble("lat", 0.0), it.optDouble("lng", 0.0)) } }
        } catch (e: Exception) { defaults() }
    }
    fun save(c: Context, list: List<Place>) = prefs(c).edit().putString("places", JSONArray().apply { list.forEach { put(it.json()) } }.toString()).apply()

    /** Looks up addresses that don't have a position yet. Run off the main thread. */
    @Suppress("DEPRECATION")
    fun geocode(c: Context, list: List<Place>): List<Place> {
        val g = Geocoder(c, Locale.getDefault())
        return list.filter { !it.found && it.address.isNotBlank() }.filter { p ->
            val r = try { g.getFromLocationName(p.address, 1) } catch (e: Exception) { null }
            val hit = r?.firstOrNull()
            if (hit != null) { p.lat = hit.latitude; p.lng = hit.longitude; false } else true
        }   // returns the ones that couldn't be found
    }

    fun hasLocation(c: Context) = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun hasBackground(c: Context) = c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun fencePi(c: Context) = PendingIntent.getBroadcast(c, 1, Intent(c, FenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    /** (Re)registers the geofences, or removes them when reminders are off. */
    @SuppressLint("MissingPermission")
    fun arm(c: Context, done: ((String?) -> Unit)? = null) {
        val client = LocationServices.getGeofencingClient(c)
        client.removeGeofences(fencePi(c))
        if (!enabled(c)) { done?.invoke(null); return }
        if (!hasLocation(c) || !hasBackground(c)) { done?.invoke("Location “Allow all the time” is needed"); return }
        val places = load(c).filter { it.found }
        if (places.isEmpty()) { done?.invoke("None of the addresses could be found"); return }
        val fences = places.mapIndexed { i, p ->
            Geofence.Builder()
                .setRequestId("${if (p.work) "w" else "h"}$i")
                .setCircularRegion(p.lat, p.lng, RADIUS)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(if (p.work) Geofence.GEOFENCE_TRANSITION_DWELL or Geofence.GEOFENCE_TRANSITION_EXIT else Geofence.GEOFENCE_TRANSITION_ENTER)
                .setLoiteringDelay(3 * 60_000)
                .build()
        }
        val req = GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(fences).build()
        try {
            client.addGeofences(req, fencePi(c))
                .addOnSuccessListener { done?.invoke(null) }
                .addOnFailureListener { e -> done?.invoke("Couldn't set up: " + (e.message ?: "location is off?")) }
        } catch (e: SecurityException) { done?.invoke("Location permission is missing") }
    }

    // ------------------------------------------------------------ asking

    const val ASK_IN = "in"; const val ASK_OUT = "out"
    const val EXTRA_KIND = "kind"; const val EXTRA_AT = "at"; const val EXTRA_ANSWER = "answer"; const val KEY_REPLY = "reply"

    private fun channel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "Work reminders", NotificationManager.IMPORTANCE_HIGH).apply { description = "Asks if you're at work or done, based on where you are" })
    }

    private fun answerPi(c: Context, kind: String, at: Int, answer: String?, code: Int, mutable: Boolean) =
        PendingIntent.getBroadcast(c, code, Intent(c, AnswerReceiver::class.java).putExtra(EXTRA_KIND, kind).putExtra(EXTRA_AT, at).apply { answer?.let { putExtra(EXTRA_ANSWER, it) } },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE))

    /** "At work?" or "Done with work?", with Yes, a typed reply, and a spoken reply. [at] is the time we noticed, in minutes. */
    fun ask(c: Context, kind: String, at: Int, where: String) {
        channel(c)
        val inQ = kind == ASK_IN
        val title = if (inQ) "At work?" else "Done with work?"
        val body = if (inQ) "You got to $where around ${Work.clock(at)}. Yes, or tell me the time you came in."
                   else "You left $where around ${Work.clock(at)}. Yes, or tell me the time you finished."
        val reply = Notification.Action.Builder(null, "Type", answerPi(c, kind, at, null, if (inQ) 11 else 21, true))
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel(if (inQ) "Yes / 8:15 / no" else "Yes / 16:30 / not yet").build())
            .build()
        val say = PendingIntent.getActivity(c, if (inQ) 12 else 22, Intent(c, VoiceAnswer::class.java).putExtra(EXTRA_KIND, kind).putExtra(EXTRA_AT, at)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(c, 13, Intent(c, LetraActivity::class.java).putExtra("openWork", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, CH)
            .setSmallIcon(R.drawable.i_work).setColor(Look.TEAL)
            .setContentTitle(title).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open).setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Yes · " + Work.clock(at), answerPi(c, kind, at, "yes", if (inQ) 10 else 20, false)).build())
            .addAction(reply)
            .addAction(Notification.Action.Builder(null, "Say it", say).build())
            .build()
        c.getSystemService(NotificationManager::class.java).notify(NOTE_ID, n)
    }

    /** Replaces the question with what was written down; it goes away by itself. */
    fun confirm(c: Context, text: String) {
        channel(c)
        val n = Notification.Builder(c, CH).setSmallIcon(R.drawable.i_work).setColor(Look.TEAL)
            .setContentTitle("Letra · Work").setContentText(text).setTimeoutAfter(10_000).setOnlyAlertOnce(true).build()
        c.getSystemService(NotificationManager::class.java).notify(NOTE_ID, n)
    }

    /**
     * Understands "yes", "da", "now", "no", "not yet", "8", "8:15", "8.15", "815", "came in at 7:50", "4:30 pm".
     * Returns minutes after midnight, or null for "no".
     */
    fun parse(answer: String, kind: String, at: Int, inMin: Int?): Int? {
        val t = answer.lowercase(Locale.ROOT).trim()
        val no = Regex("\\b(no|nope|not|ne|nisem|nein|later|kasneje)\\b").containsMatchIn(t)
        val m = Regex("(\\d{1,2})(?:\\s*[:.h]\\s*(\\d{2})|(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?").find(t)
        if (m != null) {
            var h = m.groupValues[1].toInt()
            val mm = (m.groupValues[2].ifEmpty { m.groupValues[3] }).ifEmpty { "0" }.toInt()
            val ampm = m.groupValues[4]
            if (ampm.startsWith("p") && h < 12) h += 12
            if (ampm.startsWith("a") && h == 12) h = 0
            // "left at 4" after coming in at 8 means 16:00
            if (ampm.isEmpty() && kind == ASK_OUT && inMin != null && h < 12 && h * 60 + mm < inMin) h += 12
            if (h in 0..23 && mm in 0..59) return h * 60 + mm
        }
        if (no) return null
        if (t.isEmpty() || Regex("\\b(yes|yeah|yep|yup|ok|okay|sure|da|ja|ya|now|zdaj|correct|right|done|finished|here|in)\\b").containsMatchIn(t)) return at
        return at
    }

    /** Writes the answer into today's line of the timesheet and says what was written. */
    fun record(c: Context, kind: String, at: Int, answer: String) {
        val store = Store(c)
        val note = store.all().firstOrNull { it.mode == Note.WORK } ?: Note(mode = Note.WORK)
        val today = Work.today()
        val open = Work.openShift(note)
        val min = parse(answer, kind, at, open?.inMin)
        if (min == null) {
            confirm(c, if (kind == ASK_IN) "Okay, not at work. Nothing written." else "Okay, still working. I'll ask again when you get home.")
            return
        }
        if (kind == ASK_IN) {
            val s = note.shifts.firstOrNull { it.day == today && it.outMin == null } ?: Shift(today, null, null).also { note.shifts.add(it) }
            s.inMin = min
            store.save(note)
            confirm(c, "Came in at ${Work.clock(min)} · written in Letra")
            toUrnik(c, kind, min)
        } else {
            val s = open ?: note.shifts.firstOrNull { it.day == today } ?: Shift(today, null, null).also { note.shifts.add(it) }
            s.outMin = min
            store.save(note)
            confirm(c, "Left at ${Work.clock(min)}" + (s.minutes()?.let { " · ${Work.dur(it)} today" } ?: "") + " · written in Letra")
            toUrnik(c, kind, min)
        }
        Handler(Looper.getMainLooper()).post { Work.changed.toList().forEach { it() } }
    }
}

/**
 * Sends the punch to Urnik.NET. It records the moment of the tap, so a time far from now (a correction like
 * "came in at 7:50" an hour later) isn't sent: that would put the wrong time in your official record.
 */
private fun toUrnik(c: Context, kind: String, min: Int) {
    if (!Urnik.enabled(c)) return
    val now = Work.nowMin()
    val diff = minOf(Math.abs(now - min), 1440 - Math.abs(now - min))
    if (diff <= 15) Urnik.punch(c, kind)
    else Places.confirm(c, "Written in Letra (${Work.clock(min)}). Not sent to Urnik.NET: it would record ${Work.clock(now)} instead, so fix it there.")
}

/** Geofence transitions: arriving at work, leaving it, getting home. */
class FenceReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val ev = GeofencingEvent.fromIntent(i) ?: return
        if (ev.hasError() || !Places.enabled(c)) return
        val ids = ev.triggeringGeofences?.map { it.requestId } ?: return
        val places = Places.load(c)
        val where = ids.firstNotNullOfOrNull { id -> id.drop(1).toIntOrNull()?.let { places.getOrNull(it) } }?.address?.substringBefore(",") ?: "work"
        val note = Store(c).all().firstOrNull { it.mode == Note.WORK }
        val open = note?.let { Work.openShift(it) }
        val now = Work.nowMin()
        val p = c.getSharedPreferences("work_places", Context.MODE_PRIVATE)
        when (ev.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_DWELL -> if (ids.any { it.startsWith("w") }) {
                val cameToday = note?.shifts?.any { it.day == Work.today() && it.inMin != null } == true
                if (!cameToday) Places.ask(c, Places.ASK_IN, (now - 3 + 1440) % 1440, where)
            }
            Geofence.GEOFENCE_TRANSITION_EXIT -> if (ids.any { it.startsWith("w") } && open != null) {
                p.edit().putInt("leftAt", now).putString("leftDay", Work.today()).apply()
                Places.ask(c, Places.ASK_OUT, now, where)
            }
            Geofence.GEOFENCE_TRANSITION_ENTER -> if (ids.any { it.startsWith("h") } && open != null) {
                val left = if (p.getString("leftDay", "") == Work.today()) p.getInt("leftAt", now) else now
                Places.ask(c, Places.ASK_OUT, left, "work")
            }
        }
    }
}

/** Yes / typed answers from the notification. */
class AnswerReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val kind = i.getStringExtra(Places.EXTRA_KIND) ?: return
        val at = i.getIntExtra(Places.EXTRA_AT, Work.nowMin())
        val typed = RemoteInput.getResultsFromIntent(i)?.getCharSequence(Places.KEY_REPLY)?.toString()
        Places.record(c, kind, at, typed ?: i.getStringExtra(Places.EXTRA_ANSWER) ?: "yes")
    }
}

/** Geofences are forgotten when the phone restarts or the app updates; set them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { if (Places.enabled(c)) Places.arm(c) }
}

/** "Say it": opens the phone's voice input, then writes what you said. */
class VoiceAnswer : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val kind = intent.getStringExtra(Places.EXTRA_KIND) ?: return finish()
        val prompt = if (kind == Places.ASK_IN) "At work? Say yes, or the time you came in" else "Done with work? Say yes, or the time you finished"
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt), 1)
        } catch (e: Exception) {
            Places.confirm(this, "No voice input on this phone. Use Type instead.")
            finish()
        }
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (res == RESULT_OK && said != null)
            Places.record(this, intent.getStringExtra(Places.EXTRA_KIND)!!, intent.getIntExtra(Places.EXTRA_AT, Work.nowMin()), said)
        finish()
    }
}
