package io.valroot.android

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.valroot.core.application.BitcoinConnector
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.SourceConnector
import io.valroot.core.application.SourceRefreshOptions
import io.valroot.core.application.SourceRegistry
import io.valroot.core.bitcoin.BitcoinProviderException
import io.valroot.core.bitcoin.BitcoinProviderFailure
import io.valroot.core.evm.EVM_MANIFEST
import io.valroot.core.exchange.BYBIT_MANIFEST
import io.valroot.core.exchange.BybitConnector
import io.valroot.core.evm.EvmConnector
import io.valroot.core.storage.AndroidSqliteDatabase
import io.valroot.core.sync.JobState
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
                    val bitcoinOn = app.sourceConsent() && !withoutBitcoin
                    val evmOn = app.evmConsent() && app.evmTargets().isNotEmpty()
                    val bybitOn = app.exchangeTargets(io.valroot.core.exchange.ExchangeKind.BYBIT).isNotEmpty()
                    if (!bitcoinOn && (!(evmOn || bybitOn) || walletId != null || verifyOrder)) {
                        SyncStatus(SyncPhase.IDLE, "Allow a provider before syncing")
                    } else {
                        val endpoint = app.sourceEndpoint()
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
                                        io.valroot.core.model
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
                        val connectors = mutableListOf<SourceConnector>()
                        if (bitcoinOn) {
                            connectors +=
                                BitcoinConnector(transport, endpoint) { progress ->
                                    val detail =
                                        "Bitcoin · " +
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
                        }
                        if (evmOn) {
                            val alchemy =
                                AlchemyTransport(
                                    apiKey = { app.alchemyApiKey() },
                                    beforeRequest = {
                                        ensureActive()
                                        check(app.evmConsent()) { "EVM refresh was cancelled. Retry if needed." }
                                    },
                                )
                            val oneInch =
                                app.oneInchApiKey()?.let {
                                    OneInchTransport(
                                        apiKey = { app.oneInchApiKey() },
                                        beforeRequest = {
                                            ensureActive()
                                            check(app.evmConsent()) { "EVM refresh was cancelled. Retry if needed." }
                                        },
                                    )
                                }
                            connectors += EvmConnector(alchemy::post, oneInch?.let { it::get })
                        }
                        if (bybitOn) {
                            // Exchange symbols are matched to coins through the catalog, so it is current before Bybit's records.
                            MarketFeedRefresher.refreshCatalog(app)
                            val bybit = BybitTransport(credentials = { app.exchangeKey(io.valroot.core.exchange.ExchangeKind.BYBIT, it) }, beforeRequest = ensureActive)
                            connectors += BybitConnector(bybit::get)
                        }
                        val outcomes =
                            app.refreshSources(
                                SourceRegistry(connectors),
                                SourceRefreshOptions(
                                    recoverHistory = recoverHistory,
                                    onProgress = { detail ->
                                        val shown = if (detail.startsWith("Bybit")) detail else "EVM · $detail"
                                        syncStatus.value = syncStatus.value.copy(phase = SyncPhase.SYNCING, message = shown)
                                        onProgress(shown)
                                    },
                                    withoutConsent =
                                        setOfNotNull(
                                            io.valroot.core.bitcoin.BITCOIN_MAINNET.takeIf { !app.sourceConsent() },
                                            io.valroot.core.evm.EVM_FAMILY.takeIf { !app.evmConsent() },
                                        ),
                                ),
                            )
                        val states = outcomes.flatMap { it.jobs }
                        val sourceNotes = outcomes.flatMap { it.notes }.joinToString(" ").ifEmpty { null }
                        // Once a chain has refreshed, new tokens are linked to coins and priced.
                        if (outcomes.any { (it.connector == EVM_MANIFEST.id || it.connector == BYBIT_MANIFEST.id) && it.jobs.contains(JobState.COMPLETED) }) {
                            MarketFeedRefresher.refreshEvmCatalogAndPrices(app)
                        }
                        when {
                            states.isEmpty() ->
                                SyncStatus(
                                    SyncPhase.IDLE,
                                    if (bitcoinOn) "Add a public address to sync" else sourceNotes ?: "Add a public address to sync",
                                )
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
                        ?: (failure as? io.valroot.core.application.BitcoinDiscoveryConflict)?.message
                        ?: "Could not finish sync during $stage (${failure::class.java.simpleName}). Saved data is unchanged; retry.",
                )
            }
        }
    }
}
