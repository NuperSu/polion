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
        ): SyncStatus {
            // The stage and exception class name the failing step without revealing addresses or provider payloads.
            var stage = "setup"
            return try {
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
                                io.polion.core.bitcoin.FailoverEsploraTransport(
                                    io.polion.core.bitcoin.esploraEndpoints(endpoint).map { url ->
                                        io.polion.core.bitcoin.EsploraEndpoint(
                                            url,
                                            BlockstreamTransport(
                                                url,
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
                                    },
                                ),
                            )
                        stage = if (walletId != null) "discovery" else "history"
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
                        stage = "history"
                        // Sources refresh through the connector registry; Bitcoin discovers pending wallets before history.
                        val bitcoin =
                            io.polion.core.application.BitcoinConnector(transport, endpoint) { progress ->
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
                        val outcomes =
                            app.refreshSources(
                                io.polion.core.application.SourceRegistry(listOf(bitcoin)),
                                io.polion.core.application.SourceRefreshOptions(recoverHistory = recoverHistory),
                            )
                        val states = outcomes.flatMap { it.jobs }
                        val sourceNotes = outcomes.flatMap { it.notes }.joinToString(" ").ifEmpty { null }
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
                                        when {
                                            recoverHistory -> "History recovery complete; coverage remains unverified"
                                            app.lastRefreshSkipped() -> "Checked for new activity; none found."
                                            else -> "Sync complete; coverage remains unverified"
                                        }
                                    ) + (listOfNotNull(discoveryNotice, sourceNotes).joinToString("") { " $it" }),
                                )
                        }
                    }
                }
            } catch (failure: Exception) {
                SyncStatus(
                    SyncPhase.ERROR,
                    (failure as? BitcoinProviderException)?.reason?.userMessage
                        ?: (failure as? io.polion.core.application.BitcoinDiscoveryConflict)?.message
                        ?: "Could not finish sync during $stage (${failure::class.java.simpleName}). Saved data is unchanged; retry.",
                )
            }
        }
    }
}
