package com.davacada.gasto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Reads receipts and invoices from Gmail (read-only), including Google Play receipts,
 * and turns them into payments. Uses Google sign-in on the phone; nothing is sent anywhere else.
 */
object Gmail {
    const val REQ = 4207
    private const val SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    fun connected(ctx: Context) = Prefs.bool(ctx, "gmailOn", false)
    fun lastError(ctx: Context) = Prefs.str(ctx, "gmailError")

    /** Ask for Gmail access (shows Google's own screen the first time). */
    fun connect(act: Activity, done: (String?) -> Unit) {
        Identity.getAuthorizationClient(act).authorize(request())
            .addOnSuccessListener { r ->
                if (r.hasResolution()) {
                    runCatching { act.startIntentSenderForResult(r.pendingIntent!!.intentSender, REQ, null, 0, 0, 0, null) }
                        .onFailure { done(it.message) }
                } else {
                    Prefs.setBool(act, "gmailOn", true); Prefs.setStr(act, "gmailError", null)
                    syncAsync(act, r.accessToken, done)
                }
            }
            .addOnFailureListener { e -> val m = explain(e); Prefs.setStr(act, "gmailError", m); done(m) }
    }

    fun onResult(act: Activity, data: Intent?, done: (String?) -> Unit) {
        try {
            val r: AuthorizationResult = Identity.getAuthorizationClient(act).getAuthorizationResultFromIntent(data)
            Prefs.setBool(act, "gmailOn", true); Prefs.setStr(act, "gmailError", null)
            syncAsync(act, r.accessToken, done)
        } catch (e: Exception) {
            val m = explain(e); Prefs.setStr(act, "gmailError", m); done(m)
        }
    }

    fun disconnect(ctx: Context) { Prefs.setBool(ctx, "gmailOn", false) }

    private fun explain(e: Exception): String {
        val code = (e as? ApiException)?.statusCode
        return when (code) {
            10 -> "Google hasn't been told about Gasto yet (one-time setup in Google Cloud, see Settings)."
            12501, 16 -> "Cancelled."
            7 -> "No internet."
            else -> e.message ?: "Couldn't connect to Gmail."
        }
    }

    private fun syncAsync(ctx: Context, token: String?, done: (String?) -> Unit) {
        Thread {
            val err = runCatching { if (token != null) sync(ctx, token) else "No access" }.getOrElse { it.message }
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(err) }
        }.start()
    }

    /** From the daily background check: only works if access was already given. */
    fun syncInBackground(ctx: Context) {
        if (!connected(ctx)) return
        runCatching {
            val r = Tasks.await(Identity.getAuthorizationClient(ctx).authorize(request()), 30, TimeUnit.SECONDS)
            if (!r.hasResolution() && r.accessToken != null) sync(ctx, r.accessToken!!)
        }
    }

    // MARK: reading mail

    private fun get(token: String, url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer $token")
        c.connectTimeout = 15000; c.readTimeout = 20000
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) throw RuntimeException("Gmail answered $code")
        return JSONObject(body)
    }

    /** Returns null when fine, or an error message. */
    fun sync(ctx: Context, token: String): String? {
        Store.init(ctx)
        val since = Prefs.long(ctx, "gmailSince").takeIf { it > 0 }
        val days = if (since == null) 62 else Math.max(2, ((System.currentTimeMillis() - since) / 86_400_000L + 2).toInt())
        val q = "newer_than:${days}d -category:promotions -category:social " +
            "(receipt OR invoice OR \"order\" OR \"your purchase\" OR \"payment\" OR subscription OR renewal OR \"Google Play\" " +
            "OR račun OR potrdilo OR naročnina OR plačilo OR faktura OR quittung OR rechnung)"
        var pageToken: String? = null
        var added = 0
        var pages = 0
        do {
            val url = "https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults=100&q=" + URLEncoder.encode(q, "UTF-8") +
                (pageToken?.let { "&pageToken=$it" } ?: "")
            val list = get(token, url)
            val msgs = list.optJSONArray("messages")
            if (msgs != null) for (i in 0 until msgs.length()) {
                val id = msgs.getJSONObject(i).getString("id")
                if (Store.all().any { it.id == "g:$id" } || Prefs.bool(ctx, "skip:$id", false)) continue
                val m = runCatching { get(token, "https://gmail.googleapis.com/gmail/v1/users/me/messages/$id?format=full") }.getOrNull() ?: continue
                val p = toPayment(id, m)
                if (p == null) { Prefs.setBool(ctx, "skip:$id", true); continue }
                if (Store.add(ctx, p)) added++
            }
            pageToken = list.optString("nextPageToken").ifEmpty { null }
            pages++
        } while (pageToken != null && pages < 4)
        Prefs.setLong(ctx, "gmailSince", System.currentTimeMillis())
        Prefs.setLong(ctx, "gmailSynced", System.currentTimeMillis())
        Prefs.setStr(ctx, "gmailAdded", added.toString())
        if (added > 0) Alerts.checkBudget(ctx)
        return null
    }

    private fun header(m: JSONObject, name: String): String {
        val hs = m.optJSONObject("payload")?.optJSONArray("headers") ?: return ""
        for (i in 0 until hs.length()) { val h = hs.getJSONObject(i); if (h.optString("name").equals(name, true)) return h.optString("value") }
        return ""
    }

    private fun bodyText(part: JSONObject?, out: StringBuilder) {
        if (part == null) return
        val mime = part.optString("mimeType")
        val data = part.optJSONObject("body")?.optString("data").orEmpty()
        if (data.isNotEmpty() && (mime == "text/plain" || mime == "text/html")) {
            val raw = runCatching { String(Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)) }.getOrDefault("")
            out.append(if (mime == "text/html") stripHtml(raw) else raw).append('\n')
        }
        val parts = part.optJSONArray("parts") ?: return
        // prefer plain text if there is one
        val plain = (0 until parts.length()).map { parts.getJSONObject(it) }.firstOrNull { it.optString("mimeType") == "text/plain" }
        if (plain != null && mime == "multipart/alternative") { bodyText(plain, out); return }
        for (i in 0 until parts.length()) bodyText(parts.getJSONObject(i), out)
    }

    private fun stripHtml(h: String) = h.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</tr>|</div>"), "\n").replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&euro;", "€").replace("&amp;", "&").replace("&#8364;", "€")
        .replace(Regex("[ \\t]{2,}"), " ")

    private val totalWords = Regex("""(?i)(grand total|order total|total|amount paid|amount charged|you paid|charged|skupaj|za plačilo|znesek|plačano|price|cena|gesamt)""")

    private fun toPayment(id: String, m: JSONObject): Payment? {
        val from = header(m, "From")
        val subject = header(m, "Subject")
        val sb = StringBuilder(); bodyText(m.optJSONObject("payload"), sb)
        val body = sb.toString()
        val text = "$subject\n$body"
        val receiptish = Regex("""(?i)receipt|invoice|order|purchase|payment|paid|subscription|renew|račun|potrdilo|naročnin|plačil|faktur|rechnung|quittung|charged""")
        if (!receiptish.containsMatchIn(subject) && !receiptish.containsMatchIn(body.take(1500))) return null
        // the amount: next to "total"/"skupaj" if there is one, else the first one
        val amounts = Parse.amounts(text)
        if (amounts.isEmpty()) return null
        var amount: Double? = null
        for (t in totalWords.findAll(text)) {
            val after = amounts.firstOrNull { it.first > t.range.first && it.first - t.range.last < 80 }
            if (after != null) amount = Math.abs(after.second)
        }
        if (amount == null) amount = Math.abs(amounts.first().second)
        if (amount <= 0.0 || amount > 20_000) return null

        val senderName = from.substringBefore("<").replace("\"", "").trim().ifEmpty { from.substringAfter("@").substringBefore(".") }
        val isPlay = from.contains("googleplay", true) || subject.contains("Google Play", true)
        var merchant = senderName
        if (isPlay) {
            // "Your Google Play Order Receipt from …" — the item is in the body, before its price
            val item = Regex("""(?m)^\s*([^\n€]{3,60}?)\s*(?:\((?:[^)]*)\))?\s*\n?.*?€\s?\d""").find(body)?.groupValues?.get(1)?.trim()
            merchant = item?.takeIf { it.length in 3..40 && !it.contains("total", true) } ?: "Google Play"
        }
        merchant = Parse.tidy(merchant.replace(Regex("(?i)\\b(no-?reply|noreply|info|billing|receipts?|team)\\b"), "").trim().ifEmpty { senderName })
        val dateMs = m.optString("internalDate").toLongOrNull() ?: runCatching {
            SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH).parse(header(m, "Date"))!!.time
        }.getOrDefault(System.currentTimeMillis())
        val sub = Parse.isSub(text, "$senderName $merchant") || (isPlay && Regex("(?i)subscription|naročnin").containsMatchIn(text))
        return Payment(
            id = "g:$id", amount = amount, merchant = merchant, time = dateMs,
            source = if (isPlay) "play" else "gmail",
            kind = if (sub) "sub" else "daily",
            category = Parse.category("$merchant $subject"),
            app = "Gmail", note = subject.take(120),
        )
    }
}
