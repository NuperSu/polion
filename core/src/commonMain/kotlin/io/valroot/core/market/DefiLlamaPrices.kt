package io.valroot.core.market

import io.valroot.core.model.ExactDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * DefiLlama's keyless coin prices in US dollars, addressed by CoinGecko coin ID (`coingecko:<id>`).
 *
 * - `/prices/current/<coins>` gives the latest price per coin.
 * - `/chart/<coins>?start=&span=&period=` gives `span` points from `start` (Unix seconds), daily or hourly, with no
 *   limit on how far back; one request returns at most [MAX_POINTS] points across its coins.
 *
 * A coin DefiLlama does not know, or a range before its first price, is absent from the response rather than an error.
 */
object DefiLlamaPrices {
    const val PROVIDER_ID = "defillama"
    const val BASE_URL = "https://coins.llama.fi"
    const val MAX_POINTS = 500
    const val CURRENT_BATCH = 50

    enum class Period(
        val query: String,
        val stepMillis: Long,
    ) {
        DAILY("1d", 86_400_000L),
        HOURLY("1h", 3_600_000L),
    }

    private val COIN = Regex("[a-z0-9][a-z0-9-]{0,99}")

    fun currentUrl(
        baseUrl: String,
        coins: List<String>,
    ): String {
        require(coins.size in 1..CURRENT_BATCH && coins.all { it.matches(COIN) })
        return "$baseUrl/prices/current/" + coins.joinToString(",") { "coingecko:$it" }
    }

    /** One coin's prices from [startMillis], [span] points apart by [period]. */
    fun chartUrl(
        baseUrl: String,
        coin: String,
        startMillis: Long,
        span: Int,
        period: Period,
    ): String {
        require(coin.matches(COIN) && startMillis >= 0 && span in 1..MAX_POINTS)
        return "$baseUrl/chart/coingecko:$coin?start=${startMillis / 1000}&span=$span&period=${period.query}"
    }

    /** Latest price and its time in milliseconds, by coin ID. */
    fun parseCurrent(payload: String): Map<String, Pair<Long, ExactDecimal>> =
        coins(payload)
            .mapNotNull { (coin, entry) ->
                point(entry["timestamp"], entry["price"])?.let { coin to it }
            }.toMap()

    /** Price points in time order, by coin ID. */
    fun parseChart(payload: String): Map<String, List<Pair<Long, ExactDecimal>>> =
        coins(payload).associate { (coin, entry) ->
            val prices = entry["prices"]?.jsonArray.orEmpty()
            require(prices.size <= MAX_POINTS)
            coin to
                prices
                    .mapNotNull { point ->
                        val fields = point.jsonObject
                        point(fields["timestamp"], fields["price"])
                    }.sortedBy { it.first }
                    .distinctBy { it.first }
        }

    private fun coins(payload: String): List<Pair<String, JsonObject>> {
        val coins = Json.parseToJsonElement(payload).jsonObject["coins"]?.jsonObject ?: throw IllegalArgumentException("No coins")
        return coins.mapNotNull { (key, entry) ->
            key.removePrefix("coingecko:").takeIf { key.startsWith("coingecko:") && it.matches(COIN) }?.let { it to entry.jsonObject }
        }
    }

    /** A positive price at a plausible time; anything else is skipped rather than recorded as zero. */
    private fun point(
        timestamp: kotlinx.serialization.json.JsonElement?,
        price: kotlinx.serialization.json.JsonElement?,
    ): Pair<Long, ExactDecimal>? {
        val seconds = (timestamp as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull() ?: return null
        val number = (price as? JsonPrimitive)?.takeIf { !it.isString }?.jsonPrimitive?.content ?: return null
        if (seconds !in 1..100_000_000_000L) return null
        val value = runCatching { CoingeckoPrices.parseJsonNumber(number) }.getOrNull() ?: return null
        return if (value.coefficient.sign > 0) seconds * 1000 to value else null
    }
}

/** A DefiLlama request that failed; [rateLimited] when it answered 429. */
class DefiLlamaException(
    val rateLimited: Boolean,
    message: String,
) : Exception(message)
