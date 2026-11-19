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
        private val ignore = setOf(
            "com.google.android.gm",            // Gmail: read properly through the Gmail sync instead
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
            val amounts = Parse.amounts(text)
            if (amounts.isEmpty() || !Parse.isSpend(text)) return

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
            val app = appName(ctx, pkg)
            val fallback = if (title.isNotBlank() && !title.equals(app, true) && Parse.amounts(title).isEmpty() && title.length < 30) title else app
            val merchant = Parse.merchant(text, fallback)
            val isWallet = pkg.contains("walletnfcrel") || pkg.contains("wallet")
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
    }
}
