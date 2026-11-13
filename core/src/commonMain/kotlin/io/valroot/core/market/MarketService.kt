package io.valroot.core.market

import io.valroot.core.application.AppContext
import io.valroot.core.application.AppService
import io.valroot.core.application.MarketOverview
import io.valroot.core.application.MarketRefreshResult
import io.valroot.core.application.ORDER_BOOK_DEPTH
import io.valroot.core.bitcoin.BITCOIN_ASSET
import io.valroot.core.model.AssetId
import io.valroot.core.model.ProviderId

/** Market data: CoinGecko market lists and favourites, logos, and the Kraken order book. */
class MarketService internal constructor(
    ctx: AppContext,
) : AppService(ctx) {
    fun favorites(): Set<AssetId> =
        setting("market_favorites")
            ?.lineSequence()
            ?.filter { it.isNotBlank() }
            ?.map(::AssetId)
            ?.toSet()
            .orEmpty()

    fun setFavorites(assets: Set<AssetId>) {
        require(assets.all { it.value.isNotBlank() && '\n' !in it.value && '\r' !in it.value }) { "Invalid asset ID" }
        putSetting("market_favorites", assets.map { it.value }.sorted().joinToString("\n"))
    }

    /** Cached CoinGecko feeds for the display currency, with the request state Main needs to explain gaps. */
    fun overview(): MarketOverview {
        val currency = app.assets.currency()
        val code = currency.value.lowercase()
        val markets = marketFeeds.markets(code)
        val trending = marketFeeds.trending(code)
        val ids = (markets?.items.orEmpty().map { it.coinId } + trending?.items.orEmpty().map { it.coinId }).toSet()
        return MarketOverview(
            currency,
            consent(),
            markets,
            trending,
            marketFeeds.deviceSetting("market_feed_error"),
            marketFeeds.deviceSetting("coingecko_retry_after")?.toLongOrNull()?.takeIf { it > now() },
            coingeckoApiKey() != null,
            market.providerMappings(ProviderId(CoingeckoPrices.PROVIDER_ID)).let { mapped ->
                ids.associateWith { id -> if (id == "bitcoin") BITCOIN_ASSET else mapped[id] ?: AssetId("coingecko:$id") }
            },
            app.linkedCoins.assetLogos(
                (
                    markets?.items.orEmpty().mapNotNull { coin -> coin.image?.let { coin.coinId to it } } +
                        trending?.items.orEmpty().mapNotNull { coin -> coin.image?.let { coin.coinId to it } }
                ).toMap(),
            ),
        )
    }

    /** A cached CoinGecko logo, or null when it was never fetched or is over 30 days old. */
    fun cachedLogo(url: String): ByteArray? = marketFeeds.logo(url, now())

    /** Logos are cached only for CoinGecko image URLs and only after market data consent. */
    fun saveLogo(
        url: String,
        bytes: ByteArray,
    ) {
        require(consent() && CoingeckoMarkets.logoUrl(url) == url)
        marketFeeds.saveLogo(url, bytes, now())
    }

    fun consent(): Boolean = setting("market_data_consent") == "true"

    fun setConsent(accepted: Boolean) = putSetting("market_data_consent", accepted.toString())

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
    fun refreshFeeds(
        transport: (url: String, apiKey: String?) -> String,
        maxAgeMillis: Long = 60_000L,
        baseUrl: String = "https://api.coingecko.com/api/v3",
    ): MarketRefreshResult {
        require(consent()) { "Market data consent required" }
        val started = now()
        marketFeeds.deviceSetting("coingecko_retry_after")?.toLongOrNull()?.takeIf { it > started }?.let {
            return MarketRefreshResult(0, CoingeckoProviderFailure.RATE_LIMITED, it)
        }
        val code =
            app.assets
                .currency()
                .value
                .lowercase()
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

    /** The CoinGecko coin ID for a portfolio or market asset, or null when it has none. */
    fun coinId(asset: AssetId): String? =
        assetLinks.coinOf(asset) ?: when {
            asset == BITCOIN_ASSET -> "bitcoin"
            asset.value.startsWith("coingecko:") -> asset.value.removePrefix("coingecko:")
            else ->
                market
                    .providerMappings(ProviderId(CoingeckoPrices.PROVIDER_ID))
                    .entries
                    .firstOrNull { it.value == asset }
                    ?.key
        }

    /** Order book requests send the pair and the device's IP address to Kraken; a separate consent from prices. */
    fun orderBookConsent(): Boolean = setting("order_book_consent") == "true"

    fun setOrderBookConsent(accepted: Boolean) = putSetting("order_book_consent", accepted.toString())

    /**
     * The Kraken market for [coinId] in the display currency. Bitcoin is Kraken's XBT; other coins use the Kraken markets
     * CoinGecko lists for that coin ID, cached on this device for a week, so a coin is never matched by ticker alone.
     * Null when Kraken has no market in the display currency.
     */
    fun krakenPair(
        coinId: String,
        coingecko: (url: String, apiKey: String?) -> String,
        baseUrl: String = "https://api.coingecko.com/api/v3",
    ): KrakenPair? {
        val currency = app.assets.currency().value
        if (coinId == "bitcoin") return KrakenPair("XBT", currency)
        val key = "kraken_pairs:$coinId"
        val cached =
            marketFeeds.deviceSetting(key)?.split(';')?.takeIf { it.size == 2 && now() - (it[0].toLongOrNull() ?: 0L) < 7 * 86_400_000L }
        val pairs =
            if (cached != null) {
                cached[1].split(',').filter { it.contains('/') }.map { KrakenPair(it.substringBefore('/'), it.substringAfter('/')) }
            } else {
                require(consent()) { "Market data consent required" }
                marketFeeds.deviceSetting("coingecko_retry_after")?.toLongOrNull()?.takeIf { it > now() }?.let {
                    throw CoingeckoProviderException(CoingeckoProviderFailure.RATE_LIMITED)
                }
                KrakenOrderBook.parseKrakenPairs(coingecko(KrakenOrderBook.tickersUrl(baseUrl, coinId), coingeckoApiKey()), coinId).also {
                    marketFeeds.putDeviceSetting(key, "${now()};" + it.joinToString(",") { pair -> pair.display })
                }
            }
        return KrakenOrderBook.pairFor(pairs, currency)
    }

    /** A fresh Kraken order book snapshot for [pair]; [kraken] performs the request. Needs order book consent. */
    fun orderBook(
        pair: KrakenPair,
        kraken: (url: String) -> String,
        depth: Int = ORDER_BOOK_DEPTH,
    ): OrderBookSnapshot {
        require(orderBookConsent()) { "Order book consent required" }
        val payload = kraken(KrakenOrderBook.depthUrl(pair, depth))
        return KrakenOrderBook.parseDepth(payload, pair, now(), depth)
    }
}
