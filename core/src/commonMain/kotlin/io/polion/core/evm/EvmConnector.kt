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
 * - **Unlisted sent transactions:** Alchemy lists transfers, so a sent transaction that moved nothing (for example a
 *   failed call) is not listed. Each nonce no listed transaction uses is found by binary search over the address's
 *   historical transaction count, then read from its block and receipt, at most [MAX_NONCE_SEARCHES] per refresh.
 * - **Contract payments:** where Alchemy does not list contract-made transfers, and a 1inch key is set, 1inch's history
 *   names the transactions in which a contract paid the address, and each one's 1inch trace gives the exact payments.
 *   1inch's own fees and amounts are not used. Without a key, the chain records that such payments are not listed.
 * - **Token listing:** the tokens 1inch's history moves are saved, so tokens it leaves out as scams start hidden.
 *
 * Everything recovered is saved, so each lookup happens once. 1inch is asked again after [ONE_INCH_RECHECK_MILLIS]
 * even when Alchemy shows nothing new, because a contract can pay the address in someone else's transaction.
 *
 * A failing chain is recorded and the others continue. [post] sends a JSON body to a URL whose `{key}` placeholder the
 * host replaces with the API key; [oneInch] requests a path on [OneInch.BASE_URL] with the 1inch key, or is null
 * without one.
 */
class EvmConnector(
    private val post: (url: String, body: String) -> String,
    private val oneInch: ((path: String) -> String)? = null,
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
        val recovered = app.evmRecovered()
        val recheck =
            oneInch != null &&
                chain in OneInch.CHAINS &&
                (force || recovered.listing(address, chain)?.let { app.clockMillis() - it.atMillis >= ONE_INCH_RECHECK_MILLIS } ?: true)
        var checkedOneInch = false
        if (!force && head == state.head) {
            val paid = recheck && readOneInch(app, address, chain, network.internalTransfers)
            checkedOneInch = recheck
            // A found contract payment changes the history. So does a gap a full read can now close: sent transactions
            // Alchemy did not list, or contract payments once a 1inch key is set.
            val closable =
                state.coverage?.let { note ->
                    UNLISTED_NOTE in note || (oneInch != null && chain in OneInch.CHAINS && CONTRACT_NOTE in note)
                } == true
            if (!paid && !closable) {
                // A rebasing token's balance changes without a transfer, so it is read on every refresh; a difference
                // left by an earlier check is settled too.
                if (state.balanceDifference != null || app.holdsRebasing(account, chain)) checkBalances(app, account, address, chain)
                return
            }
        }

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
        } else {
            if (recheck && !checkedOneInch) readOneInch(app, address, chain, internalListed = false)
            internal += recovered.traces(address, chain).values.flatten()
        }
        if (network.internalTransfers && recheck && !checkedOneInch) readOneInch(app, address, chain, internalListed = true)

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
        val found = recoverSentTransactions(app, address, chain, sent, receipts.keys).filter { it.hash !in listed && it.hash !in receipts }
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
                } + found
        app.applyEvmChainHistory(account, address, chain, EvmDecoder.group(chain, withFees, internal, tokens), head)

        val searched = recovered.nonces(address, chain)
        val unresolved = searched.count { it.value == null }
        val pending = (sent - receipts.size - found.size - unresolved).coerceAtLeast(0)

        fun count(n: Long) = "$n sent transaction${if (n == 1L) "" else "s"}"
        app.recordEvmCoverage(
            address,
            chain,
            listOfNotNull(
                if (pending > 0) "${count(pending)} $UNLISTED_NOTE and still to be searched for; their fees are not counted yet" else null,
                if (unresolved > 0) "${count(unresolved.toLong())} could not be found on the chain; their fees are not counted" else null,
                when {
                    network.internalTransfers -> null
                    oneInch != null && chain in OneInch.CHAINS -> null
                    chain in OneInch.CHAINS -> "$CONTRACT_NOTE; a 1inch key recovers them"
                    else -> CONTRACT_NOTE
                },
            ).joinToString(". ").ifEmpty { null },
        )
        checkBalances(app, account, address, chain)
    }

    /**
     * Reads the provider's balances, follows rebasing tokens and the native coin to them, and records any remaining
     * difference.
     */
    private fun checkBalances(
        app: PortfolioApplication,
        account: AccountId,
        address: String,
        chain: Long,
    ) {
        val (balances, errors) = Alchemy.parseTokenBalances(post(Alchemy.tokensUrl(), Alchemy.tokensBody(address, listOf(chain))))
        require(errors.isEmpty()) { "Alchemy could not report balances on this chain" }
        val tokens = balances.map { it.second }
        app.reconcileRebasingBalances(account, address, chain, tokens)
        val native = tokens.firstOrNull { it.standard == EvmTokenStandard.NATIVE }?.value ?: ExactInteger.ZERO
        app.reconcileNativeBalance(account, address, chain, native)
        app.checkEvmBalances(account, address, chain, native, tokens)
    }

    /**
     * Reads 1inch's history of the address on the chain: saves the token contracts it lists and, where Alchemy does not
     * list contract payments, traces each new transaction in which 1inch reports one. Returns whether a trace found a
     * payment. The listing is dated only once every such transaction is traced, so an interrupted run resumes.
     */
    private fun readOneInch(
        app: PortfolioApplication,
        address: String,
        chain: Long,
        internalListed: Boolean,
    ): Boolean {
        val fetch = oneInch ?: return false
        val recovered = app.evmRecovered()
        val events = mutableListOf<OneInchEvent>()
        var before: Long? = null
        do {
            require(events.size < OneInch.HISTORY_LIMIT * OneInch.MAX_HISTORY_PAGES) { "1inch history is too long" }
            val page = OneInch.parseHistory(fetch(OneInch.historyPath(address, chain, before)), address)
            events += page
            before = page.minOfOrNull { it.timestampMillis }?.minus(1)
        } while (page.size >= OneInch.HISTORY_LIMIT)
        val traced = recovered.traces(address, chain)
        val due = if (internalListed) emptyList() else events.filter { it.contractPayment && it.hash !in traced }.distinctBy { it.hash }
        var paid = false
        due.take(MAX_TRACES).forEach { event ->
            val transfers =
                OneInch.parseTrace(
                    fetch(OneInch.tracePath(chain, event.blockNumber, event.hash)),
                    event.hash,
                    event.blockNumber,
                    event.timestampMillis,
                    address,
                )
            recovered.saveTrace(address, chain, event.hash, transfers)
            if (transfers.any { it.success }) paid = true
        }
        val complete = due.size <= MAX_TRACES
        recovered.saveListing(
            address,
            chain,
            OneInchListing(if (complete) app.clockMillis() else 0L, events.flatMap { it.contracts }.toSet()),
        )
        return paid
    }

    /**
     * Finds sent transactions Alchemy did not list by their nonce: the block where the address's transaction count
     * passes the nonce holds the transaction, and its receipt gives fee and status. Results, including nonces no
     * transaction in that block uses (such as a delegation authorization), are saved. Returns every found transaction.
     */
    private fun recoverSentTransactions(
        app: PortfolioApplication,
        address: String,
        chain: Long,
        sent: Long,
        known: Set<String>,
    ): List<EvmTransaction> {
        val recovered = app.evmRecovered()
        val searched = recovered.nonces(address, chain)
        val gap = sent - known.size - searched.size
        if (gap > 0) {
            val nonces =
                known
                    .toList()
                    .chunked(Alchemy.RECEIPT_BATCH)
                    .flatMap { batch -> Alchemy.parseNonces(post(Alchemy.rpcUrl(chain), Alchemy.transactionsBody(batch))).values }
                    .toSet()
            val missing = (0 until sent).filter { it !in nonces && it !in searched }.take(MAX_NONCE_SEARCHES)
            if (missing.isNotEmpty()) {
                val latest = Alchemy.parseQuantity(post(Alchemy.rpcUrl(chain), Alchemy.blockNumberBody()))
                missing.forEach { nonce ->
                    // The count at block `low` is at most the nonce and at `high` above it.
                    var low = 0L
                    var high = latest
                    while (high - low > 1) {
                        val middle = low + (high - low) / 2
                        if (Alchemy.parseQuantity(post(Alchemy.rpcUrl(chain), Alchemy.nonceAtBody(address, middle))) > nonce) {
                            high = middle
                        } else {
                            low = middle
                        }
                    }
                    val transaction =
                        Alchemy.parseBlockTransaction(post(Alchemy.rpcUrl(chain), Alchemy.blockBody(high)), address, nonce)?.let { tx ->
                            val receipt = Alchemy.parseReceipts(post(Alchemy.rpcUrl(chain), Alchemy.receiptsBody(listOf(tx.hash)))).single()
                            tx.copy(fee = receipt.fee, success = receipt.success, position = receipt.position, source = NONCE_SOURCE)
                        }
                    recovered.saveNonce(address, chain, nonce, transaction)
                }
            }
        }
        return recovered.nonces(address, chain).values.filterNotNull()
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

        /** Nonces searched per chain and refresh; each search takes about 30 small requests. */
        const val MAX_NONCE_SEARCHES = 3

        /** Transactions traced per chain and refresh; the rest follow on the next refresh. */
        const val MAX_TRACES = 100

        /** 1inch is asked again after this long even when Alchemy shows nothing new. */
        const val ONE_INCH_RECHECK_MILLIS = 6 * 3_600_000L

        /** Coverage note text for sent transactions a full read can still search for. */
        const val UNLISTED_NOTE = "not listed by Alchemy"

        /** Coverage note text for contract payments Alchemy does not list on a chain. */
        const val CONTRACT_NOTE = "Payments made by contracts are not listed on this chain"
    }
}
