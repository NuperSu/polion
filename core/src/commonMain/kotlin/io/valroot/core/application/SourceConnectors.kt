package io.valroot.core.application

import io.valroot.core.bitcoin.BITCOIN_CONNECTOR_ID
import io.valroot.core.bitcoin.BITCOIN_MAINNET
import io.valroot.core.bitcoin.EsploraTransport
import io.valroot.core.connectors.Capability
import io.valroot.core.connectors.Coverage
import io.valroot.core.connectors.IntegrationManifest
import io.valroot.core.model.NetworkId
import io.valroot.core.sync.JobState

/** Bitcoin mainnet through an Esplora-compatible provider: history with decoding, wallet discovery and UTXO balances. */
val BITCOIN_MANIFEST =
    IntegrationManifest(
        BITCOIN_CONNECTOR_ID,
        setOf(BITCOIN_MAINNET),
        setOf("address", "wallet"),
        setOf(Capability.DISCOVERY, Capability.HISTORY, Capability.DECODING, Capability.BALANCE),
        requiresAuthentication = false,
        coverage = Coverage.PARTIAL,
    )

data class SourceRefreshOptions(
    /** Start history again from the provider instead of continuing from saved history. */
    val recoverHistory: Boolean = false,
    val onProgress: (String) -> Unit = {},
    /** Tracked networks whose provider the user has not allowed; they are skipped without a "no connector" note. */
    val withoutConsent: Set<NetworkId> = emptySet(),
)

data class SourceRefreshOutcome(
    val connector: String,
    val jobs: List<JobState>,
    /** Short user-facing notes, such as wallet discovery results. */
    val notes: List<String> = emptyList(),
)

/**
 * A source family the application refreshes. Each connector declares what it can do in its [manifest]; the application
 * chooses connectors only through a [SourceRegistry]. A new family implements this interface and writes through
 * [SourceService.ingestHistory], without changes to holdings or accounting.
 */
interface SourceConnector {
    val manifest: IntegrationManifest

    fun refresh(
        app: PortfolioApplication,
        options: SourceRefreshOptions,
    ): SourceRefreshOutcome
}

/** The connectors one refresh may use. Each network has at most one history connector, so routing is never ambiguous. */
class SourceRegistry(
    val connectors: List<SourceConnector>,
) {
    init {
        require(connectors.map { it.manifest.id }.distinct().size == connectors.size) { "Duplicate integration ID" }
        val historyNetworks = connectors.filter { Capability.HISTORY in it.manifest.capabilities }.flatMap { it.manifest.networks }
        require(historyNetworks.distinct().size == historyNetworks.size) { "Exactly one history connector may support a network" }
    }

    fun forNetwork(network: NetworkId): SourceConnector? =
        connectors.firstOrNull { network in it.manifest.networks && Capability.HISTORY in it.manifest.capabilities }
}

/**
 * Refreshes Bitcoin through [transport]: wallets that still need address discovery first, then the history of every
 * tracked address. [providerEndpoint] binds the transport to the endpoint it was created for.
 */
class BitcoinConnector(
    private val transport: EsploraTransport,
    private val providerEndpoint: String,
    private val onScanProgress: (BitcoinScanProgress) -> Unit = {},
) : SourceConnector {
    override val manifest = BITCOIN_MANIFEST

    override fun refresh(
        app: PortfolioApplication,
        options: SourceRefreshOptions,
    ): SourceRefreshOutcome {
        val notes = app.bitcoinWallets.discoverPending(transport, providerEndpoint)
        val jobs =
            app.bitcoin.refresh(transport, recoverHistory = options.recoverHistory, providerEndpoint = providerEndpoint) {
                onScanProgress(it)
                options.onProgress("Fetched ${it.transactions} transactions from ${it.addressIndex}/${it.totalAddresses} addresses")
            }
        return SourceRefreshOutcome(manifest.id, jobs, notes)
    }
}
