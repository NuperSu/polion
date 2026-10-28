package io.polion.android

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import io.polion.core.bitcoin.EsploraTransport
import io.polion.ui.SyncPhase
import io.polion.ui.SyncStatus
import kotlinx.coroutines.flow.update

/** User-started synchronization keeps its own database connection while activities are stopped. */
class BitcoinRefreshService : Service() {
    private var running = false
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (running) return START_REDELIVER_INTENT
        running = true
        stopping = false
        wakeLock =
            getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Polion:sync").apply {
                acquire(60 * 60 * 1000L)
            }
        val notification = SyncNotification.create(this, "Fetching public history…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(SyncNotification.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(SyncNotification.ID, notification)
        }
        RefreshWorker.syncStatus.value = SyncStatus(SyncPhase.SYNCING, "Fetching public history…")
        RefreshWorker.executor.execute {
            var lastNotification = 0L
            val status =
                RefreshWorker.performRefresh(applicationContext, { transport ->
                    EsploraTransport { path ->
                        check(!stopping) { "Sync stopped" }
                        transport.get(path)
                    }
                }) { detail ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastNotification >= 1000) {
                        SyncNotification.update(this, detail)
                        lastNotification = now
                    }
                }
            RefreshWorker.syncStatus.value = status
            RefreshWorker.dataRevision.update { it + 1 }
            Handler(Looper.getMainLooper()).post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        stopping = true
        RefreshWorker.syncStatus.value =
            SyncStatus(SyncPhase.ERROR, "Android's background time limit was reached. Saved data is unchanged; retry.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
}
