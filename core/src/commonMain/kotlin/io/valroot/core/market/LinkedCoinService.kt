package io.valroot.core.market

import io.valroot.core.application.AppContext
import io.valroot.core.application.AppService
import io.valroot.core.application.USD
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.ProviderId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** CoinGecko linked-coin history ranges for currencies other than USD: daily prices for a year first, then hourly prices for 90 days. */
private val LINKED_HISTORY_DAYS = listOf(365, 90)

/** DefiLlama hourly history covers this many recent days; daily history reaches back to a coin's first movement. */
private const val LINKED_HOURLY_DAYS = 90

/** A covered range whose end is older than this is extended to now; newer gaps are left to current prices. */
private const val LINKED_HISTORY_EXTEND_AFTER_MILLIS = 86_400_000L

/** Saved linked-coin logo URLs are refetched after a week. */
private const val LINKED_LOGO_MAX_AGE_MILLIS = 7 * 86_400_000L

/** Prices, price history and logos for assets linked to a CoinGecko coin (EVM tokens and exchange coins). */
class LinkedCoinService internal constructor(
    ctx: AppContext,
) : AppService(ctx) {
    /** The display and secondary currencies; USD is left to DefiLlama, so CoinGecko quotes only the others. */
    private fun linkedCurrencies(): List<AssetId> =
        listOfNotNull(app.assets.currency(), app.settings.general().secondaryCurrency).distinct().filter { it != USD }

    /** True when USD is the display or secondary currency, which DefiLlama prices. */
    private fun usdWanted(): Boolean = app.assets.currency() == USD || app.settings.general().secondaryCurrency == USD

    /**
     * Current CoinGecko prices for held coins linked to EVM assets, in the display and secondary currencies other than
     * USD, from one keyless request per 50 coins; USD comes from [refreshUsdPrices]. Each price is saved for
     * every asset linked to that coin, so ETH on Base and on Ethereum get the same observation. Needs market data
     * consent. Returns how many prices were saved.
     */
    fun refreshPrices(
        transport: (url: String, apiKey: String?) -> String,
        baseUrl: String = "https://api.coingecko.com/api/v3",
    ): Int {
        require(app.market.consent()) { "Market data consent required" }
        val links = shownLinks()
        val currencies = linkedCurrencies()
        if (links.isEmpty() || currencies.isEmpty()) return 0
        val observed = now()
        val prices = mutableListOf<PriceObservation>()
        links.values.distinct().sorted().chunked(50).forEach { batch ->
            val url =
                "$baseUrl/simple/price?ids=${batch.joinToString(",")}&vs_currencies=" +
                    currencies.joinToString(",") { it.value.lowercase() } + "&include_last_updated_at=true"
            val document = Json.parseToJsonElement(transport(url, app.market.coingeckoApiKey())).jsonObject
            batch.forEach { coin ->
                val entry = document[coin] as? kotlinx.serialization.json.JsonObject ?: return@forEach
                val validAt =
                    (entry["last_updated_at"] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.content
                        ?.toLongOrNull()
                        ?.times(1000)
                        ?.takeIf { it in 1..observed } ?: observed
                currencies.forEach { quote ->
                    val number =
                        (entry[quote.value.lowercase()] as? kotlinx.serialization.json.JsonPrimitive)
                            ?.takeIf { !it.isString }
                            ?.content
                    val value =
                        number?.let { runCatching { CoingeckoPrices.parseJsonNumber(it) }.getOrNull() }?.takeIf {
                            it.coefficient.sign >
                                0
                        }
                    if (value != null) {
                        links.filterValues { it == coin }.keys.forEach { asset ->
                            prices +=
                                PriceObservation(
                                    "coingecko:${asset.value}:${quote.value}:$validAt",
                                    io.valroot.core.model
                                        .Price(asset, quote, value),
                                    ProviderId(CoingeckoPrices.PROVIDER_ID),
                                    validAt,
                                    observed,
                                )
                        }
                    }
                }
            }
        }
        market.recordChangedProviderPrices(prices)
        return prices.size
    }

    /**
     * Current DefiLlama USD prices for held coins linked to EVM assets, one keyless request per 50 coins, when USD is the
     * display or secondary currency. Each price is saved for every asset linked to the coin. Needs market data consent.
     * Returns how many prices were saved.
     */
    fun refreshUsdPrices(
        fetch: (url: String) -> String,
        baseUrl: String = DefiLlamaPrices.BASE_URL,
    ): Int {
        require(app.market.consent()) { "Market data consent required" }
        val links = shownLinks()
        if (links.isEmpty() || !usdWanted()) return 0
        val observed = now()
        val prices =
            links.values.distinct().sorted().chunked(DefiLlamaPrices.CURRENT_BATCH).flatMap { batch ->
                DefiLlamaPrices.parseCurrent(fetch(DefiLlamaPrices.currentUrl(baseUrl, batch))).flatMap { (coin, point) ->
                    usdPrices(links, coin, listOf(point.first.coerceAtMost(observed) to point.second), observed)
                }
            }
        market.recordChangedProviderPrices(prices)
        return prices.size
    }

    /** One DefiLlama observation per asset linked to [coin] and point. */
    private fun usdPrices(
        links: Map<AssetId, String>,
        coin: String,
        points: List<Pair<Long, io.valroot.core.model.ExactDecimal>>,
        observed: Long,
    ): List<PriceObservation> =
        points.filter { (at, _) -> at in 1..observed }.flatMap { (at, value) ->
            links.filterValues { it == coin }.keys.map { asset ->
                PriceObservation(
                    "${DefiLlamaPrices.PROVIDER_ID}:${asset.value}:USD:$at",
                    io.valroot.core.model
                        .Price(asset, USD, value),
                    ProviderId(DefiLlamaPrices.PROVIDER_ID),
                    at,
                    observed,
                )
            }
        }

    /**
     * Fills the USD price history of linked coins the ledger has moved from DefiLlama, so the portfolio chart can value
     * them back to their first movement.
     *
     * - Daily prices reach from a day before the coin's first movement to now; hourly prices cover the last
     *   [LINKED_HOURLY_DAYS] days. Daily ranges of every coin come before hourly ones, and coins held now come first.
     * - Each request returns up to [DefiLlamaPrices.MAX_POINTS] points. The covered range per coin and period is saved,
     *   so each range is fetched once; a range ending more than a day ago is extended to now.
     * - Makes at most [maxRequests] requests, calling [pause] with [intervalMillis] before each.
     *
     * Does nothing unless USD is the display or secondary currency. Returns the number of requests made, so 0 means
     * every range is covered.
     */
    fun backfillUsdHistory(
        fetch: (url: String) -> String,
        baseUrl: String = DefiLlamaPrices.BASE_URL,
        maxRequests: Int = 40,
        intervalMillis: Long = 1_000L,
        pause: (Long) -> Unit = {},
    ): Int {
        require(app.market.consent()) { "Market data consent required" }
        if (!usdWanted()) return 0
        val firstMoved = mutableMapOf<AssetId, Long>()
        ledger().activities.forEach { entry ->
            entry.activity.movements.forEach { movement ->
                val asset = movement.amount.assetId
                firstMoved[asset] = minOf(firstMoved[asset] ?: Long.MAX_VALUE, entry.activity.occurredAtEpochMillis)
            }
        }
        val hidden = app.assets.hiddenAmong(firstMoved.keys)
        val links = assetLinks.links().filterKeys { it in firstMoved && it !in hidden }
        val held = app.assets.held()
        val coins =
            links.values
                .distinct()
                .sortedWith(compareBy<String>({ coin -> links.none { (asset, linked) -> linked == coin && asset in held } }, { it }))
        val firstByCoin = coins.associateWith { coin -> links.filterValues { it == coin }.keys.minOf { firstMoved.getValue(it) } }
        var requests = 0
        for (period in DefiLlamaPrices.Period.entries) {
            for (coin in coins) {
                while (true) {
                    val now = now()
                    val step = period.stepMillis
                    val start =
                        maxOf(
                            firstByCoin.getValue(coin) - step,
                            if (period == DefiLlamaPrices.Period.HOURLY) now - LINKED_HOURLY_DAYS * 86_400_000L else 0L,
                            0L,
                        )
                    val marker = "linked_usd_history:$coin:${period.query}"
                    val covered =
                        marketFeeds
                            .deviceSetting(marker)
                            ?.split(':')
                            ?.mapNotNull { it.toLongOrNull() }
                            ?.takeIf { it.size == 2 }
                    val chunk = DefiLlamaPrices.MAX_POINTS * step
                    val (from, to) =
                        when {
                            covered == null -> maxOf(start, now - chunk) to now
                            now - covered[1] > LINKED_HISTORY_EXTEND_AFTER_MILLIS -> covered[1] to minOf(now, covered[1] + chunk)
                            covered[0] > start -> maxOf(start, covered[0] - chunk) to covered[0]
                            else -> break
                        }
                    if (requests >= maxRequests) return requests
                    requests++
                    pause(intervalMillis)
                    val span = ((to - from + step - 1) / step).toInt().coerceIn(1, DefiLlamaPrices.MAX_POINTS)
                    val points =
                        DefiLlamaPrices
                            .parseChart(
                                fetch(DefiLlamaPrices.chartUrl(baseUrl, coin, from, span, period)),
                            )[coin]
                            .orEmpty()
                    // Unchanged points are not recorded twice, so a range fetched again after an interruption adds nothing.
                    market.recordChangedProviderPrices(usdPrices(links, coin, points, now()))
                    // A range with no prices (before the coin existed, or unknown to DefiLlama) still counts as covered.
                    marketFeeds.putDeviceSetting(marker, "${minOf(from, covered?.get(0) ?: from)}:${maxOf(to, covered?.get(1) ?: to)}")
                }
            }
        }
        return requests
    }

    /** Held, shown EVM assets by their linked CoinGecko coin. */
    private fun shownLinks(): Map<AssetId, String> {
        val held = app.assets.held()
        val hidden = app.assets.hiddenAmong(held)
        return assetLinks.links().filterKeys { it in held && it !in hidden }
    }

    /**
     * Fills the price history of linked coins the ledger has moved from CoinGecko's `/market_chart`, once per coin and
     * currency other than USD (see [backfillUsdHistory]), so the portfolio chart can value them before the
     * address was tracked. The keyless API reaches back one year. Every coin first gets a year of daily prices, which
     * keeps each chart point within the one-day price age; then 90 days of hourly prices. Makes at most [maxRequests]
     * requests per call, calling [pause] with [intervalMillis] before each so a paced transport accepts it; the rest
     * follow on later calls. Returns the number of prices recorded.
     */
    fun backfillHistory(
        transport: (url: String, apiKey: String?) -> String,
        baseUrl: String = "https://api.coingecko.com/api/v3",
        maxRequests: Int = 12,
        intervalMillis: Long = 6_100L,
        pause: (Long) -> Unit = {},
    ): Int {
        require(app.market.consent()) { "Market data consent required" }
        // Every linked asset the ledger has moved, not only today's holdings: a coin sold since still values past points.
        val moved =
            ledger()
                .activities
                .flatMap { entry -> entry.activity.movements.map { it.amount.assetId } }
                .toSet()
        val hidden = app.assets.hiddenAmong(moved)
        val links = assetLinks.links().filterKeys { it in moved && it !in hidden }
        val currencies = linkedCurrencies()
        // Coins held now come first: they carry today's value, and the free API allows only a few requests a minute.
        val held = app.assets.held()
        val coins =
            links.values
                .distinct()
                .sortedWith(compareBy<String>({ coin -> links.none { (asset, linked) -> linked == coin && asset in held } }, { it }))
        var requests = 0
        var recorded = 0
        for (days in LINKED_HISTORY_DAYS) {
            for (coin in coins) {
                for (quote in currencies) {
                    val marker = "linked_history:$coin:${quote.value}:$days"
                    if (marketFeeds.deviceSetting(marker) != null) continue
                    if (requests++ >= maxRequests) return recorded
                    pause(intervalMillis)
                    val url = "$baseUrl/coins/$coin/market_chart?vs_currency=${quote.value.lowercase()}&days=$days"
                    val points = CoingeckoPrices.parseChart(transport(url, app.market.coingeckoApiKey()))
                    val observed = now()
                    val prices =
                        points.filter { (at, _) -> at in 1..observed }.flatMap { (at, value) ->
                            links.filterValues { it == coin }.keys.map { asset ->
                                PriceObservation(
                                    "coingecko:${asset.value}:${quote.value}:$at",
                                    io.valroot.core.model
                                        .Price(asset, quote, value),
                                    ProviderId(CoingeckoPrices.PROVIDER_ID),
                                    at,
                                    observed,
                                )
                            }
                        }
                    // Unchanged points are not recorded twice, so a range fetched again after an interruption adds nothing.
                    market.recordChangedProviderPrices(prices)
                    marketFeeds.putDeviceSetting(marker, observed.toString())
                    recorded += prices.size
                }
            }
        }
        return recorded
    }

    /**
     * Fetches logo URLs for held linked coins with one `/coins/markets` request by coin ID, when a coin has none saved
     * yet or the saved set is older than a week.
     */
    fun refreshLogos(
        transport: (url: String, apiKey: String?) -> String,
        baseUrl: String = "https://api.coingecko.com/api/v3",
    ) {
        require(app.market.consent()) { "Market data consent required" }
        val coins =
            shownLinks()
                .values
                .distinct()
                .sorted()
                .take(CoingeckoMarkets.MARKET_SIZE)
        val saved = coinLogos()
        val fetchedAt = marketFeeds.deviceSetting("linked_coin_logos_at")?.toLongOrNull() ?: 0L
        if (coins.isEmpty() || (coins.all { it in saved } && now() - fetchedAt < LINKED_LOGO_MAX_AGE_MILLIS)) return
        val url =
            "$baseUrl/coins/markets?vs_currency=usd&ids=${coins.joinToString(",")}&per_page=${CoingeckoMarkets.MARKET_SIZE}&page=1" +
                "&sparkline=false"
        val logos =
            CoingeckoMarkets.parseMarkets(transport(url, app.market.coingeckoApiKey())).mapNotNull { coin ->
                coin.image?.let {
                    coin.coinId to
                        it
                }
            }
        db.transaction {
            marketFeeds.putDeviceSetting(
                "linked_coin_logos",
                kotlinx.serialization.json
                    .JsonObject((saved + logos).mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) })
                    .toString(),
            )
            marketFeeds.putDeviceSetting("linked_coin_logos_at", now().toString())
        }
    }

    private fun coinLogos(): Map<String, String> =
        marketFeeds
            .deviceSetting("linked_coin_logos")
            ?.let { saved ->
                runCatching {
                    Json
                        .parseToJsonElement(saved)
                        .jsonObject
                        .mapNotNull { (coin, url) ->
                            CoingeckoMarkets.logoUrl((url as? kotlinx.serialization.json.JsonPrimitive)?.content)?.let { coin to it }
                        }.toMap()
                }.getOrNull()
            }.orEmpty()

    /** Logo URLs for linked EVM assets, from their coin's saved logo or the cached market feeds. */
    internal fun assetLogos(feedLogos: Map<String, String>): Map<AssetId, String> {
        val saved = coinLogos()
        return assetLinks.links().mapNotNull { (asset, coin) -> (saved[coin] ?: feedLogos[coin])?.let { asset to it } }.toMap()
    }
}
