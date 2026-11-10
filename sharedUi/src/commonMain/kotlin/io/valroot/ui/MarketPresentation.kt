package io.valroot.ui

import io.valroot.core.application.AssetValuation
import io.valroot.core.application.MarketCoin
import io.valroot.core.application.MarketOverview
import io.valroot.core.market.AssetMetadata
import io.valroot.core.market.MarketListing
import io.valroot.core.market.PriceObservation
import io.valroot.core.market.PriceStatus
import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.Price
import io.valroot.core.model.ProviderId
import io.valroot.core.model.Rounding
import io.valroot.ui.model.ModelMainState
import io.valroot.ui.model.ModelMarketCoin
import kotlin.math.abs
import kotlin.math.roundToLong

/** The ranked universe every market section names, so a "top gainer" is never presented as a whole-market claim. */
internal const val MARKET_UNIVERSE = "TOP 250 BY MARKET CAP · 24H"
private const val TOP_COINS_SHOWN = 20
private const val MOVERS_SHOWN = 5

/** Unit prices: two decimals from 1 upward, four significant digits below it. */
internal fun marketPrice(
    value: ExactDecimal,
    currency: AssetId,
): String {
    if (value >= ExactDecimal.parse("1")) return money(value, currency)
    val text = value.toString()
    val firstDigit = text.substringAfter('.', "").indexOfFirst { it != '0' }
    val scale = if (firstDigit < 0) 2 else (firstDigit + 4).coerceIn(2, 12)
    val prefix =
        when (currency.value) {
            "USD" -> "$"
            "EUR" -> "€"
            "GBP" -> "£"
            else -> "${currency.value} "
        }
    return prefix + value.rescale(scale, Rounding.HALF_EVEN).toString()
}

/** "+1.23%", "-4.56%" or "—" for an unknown change. */
internal fun percentChange(value: Double?): String {
    if (value == null || !value.isFinite()) return "—"
    val hundredths = (abs(value) * 100).roundToLong()
    val sign =
        if (hundredths == 0L) {
            ""
        } else if (value < 0) {
            "-"
        } else {
            "+"
        }
    return "$sign${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}%"
}

/** Scales a price series into 0..1 with at most [points] samples; flat or short series draw nothing. */
internal fun sparklinePoints(
    prices: List<Double>,
    points: Int = 42,
): List<Float?> {
    if (prices.size < 2) return emptyList()
    val sampled = if (prices.size <= points) prices else List(points) { prices[it * (prices.size - 1) / (points - 1)] }
    val low = sampled.min()
    val high = sampled.max()
    if (high == low) return sampled.map { .5f }
    return sampled.map { ((it - low) / (high - low)).toFloat() }
}

/** "Updated 5 minutes ago" style freshness plus the reason the feed is not newer, if any. */
internal fun marketStatus(
    overview: MarketOverview,
    nowMillis: Long,
): String? {
    if (!overview.consent) return null
    val observed = overview.markets?.observedAtMillis
    val age = observed?.let { "Updated ${relativeAgo(it)}" }
    val problem =
        when {
            overview.retryAfterMillis != null && overview.retryAfterMillis!! > nowMillis ->
                "CoinGecko is limiting requests; try again in ${((overview.retryAfterMillis!! - nowMillis) / 1000).coerceAtLeast(1)} s" +
                    if (overview.hasApiKey) "" else " or add a free API key in Settings"
            overview.lastFailure == "OFFLINE" -> "Offline"
            overview.lastFailure == "ACCESS_DENIED" -> "CoinGecko rejected the API key"
            overview.lastFailure != null -> "CoinGecko returned unreadable data"
            else -> null
        }
    return listOfNotNull(age ?: if (problem == null) "Not loaded yet" else null, problem, "Data by CoinGecko").joinToString(" · ")
}

internal fun marketMainState(
    overview: MarketOverview?,
    savedCoins: List<MarketCoin>,
    favorites: Set<AssetId>,
    nowMillis: Long,
): ModelMainState {
    val currency = overview?.currency ?: AssetId("USD")
    val listings = overview?.markets?.items.orEmpty()

    fun key(coinId: String) = overview?.assetKeys?.get(coinId) ?: AssetId("coingecko:$coinId")

    fun coin(listing: MarketListing) =
        ModelMarketCoin(
            identity(key(listing.coinId).value, listing.symbol, listing.name),
            marketPrice(listing.price, currency),
            percentChange(listing.change24h),
            // The card's change is 24h, so its line is the last 24 hourly points of the 7-day series.
            sparklinePoints(listing.sparkline.takeLast(25)),
            favorite = key(listing.coinId) in favorites,
        )
    val trending =
        overview?.trending?.items.orEmpty().map { item ->
            ModelMarketCoin(
                identity(key(item.coinId).value, item.symbol, item.name),
                item.price?.let { marketPrice(it, currency) } ?: "—",
                percentChange(item.change24h),
                favorite = key(item.coinId) in favorites,
            )
        }
    val saved =
        savedCoins.map { coin ->
            ModelMarketCoin(
                identity(coin.asset.value, coin.metadata?.symbol, coin.metadata?.name),
                "${quotePrice(coin.price.price.value, coin.price.price.quoteAssetId)} · ${coin.priceStatus.name.lowercase()}",
                "—",
                favorite = coin.asset in favorites,
            )
        }
    val consent = overview?.consent == true
    val loaded = listings.isNotEmpty()
    val empty = if (!consent) "Market data is off" else "No market data yet. Refresh to load it."
    val ranked = listings.filter { it.change24h != null }
    val favoriteCoins =
        (listings.map(::coin) + trending + saved).filter { it.identity.id in favorites.map(AssetId::value) }.distinctBy { it.identity.id }
    return ModelMainState(
        topCoins = if (loaded) listings.take(TOP_COINS_SHOWN).map(::coin) else saved,
        favorites = favoriteCoins,
        trending = trending,
        gainers =
            ranked
                .filter { it.change24h!! > 0 }
                .sortedByDescending { it.change24h }
                .take(MOVERS_SHOWN)
                .map(::coin),
        losers =
            ranked
                .filter { it.change24h!! < 0 }
                .sortedBy { it.change24h }
                .take(MOVERS_SHOWN)
                .map(::coin),
        topCoinsTitle = if (loaded || saved.isEmpty()) "Top Coins" else "Saved prices",
        topCoinsEyebrow = if (loaded || saved.isEmpty()) "BY MARKET CAP · 24H CHANGE" else "LOCAL MARKET DATA",
        topCoinsEmptyMessage = empty,
        favoritesEmptyMessage = "No favorites yet. Tap ☆ on a coin to add it.",
        // The load card and Top Coins explain why lists are empty; the other sections stay short.
        trendingEmptyMessage =
            if (!consent) {
                "Off"
            } else if (loaded) {
                "No trending coins yet"
            } else {
                "—"
            },
        gainersEmptyMessage =
            if (!consent) {
                "Off"
            } else if (loaded) {
                "No coin rose in the last 24 hours"
            } else {
                "—"
            },
        losersEmptyMessage =
            if (!consent) {
                "Off"
            } else if (loaded) {
                "No coin fell in the last 24 hours"
            } else {
                "—"
            },
        canToggleFavorites = true,
        moversEyebrow = MARKET_UNIVERSE,
        status = overview?.let { marketStatus(it, nowMillis) },
        loadAction = if (overview != null && !consent) "Load market data" else null,
    )
}

/** A market-only coin page needs a valuation; quantity and value stay unknown because nothing is held. */
internal fun MarketListing.detailValuation(
    key: AssetId,
    currency: AssetId,
    observedAtMillis: Long,
): AssetValuation =
    AssetValuation(
        key,
        AtomicAmount(key, ExactInteger.ZERO),
        AssetMetadata(symbol, name, 8, 0),
        PriceStatus.FRESH,
        null,
        PriceObservation(
            "coingecko:markets:$coinId:$observedAtMillis",
            Price(key, currency, price),
            ProviderId("coingecko"),
            observedAtMillis,
            observedAtMillis,
        ),
    )

internal fun io.valroot.core.market.TrendingListing.detailValuation(
    key: AssetId,
    currency: AssetId,
    observedAtMillis: Long,
): AssetValuation =
    AssetValuation(
        key,
        AtomicAmount(key, ExactInteger.ZERO),
        AssetMetadata(symbol, name, 8, 0),
        if (price == null) PriceStatus.MISSING else PriceStatus.FRESH,
        null,
        price?.let {
            PriceObservation(
                "coingecko:trending:$coinId:$observedAtMillis",
                Price(key, currency, it),
                ProviderId("coingecko"),
                observedAtMillis,
                observedAtMillis,
            )
        },
    )

/** Chart labels for sparkline prices, which CoinGecko sends as floating-point numbers. */
internal fun sparklinePrice(
    value: Double,
    currency: AssetId,
): String {
    val units = (value * 100_000_000).roundToLong().coerceAtLeast(0)
    return marketPrice(ExactDecimal(ExactInteger.parse(units.toString()), 8), currency)
}
