package io.polion.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.polion.core.alerts.NotificationMessage
import io.polion.core.application.PortfolioApplication
import io.polion.core.storage.AndroidSqliteDatabase
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Periodic price alert and notification check. It is scheduled only while an alert or notification setting is on, and it
 * honours the same pause gate as the price workers, so it never reads the database while a restore replaces it.
 */
class NotificationWorker(
    context: Context,
    parameters: WorkerParameters,
) : Worker(context, parameters) {
    override fun doWork(): Result =
        try {
            synchronized(AutomaticPriceGate.lock) {
                if (AutomaticPriceGate.paused) {
                    Result.retry()
                } else {
                    PriceAlertNotifications.post(applicationContext, checkPriceAlerts(applicationContext))
                    RefreshWorker.dataRevision.update { it + 1 }
                    Result.success()
                }
            }
        } catch (_: Exception) {
            Result.retry()
        }

    companion object {
        const val WORK_NAME = "polion-notifications"

        /**
         * Enqueues a check every 15 minutes while it is [active] and cancels it otherwise. Policy KEEP leaves an existing
         * schedule alone, so reopening the app never pushes the next check back; the request itself is fixed.
         */
        fun schedule(
            context: Context,
            active: Boolean,
        ) {
            val manager = WorkManager.getInstance(context)
            if (active) {
                val request =
                    PeriodicWorkRequestBuilder<NotificationWorker>(15, TimeUnit.MINUTES)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .build()
                manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            } else {
                manager.cancelUniqueWork(WORK_NAME)
            }
        }
    }
}

/**
 * One check on its own database connection, using the market refresh the core runs when the list is older than ten
 * minutes. Callers hold [AutomaticPriceGate.lock] and have checked that no restore is in progress.
 */
internal fun checkPriceAlerts(context: Context): List<NotificationMessage> =
    AndroidSqliteDatabase(context.applicationContext.getDatabasePath("portfolio.sqlite").path).use { db ->
        PortfolioApplication(db, System::currentTimeMillis).runNotificationCheck(
            MarketFeedRefresher.transport::feed,
            LocalDate.now().toString(),
            LocalTime.now().hour,
        )
    }

/** Posts price alerts and updates; shared by [NotificationWorker] and the activity's on-demand checks. */
internal object PriceAlertNotifications {
    private const val CHANNEL = "price_alerts"

    /**
     * Posts each message under its key, so a repeated key replaces its notification, and returns how many were posted.
     * Nothing is posted without the notification permission or while notifications are turned off for the app.
     */
    @SuppressLint("MissingPermission")
    fun post(
        context: Context,
        messages: List<NotificationMessage>,
    ): Int {
        if (messages.isEmpty() || !canPost(context)) return 0
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Price alerts and updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        // The lock screen shows this version, so amounts and coin names stay out of it.
        val hidden =
            Notification
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_polion)
                .setContentTitle("Polion")
                .setContentText("New notification")
                .build()
        messages.forEach { message ->
            val notification =
                Notification
                    .Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_polion)
                    .setContentTitle(message.title)
                    .setContentText(message.body)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setPublicVersion(hidden)
                    .build()
            manager.notify(message.key.hashCode(), notification)
        }
        return messages.size
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }
}
