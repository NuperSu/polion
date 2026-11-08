package io.polion.core.evm

import io.polion.core.application.PortfolioApplication
import io.polion.core.application.SourceConnector
import io.polion.core.application.SourceRefreshOptions
import io.polion.core.application.SourceRefreshOutcome
import io.polion.core.connectors.Capability
import io.polion.core.connectors.Coverage
import io.polion.core.connectors.IntegrationManifest
import io.polion.core.model.AccountId
import io.polion.core.model.ExactInteger
import io.polion.core.sync.JobState

/** EVM addresses on the networks Alchemy serves: discovery of used chains, history with decoding, balance checks. */
val EVM_MANIFEST =
    IntegrationManifest(
        "evm-alchemy",
        setOf(EVM_FAMILY),
        setOf("address"),
        setOf(Capability.DISCOVERY, Capability.HISTORY, Capability.DECODING, Capability.BALANCE),
        requiresAuthentication = true,
        coverage = Coverage.PARTIAL,
    )

/**
 * Refreshes every tracked EVM address through Alchemy.
 *
 * - **Discovery:** balances and newest transfers per group of five networks find the chains an address has used, on the
 *   first refresh and then weekly; chains the user chose are kept.
 * - **Skip check per chain:** the newest transfer (plus the newest contract-made transfer where listed) and the count of
 *   transactions the address has sent form the head. Nothing else is read while it is unchanged.
 * - **Full read:** every transfer page, then receipts for transactions the address sent, which give their fee
 *   (including any L1 data fee) and status.
 * - **Gaps:** Alchemy lists transfers, so a sent transaction that moved nothing (for example an approval or a failed call)
 *   is not listed and its fee is unknown. The difference between the sent count and the sent transactions found is
 *   recorded per chain, as is a missing list of contract-made transfers. The balance check then shows the effect.
 *
 * A failing chain is recorded and the others continue. [post] sends a JSON body to a URL whose `{key}` placeholder the
 * host replaces with the API key.
 */
class EvmConnector(
    private val post: (url: String, body: String) -> String,
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
            // Chains the address has started to use are added on the first refresh and then weekly; chosen chains stay.
            if (options.recoverHistory || app.evmDiscoveryDue(address)) {
                app.addEvmChains(address, discover(address))
                app.recordEvmDiscovery(address)
            }
            val states = app.evmChainStates(address)
            if (states.isEmpty()) notes += "No activity found yet for ${address.take(10)}…"
            states.forEachIndexed { index, state ->
                options.onProgress("${Alchemy.network(state.chainId)?.name ?: "Chain ${state.chainId}"} · ${index + 1}/${states.size}")
                jobs +=
                    try {
                        requireNotNull(Alchemy.network(state.chainId)) { "This chain is not available from Alchemy" }
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

    /** Chains with a balance or any transfer, checked five networks per request. */
    fun discover(address: String): Set<Long> =
        Alchemy.NETWORKS
            .map { it.chainId }
            .chunked(Alchemy.MAX_NETWORKS)
            .flatMap { group ->
                val (balances, _) = Alchemy.parseTokenBalances(post(Alchemy.tokensUrl(), Alchemy.tokensBody(address, group)))
                val transfers = Alchemy.parseTransfers(post(Alchemy.transfersUrl(), Alchemy.transfersBody(address, group, maxCount = 1)))
                val slugs =
                    balances.filter { it.second.value.sign > 0 }.map { it.first } +
                        transfers.transactions.map { it.first } +
                        transfers.tokens.map { it.first }
                group.filter { chain -> Alchemy.network(chain)!!.slug in slugs }
            }.toSet()

    private fun refreshChain(
        app: PortfolioApplication,
        account: AccountId,
        address: String,
        state: EvmChainState,
        force: Boolean,
    ) {
        val chain = state.chainId
        val network = Alchemy.network(chain)!!
        val newest = Alchemy.parseTransfers(post(Alchemy.transfersUrl(), Alchemy.transfersBody(address, listOf(chain), maxCount = 1)))
        failOnErrors(newest)
        val newestInternal =
            if (network.internalTransfers) {
                Alchemy.parseTransfers(
                    post(Alchemy.transfersUrl(), Alchemy.transfersBody(address, listOf(chain), maxCount = 1, internal = true)),
                )
            } else {
                null
            }
        val sent = Alchemy.parseNonce(post(Alchemy.rpcUrl(chain), Alchemy.nonceBody(address)))
        val head =
            listOf(
                (newest.transactions.map { it.second.hash } + newest.tokens.map { "${it.second.transactionHash}:${it.second.logIndex}" })
                    .firstOrNull(),
                newestInternal
                    ?.internal
                    ?.firstOrNull()
                    ?.second
                    ?.transactionHash,
                "n$sent",
            ).joinToString("|") { it ?: "-" }
        if (!force && head == state.head) return

        val transactions = mutableListOf<EvmTransaction>()
        val internal = mutableListOf<EvmInternalTransfer>()
        val tokens = mutableListOf<EvmTokenTransfer>()
        pages(address, chain, internal = false).forEach { page ->
            transactions += page.transactions.map { it.second }
            tokens += page.tokens.map { it.second }
        }
        if (network.internalTransfers) {
            pages(address, chain, internal = true).forEach { page ->
                internal += page.internal.map { it.second }
            }
        }

        // Receipts for every transaction the address may have sent: fee, status and the actual sender.
        val candidates =
            (transactions.filter { it.from == address }.map { it.hash } + tokens.filter { it.from == address }.map { it.transactionHash })
                .distinct()
        val receipts =
            candidates
                .chunked(Alchemy.RECEIPT_BATCH)
                .flatMap { batch -> Alchemy.parseReceipts(post(Alchemy.rpcUrl(chain), Alchemy.receiptsBody(batch))) }
                .filter { it.from == address }
                .associateBy { it.hash }
        val listed = transactions.associateBy { it.hash }
        val withFees =
            transactions.map { tx ->
                receipts[tx.hash]?.let { tx.copy(fee = it.fee, success = it.success, position = it.position) } ?: tx
            } +
                receipts.values.filter { it.hash !in listed }.map { receipt ->
                    // A sent transaction listed only through its token transfers: zero native value, fee from the receipt.
                    val token = tokens.first { it.transactionHash == receipt.hash }
                    EvmTransaction(
                        receipt.hash,
                        receipt.blockNumber,
                        receipt.position,
                        token.timestampMillis,
                        address,
                        token.contract,
                        ExactInteger.ZERO,
                        receipt.fee,
                        receipt.success,
                    )
                }
        app.applyEvmChainHistory(account, address, chain, EvmDecoder.group(chain, withFees, internal, tokens), head)

        val unlisted = sent - receipts.size
        app.recordEvmCoverage(
            address,
            chain,
            listOfNotNull(
                if (unlisted > 0) {
                    "$unlisted sent transaction${if (unlisted == 1L) "" else "s"} not listed by Alchemy (for example approvals); " +
                        "their fees are not counted"
                } else {
                    null
                },
                if (!network.internalTransfers) "Payments made by contracts are not listed on this chain" else null,
            ).joinToString(". ").ifEmpty { null },
        )
        val (balances, errors) = Alchemy.parseTokenBalances(post(Alchemy.tokensUrl(), Alchemy.tokensBody(address, listOf(chain))))
        require(errors.isEmpty()) { "Alchemy could not report balances on this chain" }
        val native = balances.firstOrNull { it.second.standard == EvmTokenStandard.NATIVE }?.second?.value ?: ExactInteger.ZERO
        app.checkEvmBalances(account, address, chain, native, balances.map { it.second })
    }

    private fun pages(
        address: String,
        chain: Long,
        internal: Boolean,
    ): List<AlchemyTransfers> {
        val result = mutableListOf<AlchemyTransfers>()
        var key: String? = null
        do {
            require(result.size < MAX_PAGES) { "History is longer than $MAX_PAGES pages" }
            val page =
                Alchemy.parseTransfers(
                    post(Alchemy.transfersUrl(), Alchemy.transfersBody(address, listOf(chain), key, internal = internal)),
                )
            failOnErrors(page)
            result += page
            key = page.pageKey
        } while (key != null)
        return result
    }

    /** A timeout or error on the chain means the history is incomplete, so nothing is applied. */
    private fun failOnErrors(page: AlchemyTransfers) {
        page.errors.firstOrNull()?.let { throw IllegalStateException("Alchemy could not read this chain (${it.state})") }
    }

    companion object {
        const val MAX_PAGES = 400
    }
}
