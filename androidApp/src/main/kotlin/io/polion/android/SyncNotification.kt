package io.polion.android

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo

internal object SyncNotification {
    const val ID = 41
    private const val CHANNEL = "portfolio-sync"

    fun create(
        context: Context,
        detail: String,
    ): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Portfolio sync", NotificationManager.IMPORTANCE_LOW))
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification
            .Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_polion)
            .setContentTitle("Polion is syncing")
            .setContentText(detail)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .build()
    }

    fun foreground(context: Context): ForegroundInfo =
        if (Build.VERSION.SDK_INT >=
            29
        ) {
            ForegroundInfo(ID, create(context, "Fetching public history…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(ID, create(context, "Fetching public history…"))
        }

    @SuppressLint("MissingPermission")
    fun update(
        context: Context,
        detail: String,
    ) {
        context.getSystemService(NotificationManager::class.java).notify(ID, create(context, detail))
    }
}
