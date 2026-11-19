package com.davacada.gasto

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads payment notifications from bank apps and Google Wallet as they arrive.
 * Only apps she has switched on (or that are clearly banks/wallets) count; nothing leaves the phone.
 */
class PayListener : NotificationListenerService() {

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName || pkg in ignore) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val e = n.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val text = "$title. $body"
        handle(this, pkg, title, text, sbn.postTime)
    }

    companion object {
        @Volatile var instance: PayListener? = null

        /** Look at the notifications still in the shade (for the Scan button). Returns how many were new. */
        fun scanActive(ctx: Context): Int {
            val l = instance ?: return -1
            val before = Store.all().size
            runCatching {
                for (sbn in l.activeNotifications) {
                    val pkg = sbn.packageName ?: continue
                    if (pkg == ctx.packageName || pkg in ignore) continue
                    val e = sbn.notification?.extras ?: continue
                    val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                    val body = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
                    handle(ctx, pkg, title, "$title. $body", sbn.postTime)
                }
            }
            return Store.all().size - before
        }

        private const val GMAIL = "com.google.android.gm"
        private const val WALLET = "com.google.android.apps.walletnfcrel"
        private const val GMS = "com.google.android.gms"
        private val googlePayWords = Regex("""(?i)google pay|wallet|visa|mastercard|maestro|amex|debit|credit|card|kartic|•|\*{2,}\d{4}|contactless""")
        private val receiptish = Regex("""(?i)receipt|invoice|order|purchase|payment|paid|subscription|renew|račun|potrdilo|naročnin|plačil|faktur|charged""")
        private val ignore = setOf(
            "org.telegram.messenger", "com.whatsapp", "com.facebook.orca", "com.instagram.android",
            "com.android.systemui", "android", "com.google.android.youtube",
        )
        private val bankish = Regex("""bank|banka|nlb|revolut|wallet|walletnfcrel|paypal|n26|wise|transferwise|skb|unicredit|addiko|otp|nkbm|intesa|sparkasse|gbkr|dbs|delavska|hal\.?e|flik|bunq|curve|monese|keks|valu""", RegexOption.IGNORE_CASE)

        @Suppress("DEPRECATION")
        fun appName(ctx: Context, pkg: String): String = runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))

        fun handle(ctx: Context, pkg: String, title: String, text: String, time: Long) {
            Store.init(ctx)
            if (pkg == GMAIL) { gmailNotification(ctx, title, text, time); return }
            val amounts = Parse.amounts(text)
            if (amounts.isEmpty()) return
            // Google Pay / Wallet tap-to-pay notices are short ("Lidl · €23.84 with Visa ••1234"), with no word like "paid".
            // They come from the Wallet app or from Google Play services.
            val googlePay = pkg == WALLET || (pkg == GMS && googlePayWords.containsMatchIn(text))
            if (googlePay) {
                if (Parse.isIncoming(text)) return
                val apps = Prefs.apps(ctx)
                if (apps.add(pkg)) Prefs.setApps(ctx, apps)
                val seen = Prefs.seen(ctx); if (seen.add(pkg)) Prefs.setSeen(ctx, seen)
            } else if (!Parse.isSpend(text)) return

            // Remember every app that mentions a payment; banks and wallets count straight away.
            val seen = Prefs.seen(ctx)
            if (seen.add(pkg)) {
                Prefs.setSeen(ctx, seen)
                if (bankish.containsMatchIn(pkg) || bankish.containsMatchIn(appName(ctx, pkg))) {
                    Prefs.setApps(ctx, Prefs.apps(ctx).apply { add(pkg) })
                }
            }
            if (pkg !in Prefs.apps(ctx)) return

            val amount = Math.abs(amounts.first().second)
            val appLabel = appName(ctx, pkg)
            val app = if (googlePay) "Google Pay" else appLabel
            val fallback = if (title.isNotBlank() && !title.equals(app, true) && !title.contains("Google", true) && Parse.amounts(title).isEmpty() && title.length < 30) title
                else Regex("""^\s*([A-Z0-9ČŠŽ][^€\d•]{1,30}?)\s*[·,:-]""").find(body(text))?.groupValues?.get(1)?.trim() ?: app
            val merchant = Parse.merchant(text, fallback)
            val isWallet = googlePay || pkg.contains("wallet")
            val p = Payment(
                id = "n:$pkg:$time:$amount",
                amount = amount,
                merchant = merchant,
                time = time,
                source = if (isWallet) "wallet" else "bank",
                kind = if (Parse.isSub(text, merchant)) "sub" else "daily",
                category = Parse.category("$merchant $text"),
                app = app,
                note = text.take(200),
            )
            if (Store.add(ctx, p)) Alerts.checkBudget(ctx)
        }
    
        /** The text after the title (we join them as "title. body"). */
        private fun body(text: String) = text.substringAfter(". ", text)

        /** A new email shown by the Gmail app: title is the sender, text the subject and preview. */
        private fun gmailNotification(ctx: Context, sender: String, text: String, time: Long) {
            if (!Prefs.bool(ctx, "gmailNotes", true) || !receiptish.containsMatchIn(text)) return
            val amounts = Parse.amounts(text)
            if (amounts.isEmpty()) return
            val isPlay = sender.contains("Google Play", true) || text.contains("Google Play", true)
            val merchant = Parse.tidy(if (isPlay) "Google Play" else sender.substringBefore("<").ifBlank { "Email receipt" })
            val p = Payment(
                id = "gn:${text.hashCode()}:${amounts.first().second}",
                amount = Math.abs(amounts.first().second), merchant = merchant, time = time,
                source = if (isPlay) "play" else "gmail",
                kind = if (Parse.isSub(text, merchant)) "sub" else "daily",
                category = Parse.category("$merchant $text"), app = "Gmail", note = text.take(200),
            )
            if (Store.add(ctx, p)) Alerts.checkBudget(ctx)
        }
    }
}
