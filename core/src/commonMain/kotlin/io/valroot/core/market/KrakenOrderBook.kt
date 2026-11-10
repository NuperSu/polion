package io.valroot.core.market

import io.valroot.core.model.ExactDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One price level: [volume] of the base asset is available at [price] in the quote currency. */
data class OrderLevel(
    val price: ExactDecimal,
    val volume: ExactDecimal,
)

/** A complete order book snapshot from one venue and pair; bids are highest first, asks lowest first. */
data class OrderBookSnapshot(
    val venue: String, // "Kraken"
    val pair: String, // display form, e.g. "XBT/USD"
    val bids: List<OrderLevel>,
    val asks: List<OrderLevel>,
    val observedAtMillis: Long, // when the app received it
)

/** A Kraken market for a coin, as CoinGecko lists it: Kraken's base code and the quote currency code. */
data class KrakenPair(
    val base: String,
    val quote: String,
) {
    /** The pair name Kraken's REST API accepts, e.g. "XBTUSD". */
    val restName: String get() = base + quote
    val display: String get() = "$base/$quote"
}

/** Kraken public order book requests and parsing, plus resolution of the Kraken pairs CoinGecko lists for a coin. */
object KrakenOrderBook {
    const val VENUE = "Kraken"
    const val MAX_DEPTH = 25

    private const val KRAKEN_IDENTIFIER = "kraken"
    private const val MAX_NUMBER_LENGTH = 40
    private val CODE = Regex("[A-Z0-9]{2,10}")
    private val COIN_ID = Regex("[a-z0-9][a-z0-9-]{0,99}")

    /** Public Depth request for [pair] returning [depth] levels per side, where [depth] is 1 to [MAX_DEPTH]. */
    fun depthUrl(
        pair: KrakenPair,
        depth: Int = 10,
    ): String {
        require(depth in 1..MAX_DEPTH)
        require(pair.base.matches(CODE) && pair.quote.matches(CODE))
        return "https://api.kraken.com/0/public/Depth?pair=${pair.restName}&count=$depth"
    }

    /**
     * Parses a Kraken Depth response for [pair]. Every received level is validated, then the snapshot keeps the best
     * [depth] levels per side. An empty side is allowed. A book whose best bid is not below its best ask is rejected.
     */
    fun parseDepth(
        payload: String,
        pair: KrakenPair,
        observedAtMillis: Long,
        depth: Int = 10,
    ): OrderBookSnapshot {
        require(depth in 1..MAX_DEPTH)
        val root = Json.parseToJsonElement(payload).jsonObject
        val errors = root.field("error").jsonArray
        require(errors.isEmpty()) { "Kraken returned an error: ${errors.first().jsonPrimitive.content}" }
        val result = root.field("result").jsonObject
        require(result.size == 1) { "Expected exactly one Kraken order book" }
        val book = result.values.single().jsonObject
        val bids = levels(book.field("bids")) { previous, next -> previous > next }
        val asks = levels(book.field("asks")) { previous, next -> previous < next }
        if (bids.isNotEmpty() && asks.isNotEmpty()) {
            require(bids.first().price < asks.first().price) { "Kraken order book is crossed" }
        }
        return OrderBookSnapshot(VENUE, pair.display, bids.take(depth), asks.take(depth), observedAtMillis)
    }

    /** CoinGecko tickers request for [coinId] restricted to Kraken. [baseUrl] is used as given. */
    fun tickersUrl(
        baseUrl: String,
        coinId: String,
    ): String {
        require(coinId.matches(COIN_ID))
        return "$baseUrl/coins/$coinId/tickers?exchange_ids=kraken"
    }

    /**
     * Kraken markets for [coinId] from a CoinGecko `/coins/{id}/tickers` response, in input order without duplicates.
     * Tickers on other venues, for other coins, or flagged as anomalous or stale are dropped. A malformed ticker is
     * skipped on its own. A document without a `tickers` array is rejected.
     */
    fun parseKrakenPairs(
        payload: String,
        coinId: String,
    ): List<KrakenPair> {
        require(coinId.matches(COIN_ID))
        val tickers =
            Json
                .parseToJsonElement(payload)
                .jsonObject
                .field("tickers")
                .jsonArray
        return tickers
            .mapNotNull { ticker -> runCatching { krakenPair(ticker.jsonObject, coinId) }.getOrNull() }
            .distinct()
    }

    /** The first pair quoted in [currency], compared case-insensitively, or null when no pair has that quote. */
    fun pairFor(
        pairs: List<KrakenPair>,
        currency: String,
    ): KrakenPair? {
        val quote = currency.uppercase()
        return pairs.firstOrNull { it.quote == quote }
    }

    private fun levels(
        side: JsonElement,
        inOrder: (previous: ExactDecimal, next: ExactDecimal) -> Boolean,
    ): List<OrderLevel> {
        val levels =
            side.jsonArray.map { row ->
                val cells = row.jsonArray
                require(cells.size >= 2) { "Kraken order book level needs a price and a volume" }
                OrderLevel(positiveDecimal(cells[0]), positiveDecimal(cells[1]))
            }
        require(levels.zipWithNext().all { (previous, next) -> inOrder(previous.price, next.price) }) {
            "Kraken order book levels are not strictly ordered"
        }
        return levels
    }

    private fun positiveDecimal(cell: JsonElement): ExactDecimal {
        val text = cell.jsonPrimitive
        require(text.isString && text.content.length <= MAX_NUMBER_LENGTH) { "Kraken order book values must be decimal strings" }
        val value = ExactDecimal.parse(text.content)
        require(value.coefficient.sign > 0) { "Kraken order book values must be positive" }
        return value
    }

    /** Null for a ticker that is not a usable Kraken market for [coinId]. Throws when the ticker is malformed. */
    private fun krakenPair(
        ticker: JsonObject,
        coinId: String,
    ): KrakenPair? {
        if (text(ticker.field("market").jsonObject, "identifier") != KRAKEN_IDENTIFIER) return null
        if (text(ticker, "coin_id") != coinId) return null
        if (flagged(ticker, "is_anomaly") || flagged(ticker, "is_stale")) return null
        return KrakenPair(krakenCode(text(ticker, "base")), krakenCode(text(ticker, "target")))
    }

    /** Uppercases a CoinGecko symbol and maps Bitcoin's "BTC" to Kraken's "XBT". */
    private fun krakenCode(symbol: String): String {
        val code = symbol.uppercase()
        require(code.matches(CODE))
        return if (code == "BTC") "XBT" else code
    }

    private fun flagged(
        ticker: JsonObject,
        key: String,
    ): Boolean = ticker[key]?.jsonPrimitive?.booleanOrNull == true

    private fun text(
        obj: JsonObject,
        key: String,
    ): String {
        val value = obj.field(key).jsonPrimitive
        require(value.isString) { "Expected a string for $key" }
        return value.content
    }

    private fun JsonObject.field(key: String): JsonElement = requireNotNull(this[key]) { "Missing $key" }
}
