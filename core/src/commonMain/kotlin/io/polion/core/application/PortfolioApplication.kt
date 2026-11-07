package io.polion.core.application

import io.polion.core.accounting.AccountingReport
import io.polion.core.accounting.FifoAccounting
import io.polion.core.accounting.LedgerAccounting
import io.polion.core.accounting.QuoteValue
import io.polion.core.accounting.Treatment
import io.polion.core.accounting.TreatmentRepository
import io.polion.core.backup.BackupEncryption
import io.polion.core.backup.ByteArraySink
import io.polion.core.backup.ByteArraySource
import io.polion.core.backup.ByteSink
import io.polion.core.backup.ByteSource
import io.polion.core.backup.PortableBackup
import io.polion.core.bitcoin.BITCOIN_ASSET
import io.polion.core.bitcoin.BITCOIN_CONNECTOR_ID
import io.polion.core.bitcoin.BITCOIN_MAINNET
import io.polion.core.bitcoin.BitcoinProviderException
import io.polion.core.bitcoin.BitcoinProviderFailure
import io.polion.core.bitcoin.BitcoinScanAddress
import io.polion.core.bitcoin.BitcoinScanSession
import io.polion.core.bitcoin.BitcoinScanStage
import io.polion.core.bitcoin.EsploraHistoryFetcher
import io.polion.core.bitcoin.EsploraTransport
import io.polion.core.bitcoin.bitcoinMainnetAddress
import io.polion.core.bitcoin.bitcoinWallet
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
import io.polion.core.market.CoingeckoMarkets
import io.polion.core.market.CoingeckoPriceClient
import io.polion.core.market.CoingeckoPrices
import io.polion.core.market.CoingeckoProviderException
import io.polion.core.market.CoingeckoProviderFailure
import io.polion.core.market.CoingeckoTransport
import io.polion.core.market.KrakenPrices
import io.polion.core.market.KrakenRateLimitedException
import io.polion.core.market.MarketFeed
import io.polion.core.market.MarketFeedRepository
import io.polion.core.market.MarketRepository
import io.polion.core.market.PriceObservation
import io.polion.core.market.PricePolicy
import io.polion.core.market.PriceResult
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
import io.polion.core.reconcile.TransferLink
import io.polion.core.reconcile.TransferLinkConflict
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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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
    val status: io.polion.core.ledger.ActivityStatus? = null,
    val network: String? = null,
    val includeNetworkChoices: Boolean = false,
) {
    init {
        require(offset >= 0 && limit in 1..500)
        require(network == null || network.isNotBlank())
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

/** A saved market quote without an assertion of ownership or quantity. */
data class MarketCoin(
    val asset: AssetId,
    val metadata: AssetMetadata?,
    val priceStatus: PriceStatus,
    val price: PriceObservation,
)

data class MarketPricePoint(
    val atMillis: Long,
    val priceStatus: PriceStatus,
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
    /** Treatments priced in another quote currency; their cost or proceeds are unknown in the report currency. */
    val currencyMismatch: List<ActivityId> = emptyList(),
)

data class SavedObservation(
    val source: String,
    val retrievedAt: String,
    val checksum: String,
    val payloadVersion: String,
)

data class ActivitySourceEvidence(
    val id: String,
    val namespace: String?,
    val externalId: String?,
    val network: String?,
    val observations: List<SavedObservation>,
    val hasMoreObservations: Boolean,
)

data class SourceScanJob(
    val state: String,
    val pages: Int,
    val coverage: String,
    val provider: String?,
)

data class SourceAddressScan(
    val target: TrackingTarget,
    val job: SourceScanJob?,
)

data class SourceStatus(
    val lastSuccessfulRefresh: Long?,
    val lastError: String?,
    val interrupted: Boolean,
    val utxoMismatchCount: Int = 0,
    /** False when the latest accepted scan could not obtain the provider UTXO comparison. */
    val utxoComparisonAvailable: Boolean = true,
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
    /** Human-readable caveat about script type or branch coverage; null when none applies. */
    val notice: String? = null,
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
    val activityNetworks: List<String> = emptyList(),
    val marketCoins: List<MarketCoin> = emptyList(),
    /** The same balances valued in the secondary currency from Settings; null when none is chosen or it equals [currency]. */
    val secondaryCurrency: AssetId? = null,
    val secondaryValuations: List<AssetValuation> = emptyList(),
)

/** Wallet discovery refused an address because another account already tracks it. The message is safe to show. */
class BitcoinDiscoveryConflict(
    message: String,
) : IllegalArgumentException(message)

data class MarketOverview(
    val currency: AssetId,
    val consent: Boolean,
    val markets: io.polion.core.market.MarketFeed<io.polion.core.market.MarketListing>?,
    val trending: io.polion.core.market.MarketFeed<io.polion.core.market.TrendingListing>?,
    /** Name of the [CoingeckoProviderFailure] from the last refresh, or null after a success. */
    val lastFailure: String?,
    /** Epoch millis until which CoinGecko feed requests pause after a rate limit. */
    val retryAfterMillis: Long?,
    val hasApiKey: Boolean,
    /** CoinGecko coin ID to the asset ID used for favorites and coin pages. */
    val assetKeys: Map<String, AssetId>,
)

data class MarketRefreshResult(
    val updatedFeeds: Int,
    val failure: CoingeckoProviderFailure?,
    val retryAfterMillis: Long?,
)

enum class LandingPage { MAIN, PORTFOLIO, INSIGHTS }

data class GeneralPreferences(
    val landing: LandingPage = LandingPage.MAIN,
    val secondaryCurrency: AssetId? = null,
    val showPortfolioChart: Boolean = true,
    val zeroBasisRewards: Boolean = false,
    /** Null means the host's own device name. */
    val deviceName: String? = null,
    /** Balances, quantities and other absolute amounts show as asterisks; percentages and charts stay visible. */
    val hideAmounts: Boolean = false,
    /** Charts draw candles instead of a line. */
    val candleCharts: Boolean = false,
)

/** Privacy lock delays offered in Settings: immediately, 1, 5 and 15 minutes, and 1 hour. */
val LOCK_DELAYS = listOf(0L, 60_000L, 300_000L, 900_000L, 3_600_000L)

/** Fiat currencies with automatic Kraken prices. */
val DISPLAY_CURRENCIES = listOf("USD", "EUR", "GBP")

/** Shared persisted workflows. A source connector is required before coverage can be complete. */
class PortfolioApplication(
    private val db: SqliteDatabase,
    private val now: () -> Long,
) {
    private val store = Store(db)
    private val ownership = OwnershipRepository(db)
    private val activities = ActivityRepository(db)
    private val overlays = OverlayRepository(db)
    private val marketFeeds = MarketFeedRepository(db)
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
        val secondary = generalPreferences().secondaryCurrency?.takeIf { it != currency }
        val candidates =
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
                        (filter.status == null || activity.status == filter.status) &&
                        (
                            filter.search.isBlank() ||
                                listOfNotNull(item.label, item.note, activity.id.value, activity.kind.name)
                                    .any { it.contains(filter.search, ignoreCase = true) }
                        )
                }
        val filtered =
            filterActivitiesByNetwork(candidates, filter.network).sortedWith(
                compareByDescending<EffectiveActivity> { it.activity.status == io.polion.core.ledger.ActivityStatus.PENDING }
                    .thenByDescending { it.activity.occurredAtEpochMillis }
                    .thenBy { it.activity.id.value },
            )
        return PortfolioSnapshot(
            catalog,
            balance,
            valuations(balance, currency, at, pricePolicy(current = true)),
            filtered.drop(filter.offset).take(filter.limit),
            filtered.size,
            currency,
            coverage,
            ledger.conflicts,
            SourceStatus(
                setting("source_last_success")?.toLong(),
                setting("source_error"),
                setting("source_in_progress") == "true",
                bitcoinUtxoReview(limit = 1).total,
                setting("utxo_comparison_available") != "false",
            ),
            setting("sample_data") == "true",
            ledger.activities.count {
                it.activity.status == io.polion.core.ledger.ActivityStatus.PENDING &&
                    it.activity.movements.any { movement -> movement.accountId in selected }
            },
            sourceEndpoint(),
            sourceConsent(),
            if (filter.includeNetworkChoices) {
                db
                    .query(
                        "SELECT DISTINCT network FROM raw_records WHERE network <> '' ORDER BY network",
                    ).mapNotNull {
                        it.single()
                    }
            } else {
                emptyList()
            },
            market.savedPricedAssets(currency, at, at).mapNotNull { asset ->
                val result = market.lookup(asset, currency, at, at, pricePolicy(current = true))
                result.observation?.let { MarketCoin(asset, market.metadataAt(asset, at), result.status, it) }
            },
            secondary,
            secondary?.let { valuations(balance, it, at, pricePolicy(current = true)) }.orEmpty(),
        )
    }

    /** Match the current activity's raw source IDs; manual records have no inferred network. */
    private fun filterActivitiesByNetwork(
        items: List<EffectiveActivity>,
        network: String?,
    ): List<EffectiveActivity> {
        if (network == null) return items
        val result = mutableListOf<EffectiveActivity>()
        var offset = 0
        while (offset < items.size) {
            val batch = items.subList(offset, minOf(offset + 128, items.size))
            val matching = mutableSetOf<String>()
            batch.flatMap { it.activity.sources.map { source -> source.value } }.distinct().chunked(200).forEach { ids ->
                val placeholders = ids.joinToString(",") { "?" }
                db
                    .query("SELECT id FROM raw_records WHERE network=? AND id IN ($placeholders)", listOf(network) + ids)
                    .forEach { row -> matching.add(requireNotNull(row.single())) }
            }
            result.addAll(batch.filter { item -> item.activity.sources.any { it.value in matching } })
            offset += batch.size
        }
        return result
    }

    /** Accepted advisory evidence for the enabled Bitcoin leaf addresses, independent of portfolio display filters. */
    fun bitcoinUtxoReview(
        offset: Int = 0,
        limit: Int = 25,
        expectedSnapshotId: Long? = null,
    ): io.polion.core.bitcoin.BitcoinUtxoReviewPage {
        val claims =
            ownership
                .load()
                .targets
                .filter {
                    it.enabled &&
                        it.role == io.polion.core.ownership.TargetRole.LEAF &&
                        it.identity.networkId == BITCOIN_MAINNET &&
                        it.identity.kind == "address"
                }.groupBy { bitcoinMainnetAddress(it.identity.canonicalKey) }
        require(claims.values.all { group -> group.map { it.accountId }.distinct().size == 1 })
        return bitcoinGraph.utxoReview(claims.mapValues { it.value.first().accountId }, offset, limit, expectedSnapshotId)
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

    /** The valuation currency of [portfolio], or the default display currency for all portfolios. */
    fun displayCurrency(portfolio: PortfolioId?): AssetId = portfolioCurrency(portfolio)

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

    /** Evaluation instant shared by financial projections and their presentation. */
    fun evaluationTimeMillis(): Long = now()

    /** Saved per-unit observations; missing points stay missing and carry no holdings or returns. */
    fun priceSeries(
        asset: AssetId,
        quote: AssetId,
        times: List<Long>,
    ): List<MarketPricePoint> {
        val knownAt = now()
        val results = market.lookupSeries(asset, quote, times, knownAt, { at -> pricePolicy(current = at >= knownAt - 86_400_000L) })
        return times.zip(results) { at, result -> MarketPricePoint(at, result.status, result.observation) }
    }

    fun historySeries(
        times: List<Long>,
        portfolio: PortfolioId? = null,
    ): List<HistoryPoint> {
        val selected = scopedAccounts(ownership.load(), portfolio)
        val entries = ledger().activities.map { it.activity }
        val quote = portfolioCurrency(portfolio)
        val knownAt = now()
        val balances =
            holdings.historicalSeries(entries, times, BalanceCoverage.UNKNOWN).map { balance ->
                balance.copy(quantities = balance.quantities.filterKeys { it.account in selected })
            }
        // Prices and metadata for every instant are read once per asset instead of once per asset and instant.
        val instants = balances.map { it.atMillis }
        val assets = balances.flatMap { balance -> balance.quantities.keys.map { it.asset } }.toSet()
        val prices =
            (assets - quote).associateWith { asset ->
                instants
                    .zip(market.lookupSeries(asset, quote, instants, knownAt, { at -> pricePolicy(current = at >= knownAt - 86_400_000L) }))
                    .toMap()
            }
        val metadata = assets.associateWith { market.metadataHistory(it) }
        return balances.map { balance ->
            val at = balance.atMillis
            HistoryPoint(
                at,
                valuations(
                    balance,
                    quote,
                    knownAt,
                    pricePolicy(current = at >= knownAt - 86_400_000L),
                    priceAt = { asset -> prices[asset]?.get(at) },
                    metadataAt = { asset -> metadata[asset]?.firstOrNull { it.effectiveAtMillis <= at } },
                ),
            )
        }
    }

    /** Earliest confirmed activity in the selected account scope, for historical chart bounds. */
    fun firstHistoryActivity(portfolio: PortfolioId? = null): Long? {
        val selected = scopedAccounts(ownership.load(), portfolio)
        return ledger()
            .activities
            .asSequence()
            .map { it.activity }
            .filter {
                it.status == io.polion.core.ledger.ActivityStatus.CONFIRMED &&
                    it.movements.any { movement -> movement.accountId in selected }
            }.minOfOrNull { it.occurredAtEpochMillis }
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

    /** Resolve an effective activity only when it intersects the current portfolio scope. */
    fun effectiveActivity(
        activityId: ActivityId,
        portfolio: PortfolioId? = null,
    ): EffectiveActivity? {
        val selected = scopedAccounts(ownership.load(), portfolio)
        return ledger().activities.firstOrNull { entry ->
            entry.activity.id == activityId && entry.activity.movements.any { it.accountId in selected }
        }
    }

    /** Saved scan jobs describe fetch progress; acceptance is established separately for the whole scan. */
    fun sourceScanJobs(targetId: TrackingTargetId): List<SourceAddressScan> {
        val targets = ownership.load().targets
        val target = requireNotNull(targets.firstOrNull { it.id == targetId }) { "Unknown source" }
        val leaves = if (target.role == TargetRole.WALLET) targets.filter { it.walletId == targetId } else listOf(target)
        val hasJobs = db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='sync_jobs'").isNotEmpty()
        return leaves
            .filter { it.role == TargetRole.LEAF && it.identity.networkId == BITCOIN_MAINNET && it.identity.kind == "address" }
            .sortedBy { it.identity.canonicalKey }
            .map { leaf ->
                val suffix = "-" + sha256(bitcoinMainnetAddress(leaf.identity.canonicalKey)).take(16)
                val row =
                    if (hasJobs) {
                        db
                            .query(
                                "SELECT state, pages, coverage, last_provider FROM sync_jobs WHERE account=? AND network=? " +
                                    "AND (source='esplora' OR source LIKE 'esplora:%') AND id LIKE 'bitcoin-%' AND substr(id, -17)=? LIMIT 1",
                                listOf(leaf.accountId.value, BITCOIN_MAINNET.value, suffix),
                            ).singleOrNull()
                    } else {
                        null
                    }
                SourceAddressScan(leaf, row?.let { SourceScanJob(it[0]!!, it[1]!!.toInt(), it[2]!!, it[3]) })
            }
    }

    /** Observation metadata is evidence of saved revisions, not proof of which revision a decoder used. */
    fun activitySourceEvidence(
        activityId: ActivityId,
        portfolio: PortfolioId? = null,
    ): List<ActivitySourceEvidence>? {
        val activity = effectiveActivity(activityId, portfolio) ?: return null
        return activity.original.sources.sortedBy { it.value }.map { source ->
            val record = db.query("SELECT namespace, external_id, network FROM raw_records WHERE id=?", listOf(source.value)).singleOrNull()
            val observations =
                db.query(
                    "SELECT o.source_id, o.retrieved_at, o.checksum, r.payload_version FROM raw_observations o " +
                        "JOIN raw_revisions r ON r.record_id=o.record_id AND r.checksum=o.checksum " +
                        "WHERE o.record_id=? ORDER BY o.retrieved_at DESC, o.source_id, o.checksum LIMIT 11",
                    listOf(source.value),
                )
            ActivitySourceEvidence(
                source.value,
                record?.get(0),
                record?.get(1),
                record?.get(2),
                observations.take(10).map { SavedObservation(it[0]!!, it[1]!!, it[2]!!, it[3]!!) },
                observations.size > 10,
            )
        }
    }

    /** Reward acquisitions become known zero-cost lots in the report currency; other treatments are unchanged. */
    private fun withZeroBasisRewards(
        activity: Activity,
        saved: List<io.polion.core.accounting.Treatment>,
        quote: AssetId,
    ): List<io.polion.core.accounting.Treatment> {
        val rewards =
            activity.movements
                .filter { it.role == io.polion.core.ledger.MovementRole.REWARD }
                .map { it.id }
                .toSet()
        return saved.map { treatment ->
            if (treatment is io.polion.core.accounting.Treatment.Acquire && treatment.movement in rewards) {
                treatment.copy(cost = QuoteValue(quote, Rational.ZERO), opening = false)
            } else {
                treatment
            }
        }
    }

    fun accountingReport(portfolio: PortfolioId? = null): AccountingView {
        ensureDefaultAssets()
        val catalog = ownership.load()
        val selected = scopedAccounts(catalog, portfolio)
        val missing = mutableListOf<ActivityId>()
        val invalid = mutableListOf<ActivityId>()
        val orderingUncertain = mutableListOf<ActivityId>()
        val currencyMismatch = mutableListOf<ActivityId>()
        val quote = portfolioCurrency(portfolio)
        val zeroBasisRewards = generalPreferences().zeroBasisRewards
        val priceAt = now()
        val confirmedLedger = ledger().activities.filter { it.activity.status == io.polion.core.ledger.ActivityStatus.CONFIRMED }
        // Replay each owner's full inventory before selecting accounts, preserving transferred lot age and basis.
        val reports =
            catalog.accounts.filter { it.id in selected }.map { it.ownerId }.distinct().map { owner ->
                val ownerAccounts =
                    catalog.accounts
                        .filter { it.ownerId == owner }
                        .map { it.id }
                        .toSet()

                fun ownerAssets(activity: Activity) = activity.movements.filter { it.accountId in ownerAccounts }.map { it.amount.assetId }

                val actions = mutableListOf<io.polion.core.accounting.AccountingAction>()
                val confirmed = confirmedLedger.filter { entry -> entry.activity.movements.any { it.accountId in ownerAccounts } }
                val selectedAssets =
                    confirmed
                        .flatMap { entry ->
                            entry.activity.movements
                                .filter { it.accountId in selected && it.accountId in ownerAccounts }
                                .map { it.amount.assetId }
                        }.toSet()

                // Missing or invalid inventory in the owner/asset pool can block lots transferred into selected accounts.
                fun affectsSelection(activity: Activity) = ownerAssets(activity).any { it in selectedAssets }

                val incompleteAssets = mutableSetOf<AssetId>()
                confirmed.forEach { entry ->
                    val activity = entry.activity
                    try {
                        val saved =
                            treatments
                                .load(
                                    activity.id,
                                ).let { if (zeroBasisRewards) withZeroBasisRewards(activity, it, quote) else it }
                        if (saved.isEmpty()) {
                            if (affectsSelection(activity)) missing.add(activity.id)
                            incompleteAssets.addAll(ownerAssets(activity))
                        } else {
                            val computed =
                                LedgerAccounting.actions(activity, saved).filter { action ->
                                    when (action) {
                                        is io.polion.core.accounting.AccountingAction.Acquisition -> action.account in ownerAccounts
                                        is io.polion.core.accounting.AccountingAction.Disposal -> action.account in ownerAccounts
                                        is io.polion.core.accounting.AccountingAction.Transfer ->
                                            action.from in ownerAccounts || action.to in ownerAccounts
                                    }
                                }
                            require(
                                computed.all { action ->
                                    when (action) {
                                        is io.polion.core.accounting.AccountingAction.Transfer ->
                                            action.from in ownerAccounts && action.to in ownerAccounts && action.from != action.to
                                        else -> true
                                    }
                                },
                            ) { "Internal transfer requires distinct accounts with the same owner" }
                            // A treatment recorded in another quote currency keeps its lot and quantity, but its cost or
                            // proceeds are unknown in this report's currency. Its basis and gain therefore stay unknown.
                            val normalized =
                                computed.map { action ->
                                    when (action) {
                                        is io.polion.core.accounting.AccountingAction.Acquisition ->
                                            if (action.totalCost != null && action.totalCost.asset != quote) {
                                                currencyMismatch.add(action.activityId)
                                                action.copy(totalCost = null)
                                            } else {
                                                action
                                            }
                                        is io.polion.core.accounting.AccountingAction.Disposal ->
                                            if (action.proceeds != null && action.proceeds.asset != quote) {
                                                currencyMismatch.add(action.activityId)
                                                action.copy(proceeds = null)
                                            } else {
                                                action
                                            }
                                        is io.polion.core.accounting.AccountingAction.Transfer -> action
                                    }
                                }
                            require(
                                normalized.all { action ->
                                    val value =
                                        when (action) {
                                            is io.polion.core.accounting.AccountingAction.Acquisition -> action.totalCost
                                            is io.polion.core.accounting.AccountingAction.Disposal -> action.proceeds
                                            is io.polion.core.accounting.AccountingAction.Transfer -> null
                                        }
                                    value == null || value.amount >= Rational.ZERO
                                },
                            )
                            actions.addAll(normalized)
                        }
                    } catch (_: IllegalArgumentException) {
                        if (affectsSelection(activity)) invalid.add(activity.id)
                        incompleteAssets.addAll(ownerAssets(activity))
                    }
                }
                val bitcoin = confirmed.filter { !it.original.manual && it.original.decoderVersion?.startsWith("bitcoin-") == true }
                val bitcoinAssets = bitcoin.flatMap { ownerAssets(it.activity) }.toSet()
                val mixed =
                    confirmed.filter { entry ->
                        entry !in bitcoin &&
                            entry.activity.movements.any { it.accountId in ownerAccounts && it.amount.assetId in bitcoinAssets }
                    }
                val unverified = bitcoin.filter { !it.original.orderingKey.startsWith("bitcoin-chain:") }
                val uncertain = (unverified + if (mixed.isNotEmpty()) bitcoin + mixed else emptyList()).distinct()
                val ownerOrderingUncertain =
                    uncertain
                        .filter { entry ->
                            affectsSelection(entry.activity)
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
                val unitPrices =
                    confirmed
                        .flatMap { entry -> ownerAssets(entry.activity) }
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
                val uncertainAssets = uncertain.flatMap { ownerAssets(it.activity) }.toSet()
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
                                            consumed =
                                                disposal.consumed.map {
                                                    it.copy(
                                                        lotId = null,
                                                        basis = null,
                                                        acquisitionActivityId = null,
                                                    )
                                                },
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
                orderingUncertain.addAll(ownerOrderingUncertain)
                report
            }
        return AccountingView(
            AccountingReport(
                quote,
                reports.flatMap { it.lots }.filter { it.account in selected },
                reports.flatMap { it.disposals }.filter { it.account in selected },
                reports.flatMap { it.valuations }.filter { it.lot.account in selected },
            ),
            missing.distinct(),
            invalid.distinct(),
            orderingUncertain.distinct(),
            currencyMismatch.distinct(),
        )
    }

    fun transferLinks(): List<TransferLink> = ledger().transferLinks

    fun transferLinkConflicts(): List<TransferLinkConflict> = ledger().transferLinkConflicts

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

    /**
     * Fetches tracked mainnet addresses after consent and applies only a complete, consistent scan.
     * Explicit [recoverHistory] starts fresh history and establishes a new continuity baseline on successful apply.
     * [providerEndpoint] binds a pre-created transport to its captured endpoint throughout the scan.
     */
    @OptIn(ExperimentalTime::class)
    fun refreshBitcoin(
        transport: EsploraTransport,
        verifyBlockPositions: Boolean = false,
        recoverHistory: Boolean = false,
        providerEndpoint: String = sourceEndpoint(),
        onProgress: (BitcoinScanProgress) -> Unit = {},
    ): List<JobState> {
        require(sourceConsent()) { "Source consent required" }
        putSetting("source_in_progress", "true")
        return try {
            fun requireEndpoint() {
                require(sourceEndpoint() == providerEndpoint) {
                    throw BitcoinProviderException(BitcoinProviderFailure.HISTORY_CHANGED)
                }
            }
            requireEndpoint()
            val boundTransport =
                EsploraTransport { path ->
                    requireEndpoint()
                    transport.get(path).also { requireEndpoint() }
                }
            refreshBitcoinScan(boundTransport, verifyBlockPositions, recoverHistory, providerEndpoint, onProgress)
        } catch (failure: Exception) {
            if (failure is BitcoinProviderException &&
                (failure.reason == BitcoinProviderFailure.REORGANIZED || failure.reason == BitcoinProviderFailure.HISTORY_CHANGED)
            ) {
                bitcoinStage.clear()
            }
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
        recoverHistory: Boolean,
        providerEndpoint: String,
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
        val providerSource = SourceId("esplora:" + sha256(providerEndpoint))
        putSetting("provider_endpoint:${providerSource.value}", providerEndpoint)
        val fingerprint =
            sha256(
                "${providerSource.value}|" + addresses.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value.value}" },
            )
        val acceptedAnchors = bitcoinGraph.anchors(addresses)
        val previousCheckpoint = acceptedAnchors.maxByOrNull { it.height }
        // A non-canonical checkpoint means the accepted history may have changed, so the skip below must not run.
        var checkpointCanonical = true
        if (!recoverHistory && previousCheckpoint != null) {
            require(previousCheckpoint.height <= anchorHeight) {
                throw BitcoinProviderException(BitcoinProviderFailure.LAGGING_CHAIN)
            }
            val canonical = transport.get("/block-height/${previousCheckpoint.height}").trim()
            require(canonical.matches(Regex("[0-9a-f]{64}"))) { "Invalid checkpoint response" }
            if (canonical != previousCheckpoint.hash) {
                checkpointCanonical = false
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
        if (recoverHistory) bitcoinStage.clear()
        val pending = bitcoinStage.session()
        if (pending == null &&
            !recoverHistory &&
            checkpointCanonical &&
            previousCheckpoint != null &&
            acceptedScanUnchanged(fingerprint, addresses.keys, verifyBlockPositions) { address -> summary(address) }
        ) {
            db.transaction {
                putSetting("source_last_refresh_skipped", "true")
                putSetting("source_last_success", now().toString())
                db.execute("DELETE FROM application_settings WHERE key='source_error'")
            }
            invalidate()
            return addresses.keys.map { JobState.COMPLETED }
        }
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
            db.transaction {
                val jobs = "SELECT id FROM sync_jobs WHERE source='esplora' OR source LIKE 'esplora:%'"
                db.execute("DELETE FROM sync_cursors WHERE job_id IN ($jobs)")
                db.execute("DELETE FROM sync_errors WHERE job_id IN ($jobs)")
                db.execute("DELETE FROM sync_jobs WHERE source='esplora' OR source LIKE 'esplora:%'")
            }
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
        for ((addressIndex, scan) in scanned.withIndex()) {
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
                            EsploraHistoryFetcher(mapOf(scan.accountId to scan.address), recordingTransport) {
                                Instant.fromEpochMilliseconds(now()).toString()
                            },
                        ),
                    ),
                ).historyFetcher(BITCOIN_MAINNET)
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
            val acceptedCounts = scanned.associate { it.address to bitcoinStage.seenCounts(it.address) }
            require(transport.get("/block-height/${session.anchorHeight}").trim() == session.anchorHash) {
                throw BitcoinProviderException(BitcoinProviderFailure.REORGANIZED)
            }
            require(transport.get("/block-height/$anchorHeight").trim() == startTip) {
                throw BitcoinProviderException(BitcoinProviderFailure.REORGANIZED)
            }
            bitcoinStage.validateBlocks(
                transport,
                io.polion.core.bitcoin
                    .BitcoinBlockAnchor(anchorHeight, startTip),
            ) { validated, total ->
                onProgress(
                    BitcoinScanProgress(scanned.size, scanned.size, pagesFetched, transactionsFetched, "verify", validated, total),
                )
            }
            val stagedGraph =
                io.polion.core.bitcoin
                    .BitcoinStagedGraph(db)
            stagedGraph.rebuild(bitcoinStage.payloads(), addresses)
            require(
                db
                    .query(
                        "SELECT 1 FROM bitcoin_graph_work_transactions WHERE height>? LIMIT 1",
                        listOf(anchorHeight.toString()),
                    ).isEmpty(),
            ) {
                throw BitcoinProviderException(BitcoinProviderFailure.HISTORY_CHANGED)
            }
            // The comparison is advisory. When the provider permanently rejects the UTXO request (for example a capped
            // address), history is still applied and the snapshot is recorded as not compared, so no clean "matching"
            // result is claimed. Transient failures (offline, timeout, rate limit) still interrupt and keep the session.
            val mismatchCount: Long? =
                try {
                    io.polion.core.bitcoin
                        .BitcoinStagedUtxoComparison(db)
                        .fetchAndCompare(transport)
                } catch (failure: BitcoinProviderException) {
                    if (failure.reason != BitcoinProviderFailure.UNAVAILABLE) throw failure
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            runCatching { onProgress(BitcoinScanProgress(scanned.size, scanned.size, pagesFetched, transactionsFetched, "apply")) }
            require(
                transport.get("/block-height/${session.anchorHeight}").trim() == session.anchorHash &&
                    transport.get("/block-height/$anchorHeight").trim() == startTip,
            ) {
                throw BitcoinProviderException(BitcoinProviderFailure.REORGANIZED)
            }
            db.transaction {
                require(sourceConsent()) { "Source consent required" }
                require(sourceEndpoint() == providerEndpoint) {
                    throw BitcoinProviderException(BitcoinProviderFailure.HISTORY_CHANGED)
                }
                val currentTargets =
                    ownership.loadInTransaction().targets.filter {
                        it.role == io.polion.core.ownership.TargetRole.LEAF &&
                            it.identity.networkId == BITCOIN_MAINNET &&
                            it.identity.kind == "address"
                    }

                fun scanScope(targets: List<TrackingTarget>) =
                    targets.associate {
                        it.id to Triple(it.accountId, bitcoinMainnetAddress(it.identity.canonicalKey), it.enabled)
                    }
                require(scanScope(currentTargets) == scanScope(bitcoinTargets)) {
                    throw BitcoinProviderException(BitcoinProviderFailure.HISTORY_CHANGED)
                }
                io.polion.core.bitcoin.BitcoinGraphDeltaRepository(db).saveInTransaction(
                    providerSourceId = providerSource.value,
                    anchor =
                        io.polion.core.bitcoin
                            .BitcoinBlockAnchor(anchorHeight, startTip),
                    observedAt = Instant.fromEpochMilliseconds(now()).toString(),
                    compared = mismatchCount != null,
                    recoveryBaseline = recoverHistory,
                )
                bitcoinStage.applyCompleteInTransaction(
                    ownedAddresses,
                    reconciledAccounts,
                    requireBlockPositions = verifyBlockPositions,
                    graphState = stagedGraph::state,
                )
                putSetting("utxo_comparison_available", (mismatchCount != null).toString())
                mismatchCount?.let { putSetting("utxo_mismatch_count", it.toString()) }
                bitcoinStage.clearInTransaction()
                putSetting("bitcoin_accepted_scan", acceptedScanJson(fingerprint, acceptedCounts, verifyBlockPositions))
                putSetting("source_last_refresh_skipped", "false")
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

    /** Epoch millis of the last successful wallet refresh, or null when none has completed. */
    fun sourceLastSuccessMillis(): Long? = setting("source_last_success")?.toLongOrNull()

    /** True when the last wallet refresh found no change and left the accepted ledger as it was. */
    fun lastRefreshSkipped(): Boolean = setting("source_last_refresh_skipped") == "true"

    /**
     * Clears the last-success marker and the accepted-scan snapshot together. Any change to accepted wallet state calls
     * this, so the skip path never trusts a snapshot that no longer describes the ledger.
     */
    private fun forgetSourceSuccess() {
        db.execute("DELETE FROM application_settings WHERE key IN ('source_last_success','bitcoin_accepted_scan')")
    }

    private data class AcceptedScan(
        val fingerprint: String,
        /** Per address: (confirmed transaction count, mempool transaction count) seen by the last applied scan. */
        val counts: Map<String, Pair<Long, Long>>,
        /** Whether the last applied scan verified transaction block positions. */
        val blockPositionsVerified: Boolean,
    )

    private fun acceptedScan(): AcceptedScan? {
        val text = setting("bitcoin_accepted_scan") ?: return null
        return runCatching {
            val root = Json.parseToJsonElement(text).jsonObject
            AcceptedScan(
                root.getValue("fingerprint").jsonPrimitive.content,
                root.getValue("addresses").jsonObject.mapValues { (_, pair) ->
                    val counts = pair.jsonArray
                    counts[0].jsonPrimitive.long to counts[1].jsonPrimitive.long
                },
                root.getValue("blockPositionsVerified").jsonPrimitive.boolean,
            )
        }.getOrNull()
    }

    private fun acceptedScanJson(
        fingerprint: String,
        counts: Map<String, Pair<Long, Long>>,
        blockPositionsVerified: Boolean,
    ): String =
        buildJsonObject {
            put("fingerprint", fingerprint)
            put("blockPositionsVerified", blockPositionsVerified)
            putJsonObject("addresses") {
                counts.forEach { (address, pair) ->
                    putJsonArray(address) {
                        add(pair.first)
                        add(pair.second)
                    }
                }
            }
        }.toString()

    /**
     * True when the last applied scan covers the same provider and addresses, recorded no mempool activity, and the
     * provider still reports the same chain and mempool counts for every address. While the accepted anchor is
     * canonical, nothing at or below it has changed, and any new confirmed or pending transaction changes a count.
     * A refresh that asks for block-position verification is never skipped by a scan that did not verify them.
     */
    private fun acceptedScanUnchanged(
        fingerprint: String,
        addresses: Set<String>,
        verifyBlockPositions: Boolean,
        liveCounts: (String) -> Pair<Long, Long>,
    ): Boolean {
        val accepted = acceptedScan() ?: return false
        if (accepted.fingerprint != fingerprint || accepted.counts.keys != addresses) return false
        if (verifyBlockPositions && !accepted.blockPositionsVerified) return false
        if (accepted.counts.values.any { it.second != 0L }) return false
        return addresses.all { address -> liveCounts(address) == accepted.counts.getValue(address) }
    }

    fun priceSourceConsent(): Boolean = setting("price_source_consent") == "true"

    fun automaticPriceConsent(): Boolean = setting("automatic_price_consent") == "true"

    fun automaticPriceGeneration(): String? = setting("automatic_price_generation")

    /** Cancel in-flight automatic requests without changing the user's saved consent or scope. */
    fun invalidateAutomaticPriceRequests() =
        db.transaction {
            putSetting("automatic_price_generation", priceRequestNonce())
        }

    fun automaticPortfolioPriceConsent(): Boolean = setting("automatic_price_scope") == "all"

    fun automaticPriceStatus(): String? = setting("automatic_price_status")

    /**
     * Epoch millis of the last finished automatic price refresh, successful or not, or null when none has finished.
     * Schedules wait an interval from this time, so a failing provider is not asked again on every app start.
     */
    fun automaticPriceLastRunMillis(): Long? = setting("automatic_price_last_run")?.toLongOrNull()

    fun recordAutomaticPriceSchedulingFailure() =
        db.transaction {
            if (automaticPriceConsent()) putSetting("automatic_price_status", "Automatic price refresh could not be scheduled")
        }

    fun recordAutomaticPriceStatus(
        success: Boolean,
        rateLimited: Boolean = false,
    ) = db.transaction {
        if (automaticPriceConsent()) {
            putSetting("automatic_price_last_run", now().toString())
            putSetting(
                "automatic_price_status",
                if (success) {
                    "Last refresh succeeded at ${Instant.fromEpochMilliseconds(
                        now(),
                    )}"
                } else if (rateLimited) {
                    val until = setting("kraken_retry_after")?.toLongOrNull()?.let { Instant.fromEpochMilliseconds(it) }
                    if (until == null) {
                        "Kraken is rate limiting requests. Saved prices remain available"
                    } else {
                        "Kraken is rate limiting requests; retry after $until. Saved prices remain available"
                    }
                } else {
                    "Some price requests failed; saved prices remain available"
                },
            )
        }
    }

    fun setAutomaticPriceConsent(accepted: Boolean) = db.transaction { putAutomaticPriceConsent(accepted) }

    private fun putAutomaticPriceConsent(accepted: Boolean) {
        putSetting("automatic_price_generation", priceRequestNonce())
        putSetting("automatic_price_consent", accepted.toString())
        if (accepted) putSetting("automatic_price_scope", "all")
    }

    /**
     * The single automatic-refresh switch. Enabling it grants automatic Kraken price and CoinGecko market consent with the
     * schedule preference; disabling it withdraws the Kraken consent. Both change in one transaction, so prices and wallet history cannot disagree.
     */
    fun setAutomaticRefresh(enabled: Boolean) =
        db.transaction {
            putSetting("refresh_automatic", enabled.toString())
            putAutomaticPriceConsent(enabled)
            // Auto-sync also refreshes market lists; turning it off keeps manual market refresh available.
            if (enabled) setMarketDataConsent(true)
        }

    fun setAutomaticPortfolioPriceConsent(accepted: Boolean) =
        db.transaction {
            require(automaticPriceConsent()) { "Price provider consent required" }
            putSetting("automatic_price_generation", priceRequestNonce())
            putSetting("automatic_price_scope", if (accepted) "all" else "default")
        }

    /** Default, secondary and saved portfolio currencies supported by the automatic Bitcoin provider. */
    fun automaticPriceCurrencies(): List<AssetId> =
        (
            listOf(currency()) + listOfNotNull(generalPreferences().secondaryCurrency) +
                if (automaticPortfolioPriceConsent()) {
                    db.query("SELECT DISTINCT currency FROM portfolios").map { AssetId(requireNotNull(it.single())) }
                } else {
                    emptyList()
                }
        ).filter { it.value in DISPLAY_CURRENCIES }
            .distinct()

    fun refreshAutomaticPriceSet(
        transport: (String) -> String,
        authorize: () -> Unit = {},
    ): Int {
        require(automaticPriceConsent()) { "Price provider consent required" }
        val generation = requireNotNull(setting("automatic_price_generation"))

        fun checkAuthorization() {
            authorize()
            if (!automaticPriceConsent() || setting("automatic_price_generation") != generation) {
                throw CoingeckoProviderException(CoingeckoProviderFailure.CANCELLED)
            }
        }
        var count = 0
        var failures = 0
        val currencies = automaticPriceCurrencies()
        require(currencies.isNotEmpty()) { "No supported portfolio currency" }
        for (quote in currencies) {
            for (daily in listOf(false, true)) {
                checkAuthorization()
                try {
                    count +=
                        if (daily) {
                            refreshAutomaticDailyPricesIfDue(transport, quote, ::checkAuthorization)
                        } else {
                            refreshAutomaticPrices(transport, quote, ::checkAuthorization)
                        }
                } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    checkAuthorization()
                    if (failure is KrakenRateLimitedException) throw failure
                    if (failure is CoingeckoProviderException && failure.failure == CoingeckoProviderFailure.CANCELLED) throw failure
                    failures++
                }
            }
        }
        check(failures == 0) { "Some price requests failed" }
        return count
    }

    fun refreshAutomaticPrices(
        transport: (String) -> String,
        quote: AssetId = currency(),
        authorize: () -> Unit = {},
    ): Int {
        require(automaticPriceConsent()) { "Price provider consent required" }
        ensureDefaultAssets()
        require(quote in automaticPriceCurrencies()) { "Unsupported portfolio currency" }
        val generation = requireNotNull(setting("automatic_price_generation"))
        val observedAt = now()
        return KrakenPrices.fetch(
            guardKrakenRequests(transport, authorize),
            market,
            BITCOIN_ASSET,
            quote,
            observedAt,
            "kraken:${quote.value}:${priceRequestNonce()}",
        ) {
            authorize()
            if (!automaticPriceConsent() || setting("automatic_price_generation") != generation || quote !in automaticPriceCurrencies()) {
                throw CoingeckoProviderException(CoingeckoProviderFailure.CANCELLED)
            }
        }
    }

    /** Fetch daily history at most once per UTC day, independently of hourly refreshes. */
    fun refreshAutomaticDailyPricesIfDue(
        transport: (String) -> String,
        quote: AssetId = currency(),
        authorize: () -> Unit = {},
    ): Int {
        require(automaticPriceConsent()) { "Price provider consent required" }
        ensureDefaultAssets()
        require(quote in automaticPriceCurrencies()) { "Unsupported portfolio currency" }
        val generation = requireNotNull(setting("automatic_price_generation"))
        val observedAt = now()
        val day = observedAt / 86_400_000L
        val key = "automatic_price_daily:${quote.value}"
        if (setting(key)?.toLongOrNull() == day) return 0
        val count =
            KrakenPrices.fetch(
                guardKrakenRequests(transport, authorize),
                market,
                BITCOIN_ASSET,
                quote,
                observedAt,
                "kraken:${quote.value}:daily:${priceRequestNonce()}",
                1440,
            ) {
                authorize()
                if (!automaticPriceConsent() ||
                    setting("automatic_price_generation") != generation ||
                    quote !in automaticPriceCurrencies()
                ) {
                    throw CoingeckoProviderException(CoingeckoProviderFailure.CANCELLED)
                }
            }
        db.transaction {
            authorize()
            if (!automaticPriceConsent() || setting("automatic_price_generation") != generation || quote !in automaticPriceCurrencies()) {
                throw CoingeckoProviderException(CoingeckoProviderFailure.CANCELLED)
            }
            putSetting(key, day.toString())
        }
        return count
    }

    /** A provider cooldown is shared by app instances through the local database. */
    private fun guardKrakenRequests(
        transport: (String) -> String,
        authorize: () -> Unit,
    ): (String) -> String =
        { url ->
            authorize()
            val remaining = (setting("kraken_retry_after")?.toLongOrNull() ?: 0L) - now()
            if (remaining > 0) throw KrakenRateLimitedException(remaining)
            try {
                transport(url)
            } catch (failure: KrakenRateLimitedException) {
                val delay = failure.retryAfterMillis.coerceIn(0L, 86_400_000L)
                db.transaction {
                    val at = now().coerceAtMost(Long.MAX_VALUE - delay) + delay
                    val previous = setting("kraken_retry_after")?.toLongOrNull() ?: 0L
                    putSetting("kraken_retry_after", maxOf(previous, at).toString())
                }
                throw failure
            }
        }

    fun setPriceSourceConsent(accepted: Boolean) =
        db.transaction {
            putSetting("price_source_generation", priceRequestNonce())
            putSetting("price_source_consent", accepted.toString())
        }

    private fun priceRequestNonce() = "${kotlin.random.Random.nextLong()}:${kotlin.random.Random.nextLong()}"

    fun fetchPriceHistory(
        transport: CoingeckoTransport,
        asset: AssetId,
        quote: AssetId,
        days: Int,
        authorize: () -> Unit = {},
    ): Int {
        require(priceSourceConsent()) { "Price provider consent required" }
        require(asset == BITCOIN_ASSET && quote.value in setOf("USD", "EUR", "GBP")) { "Unsupported market" }
        require(days in setOf(1, 7, 30, 90, 365))
        ensureDefaultAssets()
        val generation = requireNotNull(setting("price_source_generation")) { "Renew price provider consent" }
        return CoingeckoPriceClient(transport, market, now).chart(
            asset,
            "bitcoin",
            quote,
            quote.value.lowercase(),
            days,
            "coingecko:${quote.value}:chart:${priceRequestNonce()}",
        ) {
            authorize()
            if (!priceSourceConsent() || setting("price_source_generation") != generation) {
                throw CoingeckoProviderException(CoingeckoProviderFailure.CANCELLED)
            }
        }
    }

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
    private fun pricePolicy(current: Boolean = false) =
        PricePolicy(
            listOf(ProviderId(KrakenPrices.PROVIDER_ID), ProviderId(CoingeckoPrices.PROVIDER_ID)),
            if (current) 7_200_000L else 86_400_000L,
            preferLatest = true,
        )

    fun automaticRefreshPreference(): Boolean? = setting("refresh_automatic")?.toBooleanStrictOrNull()

    fun wifiOnlyRefreshPreference(): Boolean = setting("refresh_wifi_only") == "true"

    fun setRefreshPreferences(
        automatic: Boolean,
        wifiOnly: Boolean,
    ) = db.transaction {
        putSetting("refresh_automatic", automatic.toString())
        putSetting("refresh_wifi_only", wifiOnly.toString())
    }

    fun sourceConsent(): Boolean = setting("source_consent") == "true"

    fun setSourceConsent(accepted: Boolean) = putSetting("source_consent", accepted.toString())

    /** Endpoint identities survive configuration changes and portable backup/restore. */
    fun bitcoinProviderEndpoints(): Map<String, String> =
        db
            .query("SELECT key, value FROM application_settings WHERE key LIKE 'provider_endpoint:%'")
            .associate { it[0]!!.removePrefix("provider_endpoint:") to it[1]!! }

    fun sourceEndpoint(): String = setting("source_endpoint") ?: "https://blockstream.info/api"

    fun configureBitcoinSource(
        endpoint: String,
        consent: Boolean,
    ) = db.transaction {
        setSourceEndpoint(endpoint)
        setSourceConsent(consent)
    }

    fun setSourceEndpoint(endpoint: String) {
        require(
            endpoint.startsWith("https://") &&
                endpoint.substringAfter("https://").substringBefore('/').isNotBlank() &&
                endpoint.length <= 2048 &&
                endpoint.none { it.isWhitespace() } &&
                !endpoint.substringAfter("https://").substringBefore('/').contains('@') &&
                !endpoint.contains('?') &&
                !endpoint.contains('#'),
        ) { "Use an HTTPS provider URL with a host and without credentials, query or fragment" }
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

    /** Null preserves entry for populated installations and newer saved setup versions. */
    fun onboardingState(): io.polion.core.onboarding.OnboardingState? =
        io.polion.core.onboarding
            .OnboardingRepository(db)
            .load()

    fun updateOnboarding(action: io.polion.core.onboarding.OnboardingAction): io.polion.core.onboarding.OnboardingState =
        io.polion.core.onboarding
            .OnboardingRepository(db)
            .update(action)

    /** Presentation preference is local and included in portable backups. */
    fun appearanceTheme(): String = setting("appearance_theme")?.takeIf { it in setOf("system", "light", "dark") } ?: "dark"

    fun setAppearanceTheme(theme: String) {
        require(theme in setOf("system", "light", "dark")) { "Invalid appearance theme" }
        putSetting("appearance_theme", theme)
    }

    fun marketFavorites(): Set<AssetId> =
        setting("market_favorites")
            ?.lineSequence()
            ?.filter { it.isNotBlank() }
            ?.map(::AssetId)
            ?.toSet()
            .orEmpty()

    fun setMarketFavorites(assets: Set<AssetId>) {
        require(assets.all { it.value.isNotBlank() && '\n' !in it.value && '\r' !in it.value }) { "Invalid asset ID" }
        putSetting("market_favorites", assets.map { it.value }.sorted().joinToString("\n"))
    }

    fun assetListPreference(): AssetListPreference {
        val parts = setting("asset_list_preference")?.split(':') ?: return AssetListPreference()
        if (parts.size !in 2..3 || parts.drop(1).any { it !in setOf("0", "1") }) return AssetListPreference()
        val sort = AssetSort.entries.firstOrNull { it.name == parts[0] } ?: return AssetListPreference()
        return AssetListPreference(sort, parts[1] == "1", parts.getOrNull(2) == "1")
    }

    fun setAssetListPreference(preference: AssetListPreference) {
        putSetting(
            "asset_list_preference",
            "${preference.sort.name}:${if (preference.hideEmpty) 1 else 0}:${if (preference.hideSmall) 1 else 0}",
        )
    }

    /** Cached CoinGecko feeds for the display currency, with the request state Main needs to explain gaps. */
    fun marketOverview(): MarketOverview {
        val currency = currency()
        val code = currency.value.lowercase()
        val markets = marketFeeds.markets(code)
        val trending = marketFeeds.trending(code)
        val ids = (markets?.items.orEmpty().map { it.coinId } + trending?.items.orEmpty().map { it.coinId }).toSet()
        return MarketOverview(
            currency,
            marketDataConsent(),
            markets,
            trending,
            marketFeeds.deviceSetting("market_feed_error"),
            marketFeeds.deviceSetting("coingecko_retry_after")?.toLongOrNull()?.takeIf { it > now() },
            coingeckoApiKey() != null,
            ids.associateWith { marketAssetKey(it) },
        )
    }

    /** How long the app may stay in the background before the privacy lock applies; null means the host default. */
    fun lockDelayMillis(): Long? = marketFeeds.deviceSetting("lock_delay")?.toLongOrNull()?.takeIf { it in LOCK_DELAYS }

    /** A device setting: it is not part of backups, like the lock itself. */
    fun setLockDelay(millis: Long) {
        require(millis in LOCK_DELAYS) { "Unsupported lock delay" }
        marketFeeds.putDeviceSetting("lock_delay", millis.toString())
    }

    /** A cached CoinGecko logo, or null when it was never fetched or is over 30 days old. */
    fun cachedLogo(url: String): ByteArray? = marketFeeds.logo(url, now())

    /** Logos are cached only for CoinGecko image URLs and only after market data consent. */
    fun saveLogo(
        url: String,
        bytes: ByteArray,
    ) {
        require(marketDataConsent() && CoingeckoMarkets.logoUrl(url) == url)
        marketFeeds.saveLogo(url, bytes, now())
    }

    /** Bitcoin and any provider-mapped coin use the portfolio asset ID, so favorites and coin pages match holdings. */
    fun marketAssetKey(coinId: String): AssetId =
        if (coinId == "bitcoin") {
            BITCOIN_ASSET
        } else {
            market.resolveProvider(ProviderId(CoingeckoPrices.PROVIDER_ID), coinId) ?: AssetId("coingecko:$coinId")
        }

    fun marketDataConsent(): Boolean = setting("market_data_consent") == "true"

    fun setMarketDataConsent(accepted: Boolean) = putSetting("market_data_consent", accepted.toString())

    /** The optional CoinGecko Demo key raises request limits. It stays on this device and is not part of backups. */
    fun coingeckoApiKey(): String? = marketFeeds.deviceSetting("coingecko_api_key")

    fun setCoingeckoApiKey(key: String?) {
        val trimmed = key?.trim()?.takeIf { it.isNotEmpty() }
        require(trimmed == null || trimmed.matches(Regex("[A-Za-z0-9_-]{10,100}"))) { "That does not look like a CoinGecko API key" }
        db.transaction {
            marketFeeds.putDeviceSetting("coingecko_api_key", trimmed)
            // A new key may lift a rate limit reached without one.
            marketFeeds.putDeviceSetting("coingecko_retry_after", null)
        }
    }

    /**
     * Refreshes the top-250 market list and the trending list for the display currency, skipping a feed younger than
     * [maxAgeMillis] (trending at most every 10 minutes). Each successful feed is saved on its own, so a failure keeps the
     * other feed and every earlier copy. A rate limit pauses all CoinGecko feed requests for a minute.
     */
    fun refreshMarketFeeds(
        transport: (url: String, apiKey: String?) -> String,
        maxAgeMillis: Long = 60_000L,
        baseUrl: String = "https://api.coingecko.com/api/v3",
    ): MarketRefreshResult {
        require(marketDataConsent()) { "Market data consent required" }
        val started = now()
        marketFeeds.deviceSetting("coingecko_retry_after")?.toLongOrNull()?.takeIf { it > started }?.let {
            return MarketRefreshResult(0, CoingeckoProviderFailure.RATE_LIMITED, it)
        }
        val code = currency().value.lowercase()
        val key = coingeckoApiKey()
        var updated = 0

        fun due(
            feed: String,
            age: Long,
        ) = marketFeeds.observedAt(feed, code)?.let { started - it >= age } ?: true
        try {
            if (due("markets", maxAgeMillis)) {
                val payload = transport(CoingeckoMarkets.marketsUrl(baseUrl, code), key)
                val listings =
                    runCatching { CoingeckoMarkets.parseMarkets(payload) }.getOrNull()?.takeIf { it.isNotEmpty() }
                        ?: throw CoingeckoProviderException(CoingeckoProviderFailure.INVALID_RESPONSE)
                marketFeeds.saveMarkets(MarketFeed(code, now(), listings))
                updated++
            }
            if (due("trending", maxOf(maxAgeMillis, 600_000L))) {
                val btc =
                    marketFeeds
                        .markets(code)
                        ?.items
                        ?.firstOrNull { it.coinId == "bitcoin" }
                        ?.price
                val payload = transport(CoingeckoMarkets.trendingUrl(baseUrl), key)
                val listings =
                    runCatching { CoingeckoMarkets.parseTrending(payload, code, btc) }.getOrNull()
                        ?: throw CoingeckoProviderException(CoingeckoProviderFailure.INVALID_RESPONSE)
                marketFeeds.saveTrending(MarketFeed(code, now(), listings))
                updated++
            }
            marketFeeds.putDeviceSetting("market_feed_error", null)
            return MarketRefreshResult(updated, null, null)
        } catch (failure: CoingeckoProviderException) {
            val retry = if (failure.failure == CoingeckoProviderFailure.RATE_LIMITED) now() + 60_000L else null
            db.transaction {
                retry?.let { marketFeeds.putDeviceSetting("coingecko_retry_after", it.toString()) }
                marketFeeds.putDeviceSetting("market_feed_error", failure.failure.name)
            }
            return MarketRefreshResult(updated, failure.failure, retry)
        }
    }

    /** Display and accounting preferences from Settings; stored in `application_settings`, so backups include them. */
    fun generalPreferences(): GeneralPreferences =
        GeneralPreferences(
            landing = LandingPage.entries.firstOrNull { it.name == setting("landing_page") } ?: LandingPage.MAIN,
            secondaryCurrency = setting("secondary_currency")?.takeIf { it in DISPLAY_CURRENCIES }?.let(::AssetId),
            showPortfolioChart = setting("show_portfolio_chart") != "false",
            zeroBasisRewards = setting("zero_basis_rewards") == "true",
            deviceName = setting("device_name"),
            hideAmounts = setting("hide_amounts") == "true",
            candleCharts = setting("chart_style") == "candles",
        )

    fun setLandingPage(page: LandingPage) = putSetting("landing_page", page.name)

    /** Null removes the secondary currency. Automatic price refresh also fetches the chosen currency. */
    fun setSecondaryCurrency(currency: AssetId?) =
        db.transaction {
            if (currency == null) {
                db.execute("DELETE FROM application_settings WHERE key='secondary_currency'")
            } else {
                require(currency.value in DISPLAY_CURRENCIES) { "Unsupported currency" }
                putSetting("secondary_currency", currency.value)
            }
            putSetting("automatic_price_generation", priceRequestNonce())
        }

    fun setShowPortfolioChart(show: Boolean) = putSetting("show_portfolio_chart", show.toString())

    fun setHideAmounts(hide: Boolean) = putSetting("hide_amounts", hide.toString())

    fun setCandleCharts(candles: Boolean) = putSetting("chart_style", if (candles) "candles" else "line")

    /**
     * Acquisitions recorded as rewards (mining, staking, forks, airdrops) count with zero cost basis instead of their
     * recorded value. The recorded value is kept, so turning this off restores it; reports replay with the current choice.
     */
    fun setZeroBasisRewards(enabled: Boolean) = putSetting("zero_basis_rewards", enabled.toString())

    /** Null or blank restores the host's default device name. */
    fun setDeviceName(name: String?) {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            db.execute("DELETE FROM application_settings WHERE key='device_name'")
        } else {
            require(trimmed.length <= 40 && trimmed.none { it.isISOControl() }) { "Use up to 40 characters on one line" }
            putSetting("device_name", trimmed)
        }
    }

    fun dashboardWidgets(): List<DashboardWidgetPreference> {
        val saved = setting("dashboard_widgets") ?: return DashboardWidget.entries.map { DashboardWidgetPreference(it, true) }
        val entries =
            saved.split(',').mapNotNull { token ->
                val parts = token.split(':')
                val widget = DashboardWidget.entries.firstOrNull { it.name == parts.firstOrNull() }
                if (parts.size != 2 || widget == null || parts[1] !in setOf("1", "0")) {
                    null
                } else {
                    DashboardWidgetPreference(widget, parts[1] == "1")
                }
            }
        return if (entries.size == DashboardWidget.entries.size && entries.map { it.widget }.toSet().size == entries.size) {
            entries
        } else {
            DashboardWidget.entries.map { DashboardWidgetPreference(it, true) }
        }
    }

    fun setDashboardWidgets(widgets: List<DashboardWidgetPreference>) {
        require(widgets.size == DashboardWidget.entries.size && widgets.map { it.widget }.toSet() == DashboardWidget.entries.toSet())
        putSetting("dashboard_widgets", widgets.joinToString(",") { "${it.widget.name}:${if (it.visible) 1 else 0}" })
    }

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
                val result = market.lookup(asset, quote, atMillis, atMillis, pricePolicy(current = true))
                val observation = result.observation?.takeIf { result.status == PriceStatus.FRESH } ?: return null
                Rational.fromDecimal(observation.price.value)
            }
        return QuoteValue(quote, wholeUnitPrice / Rational.units(ExactInteger.parse("1").timesPowerOfTen(decimals)))
    }

    private fun valuations(
        balance: BalanceView,
        currency: AssetId,
        knownAt: Long,
        // Same rule everywhere: a balance within 24 hours of now uses the short current-price window.
        policy: PricePolicy = pricePolicy(current = balance.atMillis >= knownAt - 86_400_000L),
        // Series callers pass prices and metadata resolved in bulk; the results are the same as the per-asset queries.
        priceAt: ((AssetId) -> PriceResult?)? = null,
        metadataAt: ((AssetId) -> AssetMetadata?)? = null,
    ): List<AssetValuation> =
        balance.quantities.entries
            .groupBy { it.key.asset }
            .map { (asset, rows) ->
                val units = rows.fold(io.polion.core.model.ExactInteger.ZERO) { sum, row -> sum + row.value }
                val amount = AtomicAmount(asset, units)
                val metadata = if (metadataAt != null) metadataAt(asset) else market.metadataAt(asset, balance.atMillis)
                val result =
                    if (asset ==
                        currency
                    ) {
                        null
                    } else {
                        priceAt?.invoke(asset) ?: market.lookup(asset, currency, balance.atMillis, knownAt, policy)
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

    fun accountOverview(portfolio: PortfolioId? = null): List<AccountOverview> {
        ensureDefaultAssets()
        val catalog = ownership.load()
        val unarchived = catalog.copy(accounts = catalog.accounts.map { it.copy(tags = it.tags - "polion:archived") })
        val membership = catalog.portfolios.associate { it.id to scopedAccounts(unarchived, it.id) }
        val entries = ledger().activities.map { it.activity }
        val balance = holdings.historical(entries, now(), BalanceCoverage.UNKNOWN)
        val projection = AccountHistoryProjection(db)
        val endpoints = bitcoinProviderEndpoints()
        val quote = portfolioCurrency(portfolio)
        return catalog.accounts.map { account ->
            val targets = catalog.targets.filter { it.accountId == account.id && it.identity.networkId == BITCOIN_MAINNET }
            val active =
                targets
                    .filter { it.enabled && it.role == TargetRole.LEAF && it.identity.kind == "address" }
                    .map { bitcoinMainnetAddress(it.identity.canonicalKey) }
                    .toSet()
            val accepted = projection.latest(account.id)
            val discovered =
                targets.filter { it.enabled && it.role == TargetRole.WALLET }.all { wallet ->
                    targets.any { it.enabled && it.walletId == wallet.id }
                }
            AccountOverview(
                account,
                targets.filter { it.walletId == null },
                targets.count { it.walletId != null },
                catalog.portfolios.filter { account.id in membership.getValue(it.id) }.map { it.label },
                valuations(balance.copy(quantities = balance.quantities.filterKeys { it.account == account.id }), quote, now()),
                accepted,
                discovered &&
                    active.isNotEmpty() &&
                    accepted?.addresses == active &&
                    accepted.providerSourceId == "esplora:" + sha256(sourceEndpoint()),
                accepted?.let { endpoints[it.providerSourceId] },
            )
        }
    }

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
            forgetSourceSuccess()
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
        forgetSourceSuccess()
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

    /** Wallet expressions that derive the same first address watch the same coins, whatever text they were saved with. */
    private fun sameWalletSource(
        first: String,
        second: String,
    ): Boolean =
        first == second || runCatching { bitcoinWallet(first).address(0, 0) == bitcoinWallet(second).address(0, 0) }.getOrDefault(false)

    /**
     * Saves a consented public wallet and optional ownership records as one local change, and returns the wallet ID that
     * discovery must use. Importing a wallet that is already tracked resumes it: the tracked wallet and its account are
     * kept, and discovery and history run again. Empty duplicates left by failed imports of the same wallet are removed,
     * together with an account created for them that holds nothing else.
     */
    fun saveBitcoinWallet(
        target: TrackingTarget,
        newAccount: Account? = null,
        newOwner: Owner? = null,
        consentAccepted: Boolean = false,
        providerEndpoint: String? = null,
        portfolio: PortfolioId? = null,
    ): TrackingTargetId {
        require(target.identity.networkId == BITCOIN_MAINNET && target.identity.kind == "wallet") { "Expected a Bitcoin public wallet" }
        require(target.label.isNotBlank()) { "Enter a wallet name" }
        val normalized = normalizeTarget(target)
        val walletId =
            db.transaction {
                require(sourceConsent() || consentAccepted) { "Source consent required" }
                if (providerEndpoint != null) {
                    require(
                        providerEndpoint.trimEnd('/') == sourceEndpoint() || consentAccepted,
                    ) { "Consent is required for the selected provider" }
                    setSourceEndpoint(providerEndpoint)
                }
                val catalog = ownership.loadInTransaction()
                val equivalent =
                    catalog.targets.filter {
                        it.role == TargetRole.WALLET &&
                            it.identity.networkId == BITCOIN_MAINNET &&
                            it.identity.kind == "wallet" &&
                            sameWalletSource(it.identity.canonicalKey, normalized.identity.canonicalKey)
                    }

                fun hasAddresses(wallet: TrackingTarget) = catalog.targets.any { it.walletId == wallet.id }
                val reused = equivalent.firstOrNull(::hasAddresses) ?: equivalent.firstOrNull()
                val duplicates = equivalent.filter { it != reused && !hasAddresses(it) }.map { it.id }.toSet()
                val remainingTargets = catalog.targets.filterNot { it.id in duplicates }
                // An account created only for a removed duplicate is removed when nothing else uses it.
                val emptyAccounts =
                    catalog.targets
                        .filter { it.id in duplicates }
                        .map { it.accountId }
                        .filter { accountId ->
                            accountId != reused?.accountId &&
                                remainingTargets.none { it.accountId == accountId } &&
                                catalog.accounts.none { it.parentId == accountId } &&
                                catalog.portfolios.none { accountId in it.accountIds } &&
                                db.query("SELECT 1 FROM ledger_movements WHERE account_id=? LIMIT 1", listOf(accountId.value)).isEmpty()
                        }.toSet()
                require(reused != null || catalog.targets.none { it.id == normalized.id }) { "Target already exists" }
                require(reused == null || newAccount != null || target.accountId == reused.accountId) {
                    val label = catalog.accounts.firstOrNull { it.id == reused?.accountId }?.label ?: "another account"
                    "This wallet is already tracked in account \"$label\""
                }
                val wallet = reused ?: normalized
                val createdAccount = if (reused == null) newAccount else null
                val createdOwner = if (reused == null) newOwner else null
                require(createdOwner == null || createdAccount != null && createdAccount.ownerId == createdOwner.id) {
                    "Owner requires a matching new account"
                }
                if (createdOwner != null) {
                    require(createdOwner.label.isNotBlank()) { "Enter an owner name" }
                    require(catalog.owners.none { it.id == createdOwner.id }) { "Owner already exists" }
                }
                if (createdAccount != null) {
                    require(createdAccount.id == wallet.accountId) { "Wallet account does not match" }
                    require(createdAccount.label.isNotBlank()) { "Enter an account name" }
                    require(catalog.accounts.none { it.id == createdAccount.id }) { "Account already exists" }
                }
                val account =
                    requireNotNull(
                        createdAccount ?: catalog.accounts.firstOrNull { it.id == wallet.accountId },
                    ) { "Unknown account" }
                require(account.kind != AccountKind.GROUP && "polion:archived" !in account.tags) { "Select an active account" }
                val byId = catalog.accounts.associateBy { it.id }
                var parent = account.parentId
                while (parent != null) {
                    val ancestor = requireNotNull(byId[parent]) { "Unknown parent account" }
                    require("polion:archived" !in ancestor.tags) { "Select an active account group" }
                    parent = ancestor.parentId
                }
                val portfolios =
                    catalog.portfolios.map { selected ->
                        if (selected.id != portfolio) {
                            selected
                        } else {
                            require(
                                selected.ownerIds.isEmpty() || account.ownerId in selected.ownerIds,
                            ) { "Select an owner included in this portfolio" }
                            if (selected.accountIds.isEmpty()) selected else selected.copy(accountIds = selected.accountIds + account.id)
                        }
                    }
                require(portfolio == null || catalog.portfolios.any { it.id == portfolio }) { "Unknown portfolio" }
                ownership.replaceInTransaction(
                    catalog.copy(
                        portfolios = portfolios,
                        owners = catalog.owners + listOfNotNull(createdOwner),
                        accounts = catalog.accounts.filterNot { it.id in emptyAccounts } + listOfNotNull(createdAccount),
                        targets = if (reused == null) remainingTargets + normalized else remainingTargets,
                    ),
                )
                if (consentAccepted) putSetting("source_consent", "true")
                forgetSourceSuccess()
                wallet.id
            }
        invalidate()
        return walletId
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
        forgetSourceSuccess()
    }

    /**
     * Standalone copies of a wallet address that the wallet's owner tracks in another account. Discovery moves them into
     * the wallet, because the wallet now covers that address; another owner's copy stays a conflict.
     */
    private fun adoptableAddresses(
        snapshot: OwnershipSnapshot,
        address: String,
        accountId: AccountId,
    ): List<TrackingTarget> {
        val owners = snapshot.accounts.associate { it.id to it.ownerId }
        val owner = owners[accountId] ?: return emptyList()
        return snapshot.targets.filter {
            it.role == TargetRole.LEAF &&
                it.identity.networkId == BITCOIN_MAINNET &&
                it.identity.kind == "address" &&
                it.identity.canonicalKey == address &&
                it.walletId == null &&
                it.accountId != accountId &&
                owners[it.accountId] == owner
        }
    }

    /**
     * An address that another account (or another wallet) already tracks cannot join this wallet, unless it is a
     * standalone copy the same owner keeps (see [adoptableAddresses]). The message names that account.
     */
    private fun discoveryConflict(
        snapshot: OwnershipSnapshot,
        address: String,
        accountId: AccountId,
        walletId: TrackingTargetId,
        childId: TrackingTargetId,
    ): BitcoinDiscoveryConflict? {
        val conflict =
            snapshot.targets.firstOrNull {
                it.id != childId &&
                    it.role == TargetRole.LEAF &&
                    it.identity.networkId == BITCOIN_MAINNET &&
                    it.identity.kind == "address" &&
                    it.identity.canonicalKey == address &&
                    !(it.accountId == accountId && (it.walletId == null || it.walletId == walletId))
            } ?: return null
        val label = snapshot.accounts.firstOrNull { it.id == conflict.accountId }?.label ?: "another account"
        return BitcoinDiscoveryConflict(
            "This address is already tracked in account \"$label\". Remove it there, then retry wallet discovery.",
        )
    }

    /** Discovers public addresses; requests disclose each derived address to the selected provider. */
    fun discoverBitcoinWallet(
        walletId: TrackingTargetId,
        transport: EsploraTransport,
        gapLimit: Int = 20,
        maxAddressesPerBranch: Int = 1000,
        providerEndpoint: String? = null,
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
                val adoptable = adoptableAddresses(catalog, address, target.accountId).toSet()
                discoveryConflict(catalog.copy(targets = catalog.targets - adoptable), address, target.accountId, walletId, id)?.let {
                    throw it
                }
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
        var moved = emptyList<TrackingTarget>()
        val scopeNotice =
            when {
                used == 0 ->
                    "No used addresses in ${children.size} checked. Check the expression: a bare xpub is read as native SegWit (BIP84) only."
                wallet.ranged && wallet.branchCount == 1 -> "The descriptor covers one branch; change addresses are not scanned."
                else -> null
            }
        // Merge into the catalog as it is at commit time, so edits saved during the network scan survive.
        db.transaction {
            require(sourceConsent()) { "Source consent required" }
            providerEndpoint?.let { endpoint ->
                if (sourceEndpoint() != endpoint) throw BitcoinProviderException(BitcoinProviderFailure.HISTORY_CHANGED)
            }
            val fresh = ownership.loadInTransaction()
            require(fresh.targets.firstOrNull { it.id == walletId } == target) { "Wallet changed during discovery; retry" }
            moved = children.flatMap { adoptableAddresses(fresh, it.identity.canonicalKey, target.accountId) }
            val remaining = fresh.copy(targets = fresh.targets - moved.toSet())
            children.forEach { child ->
                discoveryConflict(remaining, child.identity.canonicalKey, target.accountId, walletId, child.id)?.let { throw it }
            }
            ownership.replaceInTransaction(remaining.copy(targets = remaining.targets.filterNot { it.id in childIds } + children))
            forgetSourceSuccess()
        }
        invalidate()
        val movedNotice =
            moved.takeIf { it.isNotEmpty() }?.let { addresses ->
                val labels =
                    addresses
                        .mapNotNull { leaf -> catalog.accounts.firstOrNull { it.id == leaf.accountId }?.label }
                        .distinct()
                        .joinToString { "\"$it\"" }
                "Moved ${addresses.size} address${if (addresses.size == 1) "" else "es"} from $labels into this wallet."
            }
        return BitcoinWalletDiscovery(children.size, used, listOfNotNull(scopeNotice, movedNotice).joinToString(" ").ifEmpty { null })
    }

    /**
     * Wallets that need address discovery before a history refresh: enabled wallets with no addresses yet (for example
     * after an interrupted import) and wallets whose last applied scan saw a used address inside the trailing gap, so
     * later addresses may now be in use. History refresh only scans addresses that discovery has added.
     */
    fun bitcoinWalletsNeedingDiscovery(gapLimit: Int = 20): List<TrackingTargetId> {
        val catalog = ownership.load()
        val archived =
            catalog.accounts
                .filter { "polion:archived" in it.tags }
                .map { it.id }
                .toSet()
        val counts = acceptedScan()?.counts.orEmpty()
        return catalog.targets
            .filter {
                it.enabled &&
                    it.role == TargetRole.WALLET &&
                    it.identity.networkId == BITCOIN_MAINNET &&
                    it.identity.kind == "wallet" &&
                    it.accountId !in archived
            }.filter { wallet ->
                val children = catalog.targets.filter { it.walletId == wallet.id }
                if (children.isEmpty()) return@filter true
                val prefix = "bitcoin-wallet-child-" + sha256(wallet.id.value) + ":"
                children
                    .mapNotNull { child ->
                        val position =
                            child.id.value
                                .removePrefix(prefix)
                                .split(':')
                        val branch = position.getOrNull(0)?.toIntOrNull()
                        val index = position.getOrNull(1)?.toIntOrNull()
                        if (branch == null || index == null) null else Triple(branch, index, child.identity.canonicalKey)
                    }.groupBy { it.first }
                    .values
                    .any { branch ->
                        val last = branch.maxOf { it.second }
                        branch.any { (_, index, address) ->
                            val seen = counts[address]
                            index > last - gapLimit && seen != null && (seen.first > 0 || seen.second > 0)
                        }
                    }
            }.map { it.id }
    }

    /**
     * Discovers [bitcoinWalletsNeedingDiscovery] before a history refresh. A wallet that cannot be discovered because of an
     * address conflict or the discovery limit is skipped with its message, so other wallets still refresh; provider
     * failures propagate and stop the refresh.
     */
    fun discoverPendingBitcoinWallets(
        transport: EsploraTransport,
        providerEndpoint: String = sourceEndpoint(),
        gapLimit: Int = 20,
    ): List<String> =
        bitcoinWalletsNeedingDiscovery(gapLimit).mapNotNull { walletId ->
            try {
                discoverBitcoinWallet(walletId, transport, gapLimit, providerEndpoint = providerEndpoint).notice
            } catch (failure: IllegalArgumentException) {
                failure.message ?: "A wallet could not be discovered; retry from its connection."
            }
        }

    fun savePortfolio(value: Portfolio) =
        updateOwnership {
            it.copy(
                portfolios =
                    it.portfolios.filterNot { old -> old.id == value.id } + value,
            )
        }

    /** Read-modify-write in one transaction, so concurrent edits cannot be lost between load and replace. */
    private fun updateOwnership(change: (OwnershipSnapshot) -> OwnershipSnapshot) =
        db.transaction { ownership.replaceInTransaction(change(ownership.loadInTransaction())) }

    /** Commit all linked legs, treatments and notes together, retaining append-only manual revisions. */
    fun saveTransaction(
        draft: TransactionDraft,
        editing: Boolean = false,
    ) {
        ensureDefaultAssets()
        val previous = activities.history(draft.id).lastOrNull()
        require(if (editing) previous?.manual == true else previous == null) { "Entry identity already exists or cannot be edited" }
        require(previous == null || previous.status == io.polion.core.ledger.ActivityStatus.CONFIRMED) {
            "Only active manual entries can be edited"
        }
        require(
            overlays.overlay(draft.id)?.second?.let {
                it.amounts.isEmpty() && it.kind == null
            } != false ||
                !editing,
        ) { "Edit this corrected entry through its correction tools" }
        val accounts =
            scopedAccounts(ownership.load(), null).intersect(
                ownership
                    .load()
                    .accounts
                    .filter {
                        it.kind != AccountKind.GROUP
                    }.map { it.id }
                    .toSet(),
            )
        require(draft.account in accounts && (draft.destination == null || draft.destination in accounts)) { "Select active accounts" }
        if (draft.destination != null) {
            val catalog = ownership.load().accounts.associateBy { it.id }
            require(catalog.getValue(draft.account).ownerId == catalog.getValue(draft.destination).ownerId) {
                "Internal transfers require accounts with the same owner"
            }
        }
        val assets = listOfNotNull(draft.asset, draft.quote, draft.feeAsset).distinct()
        val precision = assets.associateWith { requireNotNull(market.metadataAt(it, draft.atMillis)) { "Unknown asset" }.decimals }
        val prepared = TransactionDrafts.prepare(draft, precision)
        db.transaction {
            activities.saveInTransaction(prepared.activity)
            treatments.saveInTransaction(draft.id, prepared.treatments)
            overlays.saveOverlayInTransaction(
                ActivityOverlay(
                    draft.id,
                    label =
                        overlays
                            .overlay(
                                draft.id,
                            )?.second
                            ?.label,
                    note = draft.notes,
                ),
            )
            db.execute(
                "INSERT OR REPLACE INTO application_settings VALUES (?, ?)",
                listOf("transaction_draft:${draft.id.value}", draft.encode()),
            )
        }
        invalidate()
    }

    /** Effective evidence intersecting the requested scope; callers paginate after computing summary metrics. */
    fun transactionEntries(filter: ActivityFilter): List<EffectiveActivity> {
        val accounts = scopedAccounts(ownership.load(), filter.portfolio).filter { filter.account == null || it == filter.account }.toSet()
        return ledger()
            .activities
            .filter { entry ->
                val a = entry.activity
                a.movements.any { it.accountId in accounts && (filter.asset == null || it.amount.assetId == filter.asset) } &&
                    (filter.kind == null || a.kind == filter.kind) &&
                    (filter.status == null || a.status == filter.status) &&
                    (
                        filter.search.isBlank() ||
                            listOfNotNull(entry.label, entry.note, a.id.value, a.kind.name).any { it.contains(filter.search, true) }
                    )
            }.sortedWith(compareByDescending<EffectiveActivity> { it.activity.occurredAtEpochMillis }.thenBy { it.activity.id.value })
    }

    fun transactionAccounts(portfolio: PortfolioId?): Set<AccountId> = scopedAccounts(ownership.load(), portfolio)

    fun transactionDraft(id: ActivityId): TransactionDraft? =
        setting("transaction_draft:${id.value}")?.let(TransactionDraft::decode)?.let { draft ->
            overlays.overlay(id)?.let { draft.copy(notes = it.second.note.orEmpty()) } ?: draft
        }

    /** Reversal is a retained manual revision; imported evidence can only be corrected by overlays. */
    fun reverseManualTransaction(id: ActivityId) {
        val previous = requireNotNull(activities.history(id).lastOrNull()) { "Unknown entry" }
        require(previous.manual && previous.status == io.polion.core.ledger.ActivityStatus.CONFIRMED) {
            "Only active manual entries can be reversed"
        }
        activities.save(previous.copy(status = io.polion.core.ledger.ActivityStatus.REVERSED))
        invalidate()
    }

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
    ): Int = overlays.setDecision(first, second, state).also { invalidate() }

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

    fun useProviderPrices(
        base: AssetId,
        quote: AssetId,
    ): Int = market.withdrawActiveManualPrices(base, quote, now())

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

    /**
     * Retires the example records. The ledger is append-only, so example transactions are reversed rather than deleted;
     * example prices are withdrawn so they cannot override real quotes; the example account is archived and the example
     * portfolio removed; an owner still named "Example owner" becomes "Personal". Each step can be repeated, and the
     * example flag is cleared last, so an interrupted removal can be retried.
     */
    fun removeExampleData() {
        require(setting("sample_data") == "true") { "No example data to remove" }
        val at = now().toString()
        db.transaction {
            db.query("SELECT id FROM market_prices WHERE manual=1 AND id LIKE 'demo-price-%'").forEach { row ->
                db.execute("INSERT OR IGNORE INTO market_override_withdrawals VALUES (?, ?, ?)", listOf(row.single(), at, at))
            }
        }
        activities
            .current()
            .filter { it.manual && it.id.value.startsWith("demo-") && it.status != io.polion.core.ledger.ActivityStatus.REVERSED }
            .forEach { activities.save(it.copy(status = io.polion.core.ledger.ActivityStatus.REVERSED)) }
        val example = AccountId("demo-wallet")
        if (ownership.load().accounts.any { it.id == example && "polion:archived" !in it.tags }) archiveAccount(example)
        updateOwnership { catalog ->
            catalog.copy(
                portfolios = catalog.portfolios.filterNot { it.id.value == "demo-portfolio" },
                owners =
                    catalog.owners.map {
                        if (it.id.value == "demo-owner" && it.label == "Example owner") it.copy(label = "Personal") else it
                    },
            )
        }
        db.execute("DELETE FROM application_settings WHERE key='sample_data'")
        invalidate()
    }

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

    /** Exports all matching activity movements in the requested scope, independently of on-screen pagination. */
    fun exportCsv(
        filter: ActivityFilter = ActivityFilter(),
        activityIds: Set<ActivityId>? = null,
    ): String {
        fun cell(
            value: String,
            text: Boolean,
        ): String {
            val first = value.dropWhile { it.isWhitespace() || it < ' ' }.firstOrNull()
            val unsafe = first in listOf('=', '+', '-', '@') || value.firstOrNull() in listOf('\t', '\r', '\n')
            val safe = if (text && unsafe) "'" + value else value
            return "\"" + safe.replace("\"", "\"\"") + "\""
        }
        val lines = mutableListOf("activity_id,occurred_at,kind,status,account,asset,atomic_units,role,label,note")
        val selected = scopedAccounts(ownership.load(), filter.portfolio)
        filterActivitiesByNetwork(overlays.replayForLedger(activities.current()).activities, filter.network).forEach { item ->
            val activity = item.activity
            if (activityIds != null && activity.id !in activityIds) return@forEach
            if (filter.kind != null && activity.kind != filter.kind) return@forEach
            if (filter.status != null && activity.status != filter.status) return@forEach
            if (filter.search.isNotBlank() &&
                listOfNotNull(item.label, item.note, activity.id.value, activity.kind.name)
                    .none { it.contains(filter.search, ignoreCase = true) }
            ) {
                return@forEach
            }
            activity.movements
                .filter { movement ->
                    movement.accountId in selected &&
                        (filter.account == null || movement.accountId == filter.account) &&
                        (filter.asset == null || movement.amount.assetId == filter.asset)
                }.forEach { movement ->
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
                        ).mapIndexed { index, value -> cell(value, index in setOf(0, 4, 5, 8, 9)) }.joinToString(",")
                }
        }
        return lines.joinToString("\r\n", postfix = "\r\n")
    }

    /** Streams an encrypted backup of every durable table into [output]. */
    fun exportBackup(
        password: CharArray,
        output: ByteSink,
    ) {
        val encrypted = BackupEncryption.encrypt(output, password)
        PortableBackup.discover(db).export(db, encrypted)
        encrypted.close()
    }

    /** In-memory form of [exportBackup], for small data and tests. */
    fun exportBackup(password: CharArray): ByteArray = ByteArraySink().also { exportBackup(password, it) }.toByteArray()

    /** Decrypts [input] and restores it into the empty [staging] database; the live database is untouched. */
    fun restoreToStaging(
        staging: SqliteDatabase,
        input: ByteSource,
        password: CharArray,
    ) {
        PortfolioApplication(staging, now)
        PortableBackup.discover(db).restoreToStaging(staging, BackupEncryption.decrypt(input, password))
    }

    /** In-memory form of [restoreToStaging], for small data and tests. */
    fun restoreToStaging(
        staging: SqliteDatabase,
        archive: ByteArray,
        password: CharArray,
    ) = restoreToStaging(staging, ByteArraySource(archive), password)

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
