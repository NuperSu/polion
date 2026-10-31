package io.polion.core.market

import io.polion.core.model.AssetId
import io.polion.core.model.ExactDecimal
import io.polion.core.model.Price
import io.polion.core.model.ProviderId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Closed BTC exchange candles. The currently forming candle is never recorded as a historical close. */
object KrakenPrices {
    const val PROVIDER_ID = "kraken"

    fun pair(quote: AssetId): Pair<String, String> =
        when (quote.value) {
            "USD" -> "XBTUSD" to "XXBTZUSD"
            "EUR" -> "XBTEUR" to "XXBTZEUR"
            "GBP" -> "XBTGBP" to "XXBTZGBP"
            else -> throw IllegalArgumentException("Unsupported quote currency")
        }

    fun parse(
        payload: String,
        resultPair: String,
        now: Long,
        intervalMinutes: Int = 60,
    ): List<Pair<Long, ExactDecimal>> {
        require(intervalMinutes == 60 || intervalMinutes == 1440)
        val interval = intervalMinutes.toLong() * 60_000L
        val root = Json.parseToJsonElement(payload).jsonObject
        require(root.getValue("error").jsonArray.isEmpty())
        val result = root.getValue("result").jsonObject
        val rows = result.getValue(resultPair).jsonArray
        require(rows.size in 2..721)
        var previous = -1L
        val points =
            rows.dropLast(1).map { row ->
                val cells = row.jsonArray
                require(cells.size == 8)
                val seconds = cells[0].jsonPrimitive
                val close = cells[4].jsonPrimitive
                require(!seconds.isString && seconds.content.matches(Regex("[0-9]{1,12}")) && close.isString)
                val start = seconds.content.toLong() * 1000
                require(start % interval == 0L && start > previous && start <= now - interval)
                previous = start
                val price = ExactDecimal.parse(close.content)
                require(price.coefficient.sign > 0 && close.content.length <= 80)
                start + interval to price
            }
        val current =
            rows
                .last()
                .jsonArray
                .first()
                .jsonPrimitive
        require(!current.isString && current.content.matches(Regex("[0-9]{1,12}")))
        val currentStart = current.content.toLong() * 1000
        require(currentStart % interval == 0L && currentStart > previous && currentStart <= now && now - currentStart < interval)
        require(points.isNotEmpty())
        return points
    }

    fun fetch(
        transport: (String) -> String,
        market: MarketRepository,
        base: AssetId,
        quote: AssetId,
        now: Long,
        requestId: String,
        intervalMinutes: Int = 60,
        authorize: () -> Unit,
    ): Int {
        val (requestPair, resultPair) = pair(quote)
        require(intervalMinutes == 60 || intervalMinutes == 1440)
        val payload = transport("https://api.kraken.com/0/public/OHLC?pair=$requestPair&interval=$intervalMinutes")
        val points =
            try {
                parse(payload, resultPair, now, intervalMinutes)
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid Kraken price response")
            }
        market.mapProvider(ProviderId(PROVIDER_ID), requestPair, base)
        market.recordChangedProviderPrices(
            points.map { (at, value) ->
                PriceObservation("$requestId:$at", Price(base, quote, value), ProviderId(PROVIDER_ID), at, now)
            },
            authorize,
        )
        return points.size
    }
}
