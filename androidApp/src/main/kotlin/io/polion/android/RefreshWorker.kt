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
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Uses the same serial queue as the foreground host for database workflows. */
class RefreshWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        syncStatus.value = SyncStatus(SyncPhase.SYNCING, "Fetching public history…")
        val status = try {
            executor.submit<SyncStatus> {
                val file = applicationContext.getDatabasePath("portfolio.sqlite")
                file.parentFile?.mkdirs()
                AndroidSqliteDatabase(file.path).use { db ->
                    val app = PortfolioApplication(db, System::currentTimeMillis)
                    if (!app.sourceConsent()) {
                        SyncStatus(SyncPhase.IDLE, "Allow a provider before syncing")
                    } else {
                        val states = app.refreshBitcoin(BlockstreamTransport(app.sourceEndpoint())) { progress ->
                        syncStatus.value = SyncStatus(SyncPhase.SYNCING, when (progress.phase) {
                            "blocks" -> "Checking blocks ${progress.blocksResolved}/${progress.totalBlocks}"
                            "verify" -> "Checking chain consistency…"
                            "apply" -> "Applying verified history…"
                            else -> "Address ${progress.addressIndex}/${progress.totalAddresses} · ${progress.pages} pages · ${progress.transactions} transactions"
                        })
                    }
                        when {
                            states.isEmpty() -> SyncStatus(SyncPhase.IDLE, "Add a public address to sync")
                            states.any { it != JobState.COMPLETED } -> SyncStatus(SyncPhase.ERROR, app.sourceFailureMessage() ?: "History is incomplete; retry.")
                            else -> SyncStatus(SyncPhase.SUCCESS, "Sync complete; coverage remains unverified")
                        }
                    }
                }
            }.get()
        } catch (failure: Exception) {
            SyncStatus(SyncPhase.ERROR, (failure as? BitcoinProviderException)?.reason?.userMessage ?: "Could not finish sync. Check storage and provider, then retry.")
        }
        syncStatus.value = status
        dataRevision.update { it + 1 }
        return if (status.phase == SyncPhase.ERROR) Result.retry() else Result.success()
    }

    companion object {
        val executor = Executors.newSingleThreadExecutor()
        val dataRevision = MutableStateFlow(0L)
        val syncStatus = MutableStateFlow(SyncStatus())
    }
}
