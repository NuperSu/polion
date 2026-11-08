package io.polion.android

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import io.polion.core.bitcoin.BitcoinProviderException
import io.polion.core.bitcoin.BitcoinProviderFailure
import io.polion.ui.SyncOperation
import io.polion.ui.SyncPhase
import io.polion.ui.SyncStatus
import kotlinx.coroutines.flow.update

/** User-started synchronization keeps its own database connection while activities are stopped. */
open class BitcoinRefreshService : Service() {
    private var running = false
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var stopping = false

    protected open val workExecutor: java.util.concurrent.Executor get() = RefreshWorker.syncExecutor

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val queued = running
        running = true
        if (wakeLock == null) {
            wakeLock =
                getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Polion:sync").apply {
                    acquire(60 * 60 * 1000L)
                }
        }
        val walletId = intent?.getStringExtra("wallet_id")
        val verifyOrder = intent?.getBooleanExtra("verify_order", false) == true
        val recoverHistory = intent?.getBooleanExtra("recover_history", false) == true
        val operation =
            when {
                verifyOrder -> SyncOperation.ORDER
                walletId != null -> SyncOperation.DISCOVERY
                else -> SyncOperation.HISTORY
            }
        val initialDetail =
            if (operation == SyncOperation.ORDER) {
                "Verifying Bitcoin order…"
            } else if (operation == SyncOperation.DISCOVERY) {
                "Discovering wallet addresses…"
            } else if (recoverHistory) {
                "Recovering full Bitcoin history…"
            } else {
                "Fetching public history…"
            }
        val notification = SyncNotification.create(this, if (queued) "Bitcoin request queued behind current work…" else initialDetail)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(SyncNotification.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(SyncNotification.ID, notification)
        }
        if (!queued) RefreshWorker.syncStatus.value = SyncStatus(SyncPhase.SYNCING, initialDetail, operation)
        workExecutor.execute {
            if (stopping) return@execute
            RefreshWorker.syncStatus.value = SyncStatus(SyncPhase.SYNCING, initialDetail, operation)
            var lastNotification = 0L
            val status =
                runRefresh(walletId, verifyOrder, recoverHistory) { detail ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastNotification >= 1000) {
                        SyncNotification.update(this, detail)
                        lastNotification = now
                    }
                }
            if (!stopping) {
                RefreshWorker.syncStatus.value = status.copy(operation = operation)
                RefreshWorker.dataRevision.update { it + 1 }
            }
            Handler(Looper.getMainLooper()).post {
                if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
        return START_REDELIVER_INTENT
    }

    protected open fun runRefresh(
        walletId: String?,
        verifyOrder: Boolean,
        recoverHistory: Boolean,
        onProgress: (String) -> Unit,
    ): SyncStatus =
        RefreshWorker.performRefresh(
            applicationContext,
            walletId = walletId,
            verifyOrder = verifyOrder,
            recoverHistory = recoverHistory,
            ensureActive = {
                if (stopping) throw BitcoinProviderException(BitcoinProviderFailure.CANCELLED)
            },
            onProgress = onProgress,
        )

    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        stopping = true
        RefreshWorker.syncStatus.value =
            RefreshWorker.syncStatus.value.copy(
                phase = SyncPhase.ERROR,
                message = "Android's background time limit was reached. Saved data is unchanged; retry.",
            )
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
