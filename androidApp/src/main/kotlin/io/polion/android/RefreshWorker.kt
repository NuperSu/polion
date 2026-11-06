package io.polion.android

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.polion.core.application.PortfolioApplication
import io.polion.core.bitcoin.BitcoinProviderException
import io.polion.core.bitcoin.BitcoinProviderFailure
import io.polion.core.storage.AndroidSqliteDatabase
import io.polion.core.sync.JobState
import io.polion.ui.SyncPhase
import io.polion.ui.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

/** Uses the same serial queue as the foreground host for database workflows. */
class RefreshWorker(
    context: Context,
    parameters: WorkerParameters,
) : Worker(context, parameters) {
    override fun doWork(): Result {
        val automaticEnabled =
            AndroidSqliteDatabase(applicationContext.getDatabasePath("portfolio.sqlite").path).use { db ->
                PortfolioApplication(db, System::currentTimeMillis).automaticRefreshPreference() != false
            }
        if (!automaticEnabled) return Result.success()
        if (syncStatus.value.phase == SyncPhase.SYNCING) return Result.retry()
        setForegroundAsync(SyncNotification.foreground(applicationContext)).get()
        syncStatus.value = SyncStatus(SyncPhase.SYNCING, "Fetching public history…")
        val status =
            syncExecutor
                .submit<SyncStatus> {
                    performRefresh(applicationContext, ensureActive = {
                        if (isStopped) throw BitcoinProviderException(BitcoinProviderFailure.CANCELLED)
                    })
                }.get()
        syncStatus.value = status
        dataRevision.update { it + 1 }
        return if (status.phase == SyncPhase.ERROR) Result.retry() else Result.success()
    }

    companion object {
        /** UI and database workflows; a long Bitcoin scan must never occupy this thread. */
        val executor = Executors.newSingleThreadExecutor()

        /** All Bitcoin sync work (foreground service and periodic worker) runs here, one scan at a time. */
        val syncExecutor = Executors.newSingleThreadExecutor()
        val dataRevision = MutableStateFlow(0L)
        val syncStatus = MutableStateFlow(SyncStatus())

        fun performRefresh(
            context: Context,
            wrap: (io.polion.core.bitcoin.EsploraTransport) -> io.polion.core.bitcoin.EsploraTransport = { it },
            walletId: String? = null,
            verifyOrder: Boolean = false,
            recoverHistory: Boolean = false,
            ensureActive: () -> Unit = {},
            onProgress: (String) -> Unit = {},
        ): SyncStatus =
            try {
                val file = context.getDatabasePath("portfolio.sqlite")
                file.parentFile?.mkdirs()
                AndroidSqliteDatabase(file.path).use { db ->
                    val app = PortfolioApplication(db, System::currentTimeMillis)
                    if (!app.sourceConsent()) {
                        SyncStatus(SyncPhase.IDLE, "Allow a provider before syncing")
                    } else {
                        val endpoint = app.sourceEndpoint()
                        val transport =
                            wrap(
                                BlockstreamTransport(
                                    endpoint,
                                    onProgress = { detail ->
                                        syncStatus.value = syncStatus.value.copy(phase = SyncPhase.SYNCING, message = detail)
                                        onProgress(detail)
                                    },
                                    ensureActive = {
                                        ensureActive()
                                        if (!app.sourceConsent() || app.sourceEndpoint() != endpoint) {
                                            throw BitcoinProviderException(BitcoinProviderFailure.CANCELLED)
                                        }
                                    },
                                ),
                            )
                        val discoveryNotice =
                            if (walletId != null) {
                                app
                                    .discoverBitcoinWallet(
                                        io.polion.core.model
                                            .TrackingTargetId(walletId),
                                        transport,
                                        providerEndpoint = endpoint,
                                    ).notice
                            } else {
                                null
                            }
                        if (verifyOrder) {
                            app.verifyAccountingOrder(transport) { progress ->
                                val detail = "Verifying blocks ${progress.blocksResolved}/${progress.totalBlocks}"
                                syncStatus.value = syncStatus.value.copy(phase = SyncPhase.SYNCING, message = detail)
                                onProgress(detail)
                            }
                            return SyncStatus(
                                SyncPhase.SUCCESS,
                                "Bitcoin order verified. Mixed-source chronology may still require review.",
                            )
                        }
                        val states =
                            app.refreshBitcoin(transport, recoverHistory = recoverHistory, providerEndpoint = endpoint) { progress ->
                                val detail =
                                    when (progress.phase) {
                                        "blocks" -> "Checking blocks ${progress.blocksResolved}/${progress.totalBlocks}"
                                        "verify" -> "Checking chain consistency…"
                                        "apply" -> "Applying verified history…"
                                        else ->
                                            "Address ${progress.addressIndex}/${progress.totalAddresses} · " +
                                                "${progress.pages} pages · ${progress.transactions} transactions"
                                    }
                                syncStatus.value = syncStatus.value.copy(phase = SyncPhase.SYNCING, message = detail)
                                onProgress(detail)
                            }
                        when {
                            states.isEmpty() -> SyncStatus(SyncPhase.IDLE, "Add a public address to sync")
                            states.any { it != JobState.COMPLETED } ->
                                SyncStatus(
                                    SyncPhase.ERROR,
                                    app.sourceFailureMessage() ?: "History is incomplete; retry.",
                                )
                            else ->
                                SyncStatus(
                                    SyncPhase.SUCCESS,
                                    (
                                        if (recoverHistory) {
                                            "History recovery complete; coverage remains unverified"
                                        } else {
                                            "Sync complete; coverage remains unverified"
                                        }
                                    ) + (discoveryNotice?.let { " $it" } ?: ""),
                                )
                        }
                    }
                }
            } catch (failure: Exception) {
                SyncStatus(
                    SyncPhase.ERROR,
                    (failure as? BitcoinProviderException)?.reason?.userMessage
                        ?: "Could not finish sync. Check storage and provider, then retry.",
                )
            }
    }
}
