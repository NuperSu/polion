package io.valroot.desktop

import io.valroot.core.application.HostRefresh
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.RefreshSummary
import io.valroot.core.bitcoin.BitcoinProviderException
import io.valroot.core.bitcoin.BitcoinProviderFailure
import io.valroot.core.bitcoin.BlockstreamTransport
import io.valroot.core.bitcoin.EsploraTransport
import io.valroot.core.model.AssetId
import io.valroot.core.model.TrackingTargetId
import io.valroot.core.sync.JobState
import io.valroot.ui.SyncOperation
import io.valroot.ui.SyncPhase
import io.valroot.ui.SyncStatus
import kotlinx.coroutines.flow.update
import javax.swing.SwingUtilities

internal fun discoverWalletStatus(
    application: PortfolioApplication,
    walletId: TrackingTargetId,
    transport: EsploraTransport,
    providerEndpoint: String = application.sources.endpoint(),
    onProgress: (String) -> Unit,
): SyncStatus =
    runCatching {
        var queries = 0
        val discoveryTransport =
            EsploraTransport { path ->
                transport.get(path).also {
                    queries++
                    onProgress("Discovering wallet · address queries answered: $queries")
                }
            }
        val result = application.bitcoinWallets.discover(walletId, discoveryTransport, providerEndpoint = providerEndpoint)
        val states =
            application.bitcoin.refresh(transport, providerEndpoint = providerEndpoint) { progress ->
                onProgress(
                    when (progress.phase) {
                        "blocks" -> "Checking blocks ${progress.blocksResolved}/${progress.totalBlocks}"
                        "verify" -> "Checking chain consistency…"
                        "apply" -> "Applying verified history…"
                        else ->
                            "Address ${progress.addressIndex}/${progress.totalAddresses} · " +
                                "${progress.pages} pages · ${progress.transactions} transactions"
                    },
                )
            }
        if (states.isEmpty() || states.any { it != JobState.COMPLETED }) {
            SyncStatus(SyncPhase.ERROR, application.sources.failureMessage() ?: "Wallet history is incomplete; retry.")
        } else {
            SyncStatus(
                SyncPhase.SUCCESS,
                listOfNotNull(
                    "Wallet discovery complete: ${result.addresses} addresses checked, ${result.usedAddresses} used; " +
                        "coverage remains unverified.",
                    result.notice,
                ).joinToString(" "),
            )
        }
    }.getOrElse {
        SyncStatus(
            SyncPhase.ERROR,
            (it as? BitcoinProviderException)?.reason?.userMessage
                ?: (it as? io.valroot.core.application.BitcoinDiscoveryConflict)?.message
                ?: "Wallet discovery incomplete. Check consent, provider and discovery limits; retry.",
        )
    }

/**
 * Provider work on the sync queue: Bitcoin, EVM and exchange refreshes, wallet discovery, the order check, and the
 * historical price fetches. One task runs at a time; a task that finds the queue busy is refused.
 */
internal class DesktopSync(
    private val state: DesktopState,
    private val prices: DesktopPrices,
) {
    /**
     * Runs one network task on the sync worker with its own database connection, so UI reads, saves and unlock
     * stay on the UI queue. A task is refused while another sync is active.
     */
    internal fun runSync(
        start: SyncStatus,
        onResult: (String) -> Unit,
        task: (PortfolioApplication) -> SyncStatus,
    ) {
        var status = SyncStatus(SyncPhase.ERROR, "Could not finish sync. Check storage and provider, then retry.")
        val accepted =
            state.syncQueue.tryStart(onReleased = {
                state.syncStatus.value = status
                state.dataRevision.update { it + 1 }
                SwingUtilities.invokeLater { onResult(status.message) }
            }) {
                state.syncStatus.value = start
                status =
                    try {
                        task(state.syncApplication()).copy(operation = start.operation)
                    } catch (failure: Exception) {
                        SyncStatus(
                            SyncPhase.ERROR,
                            (failure as? BitcoinProviderException)?.reason?.userMessage
                                ?: "Could not finish sync. Check storage and provider, then retry.",
                        ).copy(operation = start.operation)
                    }
            }
        if (!accepted) SwingUtilities.invokeLater { onResult("A sync is already running") }
    }

    private fun bitcoinTransport(
        application: PortfolioApplication,
        endpoint: String = application.sources.endpoint(),
    ): EsploraTransport =
        io.valroot.core.bitcoin.FailoverEsploraTransport(
            io.valroot.core.bitcoin.esploraEndpoints(endpoint).map { url ->
                io.valroot.core.bitcoin.EsploraEndpoint(
                    url,
                    BlockstreamTransport(
                        url,
                        onProgress = { state.syncStatus.value = state.syncStatus.value.copy(phase = SyncPhase.SYNCING, message = it) },
                        ensureActive = {
                            if (state.closing.get() || !application.sources.consent() || application.sources.endpoint() != endpoint) {
                                throw BitcoinProviderException(BitcoinProviderFailure.CANCELLED)
                            }
                        },
                    ),
                )
            },
        )

    fun verifyAccountingOrder(onResult: (String) -> Unit) {
        runSync(SyncStatus(SyncPhase.SYNCING, "Verifying Bitcoin order…", SyncOperation.ORDER), onResult) { application ->
            runCatching {
                application.bitcoin.verifyAccountingOrder(bitcoinTransport(application))
                SyncStatus(SyncPhase.SUCCESS, "Bitcoin order verified. Mixed-source chronology may still require review.")
            }.getOrElse {
                SyncStatus(
                    SyncPhase.ERROR,
                    (it as? BitcoinProviderException)?.reason?.userMessage
                        ?: "Could not verify order. Check consent, provider and saved history.",
                )
            }
        }
    }

    fun discoverWallet(
        walletId: TrackingTargetId,
        onResult: (String) -> Unit,
    ) {
        runSync(
            SyncStatus(SyncPhase.SYNCING, "Discovering wallet addresses…", SyncOperation.DISCOVERY),
            onResult,
        ) { application ->
            val endpoint = application.sources.endpoint()
            discoverWalletStatus(application, walletId, bitcoinTransport(application, endpoint), providerEndpoint = endpoint) { detail ->
                state.syncStatus.value = state.syncStatus.value.copy(phase = SyncPhase.SYNCING, message = detail)
            }
        }
    }

    fun fetchHistoricalPrice(
        asset: AssetId,
        quote: AssetId,
        dayUtcMillis: Long,
        apiKey: String,
        onResult: (String) -> Unit,
    ) {
        runSync(
            SyncStatus(SyncPhase.SYNCING, "Fetching daily historical price…", SyncOperation.PRICES),
            onResult,
        ) { application ->
            try {
                val result =
                    application.prices.fetchHistorical({ url -> state.priceTransport.get(url, apiKey) }, asset, quote, dayUtcMillis)
                if (result.status == io.valroot.core.market.PriceStatus.MISSING) {
                    SyncStatus(SyncPhase.ERROR, "No historical quote was returned. Saved prices are unchanged.")
                } else {
                    SyncStatus(SyncPhase.SUCCESS, "Daily historical price saved from CoinGecko.")
                }
            } catch (failure: io.valroot.core.market.CoingeckoProviderException) {
                SyncStatus(SyncPhase.ERROR, failure.failure.message)
            } catch (_: Exception) {
                SyncStatus(SyncPhase.ERROR, "Could not fetch a historical price. Check consent, date, connection and storage.")
            }
        }
    }

    fun fetchPriceHistory(
        asset: AssetId,
        quote: AssetId,
        days: Int,
        apiKey: String,
        onResult: (String) -> Unit,
    ) {
        runSync(
            SyncStatus(SyncPhase.SYNCING, "Fetching Bitcoin price history…", SyncOperation.PRICES),
            onResult,
        ) { application ->
            try {
                val count = application.prices.fetchHistory({ url -> state.priceTransport.get(url, apiKey) }, asset, quote, days)
                SyncStatus(SyncPhase.SUCCESS, "Saved $count Bitcoin price points from CoinGecko.")
            } catch (failure: io.valroot.core.market.CoingeckoProviderException) {
                SyncStatus(SyncPhase.ERROR, failure.failure.message)
            } catch (_: Exception) {
                SyncStatus(SyncPhase.ERROR, "Could not fetch price history. Check consent, connection and storage.")
            }
        }
    }

    fun refresh(onResult: (String) -> Unit) = refresh(false, onResult)

    fun recoverBitcoinHistory(onResult: (String) -> Unit) = refresh(true, onResult)

    fun refreshWithoutBitcoin(onResult: (String) -> Unit) = refresh(false, onResult, withoutBitcoin = true)

    private fun refresh(
        recoverHistory: Boolean,
        onResult: (String) -> Unit,
        withoutBitcoin: Boolean = false,
    ) {
        runSync(
            SyncStatus(SyncPhase.SYNCING, if (recoverHistory) "Recovering full Bitcoin history…" else "Fetching public history…"),
            onResult,
        ) { application ->
            runCatching {
                val plan =
                    HostRefresh(application, withoutBitcoin) { source ->
                        check(!state.closing.get()) { "$source refresh was cancelled. Retry if needed." }
                    }
                if (!plan.anySource) return@runCatching SyncStatus(SyncPhase.IDLE, HostRefresh.NO_PROVIDER)
                val connectors =
                    plan.connectors(
                        bitcoin = {
                            val endpoint = application.sources.endpoint()
                            HostRefresh.bitcoinConnector(bitcoinTransport(application, endpoint), endpoint) {
                                state.syncStatus.value = SyncStatus(SyncPhase.SYNCING, it)
                            }
                        },
                        refreshCatalog = { if (application.market.consent()) application.evm.refreshCatalog(state.priceTransport::feed) },
                    )
                val outcomes =
                    plan.run(connectors, recoverHistory) { state.syncStatus.value = SyncStatus(SyncPhase.SYNCING, it) }
                // Once a chain has refreshed, new tokens are linked to coins and priced.
                if (plan.catalogChanged(outcomes)) prices.refreshEvmCatalogAndPrices(application)
                plan.summary(outcomes, recoverHistory).toSyncStatus()
            }.getOrElse {
                SyncStatus(
                    SyncPhase.ERROR,
                    (it as? BitcoinProviderException)?.reason?.userMessage
                        ?: "Could not finish sync. Check storage and provider, then retry.",
                )
            }
        }
    }
}

private fun RefreshSummary.toSyncStatus() =
    SyncStatus(
        when (state) {
            RefreshSummary.State.IDLE -> SyncPhase.IDLE
            RefreshSummary.State.SUCCESS -> SyncPhase.SUCCESS
            RefreshSummary.State.ERROR -> SyncPhase.ERROR
        },
        message,
    )
