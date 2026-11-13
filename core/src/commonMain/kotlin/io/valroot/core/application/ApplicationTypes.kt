package io.valroot.core.application

import io.valroot.core.accounting.AccountingReport
import io.valroot.core.holdings.BalanceCoverage
import io.valroot.core.holdings.BalanceView
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.market.AssetMetadata
import io.valroot.core.market.CoingeckoProviderFailure
import io.valroot.core.market.MarketFeed
import io.valroot.core.market.PriceObservation
import io.valroot.core.market.PriceStatus
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.PortfolioId
import io.valroot.core.ownership.TrackingTarget
import io.valroot.core.reconcile.DuplicateConflict
import io.valroot.core.reconcile.EffectiveActivity
import io.valroot.core.storage.OwnershipSnapshot

data class ActivityFilter(
    val search: String = "",
    val portfolio: PortfolioId? = null,
    val account: AccountId? = null,
    val asset: AssetId? = null,
    val kind: ActivityKind? = null,
    val offset: Int = 0,
    val limit: Int = 50,
    val status: io.valroot.core.ledger.ActivityStatus? = null,
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
    val incoming: io.valroot.core.model.ExactInteger,
    val outgoing: io.valroot.core.model.ExactInteger,
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
    /** Addresses with more unconfirmed transactions than the provider lists; the rest appear once they confirm. */
    val pendingCapped: Int = 0,
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
    /** Held assets that are hidden: unknown tokens and NFTs until the user shows them. Not in [valuations] or totals. */
    val hiddenAssets: List<AssetValuation> = emptyList(),
)

/** Wallet discovery refused an address because another account already tracks it. The message is safe to show. */
class BitcoinDiscoveryConflict(
    message: String,
) : IllegalArgumentException(message)

data class MarketOverview(
    val currency: AssetId,
    val consent: Boolean,
    val markets: io.valroot.core.market.MarketFeed<io.valroot.core.market.MarketListing>?,
    val trending: io.valroot.core.market.MarketFeed<io.valroot.core.market.TrendingListing>?,
    /** Name of the [CoingeckoProviderFailure] from the last refresh, or null after a success. */
    val lastFailure: String?,
    /** Epoch millis until which CoinGecko feed requests pause after a rate limit. */
    val retryAfterMillis: Long?,
    val hasApiKey: Boolean,
    /** CoinGecko coin ID to the asset ID used for favorites and coin pages. */
    val assetKeys: Map<String, AssetId>,
    /** Logo URLs for EVM assets linked to a coin, by asset. */
    val assetLogos: Map<AssetId, String> = emptyMap(),
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

/** The default public Esplora endpoint. */
const val DEFAULT_ENDPOINT = "https://blockstream.info/api"

/** The most unconfirmed transactions Esplora returns for one address. */
const val MEMPOOL_PAGE = 50L

/** Privacy lock delays offered in Settings: immediately, 1, 5 and 15 minutes, and 1 hour. */
val LOCK_DELAYS = listOf(0L, 60_000L, 300_000L, 900_000L, 3_600_000L)

/** A 24-hour move at least this large, in percent, is a significant price change. */
const val SIGNIFICANT_MOVE_PERCENT = 10.0

/** A notification check refreshes a market list older than this. */
const val NOTIFICATION_MARKET_AGE_MILLIS = 10 * 60_000L

/** Order book levels shown per side. */
const val ORDER_BOOK_DEPTH = 10

/** US dollars, the only currency DefiLlama quotes. */
internal val USD = AssetId("USD")

/** Delivered notifications kept in the history. */
const val NOTIFICATION_HISTORY_SIZE = 500

/** Fiat currencies with automatic Kraken prices. */
val DISPLAY_CURRENCIES = listOf("USD", "EUR", "GBP")
