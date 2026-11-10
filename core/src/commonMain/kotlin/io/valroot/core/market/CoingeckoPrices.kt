package io.valroot.core.market

import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.Price
import io.valroot.core.model.ProviderId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** CoinGecko's historical response for one coin on one UTC date, reduced to the quoted price. */
object CoingeckoPrices {
    const val PROVIDER_ID = "coingecko"

    /**
     * Returns the price in [quoteCurrency] from a `/coins/{id}/history` response, or null when the response has no
     * such quote. A missing or non-numeric value is never converted to zero.
     */
    fun parseHistorical(
        payload: String,
        quoteCurrency: String,
    ): ExactDecimal? {
        val root = Json.parseToJsonElement(payload).jsonObject
        val market = root["market_data"]?.takeIf { it !is JsonNull }?.jsonObject ?: return null
        val prices = market["current_price"]?.takeIf { it !is JsonNull }?.jsonObject ?: return null
        val value = prices[quoteCurrency]?.takeIf { it !is JsonNull }?.jsonPrimitive ?: return null
        if (value.isString || !value.content.matches(DECIMAL)) return null
        // A zero quote is not a price; it is treated like a missing one.
        return ExactDecimal.parse(value.content).takeIf { it.coefficient.sign > 0 }
    }

    private val DECIMAL = Regex("(0|[1-9][0-9]*)(\\.[0-9]+)?")
    private val TIMESTAMP = Regex("[0-9]{1,16}")

    /** Parse exact numeric timestamps and prices; reject malformed or ambiguous series as a whole. */
    fun parseChart(payload: String): List<Pair<Long, ExactDecimal>> {
        val prices =
            Json
                .parseToJsonElement(payload)
                .jsonObject
                .getValue("prices")
                .jsonArray
        require(prices.isNotEmpty() && prices.size <= 3000)
        var previous = -1L
        return prices.map { entry ->
            val pair = entry.jsonArray
            require(pair.size == 2)
            val timestamp = pair[0].jsonPrimitive
            val value = pair[1].jsonPrimitive
            require(!timestamp.isString && !value.isString && timestamp.content.matches(TIMESTAMP))
            val at = timestamp.content.toLong()
            require(at > previous)
            previous = at
            val price = parseJsonNumber(value.content)
            require(price.coefficient.sign > 0)
            at to price
        }
    }

    internal fun parseJsonNumber(text: String): ExactDecimal {
        require(text.length <= 100)
        val parts = text.lowercase().split('e')
        require(parts.size <= 2 && parts[0].matches(DECIMAL))
        val exponent = if (parts.size == 2) parts[1].toInt() else 0
        require(exponent in -100..100)
        val mantissa = parts[0]
        val digits = mantissa.replace(".", "")
        val scale = (mantissa.substringAfter('.', "").length - exponent)
        return if (scale >=
            0
        ) {
            ExactDecimal(ExactInteger.parse(digits), scale)
        } else {
            ExactDecimal(ExactInteger.parse(digits + "0".repeat(-scale)), 0)
        }
    }
}

/** Host-supplied HTTP GET. Implementations own networking, timeouts, rate limits and API keys. */
fun interface CoingeckoTransport {
    fun get(url: String): String
}

data class CoingeckoHistoricalResult(
    val status: PriceStatus,
    val observation: PriceObservation?,
)

/**
 * Looks up CoinGecko's daily historical price for a base asset and records it as a provider observation.
 * A missing quote yields [PriceStatus.MISSING] with no observation, so an unknown price never becomes zero.
 */
class CoingeckoPriceClient(
    private val transport: CoingeckoTransport,
    private val market: MarketRepository,
    private val clock: () -> Long,
    private val baseUrl: String = "https://api.coingecko.com/api/v3",
) {
    fun chart(
        base: AssetId,
        coinId: String,
        quote: AssetId,
        quoteCurrency: String,
        days: Int,
        requestId: String,
        authorize: () -> Unit = {},
    ): Int {
        require(coinId.matches(COIN_ID) && quoteCurrency.matches(CURRENCY) && days in setOf(1, 7, 30, 90, 365))
        val payload = transport.get("$baseUrl/coins/$coinId/market_chart?vs_currency=$quoteCurrency&days=$days")
        val points =
            try {
                CoingeckoPrices.parseChart(payload)
            } catch (_: Exception) {
                throw CoingeckoProviderException(CoingeckoProviderFailure.INVALID_RESPONSE)
            }
        val observedAt = clock()
        if (points.any { (at, _) -> at > observedAt || at < observedAt - (days + 1L) * MILLIS_PER_DAY }) {
            throw CoingeckoProviderException(CoingeckoProviderFailure.INVALID_RESPONSE)
        }
        market.mapProvider(ProviderId(CoingeckoPrices.PROVIDER_ID), coinId, base)
        market.recordPrices(
            points.map { (at, price) ->
                PriceObservation("$requestId:$at", Price(base, quote, price), ProviderId(CoingeckoPrices.PROVIDER_ID), at, observedAt)
            },
            authorize,
        )
        return points.size
    }

    fun historical(
        base: AssetId,
        coinId: String,
        quote: AssetId,
        quoteCurrency: String,
        dayUtcMillis: Long,
        observationId: String,
        authorize: () -> Unit = {},
    ): CoingeckoHistoricalResult {
        require(dayUtcMillis % MILLIS_PER_DAY == 0L) { "Historical lookups are daily; pass midnight UTC" }
        require(coinId.matches(COIN_ID))
        require(quoteCurrency.matches(CURRENCY))
        market.mapProvider(ProviderId(CoingeckoPrices.PROVIDER_ID), coinId, base)
        val date = dayUtcDate(dayUtcMillis)
        val url = "$baseUrl/coins/$coinId/history?date=$date&localization=false"
        val payload = transport.get(url)
        val value =
            try {
                CoingeckoPrices.parseHistorical(payload, quoteCurrency)
            } catch (_: IllegalArgumentException) {
                throw CoingeckoProviderException(CoingeckoProviderFailure.INVALID_RESPONSE)
            }
        if (value == null) return CoingeckoHistoricalResult(PriceStatus.MISSING, null)
        val observation =
            PriceObservation(
                observationId,
                Price(base, quote, value),
                ProviderId(CoingeckoPrices.PROVIDER_ID),
                dayUtcMillis,
                clock(),
            )
        market.recordPrice(observation, authorize)
        return CoingeckoHistoricalResult(PriceStatus.FRESH, observation)
    }

    private fun dayUtcDate(dayUtcMillis: Long): String {
        val days = dayUtcMillis / MILLIS_PER_DAY
        val (year, month, day) = civilFromDays(days)
        return "%02d-%02d-%04d".format(day, month, year)
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
        val COIN_ID = Regex("[a-z0-9][a-z0-9-]*")
        val CURRENCY = Regex("[a-z]{3,4}")

        /** Howard Hinnant's civil-from-days conversion, exact for proleptic Gregorian dates. */
        fun civilFromDays(days: Long): Triple<Long, Int, Int> {
            val z = days + 719468
            val era = (if (z >= 0) z else z - 146096) / 146097
            val doe = z - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            val y = yoe + era * 400
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
            return Triple(if (m <= 2) y + 1 else y, m, d)
        }
    }
}
