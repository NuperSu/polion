package io.polion.core.evm

import io.polion.core.application.PortfolioApplication
import io.polion.core.application.SourceConnector
import io.polion.core.application.SourceRefreshOptions
import io.polion.core.application.SourceRefreshOutcome
import io.polion.core.connectors.Capability
import io.polion.core.connectors.Coverage
import io.polion.core.connectors.IntegrationManifest
import io.polion.core.sync.JobState

/** EVM addresses on every chain Blockscout indexes: discovery of used chains, history with decoding, balance checks. */
val EVM_MANIFEST =
    IntegrationManifest(
        "evm-blockscout",
        setOf(EVM_FAMILY),
        setOf("address"),
        setOf(Capability.DISCOVERY, Capability.HISTORY, Capability.DECODING, Capability.BALANCE),
        requiresAuthentication = true,
        coverage = Coverage.PARTIAL,
    )

/**
 * Refreshes every tracked EVM address. Per chain, the newest transaction, internal transfer and token transfer are read
 * first; when they equal the saved head nothing else is fetched. Otherwise the complete history is read and applied in
 * one transaction, then balances are compared with the provider's. A failing chain is recorded and the others continue.
 *
 * [get] performs a Blockscout request (the host adds the API key and paces requests); [discoverChains] returns the chain
 * IDs an address has been used on, or null when discovery is unavailable.
 */
class EvmConnector(
    private val get: (url: String) -> String,
    private val discoverChains: (address: String) -> Set<Long>? = { null },
) : SourceConnector {
    override val manifest = EVM_MANIFEST

    override fun refresh(
        app: PortfolioApplication,
        options: SourceRefreshOptions,
    ): SourceRefreshOutcome {
        val targets = app.evmTargets()
        if (targets.isEmpty()) return SourceRefreshOutcome(manifest.id, emptyList())
        require(app.evmConsent()) { "EVM consent required" }
        val jobs = mutableListOf<JobState>()
        val notes = mutableListOf<String>()
        targets.forEach { (account, address) ->
            discoverChains(address)?.let { app.addEvmChains(address, it) }
            val states = app.evmChainStates(address)
            if (states.isEmpty()) notes += "No chains found yet for ${address.take(10)}…"
            states.forEachIndexed { index, state ->
                options.onProgress("Chain ${state.chainId} · ${index + 1}/${states.size}")
                jobs +=
                    try {
                        refreshChain(app, account, address, state, force = options.recoverHistory)
                        JobState.COMPLETED
                    } catch (failure: Exception) {
                        app.recordEvmChainError(address, state.chainId, failure.message ?: "Could not refresh this chain")
                        JobState.FAILED
                    }
            }
        }
        return SourceRefreshOutcome(manifest.id, jobs, notes)
    }

    private fun refreshChain(
        app: PortfolioApplication,
        account: io.polion.core.model.AccountId,
        address: String,
        state: EvmChainState,
        force: Boolean,
    ) {
        val chain = state.chainId
        val firstTransactions = Blockscout.parseTransactions(get(Blockscout.url(chain, "addresses/$address/transactions")))
        val firstInternal = Blockscout.parseInternalTransfers(get(Blockscout.url(chain, "addresses/$address/internal-transactions")))
        val firstTokens = Blockscout.parseTokenTransfers(get(Blockscout.url(chain, "addresses/$address/token-transfers")))
        val head =
            listOf(
                firstTransactions.items.firstOrNull()?.let { "${it.hash}:${it.blockNumber}" },
                firstInternal.items.firstOrNull()?.let { "${it.transactionHash}:${it.index}" },
                firstTokens.items.firstOrNull()?.let { "${it.transactionHash}:${it.logIndex}" },
            ).joinToString("|") { it ?: "-" }
        // Pending transactions have no block yet; a head that includes one is always refetched.
        // A change deeper in history with unchanged newest items is not seen here; the balance check and an explicit full
        // history recovery ([force]) cover it.
        if (!force && head == state.head && firstTransactions.items.none { it.blockNumber == null }) return
        val transactions = all(firstTransactions, "addresses/$address/transactions", chain, Blockscout::parseTransactions)
        val internal = all(firstInternal, "addresses/$address/internal-transactions", chain, Blockscout::parseInternalTransfers)
        val tokens = all(firstTokens, "addresses/$address/token-transfers", chain, Blockscout::parseTokenTransfers)
        app.applyEvmChainHistory(account, address, chain, EvmDecoder.group(chain, transactions, internal, tokens), head)
        val native = Blockscout.parseBalance(get(Blockscout.url(chain, "addresses/$address")))
        val balances =
            all(
                Blockscout.parseTokenBalances(get(Blockscout.url(chain, "addresses/$address/tokens"))),
                "addresses/$address/tokens",
                chain,
                Blockscout::parseTokenBalances,
            )
        app.checkEvmBalances(account, address, chain, native, balances)
    }

    private fun <T> all(
        first: EvmPage<T>,
        path: String,
        chain: Long,
        parse: (String) -> EvmPage<T>,
    ): List<T> {
        val items = first.items.toMutableList()
        var next = first.next
        var pages = 1
        while (next != null) {
            require(++pages <= Blockscout.MAX_PAGES) { "History is longer than ${Blockscout.MAX_PAGES} pages" }
            val page = parse(get(Blockscout.url(chain, path, next)))
            items += page.items
            next = page.next
        }
        return items
    }
}
