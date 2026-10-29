package io.polion.android

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.polion.core.application.PortfolioApplication
import io.polion.core.bitcoin.BitcoinProviderException
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
        if (syncStatus.value.phase == SyncPhase.SYNCING) return Result.retry()
        setForegroundAsync(SyncNotification.foreground(applicationContext)).get()
        syncStatus.value = SyncStatus(SyncPhase.SYNCING, "Fetching public history…")
        val status = executor.submit<SyncStatus> { performRefresh(applicationContext) }.get()
        syncStatus.value = status
        dataRevision.update { it + 1 }
        return if (status.phase == SyncPhase.ERROR) Result.retry() else Result.success()
    }

    companion object {
        val executor = Executors.newSingleThreadExecutor()
        val dataRevision = MutableStateFlow(0L)
        val syncStatus = MutableStateFlow(SyncStatus())

        fun performRefresh(
            context: Context,
            wrap: (io.polion.core.bitcoin.EsploraTransport) -> io.polion.core.bitcoin.EsploraTransport = { it },
            walletId: String? = null,
            verifyOrder: Boolean = false,
            recoverHistory: Boolean = false,
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
                        val transport = wrap(BlockstreamTransport(app.sourceEndpoint()))
                        if (walletId != null) {
                            app.discoverBitcoinWallet(
                                io.polion.core.model
                                    .TrackingTargetId(walletId),
                                transport,
                            )
                        }
                        if (verifyOrder) {
                            app.verifyAccountingOrder(transport) { progress ->
                                val detail = "Verifying blocks ${progress.blocksResolved}/${progress.totalBlocks}"
                                syncStatus.value = SyncStatus(SyncPhase.SYNCING, detail)
                                onProgress(detail)
                            }
                            return SyncStatus(
                                SyncPhase.SUCCESS,
                                "Bitcoin order verified. Mixed-source chronology may still require review.",
                            )
                        }
                        val states =
                            app.refreshBitcoin(transport, recoverHistory = recoverHistory) { progress ->
                                val detail =
                                    when (progress.phase) {
                                        "blocks" -> "Checking blocks ${progress.blocksResolved}/${progress.totalBlocks}"
                                        "verify" -> "Checking chain consistency…"
                                        "apply" -> "Applying verified history…"
                                        else ->
                                            "Address ${progress.addressIndex}/${progress.totalAddresses} · " +
                                                "${progress.pages} pages · ${progress.transactions} transactions"
                                    }
                                syncStatus.value = SyncStatus(SyncPhase.SYNCING, detail)
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
                                    if (recoverHistory) "History recovery complete; coverage remains unverified" else "Sync complete; coverage remains unverified",
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
