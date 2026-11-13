package io.valroot.android

import io.valroot.core.application.HostRefresh
import io.valroot.core.application.RefreshSummary
import io.valroot.core.bitcoin.BlockstreamTransport
import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.bitcoin.BitcoinProviderException
import io.valroot.core.bitcoin.BitcoinProviderFailure
import io.valroot.core.storage.AndroidSqliteDatabase
import io.valroot.ui.SyncPhase
import io.valroot.ui.SyncStatus
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
                PortfolioApplication(db, System::currentTimeMillis).settings.automaticRefresh() != false
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
            wrap: (io.valroot.core.bitcoin.EsploraTransport) -> io.valroot.core.bitcoin.EsploraTransport = { it },
            walletId: String? = null,
            verifyOrder: Boolean = false,
            recoverHistory: Boolean = false,
            ensureActive: () -> Unit = {},
            onProgress: (String) -> Unit = {},
            withoutBitcoin: Boolean = false,
        ): SyncStatus {
            // The stage and exception class name the failing step without revealing addresses or provider payloads.
            var stage = "setup"
            return try {
                val file = context.getDatabasePath("portfolio.sqlite")
                file.parentFile?.mkdirs()
                AndroidSqliteDatabase(file.path).use { db ->
                    val app = PortfolioApplication(db, System::currentTimeMillis)
                    // Each source needs its own consent. Wallet discovery and order verification are Bitcoin-only.
                    val plan = HostRefresh(app, withoutBitcoin) { ensureActive() }
                    if (!plan.bitcoinOn && (!plan.anySource || walletId != null || verifyOrder)) {
                        SyncStatus(SyncPhase.IDLE, HostRefresh.NO_PROVIDER)
                    } else {
                        val endpoint = app.sources.endpoint()
                        val transport =
                            wrap(
                                io.valroot.core.bitcoin.FailoverEsploraTransport(
                                    io.valroot.core.bitcoin.esploraEndpoints(endpoint).map { url ->
                                        io.valroot.core.bitcoin.EsploraEndpoint(
                                            url,
                                            BlockstreamTransport(
                                                url,
                                                onProgress = { detail ->
                                                    syncStatus.value = syncStatus.value.copy(phase = SyncPhase.SYNCING, message = detail)
                                                    onProgress(detail)
                                                },
                                                ensureActive = {
                                                    ensureActive()
                                                    if (!app.sources.consent() || app.sources.endpoint() != endpoint) {
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
                                    .bitcoinWallets.discover(
                                        io.valroot.core.model
                                            .TrackingTargetId(walletId),
                                        transport,
                                        providerEndpoint = endpoint,
                                    ).notice
                            } else {
                                null
                            }
                        if (verifyOrder) {
                            app.bitcoin.verifyAccountingOrder(transport) { progress ->
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
                        val report = { detail: String ->
                            syncStatus.value = syncStatus.value.copy(phase = SyncPhase.SYNCING, message = detail)
                            onProgress(detail)
                        }
                        val connectors =
                            plan.connectors(
                                bitcoin = { HostRefresh.bitcoinConnector(transport, endpoint, report) },
                                refreshCatalog = { MarketFeedRefresher.refreshCatalog(app) },
                            )
                        val outcomes = plan.run(connectors, recoverHistory, report)
                        // Once a chain has refreshed, new tokens are linked to coins and priced.
                        if (plan.catalogChanged(outcomes)) MarketFeedRefresher.refreshEvmCatalogAndPrices(app)
                        val summary = plan.summary(outcomes, recoverHistory, listOfNotNull(discoveryNotice))
                        SyncStatus(
                            when (summary.state) {
                                RefreshSummary.State.IDLE -> SyncPhase.IDLE
                                RefreshSummary.State.SUCCESS -> SyncPhase.SUCCESS
                                RefreshSummary.State.ERROR -> SyncPhase.ERROR
                            },
                            summary.message,
                        )
                    }
                }
            } catch (failure: Exception) {
                SyncStatus(
                    SyncPhase.ERROR,
                    (failure as? BitcoinProviderException)?.reason?.userMessage
                        ?: (failure as? io.valroot.core.application.BitcoinDiscoveryConflict)?.message
                        ?: "Could not finish sync during $stage (${failure::class.java.simpleName}). Saved data is unchanged; retry.",
                )
            }
        }
    }
}
