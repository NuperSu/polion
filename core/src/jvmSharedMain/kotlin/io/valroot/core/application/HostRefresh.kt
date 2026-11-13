package io.valroot.core.application

import io.valroot.core.bitcoin.BITCOIN_MAINNET
import io.valroot.core.bitcoin.EsploraTransport
import io.valroot.core.evm.AlchemyTransport
import io.valroot.core.evm.EVM_FAMILY
import io.valroot.core.evm.EVM_MANIFEST
import io.valroot.core.evm.EvmConnector
import io.valroot.core.evm.OneInchTransport
import io.valroot.core.exchange.ExchangeKind
import io.valroot.core.exchange.ExchangeProviders
import io.valroot.core.exchange.JvmExchanges
import io.valroot.core.sync.JobState

/** How a refresh ended, in the words the hosts show. */
data class RefreshSummary(
    val state: State,
    val message: String,
) {
    enum class State { IDLE, SUCCESS, ERROR }
}

/**
 * One source refresh as the desktop and Android hosts run it: which sources take part (each needs its own consent or
 * tracked accounts), their connectors, progress text, and the summary shown afterwards. The host supplies what differs:
 * its Bitcoin transport, how it refreshes the coin catalog, and [beforeRequest], which throws to cancel a source's
 * next request (it receives the source's label).
 */
class HostRefresh(
    private val app: PortfolioApplication,
    private val withoutBitcoin: Boolean = false,
    private val beforeRequest: (source: String) -> Unit = {},
) {
    val bitcoinOn: Boolean = app.sources.consent() && !withoutBitcoin
    val evmOn: Boolean = app.evm.consent() && app.evm.targets().isNotEmpty()
    val exchanges: List<ExchangeKind> = ExchangeKind.entries.filter { app.exchanges.targets(it).isNotEmpty() }

    /** Whether any source may refresh; with none, nothing is fetched. */
    val anySource: Boolean get() = bitcoinOn || evmOn || exchanges.isNotEmpty()

    /**
     * The connectors of every source that takes part. Exchange symbols are matched to coins through the catalog, so
     * [refreshCatalog] runs first when an exchange takes part.
     */
    fun connectors(
        bitcoin: (() -> SourceConnector)?,
        refreshCatalog: () -> Unit,
    ): List<SourceConnector> {
        val connectors = mutableListOf<SourceConnector>()
        if (bitcoinOn && bitcoin != null) connectors += bitcoin()
        if (evmOn) {
            val check = {
                beforeRequest("EVM")
                check(app.evm.consent()) { "EVM refresh was cancelled. Retry if needed." }
            }
            val alchemy = AlchemyTransport(apiKey = { app.evm.alchemyApiKey() }, beforeRequest = check)
            val oneInch = app.evm.oneInchApiKey()?.let { OneInchTransport(apiKey = { app.evm.oneInchApiKey() }, beforeRequest = check) }
            connectors += EvmConnector(alchemy::post, oneInch?.let { it::get })
        }
        if (exchanges.isNotEmpty()) runCatching(refreshCatalog)
        exchanges.forEach { kind -> connectors += JvmExchanges.of(kind).connector(app) { beforeRequest(kind.label) } }
        return connectors
    }

    /** Runs [connectors]; [onProgress] gets each step's text, prefixed with its source. */
    fun run(
        connectors: List<SourceConnector>,
        recoverHistory: Boolean,
        onProgress: (String) -> Unit,
    ): List<SourceRefreshOutcome> =
        app.sources.refresh(
            SourceRegistry(connectors),
            SourceRefreshOptions(
                recoverHistory = recoverHistory,
                onProgress = { detail ->
                    onProgress(if (ExchangeKind.entries.any { detail.startsWith(it.label) }) detail else "EVM · $detail")
                },
                withoutConsent =
                    setOfNotNull(
                        // A refresh without Bitcoin skips it like a source without consent, without a "no connector" note.
                        BITCOIN_MAINNET.takeIf { withoutBitcoin || !app.sources.consent() },
                        EVM_FAMILY.takeIf { !app.evm.consent() },
                    ),
            ),
        )

    /** Whether a source that brings new tokens or coins completed, so they are linked to coins and priced next. */
    fun catalogChanged(outcomes: List<SourceRefreshOutcome>): Boolean {
        val linking = setOf(EVM_MANIFEST.id) + ExchangeProviders.all.map { it.manifest.id }
        return outcomes.any { it.connector in linking && JobState.COMPLETED in it.jobs }
    }

    fun summary(
        outcomes: List<SourceRefreshOutcome>,
        recoverHistory: Boolean,
        extraNotes: List<String> = emptyList(),
    ): RefreshSummary {
        val states = outcomes.flatMap { it.jobs }
        val notes = extraNotes + outcomes.flatMap { it.notes }
        return when {
            states.isEmpty() ->
                RefreshSummary(
                    RefreshSummary.State.IDLE,
                    if (bitcoinOn) NOTHING_TO_SYNC else notes.joinToString(" ").ifEmpty { NOTHING_TO_SYNC },
                )
            states.any { it != JobState.COMPLETED } ->
                RefreshSummary(RefreshSummary.State.ERROR, app.sources.failureMessage() ?: "History is incomplete; retry.")
            else ->
                RefreshSummary(
                    RefreshSummary.State.SUCCESS,
                    when {
                        recoverHistory -> "History recovery complete; coverage remains unverified"
                        app.sources.lastRefreshSkipped() -> "Checked for new activity; none found."
                        else -> "Sync complete; coverage remains unverified"
                    } + notes.joinToString("") { " $it" },
                )
        }
    }

    companion object {
        const val NO_PROVIDER = "Allow a provider before syncing"
        const val NOTHING_TO_SYNC = "Add a public address to sync"

        /** A Bitcoin scan step as progress text. */
        fun bitcoinProgress(progress: BitcoinScanProgress): String =
            "Bitcoin · " +
                when (progress.phase) {
                    "blocks" -> "Checking blocks ${progress.blocksResolved}/${progress.totalBlocks}"
                    "verify" -> "Checking chain consistency…"
                    "apply" -> "Applying verified history…"
                    else ->
                        "Address ${progress.addressIndex}/${progress.totalAddresses} · " +
                            "${progress.pages} pages · ${progress.transactions} transactions"
                }

        /** A Bitcoin connector over [transport], reporting progress as [bitcoinProgress] text. */
        fun bitcoinConnector(
            transport: EsploraTransport,
            endpoint: String,
            onProgress: (String) -> Unit,
        ): SourceConnector = BitcoinConnector(transport, endpoint) { onProgress(bitcoinProgress(it)) }
    }
}
