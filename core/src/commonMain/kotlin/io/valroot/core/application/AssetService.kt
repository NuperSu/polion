package io.valroot.core.application

import io.valroot.core.accounting.QuoteValue
import io.valroot.core.bitcoin.BITCOIN_ASSET
import io.valroot.core.bitcoin.BITCOIN_MAINNET
import io.valroot.core.evm.EvmAssets
import io.valroot.core.holdings.BalanceCoverage
import io.valroot.core.holdings.BalanceView
import io.valroot.core.market.AssetDefinition
import io.valroot.core.market.AssetMetadata
import io.valroot.core.market.CoingeckoPrices
import io.valroot.core.market.PricePolicy
import io.valroot.core.market.PriceResult
import io.valroot.core.market.PriceStatus
import io.valroot.core.model.AccountId
import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.PortfolioId
import io.valroot.core.model.ProviderId
import io.valroot.core.model.Rational
import io.valroot.core.storage.OwnershipSnapshot

/** A token is judged against a 1inch listing only if it first moved at least this long before the listing. */
private const val ONE_INCH_LISTING_MARGIN_MILLIS = 3_600_000L

/** Assets and their values: the portfolio currency, default assets, which assets are shown, and valuation of balances. */
class AssetService internal constructor(
    ctx: AppContext,
) : AppService(ctx) {
    /**
     * Assets hidden from values and lists. An EVM token without a CoinGecko coin, a token 1inch leaves out of the
     * address's app.analytics.history (it omits tokens it rates as scams), and every NFT start hidden, because such tokens are often
     * spam; the user's choice to show or hide an asset always wins.
     */
    fun hidden(): Set<AssetId> = hiddenAmong(held())

    /** Assets with a recorded balance in any account, from up-to-date holdings. */
    internal fun held(): Set<AssetId> {
        if (!holdingsCurrent) {
            holdings.refresh(ledger().activities.map { it.activity })
            holdingsCurrent = true
        }
        return holdings
            .current(BalanceCoverage.UNKNOWN, now())
            .quantities
            .filterValues { it.sign != 0 }
            .keys
            .map { it.asset }
            .toSet()
    }

    internal fun hiddenAmong(assets: Set<AssetId>): Set<AssetId> {
        if (assets.none { it.value.startsWith("eip155:") }) return emptySet()
        val links = assetLinks.links()
        val choices = assetLinks.choices()
        val unlisted = unlistedByOneInch(assets.filter { it !in choices && it in links && it.value.contains("/erc20:") })
        return assets
            .filter { asset ->
                choices[asset]?.not() ?: (
                    asset.value.startsWith("eip155:") &&
                        (asset.value.contains("/nft:") || (!asset.value.endsWith("/native") && (asset !in links || asset in unlisted)))
                )
            }.toSet()
    }

    /**
     * Tokens 1inch leaves out of every tracked address's history on their chain. A token counts only when 1inch has
     * listed every tracked address on the chain since the token first moved, so a token newer than the listing is not
     * judged.
     */
    private fun unlistedByOneInch(tokens: List<AssetId>): Set<AssetId> {
        if (tokens.isEmpty()) return emptySet()
        val listings = evmRecovered.listings()
        if (listings.isEmpty()) return emptySet()
        val tracked = evmChains.states().groupingBy { it.chainId }.eachCount()
        val candidates =
            tokens.filter { asset ->
                val chain =
                    io.valroot.core.evm.EvmAssets
                        .chainOf(asset) ?: return@filter false
                val chainListings = listings[chain] ?: return@filter false
                chainListings.size >= (tracked[chain] ?: 0) &&
                    chainListings.none { asset.value.substringAfter("/erc20:") in it.contracts }
            }
        if (candidates.isEmpty()) return emptySet()
        val firstMoved = mutableMapOf<AssetId, Long>()
        ledger().activities.forEach { entry ->
            entry.activity.movements.forEach { movement ->
                val asset = movement.amount.assetId
                if (asset in candidates) {
                    firstMoved[asset] = minOf(firstMoved[asset] ?: Long.MAX_VALUE, entry.activity.occurredAtEpochMillis)
                }
            }
        }
        return candidates
            .filter { asset ->
                val listed =
                    listings
                        .getValue(
                            io.valroot.core.evm.EvmAssets
                                .chainOf(asset)!!,
                        ).minOf { it.atMillis }
                (firstMoved[asset] ?: Long.MAX_VALUE) < listed - ONE_INCH_LISTING_MARGIN_MILLIS
            }.toSet()
    }

    fun setShown(
        asset: AssetId,
        shown: Boolean,
    ) = assetLinks.setShown(asset, shown)

    /** Every portfolio asset that is the CoinGecko coin [coinId], for example ETH on each EVM chain. */
    fun forCoin(coinId: String): List<AssetId> =
        (if (coinId == "bitcoin") listOf(BITCOIN_ASSET) else emptyList()) +
            assetLinks.assetsOf(coinId) +
            listOfNotNull(market.resolveProvider(ProviderId(CoingeckoPrices.PROVIDER_ID), coinId))

    internal fun scopedAccounts(
        catalog: OwnershipSnapshot,
        portfolioId: PortfolioId?,
    ): Set<AccountId> {
        if (portfolioId == null) {
            return catalog.accounts
                .filter { "valroot:archived" !in it.tags }
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
                "valroot:archived" !in account.tags && within && (portfolio.ownerIds.isEmpty() || account.ownerId in portfolio.ownerIds)
            }.map { it.id }
            .toSet()
    }

    /** The valuation currency of [portfolio], or the default display currency for all portfolios. */
    fun displayCurrency(portfolio: PortfolioId?): AssetId = portfolioCurrency(portfolio)

    internal fun portfolioCurrency(portfolio: PortfolioId?): AssetId =
        portfolio?.let { id ->
            AssetId(
                ownership
                    .load()
                    .portfolios
                    .single { it.id == id }
                    .valuationCurrency,
            )
        } ?: currency()

    /** Returns quote value per atomic unit only for a fresh observation; missing or stale prices stay null. */
    internal fun unitPrice(
        asset: AssetId,
        quote: AssetId,
        atMillis: Long,
    ): QuoteValue? {
        val decimals = market.metadataAt(asset, atMillis)?.decimals ?: return null
        val wholeUnitPrice =
            if (asset == quote ||
                io.valroot.core.exchange.ExchangeAssets
                    .currencyOf(asset) == quote
            ) {
                Rational.units(ExactInteger.parse("1"))
            } else {
                val result = market.lookup(asset, quote, atMillis, atMillis, app.prices.policy(current = true))
                val observation = result.observation?.takeIf { result.status == PriceStatus.FRESH } ?: return null
                Rational.fromDecimal(observation.price.value)
            }
        return QuoteValue(quote, wholeUnitPrice / Rational.units(ExactInteger.parse("1").timesPowerOfTen(decimals)))
    }

    internal fun valuations(
        balance: BalanceView,
        currency: AssetId,
        knownAt: Long,
        // Same rule everywhere: a balance within 24 hours of now uses the short current-price window.
        policy: PricePolicy = app.prices.policy(current = balance.atMillis >= knownAt - 86_400_000L),
        // Series callers pass prices and metadata resolved in bulk; the results are the same as the per-asset queries.
        priceAt: ((AssetId) -> PriceResult?)? = null,
        metadataAt: ((AssetId) -> AssetMetadata?)? = null,
        // Hidden assets (unknown tokens, NFTs) stay in the ledger but out of values and totals unless asked for.
        hidden: Boolean = false,
    ): List<AssetValuation> {
        val hiddenSet =
            hiddenAmong(
                balance.quantities.keys
                    .map { it.asset }
                    .toSet(),
            )
        return balance.quantities.entries
            .filter { (it.key.asset in hiddenSet) == hidden }
            .groupBy { it.key.asset }
            .map { (asset, rows) ->
                val units = rows.fold(io.valroot.core.model.ExactInteger.ZERO) { sum, row -> sum + row.value }
                val amount = AtomicAmount(asset, units)
                val metadata = if (metadataAt != null) metadataAt(asset) else market.metadataAt(asset, balance.atMillis)
                // A currency held on an exchange is worth one of the same currency.
                val own =
                    asset == currency ||
                        io.valroot.core.exchange.ExchangeAssets
                            .currencyOf(asset) == currency
                val result =
                    if (own) {
                        null
                    } else {
                        priceAt?.invoke(asset) ?: market.lookup(asset, currency, balance.atMillis, knownAt, policy)
                    }
                val value =
                    if (own) {
                        metadata?.let { amount.decimal(it.decimals) }
                    } else {
                        metadata?.let { result?.value(amount, it.decimals) }
                    }
                AssetValuation(
                    asset,
                    amount,
                    metadata,
                    if (own && metadata != null) {
                        PriceStatus.FRESH
                    } else {
                        result?.status ?: PriceStatus.MISSING
                    },
                    value,
                    result?.observation,
                )
            }.sortedBy { it.asset.value }
    }

    /** Assets a CSV row can name, with their current symbol and name, for choosing what unresolved text means. */
    fun all(): List<Pair<AssetId, AssetMetadata>> {
        ensureDefaults()
        return market.assetIds().mapNotNull { id -> market.metadataAt(id, Long.MAX_VALUE)?.let { id to it } }
    }

    fun register(
        definition: AssetDefinition,
        metadata: AssetMetadata,
    ) = market.registerAsset(definition, metadata)

    fun setCurrency(currency: AssetId) {
        ensureDefaults()
        require(market.asset(currency) != null) { "Unknown currency asset" }
        db.execute(
            "INSERT OR REPLACE INTO application_settings VALUES ('currency', ?)",
            listOf(currency.value),
        )
    }

    internal fun ensureDefaults() {
        for ((code, name) in listOf("USD" to "US Dollar", "EUR" to "Euro", "GBP" to "Pound Sterling")) {
            val currency = AssetId(code)
            if (market.asset(currency) == null) {
                market.registerAsset(
                    AssetDefinition(
                        currency,
                        io.valroot.core.market
                            .AssetIdentity(io.valroot.core.market.AssetKind.FIAT, null, code),
                    ),
                    AssetMetadata(code, name, 2, 0),
                )
            }
        }
        if (market.asset(BITCOIN_ASSET) == null) {
            market.registerAsset(
                AssetDefinition(
                    BITCOIN_ASSET,
                    io.valroot.core.market
                        .AssetIdentity(io.valroot.core.market.AssetKind.NATIVE, BITCOIN_MAINNET, "BTC"),
                ),
                AssetMetadata("BTC", "Bitcoin", 8, 0),
            )
        }
    }

    fun currency(): AssetId =
        AssetId(
            db.query("SELECT value FROM application_settings WHERE key='currency'").singleOrNull()?.single() ?: "USD",
        )
}
