package io.polion.core.market

import io.polion.core.model.AssetId
import io.polion.core.model.ExactDecimal
import io.polion.core.model.Price
import io.polion.core.model.ProviderId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
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
        return ExactDecimal.parse(value.content)
    }

    private val DECIMAL = Regex("(0|[1-9][0-9]*)(\\.[0-9]+)?")
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
    fun historical(
        base: AssetId,
        coinId: String,
        quote: AssetId,
        quoteCurrency: String,
        dayUtcMillis: Long,
        observationId: String,
    ): CoingeckoHistoricalResult {
        require(dayUtcMillis % MILLIS_PER_DAY == 0L) { "Historical lookups are daily; pass midnight UTC" }
        require(coinId.matches(COIN_ID))
        require(quoteCurrency.matches(CURRENCY))
        market.mapProvider(ProviderId(CoingeckoPrices.PROVIDER_ID), coinId, base)
        val date = dayUtcDate(dayUtcMillis)
        val url = "$baseUrl/coins/$coinId/history?date=$date&localization=false"
        val value = CoingeckoPrices.parseHistorical(transport.get(url), quoteCurrency)
        if (value == null) return CoingeckoHistoricalResult(PriceStatus.MISSING, null)
        val observation =
            PriceObservation(
                observationId,
                Price(base, quote, value),
                ProviderId(CoingeckoPrices.PROVIDER_ID),
                dayUtcMillis,
                clock(),
            )
        market.recordPrice(observation)
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
