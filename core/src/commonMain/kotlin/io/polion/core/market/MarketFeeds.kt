package io.polion.core.market

import io.polion.core.model.ExactDecimal
import io.polion.core.model.Rounding
import io.polion.core.storage.SqliteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** One coin from CoinGecko's market list, ranked by market capitalisation. Percent changes are display figures. */
data class MarketListing(
    val coinId: String,
    val symbol: String,
    val name: String,
    val rank: Int?,
    val price: ExactDecimal,
    val marketCap: ExactDecimal?,
    val change1h: Double?,
    val change24h: Double?,
    val change7d: Double?,
    /** Hourly prices over the last seven days, oldest first. */
    val sparkline: List<Double>,
    /** CoinGecko's small logo URL, or null when it sent none. */
    val image: String? = null,
    /** All-time high and low prices in the list currency, with the time each was set. */
    val allTimeHigh: ExactDecimal? = null,
    val allTimeHighAtMillis: Long? = null,
    val allTimeLow: ExactDecimal? = null,
    val allTimeLowAtMillis: Long? = null,
)

/** One coin from CoinGecko's search trending list. The price is derived from its BTC price when available. */
data class TrendingListing(
    val coinId: String,
    val symbol: String,
    val name: String,
    val rank: Int?,
    val price: ExactDecimal?,
    val change24h: Double?,
    val image: String? = null,
)

/** A cached feed and when CoinGecko returned it; feeds are cached independently. */
data class MarketFeed<T>(
    val currency: String,
    val observedAtMillis: Long,
    val items: List<T>,
)

/** Requests and parsing for the CoinGecko market feeds. Malformed entries are skipped; a malformed document is rejected. */
object CoingeckoMarkets {
    const val MARKET_SIZE = 250
    private val IMAGE =
        Regex(
            "https://(coin-images|assets)\\.coingecko\\.com/coins/images/[0-9]+/(thumb|small|large|standard)/[A-Za-z0-9._%+-]+(\\?[0-9]+)?",
        )

    private val SIZE = Regex("/(thumb|large|standard)/")
    private val CURRENCY = Regex("[a-z]{3,4}")

    /** CoinGecko's own logo URL in the small size; anything from another host is ignored. */
    fun logoUrl(url: String?): String? = url?.takeIf { it.length <= 300 && it.matches(IMAGE) }?.replace(SIZE, "/small/")

    private val COIN_ID = Regex("[a-z0-9][a-z0-9-]{0,99}")

    fun marketsUrl(
        baseUrl: String,
        currency: String,
    ): String {
        require(currency.matches(CURRENCY))
        return "$baseUrl/coins/markets?vs_currency=$currency&order=market_cap_desc&per_page=$MARKET_SIZE&page=1" +
            "&sparkline=true&price_change_percentage=1h,24h,7d"
    }

    fun trendingUrl(baseUrl: String) = "$baseUrl/search/trending"

    fun parseMarkets(payload: String): List<MarketListing> {
        val rows = Json.parseToJsonElement(payload).jsonArray
        require(rows.size <= MARKET_SIZE)
        return rows
            .mapNotNull { row ->
                runCatching {
                    val coin = row.jsonObject
                    MarketListing(
                        coinId = coin.text("id")!!.also { require(it.matches(COIN_ID)) },
                        symbol = coin.text("symbol")!!.uppercase().take(20),
                        name = coin.text("name")!!.take(100),
                        rank = coin.number("market_cap_rank")?.toIntOrNull(),
                        price = CoingeckoPrices.parseJsonNumber(coin.number("current_price")!!).also { require(it.coefficient.sign > 0) },
                        marketCap = coin.number("market_cap")?.let { runCatching { CoingeckoPrices.parseJsonNumber(it) }.getOrNull() },
                        change1h = coin.number("price_change_percentage_1h_in_currency")?.toDoubleOrNull(),
                        change24h = coin.number("price_change_percentage_24h_in_currency")?.toDoubleOrNull(),
                        change7d = coin.number("price_change_percentage_7d_in_currency")?.toDoubleOrNull(),
                        sparkline =
                            (coin["sparkline_in_7d"] as? JsonObject)
                                ?.get("price")
                                ?.let { it as? JsonArray }
                                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.content?.toDoubleOrNull() }
                                ?.filter { it.isFinite() && it > 0 }
                                ?.takeLast(200)
                                .orEmpty(),
                        image = logoUrl(coin.text("image")),
                        allTimeHigh = coin.positive("ath"),
                        allTimeHighAtMillis = coin.millis("ath_date"),
                        allTimeLow = coin.positive("atl"),
                        allTimeLowAtMillis = coin.millis("atl_date"),
                    )
                }.getOrNull()
            }.distinctBy { it.coinId }
    }

    /** [btcPrice] converts each trending coin's BTC price into the requested currency. */
    fun parseTrending(
        payload: String,
        currency: String,
        btcPrice: ExactDecimal?,
    ): List<TrendingListing> {
        val coins = Json.parseToJsonElement(payload).jsonObject["coins"]!!.jsonArray
        return coins
            .take(30)
            .mapNotNull { entry ->
                runCatching {
                    val item = entry.jsonObject["item"]!!.jsonObject
                    val data = item["data"] as? JsonObject
                    val priceBtc = item.number("price_btc")?.let { runCatching { CoingeckoPrices.parseJsonNumber(it) }.getOrNull() }
                    TrendingListing(
                        coinId = item.text("id")!!.also { require(it.matches(COIN_ID)) },
                        symbol = item.text("symbol")!!.uppercase().take(20),
                        name = item.text("name")!!.take(100),
                        rank = item.number("market_cap_rank")?.toIntOrNull(),
                        price =
                            if (priceBtc != null && btcPrice != null && priceBtc.coefficient.sign > 0) {
                                (priceBtc * btcPrice).rescaleDown(12)
                            } else {
                                null
                            },
                        change24h = (data?.get("price_change_percentage_24h") as? JsonObject)?.number(currency)?.toDoubleOrNull(),
                        image = logoUrl(item.text("small") ?: item.text("large") ?: item.text("thumb")),
                    )
                }.getOrNull()
            }.distinctBy { it.coinId }
    }

    private fun ExactDecimal.rescaleDown(maxScale: Int): ExactDecimal =
        if (scale <=
            maxScale
        ) {
            this
        } else {
            rescale(maxScale, Rounding.HALF_EVEN)
        }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.takeIf {
                it.isString
            }?.content
            ?.takeIf { it.isNotBlank() }

    private fun JsonObject.number(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.content

    private fun JsonObject.positive(key: String): ExactDecimal? =
        number(key)?.let { runCatching { CoingeckoPrices.parseJsonNumber(it) }.getOrNull() }?.takeIf { it.coefficient.sign > 0 }

    private fun JsonObject.millis(key: String): Long? =
        text(key)?.let {
            runCatching {
                kotlin.time.Instant
                    .parse(it)
                    .toEpochMilliseconds()
            }.getOrNull()
        }
}

/**
 * Cached market feeds and device-only settings. Neither table is part of encrypted backups: feeds are refetched, and
 * device settings such as API keys belong to one installation.
 */
class MarketFeedRepository(
    private val db: SqliteDatabase,
) {
    companion object {
        const val FEED_TABLE = "market_feed_cache"
        const val DEVICE_TABLE = "device_settings"
        const val LOGO_TABLE = "market_logo_cache"
        const val MAX_LOGO_BYTES = 256 * 1024
        val LOCAL_TABLES = listOf(FEED_TABLE, DEVICE_TABLE, LOGO_TABLE)
    }

    init {
        db.execute(
            "CREATE TABLE IF NOT EXISTS $FEED_TABLE (feed TEXT NOT NULL, currency TEXT NOT NULL, observed_at INTEGER NOT NULL, " +
                "payload TEXT NOT NULL, PRIMARY KEY(feed, currency))",
        )
        db.execute("CREATE TABLE IF NOT EXISTS $DEVICE_TABLE (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS $LOGO_TABLE (url TEXT PRIMARY KEY, fetched_at INTEGER NOT NULL, data TEXT NOT NULL)")
    }

    /** Cached logo bytes; logos older than [maxAgeMillis] count as missing so they are fetched again. */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    fun logo(
        url: String,
        nowMillis: Long,
        maxAgeMillis: Long = 30L * 86_400_000L,
    ): ByteArray? =
        db
            .query("SELECT fetched_at, data FROM $LOGO_TABLE WHERE url=?", listOf(url))
            .singleOrNull()
            ?.takeIf { nowMillis - it[0]!!.toLong() < maxAgeMillis }
            ?.let {
                kotlin.io.encoding.Base64
                    .decode(it[1]!!)
            }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    fun saveLogo(
        url: String,
        bytes: ByteArray,
        nowMillis: Long,
    ) {
        require(bytes.size <= MAX_LOGO_BYTES)
        db.execute(
            "INSERT OR REPLACE INTO $LOGO_TABLE VALUES (?, ?, ?)",
            listOf(
                url,
                nowMillis.toString(),
                kotlin.io.encoding.Base64
                    .encode(bytes),
            ),
        )
    }

    fun deviceSetting(key: String): String? = db.query("SELECT value FROM $DEVICE_TABLE WHERE key=?", listOf(key)).singleOrNull()?.single()

    fun putDeviceSetting(
        key: String,
        value: String?,
    ) {
        if (value == null) {
            db.execute("DELETE FROM $DEVICE_TABLE WHERE key=?", listOf(key))
        } else {
            db.execute("INSERT OR REPLACE INTO $DEVICE_TABLE VALUES (?, ?)", listOf(key, value))
        }
    }

    fun observedAt(
        feed: String,
        currency: String,
    ): Long? =
        db
            .query("SELECT observed_at FROM $FEED_TABLE WHERE feed=? AND currency=?", listOf(feed, currency))
            .singleOrNull()
            ?.single()
            ?.toLong()

    fun saveMarkets(feed: MarketFeed<MarketListing>) =
        save(
            "markets",
            feed,
            buildJsonArray {
                feed.items.forEach { coin ->
                    add(
                        buildJsonObject {
                            put("id", coin.coinId)
                            put("symbol", coin.symbol)
                            put("name", coin.name)
                            coin.rank?.let { put("rank", it) }
                            put("price", coin.price.toString())
                            coin.marketCap?.let { put("cap", it.toString()) }
                            coin.change1h?.let { put("c1h", it) }
                            coin.change24h?.let { put("c24h", it) }
                            coin.change7d?.let { put("c7d", it) }
                            // One string instead of 168 JSON numbers per coin keeps the cached list quick to read.
                            put("spark", coin.sparkline.joinToString(","))
                            coin.image?.let { put("image", it) }
                            coin.allTimeHigh?.let { put("ath", it.toString()) }
                            coin.allTimeHighAtMillis?.let { put("athAt", it) }
                            coin.allTimeLow?.let { put("atl", it.toString()) }
                            coin.allTimeLowAtMillis?.let { put("atlAt", it) }
                        },
                    )
                }
            },
        )

    fun saveTrending(feed: MarketFeed<TrendingListing>) =
        save(
            "trending",
            feed,
            buildJsonArray {
                feed.items.forEach { coin ->
                    add(
                        buildJsonObject {
                            put("id", coin.coinId)
                            put("symbol", coin.symbol)
                            put("name", coin.name)
                            coin.rank?.let { put("rank", it) }
                            coin.price?.let { put("price", it.toString()) }
                            coin.change24h?.let { put("c24h", it) }
                            coin.image?.let { put("image", it) }
                        },
                    )
                }
            },
        )

    fun markets(currency: String): MarketFeed<MarketListing>? =
        load("markets", currency) { coin ->
            MarketListing(
                coin.string("id")!!,
                coin.string("symbol")!!,
                coin.string("name")!!,
                coin.string("rank")?.toIntOrNull(),
                ExactDecimal.parse(coin.string("price")!!),
                coin.string("cap")?.let(ExactDecimal::parse),
                coin.string("c1h")?.toDoubleOrNull(),
                coin.string("c24h")?.toDoubleOrNull(),
                coin.string("c7d")?.toDoubleOrNull(),
                coin
                    .string("spark")
                    ?.split(',')
                    ?.mapNotNull { it.toDoubleOrNull() }
                    .orEmpty(),
                CoingeckoMarkets.logoUrl(coin.string("image")),
                coin.string("ath")?.let(ExactDecimal::parse),
                coin.string("athAt")?.toLongOrNull(),
                coin.string("atl")?.let(ExactDecimal::parse),
                coin.string("atlAt")?.toLongOrNull(),
            )
        }

    fun trending(currency: String): MarketFeed<TrendingListing>? =
        load("trending", currency) { coin ->
            TrendingListing(
                coin.string("id")!!,
                coin.string("symbol")!!,
                coin.string("name")!!,
                coin.string("rank")?.toIntOrNull(),
                coin.string("price")?.let(ExactDecimal::parse),
                coin.string("c24h")?.toDoubleOrNull(),
                CoingeckoMarkets.logoUrl(coin.string("image")),
            )
        }

    /** The CoinGecko chain and contract catalog cached on this device, with when it was fetched. */
    fun catalog(): Pair<Long, CoingeckoCatalog>? {
        val row =
            db.query("SELECT observed_at, payload FROM $FEED_TABLE WHERE feed='evm-catalog' AND currency='-'").singleOrNull() ?: return null
        val observed = row[0]!!.toLong()
        cachedCatalog?.takeIf { it.first == observed }?.let { return it }
        return (observed to CoingeckoCatalog.decode(row[1]!!)).also { cachedCatalog = it }
    }

    fun saveCatalog(
        catalog: CoingeckoCatalog,
        observedAtMillis: Long,
    ) {
        db.execute(
            "INSERT OR REPLACE INTO $FEED_TABLE VALUES ('evm-catalog', '-', ?, ?)",
            listOf(observedAtMillis.toString(), catalog.encode()),
        )
        cachedCatalog = observedAtMillis to catalog
    }

    private var cachedCatalog: Pair<Long, CoingeckoCatalog>? = null

    private fun save(
        feed: String,
        value: MarketFeed<*>,
        items: JsonElement,
    ) = db.execute(
        "INSERT OR REPLACE INTO $FEED_TABLE VALUES (?, ?, ?, ?)",
        listOf(feed, value.currency, value.observedAtMillis.toString(), items.toString()),
    )

    /** Parsed feeds by feed and currency; reused while the stored copy has the same observation time. */
    private val parsed = mutableMapOf<Pair<String, String>, MarketFeed<*>>()

    private fun <T> load(
        feed: String,
        currency: String,
        read: (JsonObject) -> T,
    ): MarketFeed<T>? {
        val observed = observedAt(feed, currency) ?: return null
        @Suppress("UNCHECKED_CAST")
        (parsed[feed to currency] as MarketFeed<T>?)?.takeIf { it.observedAtMillis == observed }?.let { return it }
        val row =
            db
                .query("SELECT observed_at, payload FROM $FEED_TABLE WHERE feed=? AND currency=?", listOf(feed, currency))
                .singleOrNull() ?: return null
        val items = Json.parseToJsonElement(row[1]!!).jsonArray.mapNotNull { runCatching { read(it.jsonObject) }.getOrNull() }
        val loaded: MarketFeed<T> = MarketFeed(currency, row[0]!!.toLong(), items)
        parsed[feed to currency] = loaded
        return loaded
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
}
