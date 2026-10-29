package io.polion.core.application

import io.polion.core.accounting.AccountingReport
import io.polion.core.accounting.FifoAccounting
import io.polion.core.accounting.LedgerAccounting
import io.polion.core.accounting.QuoteValue
import io.polion.core.accounting.Treatment
import io.polion.core.accounting.TreatmentRepository
import io.polion.core.backup.BackupEncryption
import io.polion.core.backup.PortableBackup
import io.polion.core.bitcoin.BITCOIN_ASSET
import io.polion.core.bitcoin.BITCOIN_CONNECTOR_ID
import io.polion.core.bitcoin.BITCOIN_MAINNET
import io.polion.core.bitcoin.BitcoinProviderException
import io.polion.core.bitcoin.BitcoinProviderFailure
import io.polion.core.bitcoin.BitcoinScanAddress
import io.polion.core.bitcoin.BitcoinScanSession
import io.polion.core.bitcoin.BitcoinScanStage
import io.polion.core.bitcoin.BitcoinUtxoGraph
import io.polion.core.bitcoin.EsploraHistoryFetcher
import io.polion.core.bitcoin.EsploraTransport
import io.polion.core.bitcoin.EsploraUtxoFetcher
import io.polion.core.bitcoin.bitcoinMainnetAddress
import io.polion.core.bitcoin.bitcoinWallet
import io.polion.core.bitcoin.compareUtxoSets
import io.polion.core.connectors.Capability
import io.polion.core.connectors.ConnectorRegistry
import io.polion.core.connectors.Coverage
import io.polion.core.connectors.HistoryFetcher
import io.polion.core.connectors.HistoryPage
import io.polion.core.connectors.HistoryRequest
import io.polion.core.connectors.IntegrationManifest
import io.polion.core.connectors.RegisteredConnector
import io.polion.core.holdings.BalanceCoverage
import io.polion.core.holdings.BalanceView
import io.polion.core.holdings.HoldingsRepository
import io.polion.core.ledger.Activity
import io.polion.core.ledger.ActivityKind
import io.polion.core.ledger.ActivityRepository
import io.polion.core.market.AssetDefinition
import io.polion.core.market.AssetMetadata
import io.polion.core.market.CoingeckoHistoricalResult
import io.polion.core.market.CoingeckoPriceClient
import io.polion.core.market.CoingeckoPrices
import io.polion.core.market.CoingeckoProviderException
import io.polion.core.market.CoingeckoProviderFailure
import io.polion.core.market.CoingeckoTransport
import io.polion.core.market.MarketRepository
import io.polion.core.market.PriceObservation
import io.polion.core.market.PricePolicy
import io.polion.core.market.PriceStatus
import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.ExactDecimal
import io.polion.core.model.ExactInteger
import io.polion.core.model.PortfolioId
import io.polion.core.model.ProviderId
import io.polion.core.model.Rational
import io.polion.core.model.SourceId
import io.polion.core.model.SyncJobId
import io.polion.core.model.TrackingTargetId
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import io.polion.core.ownership.Owner
import io.polion.core.ownership.Portfolio
import io.polion.core.ownership.TargetIdentity
import io.polion.core.ownership.TargetRole
import io.polion.core.ownership.TrackingTarget
import io.polion.core.reconcile.ActivityOverlay
import io.polion.core.reconcile.ClassificationRule
import io.polion.core.reconcile.DuplicateConflict
import io.polion.core.reconcile.EffectiveActivity
import io.polion.core.reconcile.EffectiveLedger
import io.polion.core.reconcile.LinkState
import io.polion.core.reconcile.OverlayRepository
import io.polion.core.reconcile.Reconciliation
import io.polion.core.reconcile.ReconciliationCandidate
import io.polion.core.storage.OwnershipRepository
import io.polion.core.storage.OwnershipSnapshot
import io.polion.core.storage.SqliteDatabase
import io.polion.core.storage.Store
import io.polion.core.storage.sha256
import io.polion.core.sync.JobState
import io.polion.core.sync.SyncClock
import io.polion.core.sync.SyncJob
import io.polion.core.sync.SyncOrchestrator
import io.polion.core.sync.SyncProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

data class ActivityFilter(
    val search: String = "",
    val portfolio: PortfolioId? = null,
    val account: AccountId? = null,
    val asset: AssetId? = null,
    val kind: ActivityKind? = null,
    val offset: Int = 0,
    val limit: Int = 50,
) {
    init {
        require(offset >= 0 && limit in 1..500)
    }
}

data class AssetValuation(
    val asset: AssetId,
    val amount: AtomicAmount,
    val metadata: AssetMetadata?,
    val priceStatus: PriceStatus,
    val value: ExactDecimal?,
    val price: PriceObservation?,
)

data class HistoryPoint(
    val atMillis: Long,
    val valuations: List<AssetValuation>,
)

data class AccountAnalytics(
    val account: AccountId,
    val label: String,
    val valuations: List<AssetValuation>,
)

data class CategoryAnalytics(
    val kind: ActivityKind,
    val asset: AssetId,
    val incoming: io.polion.core.model.ExactInteger,
    val outgoing: io.polion.core.model.ExactInteger,
    val activities: Int,
)

data class AnalyticsSnapshot(
    val atMillis: Long,
    val currency: AssetId,
    val allocation: List<AssetValuation>,
    val accounts: List<AccountAnalytics>,
    val categories: List<CategoryAnalytics>,
    val coverage: BalanceCoverage,
)

data class AccountingView(
    val report: AccountingReport,
    val missingTreatments: List<ActivityId>,
    val invalidTreatments: List<ActivityId>,
    val orderingUncertain: List<ActivityId>,
)

data class SourceStatus(
    val lastSuccessfulRefresh: Long?,
    val lastError: String?,
    val interrupted: Boolean,
    val utxoMismatchCount: Int = 0,
)

data class BitcoinScanProgress(
    val addressIndex: Int,
    val totalAddresses: Int,
    val pages: Int,
    val transactions: Int,
    val phase: String = "history",
    val blocksResolved: Int = 0,
    val totalBlocks: Int = 0,
)

data class BitcoinWalletDiscovery(
    val addresses: Int,
    val usedAddresses: Int,
)

data class PortfolioSnapshot(
    val ownership: OwnershipSnapshot,
    val balances: BalanceView,
    val valuations: List<AssetValuation>,
    val activities: List<EffectiveActivity>,
    val activityTotal: Int,
    val currency: AssetId,
    val coverage: BalanceCoverage,
    val duplicateConflicts: List<DuplicateConflict>,
    val sourceStatus: SourceStatus,
    val sampleData: Boolean,
    val pendingCount: Int,
    val sourceEndpoint: String,
    val sourceConsent: Boolean,
)

/** Shared persisted workflows. A source connector is required before coverage can be complete. */
class PortfolioApplication(
    private val db: SqliteDatabase,
    private val now: () -> Long,
) {
    private val store = Store(db)
    private val ownership = OwnershipRepository(db)
    private val activities = ActivityRepository(db)
    private val overlays = OverlayRepository(db)
    private val holdings = HoldingsRepository(db)
    private val market = MarketRepository(db)
    private val treatments = TreatmentRepository(db)
    private val bitcoinStage = BitcoinScanStage(db, store, activities)
    private val bitcoinGraph =
        io.polion.core.bitcoin
            .BitcoinGraphRepository(db)
    private var cachedLedger: EffectiveLedger? = null
    private var holdingsCurrent = false

    private fun ledger(): EffectiveLedger = cachedLedger ?: overlays.replayForLedger(activities.current()).also { cachedLedger = it }

    private fun invalidate() {
        cachedLedger = null
        holdingsCurrent = false
    }

    fun reload() = invalidate()

    private fun fetcher(block: (HistoryRequest) -> HistoryPage): HistoryFetcher =
        object : HistoryFetcher {
            override fun fetch(request: HistoryRequest): HistoryPage = block(request)
        }

    init {
        db.execute("CREATE TABLE IF NOT EXISTS application_settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        sync(fetcher { error("No transport configured") })
    }

    fun snapshot(filter: ActivityFilter = ActivityFilter()): PortfolioSnapshot {
        ensureDefaultAssets()
        val at = now()
        val catalog = ownership.load()
        val selected = scopedAccounts(catalog, filter.portfolio)
        val ledger = ledger()
        if (!holdingsCurrent) {
            holdings.refresh(ledger.activities.map { it.activity })
            holdingsCurrent = true
        }
        val coverage = if (setting("source_last_success") != null) BalanceCoverage.PARTIAL else BalanceCoverage.UNKNOWN
        val fullBalance = holdings.current(coverage, at)
        val balance = fullBalance.copy(quantities = fullBalance.quantities.filterKeys { it.account in selected })
        val currency = filter.portfolio?.let { id -> AssetId(catalog.portfolios.single { it.id == id }.valuationCurrency) } ?: currency()
        val filtered =
            ledger.activities
                .filter { item ->
                    val activity = item.activity
                    activity.movements.any { it.accountId in selected } &&
                        (filter.account == null || activity.movements.any { it.accountId == filter.account && it.accountId in selected }) &&
                        (
                            filter.asset == null ||
                                activity.movements.any {
                                    it.amount.assetId == filter.asset && it.accountId in selected
                                }
                        ) &&
                        (filter.kind == null || activity.kind == filter.kind) &&
                        (
                            filter.search.isBlank() ||
                                listOfNotNull(item.label, item.note, activity.id.value, activity.kind.name)
                                    .any { it.contains(filter.search, ignoreCase = true) }
                        )
                }.sortedWith(
                    compareByDescending<EffectiveActivity> { it.activity.status == io.polion.core.ledger.ActivityStatus.PENDING }
                        .thenByDescending { it.activity.occurredAtEpochMillis }
                        .thenBy { it.activity.id.value },
                )
        return PortfolioSnapshot(
            catalog,
            balance,
            valuations(balance, currency, at),
            filtered.drop(filter.offset).take(filter.limit),
            filtered.size,
            currency,
            coverage,
            ledger.conflicts,
            SourceStatus(
                setting("source_last_success")?.toLong(),
                setting("source_error"),
                setting("source_in_progress") == "true",
                setting("utxo_mismatch_count")?.toIntOrNull() ?: 0,
            ),
            setting("sample_data") == "true",
            ledger.activities.count {
                it.activity.status == io.polion.core.ledger.ActivityStatus.PENDING &&
                    it.activity.movements.any { movement -> movement.accountId in selected }
            },
            sourceEndpoint(),
            sourceConsent(),
        )
    }

    private fun scopedAccounts(
        catalog: OwnershipSnapshot,
        portfolioId: PortfolioId?,
    ): Set<AccountId> {
        if (portfolioId == null) {
            return catalog.accounts
                .filter { "polion:archived" !in it.tags }
                .map { it.id }
                .toSet()
        }
        val portfolio = requireNotNull(catalog.portfolios.firstOrNull { it.id == portfolioId }) { "Unknown portfolio" }
        val byId = catalog.accounts.associateBy { it.id }
        return catalog.accounts
            .filter { account ->
                var cursor: AccountId? = account.id
                var within = portfolio.accountIds.isEmpty()
                while (cursor != null) {
                    if (cursor in portfolio.accountIds) within = true
                    cursor = byId[cursor]?.parentId
                }
                "polion:archived" !in account.tags && within && (portfolio.ownerIds.isEmpty() || account.ownerId in portfolio.ownerIds)
            }.map { it.id }
            .toSet()
    }

    private fun portfolioCurrency(portfolio: PortfolioId?): AssetId =
        portfolio?.let { id ->
            AssetId(
                ownership
                    .load()
                    .portfolios
                    .single { it.id == id }
                    .valuationCurrency,
            )
        } ?: currency()

    fun history(
        atMillis: Long,
        portfolio: PortfolioId? = null,
    ): List<AssetValuation> {
        val selected = scopedAccounts(ownership.load(), portfolio)
        val balance = holdings.historical(ledger().activities.map { it.activity }, atMillis, BalanceCoverage.UNKNOWN)
        return valuations(
            balance.copy(quantities = balance.quantities.filterKeys { it.account in selected }),
            portfolioCurrency(portfolio),
            now(),
        )
    }

    fun historySeries(
        times: List<Long>,
        portfolio: PortfolioId? = null,
    ): List<HistoryPoint> {
        val selected = scopedAccounts(ownership.load(), portfolio)
        val entries = ledger().activities.map { it.activity }
        val quote = portfolioCurrency(portfolio)
        val knownAt = now()
        return times.map { at ->
            val balance = holdings.historical(entries, at, BalanceCoverage.UNKNOWN)
            HistoryPoint(
                at,
                valuations(balance.copy(quantities = balance.quantities.filterKeys { it.account in selected }), quote, knownAt),
            )
        }
    }

    fun treatments(activityId: ActivityId): List<Treatment> =
        try {
            treatments.load(activityId)
        } catch (_: IllegalArgumentException) {
            emptyList()
        }

    fun analytics(
        atMillis: Long,
        portfolio: PortfolioId? = null,
    ): AnalyticsSnapshot {
        ensureDefaultAssets()
        val catalog = ownership.load()
        val selected = scopedAccounts(catalog, portfolio)
        val quote = portfolioCurrency(portfolio)
        val knownAt = now()
        val entries = ledger().activities.map { it.activity }
        val balance = holdings.historical(entries, atMillis, BalanceCoverage.UNKNOWN)
        val scoped = balance.copy(quantities = balance.quantities.filterKeys { it.account in selected })
        val accounts =
            catalog.accounts
                .filter { it.id in selected }
                .map { account ->
                    val view = scoped.copy(quantities = scoped.quantities.filterKeys { it.account == account.id })
                    AccountAnalytics(account.id, account.label, valuations(view, quote, knownAt))
                }.filter { it.valuations.isNotEmpty() }
        val rows =
            entries
                .filter { it.status == io.polion.core.ledger.ActivityStatus.CONFIRMED && it.occurredAtEpochMillis <= atMillis }
                .flatMap { activity -> activity.movements.filter { it.accountId in selected }.map { activity to it } }
        val categories =
            rows
                .groupBy { it.first.kind to it.second.amount.assetId }
                .map { (key, group) ->
                    val incoming =
                        group.filter { it.second.amount.atomicUnits.sign > 0 }.fold(io.polion.core.model.ExactInteger.ZERO) { sum, row ->
                            sum +
                                row.second.amount.atomicUnits
                        }
                    val outgoing =
                        group.filter { it.second.amount.atomicUnits.sign < 0 }.fold(io.polion.core.model.ExactInteger.ZERO) { sum, row ->
                            sum -
                                row.second.amount.atomicUnits
                        }
                    CategoryAnalytics(key.first, key.second, incoming, outgoing, group.map { it.first.id }.distinct().size)
                }.sortedWith(compareBy({ it.kind.name }, { it.asset.value }))
        return AnalyticsSnapshot(atMillis, quote, valuations(scoped, quote, knownAt), accounts, categories, BalanceCoverage.UNKNOWN)
    }

    fun saveTreatments(
        activityId: ActivityId,
        values: List<Treatment>,
    ) {
        val activity =
            ledger().activities.firstOrNull { it.activity.id == activityId }?.activity
                ?: throw IllegalArgumentException("Unknown activity")
        LedgerAccounting.actions(activity, values)
        treatments.save(activityId, values)
    }

    fun accountingReport(portfolio: PortfolioId? = null): AccountingView {
        ensureDefaultAssets()
        val catalog = ownership.load()
        val selected = scopedAccounts(catalog, portfolio)
        val missing = mutableListOf<ActivityId>()
        val invalid = mutableListOf<ActivityId>()
        val actions = mutableListOf<io.polion.core.accounting.AccountingAction>()
        val quote = portfolioCurrency(portfolio)
        val incompleteAssets = mutableSetOf<AssetId>()
        ledger().activities.forEach { entry ->
            val activity = entry.activity
            if (activity.status != io.polion.core.ledger.ActivityStatus.CONFIRMED) return@forEach
            try {
                val saved = treatments.load(activity.id)
                if (saved.isEmpty()) {
                    if (activity.movements.any { it.accountId in selected }) missing.add(activity.id)
                    incompleteAssets.addAll(activity.movements.map { it.amount.assetId })
                } else {
                    val computed = LedgerAccounting.actions(activity, saved)
                    val quotes =
                        computed.mapNotNull { action ->
                            when (action) {
                                is io.polion.core.accounting.AccountingAction.Acquisition -> action.totalCost
                                is io.polion.core.accounting.AccountingAction.Disposal -> action.proceeds
                                is io.polion.core.accounting.AccountingAction.Transfer -> null
                            }
                        }
                    require(quotes.all { it.asset == quote })
                    actions.addAll(computed)
                }
            } catch (_: IllegalArgumentException) {
                if (activity.movements.any { it.accountId in selected }) invalid.add(activity.id)
                incompleteAssets.addAll(activity.movements.map { it.amount.assetId })
            }
        }
        val confirmed = ledger().activities.filter { it.activity.status == io.polion.core.ledger.ActivityStatus.CONFIRMED }
        val bitcoin = confirmed.filter { !it.original.manual && it.original.decoderVersion?.startsWith("bitcoin-") == true }
        val bitcoinAssets = bitcoin.flatMap { it.activity.movements.map { movement -> movement.amount.assetId } }.toSet()
        val mixed =
            confirmed.filter { entry ->
                entry !in bitcoin && entry.activity.movements.any { it.amount.assetId in bitcoinAssets }
            }
        val unverified = bitcoin.filter { !it.original.orderingKey.startsWith("bitcoin-chain:") }
        val uncertain = (unverified + if (mixed.isNotEmpty()) bitcoin + mixed else emptyList()).distinct()
        val orderingUncertain =
            uncertain
                .filter { entry ->
                    entry.activity.movements.any { it.accountId in selected }
                }.map { it.activity.id }
        // Chain positions establish relative order only among Bitcoin activities. Mixed sources
        // use the timestamp fallback and remain uncertain until cross-source order is explicit.
        val chainOrder = bitcoin.associate { it.activity.id to it.original.orderingKey }
        val replayOrder =
            if (bitcoin.isNotEmpty() && unverified.isEmpty() && mixed.isEmpty()) {
                compareBy<io.polion.core.accounting.AccountingAction>(
                    { chainOrder[it.activityId] ?: "" },
                    { it.order },
                    { it.activityId.value },
                )
            } else {
                compareBy({ it.at }, { it.order }, { it.activityId.value })
            }
        val priceAt = now()
        val unitPrices =
            confirmed
                .flatMap { entry -> entry.activity.movements.map { it.amount.assetId } }
                .toSet()
                .associateWith { unitPrice(it, quote, priceAt) }
        val calculated =
            FifoAccounting.calculate(
                actions.filter { action ->
                    val asset =
                        when (action) {
                            is io.polion.core.accounting.AccountingAction.Acquisition -> action.asset
                            is io.polion.core.accounting.AccountingAction.Disposal -> action.asset
                            is io.polion.core.accounting.AccountingAction.Transfer -> action.asset
                        }
                    asset !in incompleteAssets
                },
                catalog.accounts.associate { it.id to it.ownerId },
                quote,
                unitPrices = unitPrices,
                replayOrder = replayOrder,
            )
        val uncertainAssets = uncertain.flatMap { it.activity.movements.map { movement -> movement.amount.assetId } }.toSet()
        val report =
            if (uncertain.isEmpty()) {
                calculated
            } else {
                calculated.copy(
                    lots = calculated.lots.map { if (it.asset in uncertainAssets) it.copy(basis = null) else it },
                    disposals =
                        calculated.disposals.map { disposal ->
                            if (disposal.asset in
                                uncertainAssets
                            ) {
                                disposal.copy(
                                    basis = null,
                                    gain = null,
                                    consumed = disposal.consumed.map { it.copy(lotId = null, basis = null) },
                                )
                            } else {
                                disposal
                            }
                        },
                    valuations =
                        calculated.valuations.map {
                            if (it.lot.asset in
                                uncertainAssets
                            ) {
                                it.copy(lot = it.lot.copy(basis = null), unrealizedGain = null)
                            } else {
                                it
                            }
                        },
                )
            }
        return AccountingView(
            report.copy(
                lots = report.lots.filter { it.account in selected },
                disposals = report.disposals.filter { it.account in selected },
                valuations = report.valuations.filter { it.lot.account in selected },
            ),
            missing,
            invalid,
            orderingUncertain,
        )
    }

    fun reconciliationCandidates(windowMillis: Long): List<ReconciliationCandidate> {
        val reviewedPairs = overlays.decisions().map { it.first to it.second }.toSet()
        val pending =
            Reconciliation
                .suggest(ledger().activities.map { it.activity }, windowMillis)
                .filterNot { (it.first to it.second) in reviewedPairs }
        val counts = pending.flatMap { listOf(it.first, it.second) }.groupingBy { it }.eachCount()
        return pending.map {
            it.copy(ambiguous = it.matchedMovements > 1 || counts.getValue(it.first) > 1 || counts.getValue(it.second) > 1)
        }
    }

    private fun sync(
        fetcher: HistoryFetcher,
        source: SourceId = SourceId("esplora"),
    ): SyncOrchestrator =
        SyncOrchestrator(
            db,
            store,
            listOf(SyncProvider(ProviderId(source.value), fetcher, setOf(BITCOIN_MAINNET), setOf(source))),
            SyncClock { now() },
        )

    /** Fetches tracked mainnet addresses after explicit consent and applies only a complete, consistent scan. */
    @OptIn(ExperimentalTime::class)
    fun refreshBitcoin(
        transport: EsploraTransport,
        verifyBlockPositions: Boolean = false,
        onProgress: (BitcoinScanProgress) -> Unit = {},
    ): List<JobState> {
        require(sourceConsent()) { "Source consent required" }
        putSetting("source_in_progress", "true")
        return try {
            refreshBitcoinScan(transport, verifyBlockPositions, onProgress)
        } catch (failure: Exception) {
            putSetting(
                "source_error",
                (failure as? BitcoinProviderException)?.reason?.userMessage
                    ?: "Could not finish sync. Check storage and provider, then retry.",
            )
            throw failure
        } finally {
            db.execute("DELETE FROM application_settings WHERE key='source_in_progress'")
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun refreshBitcoinScan(
        transport: EsploraTransport,
        verifyBlockPositions: Boolean,
        onProgress: (BitcoinScanProgress) -> Unit,
    ): List<JobState> {
        require(sourceConsent()) { "Source consent required" }
        val bitcoinTargets =
            ownership.load().targets.filter {
                it.role == io.polion.core.ownership.TargetRole.LEAF &&
                    it.identity.networkId == BITCOIN_MAINNET &&
                    it.identity.kind == "address"
            }
        val targets = bitcoinTargets.filter { it.enabled }
        val ownedClaims = bitcoinTargets.groupBy { bitcoinMainnetAddress(it.identity.canonicalKey) }
        require(
            ownedClaims.values.all { group ->
                group.map { it.accountId }.distinct().size == 1
            },
        ) { "Address assigned to multiple accounts" }
        val ownedAddresses = ownedClaims.mapValues { it.value.first().accountId }
        val reconciledAccounts = bitcoinTargets.groupBy { it.accountId }.filterValues { group -> group.all { it.enabled } }.keys
        val claims = targets.groupBy { bitcoinMainnetAddress(it.identity.canonicalKey) }
        require(claims.values.all { group -> group.map { it.accountId }.distinct().size == 1 }) { "Address assigned to multiple accounts" }
        val addresses = claims.mapValues { it.value.first().accountId }
        if (addresses.isEmpty()) {
            bitcoinStage.clear()
            return emptyList()
        }
        if (market.asset(BITCOIN_ASSET) == null) {
            registerAsset(
                AssetDefinition(
                    BITCOIN_ASSET,
                    io.polion.core.market
                        .AssetIdentity(io.polion.core.market.AssetKind.NATIVE, BITCOIN_MAINNET, "BTC"),
                ),
                AssetMetadata("BTC", "Bitcoin", 8, 0),
            )
        }
        val startTip = transport.get("/blocks/tip/hash").trim()
        require(startTip.matches(Regex("[0-9a-f]{64}"))) { "Invalid chain tip" }
        val anchor = Json.parseToJsonElement(transport.get("/block/$startTip")).jsonObject
        require(anchor.getValue("id").jsonPrimitive.content == startTip) { "Invalid chain anchor" }
        val anchorHeight = anchor.getValue("height").jsonPrimitive.long
        require(anchorHeight >= 0) { "Invalid chain anchor height" }

        fun summary(address: String): Pair<Long, Long> {
            val json = Json.parseToJsonElement(transport.get("/address/$address")).jsonObject
            val chain =
                json
                    .getValue("chain_stats")
                    .jsonObject
                    .getValue("tx_count")
                    .jsonPrimitive.long
            val mempool =
                json
                    .getValue("mempool_stats")
                    .jsonObject
                    .getValue("tx_count")
                    .jsonPrimitive.long
            require(chain >= 0 && mempool >= 0)
            return chain to mempool
        }
        val providerSource = SourceId("esplora:" + sha256(sourceEndpoint()))
        putSetting("provider_endpoint:${providerSource.value}", sourceEndpoint())
        val fingerprint =
            sha256(
                "${providerSource.value}|" + addresses.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value.value}" },
            )
        val acceptedAnchors = bitcoinGraph.anchors(addresses)
        val previousCheckpoint = acceptedAnchors.maxByOrNull { it.height }
        if (previousCheckpoint != null) {
            require(previousCheckpoint.height <= anchorHeight) {
                throw BitcoinProviderException(BitcoinProviderFailure.LAGGING_CHAIN)
            }
            val canonical = transport.get("/block-height/${previousCheckpoint.height}").trim()
            require(canonical.matches(Regex("[0-9a-f]{64}"))) { "Invalid checkpoint response" }
            if (canonical != previousCheckpoint.hash) {
                val continuity =
                    try {
                        io.polion.core.bitcoin
                            .BitcoinReorgDetector(transport)
                            .check(acceptedAnchors, anchorHeight)
                    } catch (_: io.polion.core.bitcoin.BitcoinReorgBeyondBoundException) {
                        throw BitcoinProviderException(BitcoinProviderFailure.REORG_RECOVERY_REQUIRED)
                    }
                require(continuity.aboveTip.isEmpty() && continuity.commonAncestor != null) {
                    throw BitcoinProviderException(BitcoinProviderFailure.REORG_RECOVERY_REQUIRED)
                }
                bitcoinStage.clear()
            }
        }
        val pending = bitcoinStage.session()
        val resumable = pending != null && pending.fingerprint == fingerprint && pending.anchorHeight <= anchorHeight
        if (!resumable) {
            bitcoinStage.createSession(
                BitcoinScanSession(
                    (pending?.generation ?: 0L) + 1,
                    fingerprint,
                    startTip,
                    anchorHeight,
                ),
                addresses.map { (address, account) -> BitcoinScanAddress(address, account, null) },
            )
            db.execute("DELETE FROM sync_jobs WHERE source='esplora' OR source LIKE 'esplora:%'")
        }
        val session = requireNotNull(bitcoinStage.session())
        val scanned = bitcoinStage.scanAddresses()
        var providerFailure: BitcoinProviderException? = null
        var integrityFailure: BitcoinProviderException? = null
        val recordingTransport =
            EsploraTransport { path ->
                try {
                    transport.get(path).also { contents ->
                        if (path.endsWith("/txs/mempool")) {
                            bitcoinStage.recordMempool(path.removePrefix("/address/").removeSuffix("/txs/mempool"), sha256(contents))
                        }
                    }
                } catch (
                    failure: BitcoinProviderException,
                ) {
                    providerFailure = failure
                    throw failure
                }
            }
        val result = mutableListOf<JobState>()
        var pagesFetched = 0
        var transactionsFetched = 0
        val historyFetcher =
            ConnectorRegistry(
                listOf(
                    RegisteredConnector(
                        IntegrationManifest(
                            BITCOIN_CONNECTOR_ID,
                            setOf(BITCOIN_MAINNET),
                            setOf("address"),
                            setOf(Capability.HISTORY, Capability.DECODING),
                            requiresAuthentication = false,
                            coverage = Coverage.PARTIAL,
                        ),
                        EsploraHistoryFetcher(scanned.associate { it.accountId to it.address }, recordingTransport) {
                            Instant.fromEpochMilliseconds(now()).toString()
                        },
                    ),
                ),
            ).historyFetcher(BITCOIN_MAINNET)
        for ((addressIndex, scan) in scanned.withIndex()) {
            val counter = (setting("sync_sequence")?.toLong() ?: 0L) + 1
            putSetting("sync_sequence", counter.toString())
            val jobId = SyncJobId("bitcoin-${session.generation}-${sha256(scan.address).take(16)}")
            val orchestrator =
                sync(
                    fetcher { request ->
                        try {
                            historyFetcher.fetch(request).also { page ->
                                bitcoinStage.addPage(page.observations, scan.address)
                                pagesFetched++
                                transactionsFetched += page.observations.size
                                runCatching {
                                    onProgress(BitcoinScanProgress(addressIndex + 1, scanned.size, pagesFetched, transactionsFetched))
                                }
                            }
                        } catch (failure: BitcoinProviderException) {
                            if (failure.reason == BitcoinProviderFailure.HISTORY_CHANGED ||
                                failure.reason == BitcoinProviderFailure.REORGANIZED
                            ) {
                                bitcoinStage.clear()
                                integrityFailure = failure
                            }
                            throw failure
                        }
                    },
                    providerSource,
                )
            if (orchestrator.status(jobId) == null) {
                orchestrator.enqueue(SyncJob(jobId, providerSource, BITCOIN_MAINNET, scan.accountId))
            }
            orchestrator.retry(jobId)
            while (orchestrator.runOne()) Unit
            val state = orchestrator.status(jobId)?.state ?: JobState.FAILED
            result.add(state)
            if (state != JobState.COMPLETED) break
        }
        integrityFailure?.let { throw it }
        if (result.size == scanned.size && result.all { it == JobState.COMPLETED }) {
            if (verifyBlockPositions) {
                runCatching { onProgress(BitcoinScanProgress(scanned.size, scanned.size, pagesFetched, transactionsFetched, "blocks")) }
                bitcoinStage.resolveBlocks(transport) { resolved, total ->
                    onProgress(
                        BitcoinScanProgress(scanned.size, scanned.size, pagesFetched, transactionsFetched, "blocks", resolved, total),
                    )
                }
            }
            runCatching { onProgress(BitcoinScanProgress(scanned.size, scanned.size, pagesFetched, transactionsFetched, "verify")) }
            require(
                bitcoinStage.scanAddresses().all { scan ->
                    scan.mempoolDigest != null && scan.mempoolDigest == sha256(transport.get("/address/${scan.address}/txs/mempool"))
                },
            ) { throw BitcoinProviderException(BitcoinProviderFailure.HISTORY_CHANGED) }
            require(
                scanned.all { scan -> summary(scan.address) == bitcoinStage.seenCounts(scan.address) },
            ) { throw BitcoinProviderException(BitcoinProviderFailure.HISTORY_CHANGED) }
            val provider = EsploraUtxoFetcher(transport).fetch(scanned.map { it.address })
            require(transport.get("/block-height/${session.anchorHeight}").trim() == session.anchorHash) {
                throw BitcoinProviderException(BitcoinProviderFailure.REORGANIZED)
            }
            require(transport.get("/block-height/$anchorHeight").trim() == startTip) {
                throw BitcoinProviderException(BitcoinProviderFailure.REORGANIZED)
            }
            bitcoinStage.validateBlocks(transport)
            val graph = BitcoinUtxoGraph.build(bitcoinStage.payloads(), addresses)
            require(graph.transactions.values.all { it.block == null || it.block.height <= anchorHeight }) {
                throw BitcoinProviderException(BitcoinProviderFailure.HISTORY_CHANGED)
            }
            val utxoComparison = compareUtxoSets(graph, provider)
            val mismatchCount = utxoComparison.mismatches.size + utxoComparison.missingParents.size
            runCatching { onProgress(BitcoinScanProgress(scanned.size, scanned.size, pagesFetched, transactionsFetched, "apply")) }
            require(
                transport.get("/block-height/${session.anchorHeight}").trim() == session.anchorHash &&
                    transport.get("/block-height/$anchorHeight").trim() == startTip,
            ) {
                throw BitcoinProviderException(BitcoinProviderFailure.REORGANIZED)
            }
            db.transaction {
                bitcoinGraph.saveInTransaction(
                    graph = graph,
                    providerSourceId = providerSource.value,
                    anchor =
                        io.polion.core.bitcoin
                            .BitcoinBlockAnchor(anchorHeight, startTip),
                    observedAt = Instant.fromEpochMilliseconds(now()).toString(),
                    rawChecksums = bitcoinStage.rawChecksums(),
                    comparison = utxoComparison,
                )
                bitcoinStage.applyCompleteInTransaction(
                    ownedAddresses,
                    reconciledAccounts,
                    requireBlockPositions = verifyBlockPositions,
                    graph = graph,
                )
                putSetting("utxo_mismatch_count", mismatchCount.toString())
                bitcoinStage.clearInTransaction()
                putSetting("source_last_success", now().toString())
                db.execute("DELETE FROM application_settings WHERE key='source_error'")
            }
            invalidate()
        } else {
            putSetting("source_error", providerFailure?.reason?.userMessage ?: "History is incomplete. Saved data is unchanged; retry.")
        }
        return result
    }

    /** Verify saved confirmed transactions against active block lists without refetching address history. */
    fun verifyAccountingOrder(
        transport: EsploraTransport,
        onProgress: (BitcoinScanProgress) -> Unit = {},
    ) {
        require(sourceConsent()) { "Source consent required" }
        val targets =
            ownership.load().targets.filter {
                it.role == io.polion.core.ownership.TargetRole.LEAF &&
                    it.identity.networkId == BITCOIN_MAINNET &&
                    it.identity.kind == "address"
            }
        val claims = targets.groupBy { bitcoinMainnetAddress(it.identity.canonicalKey) }
        require(claims.values.all { group -> group.map { it.accountId }.distinct().size == 1 })
        val addresses = claims.mapValues { it.value.first().accountId }
        val decoder =
            io.polion.core.bitcoin
                .BitcoinDecoder(addresses)
        val entries =
            ledger().activities.map { it.original }.filter {
                !it.manual &&
                    it.decoderVersion?.startsWith("bitcoin-") == true &&
                    it.status == io.polion.core.ledger.ActivityStatus.CONFIRMED
            }
        putSetting("source_in_progress", "true")
        try {
            bitcoinStage.clear()
            val canonicalHashes = mutableMapOf<Long, String>()
            entries.forEach { activity ->
                val rows =
                    db.query(
                        "SELECT r.id, r.namespace, r.external_id, r.network, o.source_id, o.retrieved_at, v.payload, v.payload_version, v.checksum, o.context FROM raw_records r JOIN raw_revisions v ON v.record_id=r.id JOIN raw_observations o ON o.record_id=v.record_id AND o.checksum=v.checksum WHERE r.id=? ORDER BY o.retrieved_at DESC, o.source_id DESC",
                        listOf(activity.id.value),
                    )
                val raw =
                    rows
                        .asSequence()
                        .map { row ->
                            io.polion.core.storage.RawObservation(
                                row[0]!!,
                                row[1]!!,
                                row[2]!!,
                                row[3]!!,
                                row[4]!!,
                                row[5]!!,
                                row[6]!!,
                                row[7]!!,
                                row[8]!!,
                                row[9]!!,
                            )
                        }.firstOrNull { observation ->
                            val decoded = runCatching { decoder.decode(observation).singleOrNull() }.getOrNull()
                            if (decoded == null ||
                                activity.copy(orderingKey = decoded.orderingKey, sources = decoded.sources) != decoded
                            ) {
                                false
                            } else {
                                val status =
                                    Json
                                        .parseToJsonElement(observation.payload)
                                        .jsonObject
                                        .getValue("status")
                                        .jsonObject
                                val height = status.getValue("block_height").jsonPrimitive.long
                                val hash = status.getValue("block_hash").jsonPrimitive.content
                                canonicalHashes.getOrPut(height) { transport.get("/block-height/$height").trim() } == hash
                            }
                        } ?: error("Saved Bitcoin transaction provenance is incomplete; refresh history")
                bitcoinStage.addPage(listOf(raw))
            }
            bitcoinStage.validateBlocks(transport)
            bitcoinStage.resolveBlocks(transport) { resolved, total ->
                onProgress(BitcoinScanProgress(0, 0, 0, entries.size, "blocks", resolved, total))
            }
            bitcoinStage.validateBlocks(transport)
            bitcoinStage.applyVerifiedOrder(addresses)
            invalidate()
        } finally {
            try {
                bitcoinStage.clear()
            } finally {
                db.execute("DELETE FROM application_settings WHERE key='source_in_progress'")
            }
        }
    }

    fun sourceFailureMessage(): String? = setting("source_error")

    fun priceSourceConsent(): Boolean = setting("price_source_consent") == "true"

    fun setPriceSourceConsent(accepted: Boolean) =
        db.transaction {
            putSetting("price_source_generation", priceRequestNonce())
            putSetting("price_source_consent", accepted.toString())
        }

    private fun priceRequestNonce() = "${kotlin.random.Random.nextLong()}:${kotlin.random.Random.nextLong()}"

    /** Explicit daily market observations do not classify acquisitions or establish their cost basis. */
    fun fetchHistoricalPrice(
        transport: CoingeckoTransport,
        asset: AssetId,
        quote: AssetId,
        dayUtcMillis: Long,
        authorize: () -> Unit = {},
    ): CoingeckoHistoricalResult {
        require(priceSourceConsent()) { "Price provider consent required" }
        require(asset == BITCOIN_ASSET) { "Historical provider mapping is available for Bitcoin only" }
        require(quote.value in setOf("USD", "EUR", "GBP")) { "Unsupported quote currency" }
        val observedAt = now()
        require(dayUtcMillis >= 0 && dayUtcMillis % 86_400_000L == 0L && dayUtcMillis <= observedAt) { "Choose a past or current UTC date" }
        require(dayUtcMillis >= observedAt - 365L * 86_400_000L) { "Demo historical prices cover the past 365 days" }
        ensureDefaultAssets()
        val consentGeneration = requireNotNull(setting("price_source_generation")) { "Renew price provider consent" }
        return CoingeckoPriceClient(transport, market, now).historical(
            asset,
            "bitcoin",
            quote,
            quote.value.lowercase(),
            dayUtcMillis,
            "coingecko:${quote.value}:$dayUtcMillis:${priceRequestNonce()}",
            authorize = {
                authorize()
                if (!priceSourceConsent() || setting("price_source_generation") != consentGeneration) {
                    throw CoingeckoProviderException(CoingeckoProviderFailure.CANCELLED)
                }
            },
        )
    }

    /** Saved provider quotes remain selectable offline, independently of permission for new requests. */
    private fun pricePolicy() = PricePolicy(listOf(ProviderId(CoingeckoPrices.PROVIDER_ID)), 86_400_000L)

    fun sourceConsent(): Boolean = setting("source_consent") == "true"

    fun setSourceConsent(accepted: Boolean) = putSetting("source_consent", accepted.toString())

    /** Endpoint identities survive configuration changes and portable backup/restore. */
    fun bitcoinProviderEndpoints(): Map<String, String> =
        db
            .query("SELECT key, value FROM application_settings WHERE key LIKE 'provider_endpoint:%'")
            .associate { it[0]!!.removePrefix("provider_endpoint:") to it[1]!! }

    fun sourceEndpoint(): String = setting("source_endpoint") ?: "https://blockstream.info/api"

    fun setSourceEndpoint(endpoint: String) {
        require(
            endpoint.startsWith("https://") &&
                endpoint.length <= 2048 &&
                endpoint.none { it.isWhitespace() } &&
                !endpoint.substringAfter("https://").substringBefore('/').contains('@') &&
                !endpoint.contains('?') &&
                !endpoint.contains('#'),
        )
        putSetting("source_endpoint", endpoint.trimEnd('/'))
    }

    private fun setting(key: String): String? =
        db.query("SELECT value FROM application_settings WHERE key=?", listOf(key)).singleOrNull()?.single()

    private fun putSetting(
        key: String,
        value: String,
    ) = db.execute(
        "INSERT OR REPLACE INTO application_settings VALUES (?, ?)",
        listOf(key, value),
    )

    /** Returns quote value per atomic unit only for a fresh observation; missing or stale prices stay null. */
    private fun unitPrice(
        asset: AssetId,
        quote: AssetId,
        atMillis: Long,
    ): QuoteValue? {
        val decimals = market.metadataAt(asset, atMillis)?.decimals ?: return null
        val wholeUnitPrice =
            if (asset == quote) {
                Rational.units(ExactInteger.parse("1"))
            } else {
                val result = market.lookup(asset, quote, atMillis, atMillis, pricePolicy())
                val observation = result.observation?.takeIf { result.status == PriceStatus.FRESH } ?: return null
                Rational.fromDecimal(observation.price.value)
            }
        return QuoteValue(quote, wholeUnitPrice / Rational.units(ExactInteger.parse("1").timesPowerOfTen(decimals)))
    }

    private fun valuations(
        balance: BalanceView,
        currency: AssetId,
        knownAt: Long,
    ): List<AssetValuation> =
        balance.quantities.entries
            .groupBy { it.key.asset }
            .map { (asset, rows) ->
                val units = rows.fold(io.polion.core.model.ExactInteger.ZERO) { sum, row -> sum + row.value }
                val amount = AtomicAmount(asset, units)
                val metadata = market.metadataAt(asset, balance.atMillis)
                val result =
                    if (asset ==
                        currency
                    ) {
                        null
                    } else {
                        market.lookup(asset, currency, balance.atMillis, knownAt, pricePolicy())
                    }
                val value =
                    if (asset ==
                        currency
                    ) {
                        metadata?.let { amount.decimal(it.decimals) }
                    } else {
                        metadata?.let { result?.value(amount, it.decimals) }
                    }
                AssetValuation(
                    asset,
                    amount,
                    metadata,
                    if (asset == currency &&
                        metadata != null
                    ) {
                        PriceStatus.FRESH
                    } else {
                        result?.status ?: PriceStatus.MISSING
                    },
                    value,
                    result?.observation,
                )
            }.sortedBy { it.asset.value }

    fun saveOwner(value: Owner) = updateOwnership { it.copy(owners = it.owners.filterNot { old -> old.id == value.id } + value) }

    fun saveAccount(value: Account) = updateOwnership { it.copy(accounts = it.accounts.filterNot { old -> old.id == value.id } + value) }

    /** Removes an account and descendants from active scopes while preserving their financial evidence. */
    fun archiveAccount(
        id: AccountId,
        archived: Boolean = true,
    ) {
        db.transaction {
            val catalog = ownership.loadInTransaction()
            require(catalog.accounts.any { it.id == id }) { "Unknown account" }

            fun descendants(roots: Set<AccountId>): Set<AccountId> {
                val result = roots.toMutableSet()
                do {
                    val before = result.size
                    catalog.accounts.filter { it.parentId in result }.forEach { result.add(it.id) }
                } while (result.size != before)
                return result
            }

            val prefix = "archive_root:"
            val roots =
                db
                    .query("SELECT key FROM application_settings")
                    .mapNotNull { row ->
                        row
                            .single()
                            ?.takeIf { it.startsWith(prefix) }
                            ?.removePrefix(prefix)
                            ?.let(::AccountId)
                    }.toMutableSet()
            val inherited = descendants(roots)
            roots.addAll(catalog.accounts.filter { "polion:archived" in it.tags && it.id !in inherited }.map { it.id })
            if (archived) roots.add(id) else roots.remove(id)
            roots.forEach { putSetting("$prefix${it.value}", "true") }
            if (!archived) db.execute("DELETE FROM application_settings WHERE key=?", listOf("$prefix${id.value}"))

            val effective = descendants(roots)
            catalog.accounts.forEach { account ->
                if (account.id in effective) {
                    db.execute("INSERT OR IGNORE INTO ownership_tags VALUES ('account', ?, 'polion:archived')", listOf(account.id.value))
                } else {
                    db.execute(
                        "DELETE FROM ownership_tags WHERE entity_type='account' AND entity_id=? AND tag='polion:archived'",
                        listOf(account.id.value),
                    )
                }
            }
            catalog.targets.forEach { target ->
                val key = "archived_target:${target.id.value}"
                if (target.accountId in effective) {
                    if (setting(key) == null) putSetting(key, target.enabled.toString())
                    db.execute("UPDATE targets SET enabled=0 WHERE id=?", listOf(target.id.value))
                } else if (setting(key) != null) {
                    val enabled = setting(key)?.toBooleanStrictOrNull() ?: target.enabled
                    db.execute("UPDATE targets SET enabled=? WHERE id=?", listOf(if (enabled) "1" else "0", target.id.value))
                    db.execute("DELETE FROM application_settings WHERE key=?", listOf(key))
                }
            }
            db.execute("DELETE FROM application_settings WHERE key='source_last_success'")
        }
        invalidate()
    }

    fun saveAccountAndTarget(
        account: Account,
        target: TrackingTarget,
    ) {
        require(target.accountId == account.id)
        val normalized = normalizeTarget(target)
        val catalog = ownership.load()
        require(catalog.accounts.none { it.id == account.id }) { "Account already exists" }
        require(catalog.targets.none { it.id == normalized.id }) { "Target already exists" }
        require(
            catalog.targets.filter { it.identity == normalized.identity && it.role == normalized.role }.all {
                it.accountId ==
                    normalized.accountId
            },
        ) {
            "Address assigned to multiple accounts"
        }
        ownership.replace(catalog.copy(accounts = catalog.accounts + account, targets = catalog.targets + normalized))
        db.execute("DELETE FROM application_settings WHERE key='source_last_success'")
    }

    private fun normalizeTarget(value: TrackingTarget): TrackingTarget =
        if (value.identity.networkId == BITCOIN_MAINNET && value.identity.kind == "address") {
            value.copy(identity = value.identity.copy(canonicalKey = bitcoinMainnetAddress(value.identity.canonicalKey)))
        } else if (value.identity.networkId == BITCOIN_MAINNET && value.identity.kind == "wallet") {
            require(value.role == TargetRole.WALLET && value.walletId == null) { "Invalid wallet target" }
            value.copy(identity = value.identity.copy(canonicalKey = bitcoinWallet(value.identity.canonicalKey).canonical))
        } else {
            value
        }

    /** Saves a consented public wallet and optional ownership records as one local change. */
    fun saveBitcoinWallet(
        target: TrackingTarget,
        newAccount: Account? = null,
        newOwner: Owner? = null,
        consentAccepted: Boolean = false,
    ) {
        require(target.identity.networkId == BITCOIN_MAINNET && target.identity.kind == "wallet") { "Expected a Bitcoin public wallet" }
        require(target.label.isNotBlank()) { "Enter a wallet name" }
        val normalized = normalizeTarget(target)
        db.transaction {
            require(sourceConsent() || consentAccepted) { "Source consent required" }
            val catalog = ownership.loadInTransaction()
            require(catalog.targets.none { it.id == normalized.id }) { "Target already exists" }
            require(
                catalog.targets.none { it.identity == normalized.identity && it.role == TargetRole.WALLET },
            ) { "Wallet already tracked" }
            require(newOwner == null || newAccount != null && newAccount.ownerId == newOwner.id) { "Owner requires a matching new account" }
            if (newOwner != null) {
                require(newOwner.label.isNotBlank()) { "Enter an owner name" }
                require(catalog.owners.none { it.id == newOwner.id }) { "Owner already exists" }
            }
            if (newAccount != null) {
                require(newAccount.id == normalized.accountId) { "Wallet account does not match" }
                require(newAccount.label.isNotBlank()) { "Enter an account name" }
                require(catalog.accounts.none { it.id == newAccount.id }) { "Account already exists" }
            }
            val account = requireNotNull(newAccount ?: catalog.accounts.firstOrNull { it.id == normalized.accountId }) { "Unknown account" }
            require(account.kind != AccountKind.GROUP && "polion:archived" !in account.tags) { "Select an active account" }
            val byId = catalog.accounts.associateBy { it.id }
            var parent = account.parentId
            while (parent != null) {
                val ancestor = requireNotNull(byId[parent]) { "Unknown parent account" }
                require("polion:archived" !in ancestor.tags) { "Select an active account group" }
                parent = ancestor.parentId
            }
            ownership.replaceInTransaction(
                catalog.copy(
                    owners = catalog.owners + listOfNotNull(newOwner),
                    accounts = catalog.accounts + listOfNotNull(newAccount),
                    targets = catalog.targets + normalized,
                ),
            )
            if (consentAccepted) putSetting("source_consent", "true")
            db.execute("DELETE FROM application_settings WHERE key='source_last_success'")
        }
        invalidate()
    }

    fun saveTarget(value: TrackingTarget) {
        val normalized = normalizeTarget(value)
        val catalog = ownership.load()
        val previous = catalog.targets.firstOrNull { it.id == normalized.id }
        if (previous != null &&
            (previous.identity != normalized.identity || previous.accountId != normalized.accountId || previous.role != normalized.role)
        ) {
            require(activities.current().none { !it.manual && it.decoderVersion?.startsWith("bitcoin-") == true }) {
                "Cannot reassign a tracked target with Bitcoin activity"
            }
        }
        if (previous?.role == TargetRole.WALLET && catalog.targets.any { it.walletId == previous.id }) {
            require(
                previous.identity == normalized.identity && previous.accountId == normalized.accountId && previous.role == normalized.role,
            ) {
                "Cannot change a wallet after discovery"
            }
        }
        val existing = catalog.targets.filter { it.id != normalized.id && it.identity == normalized.identity && it.role == normalized.role }
        require(existing.all { it.accountId == normalized.accountId }) { "Address assigned to multiple accounts" }
        require(normalized.role != TargetRole.WALLET || existing.isEmpty()) { "Wallet already tracked" }
        updateOwnership { snapshot ->
            snapshot.copy(
                targets =
                    snapshot.targets.filterNot { old -> old.id == normalized.id }.map { child ->
                        if (child.walletId == normalized.id) child.copy(enabled = normalized.enabled) else child
                    } + normalized,
            )
        }
        db.execute("DELETE FROM application_settings WHERE key='source_last_success'")
    }

    /** Discovers public addresses; requests disclose each derived address to the selected provider. */
    fun discoverBitcoinWallet(
        walletId: TrackingTargetId,
        transport: EsploraTransport,
        gapLimit: Int = 20,
        maxAddressesPerBranch: Int = 1000,
    ): BitcoinWalletDiscovery {
        require(sourceConsent()) { "Source consent required" }
        require(gapLimit in 1..100 && maxAddressesPerBranch in gapLimit..10000)
        val catalog = ownership.load()
        val target = requireNotNull(catalog.targets.firstOrNull { it.id == walletId }) { "Unknown wallet" }
        require(
            target.enabled &&
                target.role == TargetRole.WALLET &&
                target.identity.networkId == BITCOIN_MAINNET &&
                target.identity.kind == "wallet",
        ) { "Enabled Bitcoin wallet required" }
        val wallet = bitcoinWallet(target.identity.canonicalKey)
        val oldChildren = catalog.targets.filter { it.walletId == walletId }
        val children = mutableListOf<TrackingTarget>()
        var used = 0
        for (branch in 0 until wallet.branchCount) {
            val prefix = "bitcoin-wallet-child-" + sha256(walletId.value) + ":$branch:"
            val previousMax =
                oldChildren
                    .mapNotNull {
                        it.id.value
                            .takeIf { id -> id.startsWith(prefix) }
                            ?.removePrefix(prefix)
                            ?.toIntOrNull()
                    }.maxOrNull() ?: -1
            var gap = 0
            var index = 0
            while (index < maxAddressesPerBranch) {
                val address = wallet.address(branch, index)
                val json = Json.parseToJsonElement(transport.get("/address/$address")).jsonObject
                val chain =
                    json
                        .getValue("chain_stats")
                        .jsonObject
                        .getValue("tx_count")
                        .jsonPrimitive.long
                val mempool =
                    json
                        .getValue("mempool_stats")
                        .jsonObject
                        .getValue("tx_count")
                        .jsonPrimitive.long
                require(chain >= 0 && mempool >= 0) { "Invalid address summary" }
                val hasActivity = chain > 0 || mempool > 0
                if (hasActivity) used++
                gap = if (hasActivity) 0 else gap + 1
                val id = TrackingTargetId(prefix + index)
                val existing = catalog.targets.firstOrNull { it.id == id }
                require(existing == null || existing.walletId == walletId && existing.identity.canonicalKey == address) {
                    "Wallet child identity conflict"
                }
                require(
                    catalog.targets
                        .filter {
                            it.role == TargetRole.LEAF &&
                                it.identity.networkId == BITCOIN_MAINNET &&
                                it.identity.kind == "address" &&
                                it.identity.canonicalKey == address &&
                                it.id != id
                        }.all {
                            it.accountId == target.accountId && (it.walletId == null || it.walletId == walletId)
                        },
                ) { "Address assigned to another account or wallet" }
                children.add(
                    TrackingTarget(
                        id,
                        target.accountId,
                        TargetIdentity(BITCOIN_MAINNET, "address", address),
                        TargetRole.LEAF,
                        "${target.label} / $branch / $index",
                        enabled = true,
                        walletId = walletId,
                    ),
                )
                index++
                if (!wallet.ranged || gap >= gapLimit && index > previousMax) break
            }
            require(!wallet.ranged || gap >= gapLimit && index > previousMax) {
                "Discovery limit reached before the gap was verified; increase the limit"
            }
        }
        val childIds = children.map { it.id }.toSet()
        ownership.replace(catalog.copy(targets = catalog.targets.filterNot { it.id in childIds } + children))
        db.execute("DELETE FROM application_settings WHERE key='source_last_success'")
        return BitcoinWalletDiscovery(children.size, used)
    }

    fun savePortfolio(value: Portfolio) =
        updateOwnership {
            it.copy(
                portfolios =
                    it.portfolios.filterNot { old -> old.id == value.id } + value,
            )
        }

    private fun updateOwnership(change: (OwnershipSnapshot) -> OwnershipSnapshot) = ownership.replace(change(ownership.load()))

    fun saveManualActivity(value: Activity) {
        require(value.manual)
        val accountIds =
            ownership
                .load()
                .accounts
                .map { it.id }
                .toSet()
        require(value.movements.all { it.accountId in accountIds }) { "Unknown account" }
        activities.save(value)
        invalidate()
    }

    fun saveOverlay(value: ActivityOverlay): Int {
        require(activities.current().any { it.id == value.activityId }) { "Unknown activity" }
        return overlays.saveOverlay(value).also { invalidate() }
    }

    fun overlay(id: ActivityId): ActivityOverlay? = overlays.overlay(id)?.second

    fun rules(): List<ClassificationRule> = overlays.rules().map { it.second }

    fun saveRule(value: ClassificationRule): Int = overlays.saveRule(value).also { invalidate() }

    fun setDecision(
        first: ActivityId,
        second: ActivityId,
        state: LinkState,
    ): Int = overlays.setDecision(first, second, state)

    fun setDuplicateDecision(
        first: ActivityId,
        second: ActivityId,
        canonical: ActivityId,
        state: LinkState,
    ): Int = overlays.setDuplicateDecision(first, second, canonical, state).also { invalidate() }

    fun registerAsset(
        definition: AssetDefinition,
        metadata: AssetMetadata,
    ) = market.registerAsset(definition, metadata)

    fun recordManualPrice(value: PriceObservation) {
        ensureDefaultAssets()
        require(value.manual)
        market.recordPrice(value)
    }

    fun setCurrency(currency: AssetId) {
        ensureDefaultAssets()
        require(market.asset(currency) != null) { "Unknown currency asset" }
        db.execute(
            "INSERT OR REPLACE INTO application_settings VALUES ('currency', ?)",
            listOf(currency.value),
        )
    }

    private fun ensureDefaultAssets() {
        for ((code, name) in listOf("USD" to "US Dollar", "EUR" to "Euro", "GBP" to "Pound Sterling")) {
            val currency = AssetId(code)
            if (market.asset(currency) == null) {
                market.registerAsset(
                    AssetDefinition(
                        currency,
                        io.polion.core.market
                            .AssetIdentity(io.polion.core.market.AssetKind.FIAT, null, code),
                    ),
                    AssetMetadata(code, name, 2, 0),
                )
            }
        }
        if (market.asset(BITCOIN_ASSET) == null) {
            market.registerAsset(
                AssetDefinition(
                    BITCOIN_ASSET,
                    io.polion.core.market
                        .AssetIdentity(io.polion.core.market.AssetKind.NATIVE, BITCOIN_MAINNET, "BTC"),
                ),
                AssetMetadata("BTC", "Bitcoin", 8, 0),
            )
        }
    }

    fun currency(): AssetId =
        AssetId(
            db.query("SELECT value FROM application_settings WHERE key='currency'").singleOrNull()?.single() ?: "USD",
        )

    /** Seeds example records only when the portfolio is empty and the user explicitly requests them. */
    fun seedDemo() {
        require(ownership.load().owners.isEmpty() && activities.current().isEmpty()) { "Example data requires an empty portfolio" }
        putSetting("sample_data", "true")
        val at = now()
        val owner =
            io.polion.core.model
                .OwnerId("demo-owner")
        val account = AccountId("demo-wallet")
        val btc = AssetId("bitcoin:btc")
        val usd = AssetId("USD")
        registerAsset(
            AssetDefinition(
                btc,
                io.polion.core.market.AssetIdentity(
                    io.polion.core.market.AssetKind.NATIVE,
                    io.polion.core.model
                        .NetworkId("bitcoin-mainnet"),
                    "BTC",
                ),
            ),
            AssetMetadata("BTC", "Bitcoin", 8, 0),
        )
        registerAsset(
            AssetDefinition(
                usd,
                io.polion.core.market
                    .AssetIdentity(io.polion.core.market.AssetKind.FIAT, null, "USD"),
            ),
            AssetMetadata("USD", "US Dollar", 2, 0),
        )
        saveOwner(Owner(owner, "Example owner"))
        saveAccount(Account(account, owner, "Example wallet", io.polion.core.ownership.AccountKind.WALLET))
        savePortfolio(
            Portfolio(
                io.polion.core.model
                    .PortfolioId("demo-portfolio"),
                "Example portfolio",
                "USD",
            ),
        )
        saveManualActivity(
            Activity(
                ActivityId("demo-deposit"),
                at,
                "demo-deposit",
                ActivityKind.DEPOSIT,
                io.polion.core.ledger.ActivityStatus.CONFIRMED,
                listOf(
                    io.polion.core.ledger.Movement(
                        io.polion.core.model
                            .EventId("demo-movement"),
                        account,
                        AtomicAmount(
                            btc,
                            io.polion.core.model.ExactInteger
                                .parse("125000000"),
                        ),
                        io.polion.core.ledger.MovementRole.TRANSFER,
                    ),
                ),
                emptySet(),
                null,
                true,
            ),
        )
        val day = 86_400_000L

        fun addExample(
            id: String,
            whenAt: Long,
            units: String,
            pending: Boolean = false,
        ) {
            saveManualActivity(
                Activity(
                    ActivityId(id),
                    whenAt,
                    id,
                    if (units.startsWith('-')) ActivityKind.PAYMENT else ActivityKind.DEPOSIT,
                    if (pending) io.polion.core.ledger.ActivityStatus.PENDING else io.polion.core.ledger.ActivityStatus.CONFIRMED,
                    listOf(
                        io.polion.core.ledger.Movement(
                            io.polion.core.model
                                .EventId("$id-movement"),
                            account,
                            AtomicAmount(
                                btc,
                                io.polion.core.model.ExactInteger
                                    .parse(units),
                            ),
                            io.polion.core.ledger.MovementRole.TRANSFER,
                        ),
                    ),
                    emptySet(),
                    null,
                    true,
                ),
            )
        }
        addExample("demo-earlier", at - 60 * day, "40000000")
        addExample("demo-payment", at - 15 * day, "-15000000")
        addExample("demo-pending", at - day, "8000000", true)
        listOf(90L to "42000", 60L to "48000", 30L to "60000", 0L to "65000").forEach { (days, price) ->
            val time = at - days * day
            recordManualPrice(
                PriceObservation(
                    "demo-price-$days",
                    io.polion.core.model
                        .Price(btc, usd, ExactDecimal.parse(price)),
                    null,
                    time,
                    time,
                    true,
                ),
            )
        }
        saveTreatments(
            ActivityId("demo-earlier"),
            listOf(
                Treatment.Acquire(
                    io.polion.core.model
                        .EventId("demo-earlier-movement"),
                    io.polion.core.accounting
                        .QuoteValue(
                            usd,
                            io.polion.core.model.Rational
                                .of("19200"),
                        ),
                ),
            ),
        )
        saveTreatments(
            ActivityId("demo-payment"),
            listOf(
                Treatment.Dispose(
                    io.polion.core.model
                        .EventId("demo-payment-movement"),
                    io.polion.core.accounting
                        .QuoteValue(
                            usd,
                            io.polion.core.model.Rational
                                .of("9000"),
                        ),
                ),
            ),
        )
        saveTreatments(
            ActivityId("demo-deposit"),
            listOf(
                Treatment.Acquire(
                    io.polion.core.model
                        .EventId("demo-movement"),
                    io.polion.core.accounting
                        .QuoteValue(
                            usd,
                            io.polion.core.model.Rational
                                .of("81250"),
                        ),
                ),
            ),
        )
    }

    fun exportCsv(): String {
        fun cell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
        val lines = mutableListOf("activity_id,occurred_at,kind,status,account,asset,atomic_units,role,label,note")
        overlays.replayForLedger(activities.current()).activities.forEach { item ->
            item.activity.movements.forEach { movement ->
                lines +=
                    listOf(
                        item.activity.id.value,
                        item.activity.occurredAtEpochMillis.toString(),
                        item.activity.kind.name,
                        item.activity.status.name,
                        movement.accountId.value,
                        movement.amount.assetId.value,
                        movement.amount.atomicUnits.toString(),
                        movement.role.name,
                        item.label.orEmpty(),
                        item.note.orEmpty(),
                    ).joinToString(",", transform = ::cell)
            }
        }
        return lines.joinToString("\r\n", postfix = "\r\n")
    }

    fun exportBackup(password: CharArray): ByteArray =
        BackupEncryption.encrypt(PortableBackup.discover(db).export(db).encodeToByteArray(), password)

    fun restoreToStaging(
        staging: SqliteDatabase,
        archive: ByteArray,
        password: CharArray,
    ) {
        PortfolioApplication(staging, now)
        PortableBackup
            .discover(
                db,
            ).restoreToStaging(staging, BackupEncryption.decrypt(archive, password).decodeToString(throwOnInvalidSequence = true))
    }

    /** Erases application rows and purges deleted content from active SQLite files. */
    fun wipe() {
        db.query("PRAGMA secure_delete = ON")
        db.query("PRAGMA journal_size_limit = 0")
        db.transaction {
            val tables = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").map { it.single()!! }
            require(tables.all { it.matches(Regex("[a-z][a-z0-9_]*")) })
            db.execute("PRAGMA defer_foreign_keys = ON")
            tables.filterNot { it.endsWith("_schema") || it == "android_metadata" }.forEach { db.execute("DELETE FROM $it") }
            check(db.query("PRAGMA foreign_key_check").isEmpty())
            invalidate()
        }
        // Rebuild free pages containing data deleted before secure deletion was enabled.
        db.execute("VACUUM")
        val checkpoint = db.query("PRAGMA wal_checkpoint(TRUNCATE)").single()
        check(checkpoint[0] == "0") { "Database cleanup is busy; retry erasure" }
    }
}
