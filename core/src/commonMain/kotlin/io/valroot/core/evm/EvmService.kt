package io.valroot.core.evm

import io.valroot.core.application.AppContext
import io.valroot.core.application.AppService
import io.valroot.core.holdings.BalanceCoverage
import io.valroot.core.ledger.Activity
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.market.CoingeckoCatalog
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.ExactInteger
import io.valroot.core.storage.sha256
import kotlin.time.Instant

/** EVM addresses: provider keys and consent, tracked chains, decoded history, balance checks and the token catalog. */
class EvmService internal constructor(
    ctx: AppContext,
) : AppService(ctx) {
    /**
     * Refreshes the CoinGecko chain and contract catalog when it is older than a week (two keyless requests), then links
     * held EVM assets to their coins. Needs market data consent.
     */
    fun refreshCatalog(
        transport: (url: String, apiKey: String?) -> String,
        baseUrl: String = "https://api.coingecko.com/api/v3",
    ) {
        require(app.market.consent()) { "Market data consent required" }
        val cached = marketFeeds.catalog()
        if (cached == null || now() - cached.first > 7 * 86_400_000L || !cached.second.hasSymbols || !cached.second.hasOtherPlatforms) {
            val key = app.market.coingeckoApiKey()
            val platforms = CoingeckoCatalog.parsePlatforms(transport(baseUrl + CoingeckoCatalog.PLATFORMS_PATH, key))
            require(platforms.isNotEmpty()) { "CoinGecko returned no chains" }
            val coins = transport(baseUrl + CoingeckoCatalog.COINS_PATH, key)
            val catalog =
                CoingeckoCatalog(
                    platforms,
                    CoingeckoCatalog.parseContracts(coins, platforms),
                    CoingeckoCatalog.parseNatives(coins, platforms),
                    CoingeckoCatalog.parseSymbols(coins),
                )
            marketFeeds.saveCatalog(catalog, now())
        }
        linkAssets()
    }

    /** Links registered EVM native and token assets to CoinGecko coins from the cached catalog. Links never change once set. */
    fun linkAssets(): Int {
        val catalog = marketFeeds.catalog()?.second ?: return 0
        val linked = assetLinks.links()
        var count = 0
        db.transaction {
            market.assetIds().filter { it.value.startsWith("eip155:") && it !in linked }.forEach { asset ->
                val chain =
                    io.valroot.core.evm.EvmAssets
                        .chainOf(asset) ?: return@forEach
                val coin =
                    when {
                        asset.value.endsWith("/native") -> catalog.nativeCoin(chain)
                        asset.value.contains("/erc20:") -> catalog.tokenCoin(chain, asset.value.substringAfter("/erc20:"))
                        else -> null
                    }
                if (coin != null) {
                    assetLinks.link(asset, coin)
                    count++
                }
            }
        }
        return count
    }

    /** The optional 1inch API key that recovers contract payments Alchemy does not list; a device setting, not part of backups. */
    fun oneInchApiKey(): String? = marketFeeds.deviceSetting("oneinch_api_key")

    fun setOneInchApiKey(key: String?) {
        val trimmed = key?.trim()?.takeIf { it.isNotEmpty() }
        require(trimmed == null || trimmed.matches(Regex("[A-Za-z0-9_-]{16,200}"))) { "That does not look like a 1inch API key" }
        marketFeeds.putDeviceSetting("oneinch_api_key", trimmed)
    }

    /** The Alchemy API key for EVM history; a device setting, not part of backups. */
    fun alchemyApiKey(): String? = marketFeeds.deviceSetting("alchemy_api_key")

    fun setAlchemyApiKey(key: String?) {
        val trimmed = key?.trim()?.takeIf { it.isNotEmpty() }
        require(trimmed == null || trimmed.matches(Regex("[A-Za-z0-9_-]{16,100}"))) { "That does not look like an Alchemy API key" }
        marketFeeds.putDeviceSetting("alchemy_api_key", trimmed)
    }

    /** EVM chains whose history Alchemy serves, named from CoinGecko's catalog when it is cached. */
    fun chainChoices(): List<io.valroot.core.market.EvmPlatform> {
        val catalog = marketFeeds.catalog()?.second
        return io.valroot.core.evm.Alchemy.NETWORKS
            .map { network ->
                catalog?.platform(network.chainId) ?: io.valroot.core.market
                    .EvmPlatform(network.slug, network.chainId, network.name, null)
            }.sortedBy { it.name.lowercase() }
    }

    /** EVM addresses are sent to Alchemy only after this consent. */
    fun consent(): Boolean = setting("evm_consent") == "true"

    fun setConsent(accepted: Boolean) = putSetting("evm_consent", accepted.toString())

    /** Enabled EVM address targets with their accounts. */
    fun targets(): List<Pair<AccountId, String>> =
        ownership
            .load()
            .targets
            .filter { it.enabled && it.identity.networkId == io.valroot.core.evm.EVM_FAMILY && it.identity.kind == "address" }
            .map { it.accountId to it.identity.canonicalKey }

    fun chainStates(address: String? = null): List<io.valroot.core.evm.EvmChainState> = evmChains.states(address)

    fun addChains(
        address: String,
        chains: Collection<Long>,
    ) = evmChains.addChains(
        io.valroot.core.evm
            .evmAddress(address),
        chains,
    )

    /** Whether [address] should be checked for chains it has started to use: never checked yet, or not for a week. */
    fun discoveryDue(address: String): Boolean =
        setting("evm_discovered:$address")?.toLongOrNull()?.let { now() - it >= 7 * 86_400_000L } ?: true

    fun recordDiscovery(address: String) = putSetting("evm_discovered:$address", now().toString())

    fun recordCoverage(
        address: String,
        chainId: Long,
        coverage: String?,
    ) = evmChains.recordCoverage(address, chainId, coverage)

    fun recordChainError(
        address: String,
        chainId: Long,
        message: String,
    ) = evmChains.recordError(address, chainId, message)

    /**
     * Applies the complete history of [address] on one chain in one transaction: registers the assets it uses (native
     * coins named from CoinGecko's catalog), records each transaction group as a raw provider record, saves changed
     * activities, and supersedes activities the provider no longer reports (for example after a reorganisation).
     */
    fun applyChainHistory(
        account: AccountId,
        address: String,
        chainId: Long,
        groups: List<io.valroot.core.evm.EvmTransactionGroup>,
        head: String,
        withdrawals: List<io.valroot.core.evm.EvmWithdrawal> = emptyList(),
    ): Int {
        require(consent()) { "EVM consent required" }
        require(targets().any { it.first == account && it.second == address }) { "Unknown EVM address" }
        val retrieved = now()
        val catalog = marketFeeds.catalog()?.second
        var saved = 0
        db.transaction {
            val known = market.assetIds().toSet()
            groups.flatMap(io.valroot.core.evm.EvmDecoder::assets).distinctBy { it.definition.id }.forEach { info ->
                if (info.definition.id !in known) {
                    val metadata =
                        if (info.definition.identity.kind == io.valroot.core.market.AssetKind.NATIVE) {
                            catalog?.nativeLabel(chainId)?.let { (symbol, name) -> info.metadata.copy(symbol = symbol, name = name) }
                                ?: info.metadata.copy(symbol = "COIN", name = "Native coin (chain $chainId)")
                        } else {
                            info.metadata
                        }
                    market.registerAssetInTransaction(info.definition, metadata)
                }
            }
            val ids = mutableSetOf<ActivityId>()
            groups.forEach { group ->
                val payload = group.canonical()
                val source =
                    store.ingestInTransaction(
                        io.valroot.core.storage.RawObservation(
                            "eip155:$chainId:${group.hash}",
                            "eip155:$chainId",
                            group.hash,
                            "eip155:$chainId",
                            "alchemy",
                            Instant.fromEpochMilliseconds(retrieved).toString(),
                            payload,
                            io.valroot.core.evm.EvmDecoder.VERSION,
                            sha256(payload),
                            "address=$address",
                        ),
                    )
                val activity =
                    io.valroot.core.evm.EvmDecoder
                        .decode(group, address, account, source) ?: return@forEach
                ids += activity.id
                if (activities.currentById(activity.id) != activity) {
                    activities.saveInTransaction(activity)
                    saved++
                }
            }
            withdrawals.forEach { withdrawal ->
                val payload =
                    "evm-1|$chainId|withdrawal|${withdrawal.index}|${withdrawal.blockNumber}|${withdrawal.receiver}|${withdrawal.amount}"
                val source =
                    store.ingestInTransaction(
                        io.valroot.core.storage.RawObservation(
                            "eip155:$chainId:withdrawal-${withdrawal.index}",
                            "eip155:$chainId",
                            "withdrawal-${withdrawal.index}",
                            "eip155:$chainId",
                            "alchemy",
                            Instant.fromEpochMilliseconds(retrieved).toString(),
                            payload,
                            io.valroot.core.evm.EvmDecoder.VERSION,
                            sha256(payload),
                            "address=$address",
                        ),
                    )
                val activity =
                    io.valroot.core.evm.EvmDecoder
                        .decodeWithdrawal(chainId, withdrawal, address, account, source) ?: return@forEach
                ids += activity.id
                if (activities.currentById(activity.id) != activity) {
                    activities.saveInTransaction(activity)
                    saved++
                }
            }
            val prefix = "eip155:$chainId:"
            activities
                .current()
                .filter { it.id.value.startsWith(prefix) && it.id.value.endsWith(":$address") && it.id !in ids }
                .filter {
                    it.status == io.valroot.core.ledger.ActivityStatus.CONFIRMED ||
                        it.status == io.valroot.core.ledger.ActivityStatus.PENDING
                }.forEach { activities.saveInTransaction(it.copy(status = io.valroot.core.ledger.ActivityStatus.SUPERSEDED)) }
            evmChains.recordSuccess(address, chainId, head, retrieved)
        }
        invalidate()
        linkAssets()
        return saved
    }

    /** Whether [account] holds a rebasing token on [chainId], so its balance is read even when no transfer is new. */
    fun holdsRebasing(
        account: AccountId,
        chainId: Long,
    ): Boolean {
        app.assets.held()
        return holdings
            .current(BalanceCoverage.UNKNOWN, now())
            .quantities
            .any { (key, units) ->
                key.account == account &&
                    key.asset in EvmAssets.REBASING &&
                    EvmAssets.chainOf(key.asset) == chainId &&
                    units.sign != 0
            }
    }

    /**
     * Brings rebasing tokens ([EvmAssets.REBASING]) on one chain to the provider's balance, which changes without
     * transfers. Each difference is one activity at the time of the check: a reward when a yield token grows, otherwise
     * an adjustment. Only tokens the provider lists are compared. Returns the number of activities recorded.
     */
    fun reconcileRebasingBalances(
        account: AccountId,
        address: String,
        chainId: Long,
        tokens: List<io.valroot.core.evm.EvmTokenBalance>,
    ): Int {
        require(consent()) { "EVM consent required" }
        app.assets.held()
        val ledger =
            holdings
                .current(BalanceCoverage.UNKNOWN, now())
                .quantities
                .filterKeys { it.account == account }
                .mapKeys { it.key.asset }
        val provider =
            tokens
                .filter { it.standard == EvmTokenStandard.ERC20 }
                .associate { EvmAssets.token(chainId, it.contract) to it.value }
        val at = now()
        var saved = 0
        db.transaction {
            EvmAssets.REBASING.filterKeys { EvmAssets.chainOf(it) == chainId && it in provider }.forEach { (asset, yields) ->
                val difference = provider.getValue(asset) - (ledger[asset] ?: io.valroot.core.model.ExactInteger.ZERO)
                if (difference.sign == 0) return@forEach
                val contract = asset.value.substringAfter("/erc20:")
                val id = "evm-rebase:$chainId:$contract:$address:$at"
                val payload = "evm-rebase-1|$chainId|$contract|$address|$at|$difference"
                val source =
                    store.ingestInTransaction(
                        io.valroot.core.storage.RawObservation(
                            id,
                            "eip155:$chainId",
                            "rebase-$contract-$at",
                            "eip155:$chainId",
                            "alchemy",
                            Instant.fromEpochMilliseconds(at).toString(),
                            payload,
                            io.valroot.core.evm.EvmDecoder.VERSION,
                            sha256(payload),
                            "address=$address",
                        ),
                    )
                val reward = yields && difference.sign > 0
                activities.saveInTransaction(
                    Activity(
                        ActivityId(id),
                        at,
                        "evm-rebase:$chainId:${at.toString().padStart(20, '0')}:$contract",
                        if (reward) ActivityKind.REWARD else ActivityKind.OTHER,
                        ActivityStatus.CONFIRMED,
                        listOf(
                            io.valroot.core.ledger.Movement(
                                io.valroot.core.model
                                    .EventId("$id:balance"),
                                account,
                                io.valroot.core.model
                                    .AtomicAmount(asset, difference),
                                if (reward) io.valroot.core.ledger.MovementRole.REWARD else io.valroot.core.ledger.MovementRole.TRANSFER,
                            ),
                        ),
                        setOf(source),
                        io.valroot.core.evm.EvmDecoder.VERSION,
                    ),
                )
                saved++
            }
        }
        if (saved > 0) invalidate()
        return saved
    }

    /**
     * Brings the native coin on one chain to the provider's [balance] when the transfer list misses movements: payments
     * made by contracts on chains where none are listed, fees of unlisted transactions, or staking withdrawals. The
     * adjustments are derived again on every call and replace the previous ones:
     * - wherever the running balance would fall below zero, the missing amount is an inflow 1 ms before that activity,
     *   the latest moment it can have arrived;
     * - the remaining difference follows 1 ms after the chain's last activity.
     * Returns the number of adjustments.
     */
    fun reconcileNativeBalance(
        account: AccountId,
        address: String,
        chainId: Long,
        balance: io.valroot.core.model.ExactInteger,
    ): Int {
        require(consent()) { "EVM consent required" }
        val native = EvmAssets.native(chainId)
        val prefix = "evm-adjust:$chainId:$address:"
        val zero = io.valroot.core.model.ExactInteger.ZERO
        val history =
            ledger()
                .activities
                .map { it.activity }
                .filter { it.status == ActivityStatus.CONFIRMED && !it.id.value.startsWith(prefix) }
                .mapNotNull { activity ->
                    activity.movements
                        .filter { it.accountId == account && it.amount.assetId == native }
                        .takeIf { it.isNotEmpty() }
                        ?.fold(zero) { sum, movement -> sum + movement.amount.atomicUnits }
                        ?.let { activity.occurredAtEpochMillis to it }
                }.sortedBy { it.first }
        val adjustments = mutableListOf<Pair<Long, io.valroot.core.model.ExactInteger>>()
        var running = zero
        history.forEach { (at, change) ->
            running += change
            if (running.sign < 0) {
                adjustments += (at - 1) to -running
                running = zero
            }
        }
        val rest = balance - running
        if (rest.sign != 0) adjustments += ((history.lastOrNull()?.first ?: now()) + 1) to rest
        val wanted =
            adjustments.mapIndexed { index, (at, amount) ->
                val id = "$prefix$at:$index"
                Activity(
                    ActivityId(id),
                    at,
                    "evm-adjust:$chainId:${at.toString().padStart(20, '0')}:$index",
                    ActivityKind.OTHER,
                    ActivityStatus.CONFIRMED,
                    listOf(
                        io.valroot.core.ledger.Movement(
                            io.valroot.core.model
                                .EventId("$id:native"),
                            account,
                            io.valroot.core.model
                                .AtomicAmount(native, amount),
                            io.valroot.core.ledger.MovementRole.TRANSFER,
                        ),
                    ),
                    emptySet(),
                    null,
                    manual = true,
                ) to "evm-adjust-1|$chainId|$address|$at|$amount"
            }
        var changed = 0
        db.transaction {
            val wantedIds = wanted.map { it.first.id }.toSet()
            activities
                .current()
                .filter { it.id.value.startsWith(prefix) && it.id !in wantedIds && it.status == ActivityStatus.CONFIRMED }
                .forEach {
                    activities.saveInTransaction(it.copy(status = ActivityStatus.SUPERSEDED))
                    changed++
                }
            wanted.forEach { (activity, payload) ->
                val source =
                    store.ingestInTransaction(
                        io.valroot.core.storage.RawObservation(
                            activity.id.value,
                            "eip155:$chainId",
                            activity.id.value.removePrefix("evm-adjust:"),
                            "eip155:$chainId",
                            "alchemy",
                            Instant.fromEpochMilliseconds(now()).toString(),
                            payload,
                            io.valroot.core.evm.EvmDecoder.VERSION,
                            sha256(payload),
                            "address=$address",
                        ),
                    )
                val sourced =
                    activity.copy(
                        sources = setOf(source),
                        decoderVersion = io.valroot.core.evm.EvmDecoder.VERSION,
                        manual = false,
                    )
                if (activities.currentById(sourced.id) != sourced) {
                    activities.saveInTransaction(sourced)
                    changed++
                }
            }
        }
        if (changed > 0) invalidate()
        return wanted.size
    }

    /**
     * Compares this address's ledger balances on one chain with the provider's: the native balance and every token
     * balance it lists. Returns and records the differences, or null when everything matches.
     */
    fun checkBalances(
        account: AccountId,
        address: String,
        chainId: Long,
        native: io.valroot.core.model.ExactInteger,
        tokens: List<io.valroot.core.evm.EvmTokenBalance>,
    ): String? {
        val hidden = app.assets.hiddenAmong(app.assets.held())
        val ledger =
            holdings
                .current(BalanceCoverage.UNKNOWN, now())
                .quantities
                .filterKeys { it.account == account && EvmAssets.chainOf(it.asset) == chainId }
                .mapKeys { it.key.asset }
        val provider =
            mapOf(EvmAssets.native(chainId) to native) +
                tokens.filter { it.standard != EvmTokenStandard.NATIVE }.associate { token ->
                    val asset =
                        if (token.standard == EvmTokenStandard.ERC20) {
                            EvmAssets.token(chainId, token.contract)
                        } else {
                            EvmAssets.nft(chainId, token.contract, token.tokenId ?: io.valroot.core.model.ExactInteger.ZERO)
                        }
                    asset to token.value
                }
        val differences =
            (ledger.keys + provider.keys)
                .sortedBy { it.value }
                .mapNotNull { asset ->
                    val mine = ledger[asset] ?: io.valroot.core.model.ExactInteger.ZERO
                    val theirs = provider[asset] ?: io.valroot.core.model.ExactInteger.ZERO
                    // Only the native coin and listed tokens count; hidden assets (mostly spam, whose events need not add up)
                    // are not compared.
                    val compared = asset !in hidden && (asset in provider || asset.value.endsWith("/native"))
                    if (mine == theirs || !compared) null else "${asset.value}: ledger $mine, provider $theirs"
                }.joinToString("; ")
                .ifEmpty { null }
        evmChains.recordBalanceDifference(address, chainId, differences)
        return differences
    }

    /** Payments, transactions and token listings recovered beyond Alchemy's lists; see [EvmConnector]. */
    fun recovered(): EvmRecoveredRepository = evmRecovered
}
