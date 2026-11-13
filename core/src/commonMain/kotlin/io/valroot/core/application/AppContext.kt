package io.valroot.core.application

import io.valroot.core.accounting.TreatmentRepository
import io.valroot.core.alerts.NotificationRepository
import io.valroot.core.bitcoin.BITCOIN_ASSET
import io.valroot.core.bitcoin.BitcoinGraphRepository
import io.valroot.core.bitcoin.BitcoinScanStage
import io.valroot.core.connectors.HistoryFetcher
import io.valroot.core.connectors.HistoryPage
import io.valroot.core.connectors.HistoryRequest
import io.valroot.core.evm.EvmChainRepository
import io.valroot.core.evm.EvmRecoveredRepository
import io.valroot.core.exchange.ExchangeCoinRepository
import io.valroot.core.exchange.ExchangeSyncRepository
import io.valroot.core.holdings.HoldingsRepository
import io.valroot.core.ledger.ActivityRepository
import io.valroot.core.market.AssetLinkRepository
import io.valroot.core.market.MarketFeedRepository
import io.valroot.core.market.MarketRepository
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.reconcile.AmountScale
import io.valroot.core.reconcile.EffectiveLedger
import io.valroot.core.reconcile.OverlayRepository
import io.valroot.core.storage.OwnershipRepository
import io.valroot.core.storage.SqliteDatabase
import io.valroot.core.storage.Store

/**
 * State shared by the services of one [PortfolioApplication]: the database, the clock, the repositories, the
 * replayed ledger with its holdings projection, and the application settings table.
 *
 * The ledger is replayed once and kept until a write calls [invalidate]; holdings are rebuilt from it on the next read.
 */
internal class AppContext(
    val db: SqliteDatabase,
    val now: () -> Long,
) {
    internal lateinit var app: PortfolioApplication
    val store = Store(db)
    val ownership = OwnershipRepository(db)
    val activities = ActivityRepository(db)
    val overlays = OverlayRepository(db)
    val marketFeeds = MarketFeedRepository(db)
    val holdings = HoldingsRepository(db)
    val market = MarketRepository(db)
    val treatments = TreatmentRepository(db)
    val notifications = NotificationRepository(db)
    val csvImports = CsvImportRepository(db)
    val evmChains = EvmChainRepository(db)
    val evmRecovered = EvmRecoveredRepository(db)
    val assetLinks = AssetLinkRepository(db)
    val exchangeCoins = ExchangeCoinRepository(db)
    val exchangeSync = ExchangeSyncRepository(db)
    val bitcoinStage = BitcoinScanStage(db, store, activities)
    val bitcoinGraph = BitcoinGraphRepository(db)
    private var cachedLedger: EffectiveLedger? = null

    /** False after a write until the holdings projection is rebuilt from the current ledger. */
    var holdingsCurrent = false

    fun ledger(): EffectiveLedger = cachedLedger ?: overlays.replayForLedger(activities.current(), amountScale()).also { cachedLedger = it }

    /**
     * Amounts of one CoinGecko coin in any asset compare as decimals, so an exchange withdrawal can be linked to its
     * arrival as a token. Assets without a coin or without known decimals compare only with themselves.
     */
    fun amountScale(): AmountScale {
        val links = assetLinks.links()
        val decimals = mutableMapOf<AssetId, Int?>()
        return AmountScale { amount ->
            val asset = amount.assetId
            val coin = links[asset] ?: if (asset == BITCOIN_ASSET) "bitcoin" else null
            val places = decimals.getOrPut(asset) { market.metadataAt(asset, Long.MAX_VALUE)?.decimals }
            if (coin == null || places == null) {
                asset.value to ExactDecimal(amount.atomicUnits, 0)
            } else {
                "coin:$coin" to ExactDecimal(amount.atomicUnits, places)
            }
        }
    }

    fun invalidate() {
        cachedLedger = null
        holdingsCurrent = false
    }

    fun setting(key: String): String? = db.query("SELECT value FROM application_settings WHERE key=?", listOf(key)).singleOrNull()?.single()

    fun putSetting(
        key: String,
        value: String,
    ) = db.execute(
        "INSERT OR REPLACE INTO application_settings VALUES (?, ?)",
        listOf(key, value),
    )

    fun fetcher(block: (HistoryRequest) -> HistoryPage): HistoryFetcher =
        object : HistoryFetcher {
            override fun fetch(request: HistoryRequest): HistoryPage = block(request)
        }
}

/**
 * Base of the application's feature services. It exposes the shared [AppContext] under the names the services use and
 * reaches other services through [app].
 */
abstract class AppService internal constructor(
    private val ctx: AppContext,
) {
    protected val app: PortfolioApplication get() = ctx.app
    protected val db get() = ctx.db
    protected val now get() = ctx.now
    protected val store get() = ctx.store
    protected val ownership get() = ctx.ownership
    protected val activities get() = ctx.activities
    protected val overlays get() = ctx.overlays
    protected val marketFeeds get() = ctx.marketFeeds
    protected val holdings get() = ctx.holdings
    protected val market get() = ctx.market
    protected val treatments get() = ctx.treatments
    protected val notifications get() = ctx.notifications
    internal val csvImports get() = ctx.csvImports
    protected val evmChains get() = ctx.evmChains
    protected val evmRecovered get() = ctx.evmRecovered
    protected val assetLinks get() = ctx.assetLinks
    protected val exchangeCoins get() = ctx.exchangeCoins
    protected val exchangeSync get() = ctx.exchangeSync
    protected val bitcoinStage get() = ctx.bitcoinStage
    protected val bitcoinGraph get() = ctx.bitcoinGraph
    protected var holdingsCurrent: Boolean
        get() = ctx.holdingsCurrent
        set(value) {
            ctx.holdingsCurrent = value
        }

    protected fun ledger() = ctx.ledger()

    protected fun amountScale() = ctx.amountScale()

    protected fun invalidate() = ctx.invalidate()

    protected fun setting(key: String) = ctx.setting(key)

    protected fun putSetting(
        key: String,
        value: String,
    ) = ctx.putSetting(key, value)

    protected fun fetcher(block: (HistoryRequest) -> HistoryPage) = ctx.fetcher(block)
}
