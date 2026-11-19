package com.davacada.gasto

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.Notification
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

object Alerts {
    private const val CH = "gasto"

    private fun post(ctx: Context, id: Int, title: String, body: String) {
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Budget", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, GastoActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(id, Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(C.G1)
            .setContentTitle(title).setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open).setAutoCancel(true).build())
    }

    /** Once per month, when spending passes 80% of the base. */
    fun checkBudget(ctx: Context) {
        if (!Prefs.bool(ctx, "alert80", true)) return
        val base = Prefs.base(ctx)
        val spent = Period.payments(ctx).sumOf { it.amount }
        val key = "warned:${Period.start(ctx)}"
        if (spent >= base * 0.8 && !Prefs.bool(ctx, key, false)) {
            Prefs.setBool(ctx, key, true)
            post(ctx, 80, "You've used 80% of €${base.toInt()}",
                "${eur(base - spent)} left for ${Period.daysLeft(ctx)} days, about ${eur((base - spent) / Period.daysLeft(ctx))} a day.")
        }
    }

    /** Two days before a subscription renews. */
    fun checkRenewals(ctx: Context) {
        if (!Prefs.bool(ctx, "alertRenew", true)) return
        val now = System.currentTimeMillis()
        for (s in Subs.list()) {
            val inDays = (s.next - now) / 86_400_000.0
            val key = "renew:${s.merchant}:${s.next / 86_400_000L}"
            if (inDays in 0.0..2.2 && !Prefs.bool(ctx, key, false)) {
                Prefs.setBool(ctx, key, true)
                post(ctx, 1000 + (s.merchant.hashCode() and 0xFFF), "${s.merchant} renews soon",
                    "${eur(s.amount)} in ${if (inDays < 1) "a day" else "${Math.round(inDays)} days"}. Cancel it now if you don't use it.")
            }
        }
    }

    fun schedule(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("daily", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DailyWork>(12, TimeUnit.HOURS).build())
    }
}

class DailyWork(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Store.init(applicationContext)
        Gmail.syncInBackground(applicationContext)
        Alerts.checkBudget(applicationContext)
        Alerts.checkRenewals(applicationContext)
        return Result.success()
    }
}
