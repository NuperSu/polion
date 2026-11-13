package io.valroot.core.application

import io.valroot.core.alerts.NotificationService
import io.valroot.core.backup.BackupService
import io.valroot.core.bitcoin.BitcoinService
import io.valroot.core.bitcoin.BitcoinWalletService
import io.valroot.core.evm.EvmService
import io.valroot.core.exchange.ExchangeService
import io.valroot.core.holdings.BalanceCoverage
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.market.LinkedCoinService
import io.valroot.core.market.MarketService
import io.valroot.core.model.AssetId
import io.valroot.core.ownership.AccountService
import io.valroot.core.reconcile.EffectiveActivity
import io.valroot.core.storage.SqliteDatabase

/**
 * The application over one database: shared persisted workflows grouped into feature services that share one
 * [AppContext]. A source connector is required before coverage can be complete.
 */
class PortfolioApplication(
    db: SqliteDatabase,
    now: () -> Long,
) {
    private val ctx = AppContext(db, now).also { it.app = this }
    val assets: AssetService = AssetService(ctx)
    val linkedCoins: LinkedCoinService = LinkedCoinService(ctx)
    val evm: EvmService = EvmService(ctx)
    val exchanges: ExchangeService = ExchangeService(ctx)
    val bitcoin: BitcoinService = BitcoinService(ctx)
    val bitcoinWallets: BitcoinWalletService = BitcoinWalletService(ctx)
    val sources: SourceService = SourceService(ctx)
    val analytics: AnalyticsService = AnalyticsService(ctx)
    val accounting: AccountingService = AccountingService(ctx)
    val prices: PriceService = PriceService(ctx)
    val settings: SettingsService = SettingsService(ctx)
    val market: MarketService = MarketService(ctx)
    val notifications: NotificationService = NotificationService(ctx)
    val accounts: AccountService = AccountService(ctx)
    val transactions: TransactionService = TransactionService(ctx)
    val csv: CsvService = CsvService(ctx)
    val backup: BackupService = BackupService(ctx)
    val demo: DemoService = DemoService(ctx)

    fun reload() = ctx.invalidate()

    init {
        ctx.db.execute("CREATE TABLE IF NOT EXISTS application_settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        sources.sync(ctx.fetcher { error("No transport configured") })
    }

    fun snapshot(filter: ActivityFilter = ActivityFilter()): PortfolioSnapshot {
        assets.ensureDefaults()
        val at = ctx.now()
        val catalog = ctx.ownership.load()
        val selected = assets.scopedAccounts(catalog, filter.portfolio)
        val ledger = ctx.ledger()
        if (!ctx.holdingsCurrent) {
            ctx.holdings.refresh(ledger.activities.map { it.activity })
            ctx.holdingsCurrent = true
        }
        val coverage = if (ctx.setting("source_last_success") != null) BalanceCoverage.PARTIAL else BalanceCoverage.UNKNOWN
        val fullBalance = ctx.holdings.current(coverage, at)
        val balance = fullBalance.copy(quantities = fullBalance.quantities.filterKeys { it.account in selected })
        val currency =
            filter.portfolio?.let { id -> AssetId(catalog.portfolios.single { it.id == id }.valuationCurrency) } ?: assets.currency()
        val secondary = settings.general().secondaryCurrency?.takeIf { it != currency }
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
                compareByDescending<EffectiveActivity> { it.activity.status == io.valroot.core.ledger.ActivityStatus.PENDING }
                    .thenByDescending { it.activity.occurredAtEpochMillis }
                    .thenBy { it.activity.id.value },
            )
        return PortfolioSnapshot(
            catalog,
            balance,
            assets.valuations(balance, currency, at, prices.policy(current = true)),
            filtered.drop(filter.offset).take(filter.limit),
            filtered.size,
            currency,
            coverage,
            ledger.conflicts,
            SourceStatus(
                ctx.setting("source_last_success")?.toLong(),
                ctx.setting("source_error"),
                ctx.setting("source_in_progress") == "true",
                bitcoin.utxoReview(limit = 1).total,
                ctx.setting("utxo_comparison_available") != "false",
                ctx.setting("pending_capped")?.toIntOrNull() ?: 0,
            ),
            ctx.setting("sample_data") == "true",
            ledger.activities.count {
                it.activity.status == io.valroot.core.ledger.ActivityStatus.PENDING &&
                    it.activity.movements.any { movement -> movement.accountId in selected }
            },
            sources.endpoint(),
            sources.consent(),
            if (filter.includeNetworkChoices) {
                ctx.db
                    .query(
                        "SELECT DISTINCT network FROM raw_records WHERE network <> '' ORDER BY network",
                    ).mapNotNull {
                        it.single()
                    }
            } else {
                emptyList()
            },
            ctx.market.savedPricedAssets(currency, at, at).mapNotNull { asset ->
                val result = ctx.market.lookup(asset, currency, at, at, prices.policy(current = true))
                result.observation?.let { MarketCoin(asset, ctx.market.metadataAt(asset, at), result.status, it) }
            },
            secondary,
            secondary?.let { assets.valuations(balance, it, at, prices.policy(current = true)) }.orEmpty(),
            assets.valuations(balance, currency, at, prices.policy(current = true), hidden = true),
        )
    }

    /** Match the current activity's raw source IDs; manual records have no inferred network. */
    internal fun filterActivitiesByNetwork(
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
                ctx.db
                    .query("SELECT id FROM raw_records WHERE network=? AND id IN ($placeholders)", listOf(network) + ids)
                    .forEach { row -> matching.add(requireNotNull(row.single())) }
            }
            result.addAll(batch.filter { item -> item.activity.sources.any { it.value in matching } })
            offset += batch.size
        }
        return result
    }

    /** The application clock, for connectors that time their own lookups. */
    fun clockMillis(): Long = ctx.now()
}
