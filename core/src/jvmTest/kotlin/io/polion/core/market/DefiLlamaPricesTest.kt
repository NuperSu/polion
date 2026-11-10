package io.polion.core.market

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DefiLlamaPricesTest {
    @Test fun currentPricesAreReadByCoinAndInvalidOnesSkipped() {
        val prices =
            DefiLlamaPrices.parseCurrent(
                """{"coins":{
                  "coingecko:ethereum":{"price":2118.44,"symbol":"ETH","timestamp":1794230098,"confidence":0.999},
                  "coingecko:zero":{"price":0,"timestamp":1794230098},
                  "coingecko:text":{"price":"5","timestamp":1794230098},
                  "ethereum:0xae7ab96520de3a18e5e111b5eaab095312d7fe84":{"price":2117.5,"timestamp":1794230098}}}""",
            )
        assertEquals(setOf("ethereum"), prices.keys)
        assertEquals(1_794_230_098_000L, prices.getValue("ethereum").first)
        assertEquals("2118.44", prices.getValue("ethereum").second.toString())
    }

    @Test fun chartPointsAreOrderedAndAMissingCoinIsAbsent() {
        val chart =
            DefiLlamaPrices.parseChart(
                """{"coins":{"coingecko:ethereum":{"symbol":"ETH","confidence":0.99,"prices":[
                  {"timestamp":1641081653,"price":3781.8},{"timestamp":1640995250,"price":3680.9}]}}}""",
            )
        assertEquals(listOf(1_640_995_250_000L, 1_641_081_653_000L), chart.getValue("ethereum").map { it.first })
        assertTrue(DefiLlamaPrices.parseChart("""{"coins":{}}""").isEmpty())
        assertFailsWith<IllegalArgumentException> { DefiLlamaPrices.parseChart("""{"message":"Requested 4000 data points"}""") }
    }

    @Test fun urlsStayWithinTheApiLimits() {
        assertEquals(
            "https://coins.llama.fi/chart/coingecko:ethereum?start=1640995200&span=500&period=1h",
            DefiLlamaPrices.chartUrl(DefiLlamaPrices.BASE_URL, "ethereum", 1_640_995_200_123L, 500, DefiLlamaPrices.Period.HOURLY),
        )
        assertFailsWith<IllegalArgumentException> {
            DefiLlamaPrices.chartUrl(DefiLlamaPrices.BASE_URL, "ethereum", 0, 501, DefiLlamaPrices.Period.DAILY)
        }
        assertFailsWith<IllegalArgumentException> { DefiLlamaPrices.currentUrl(DefiLlamaPrices.BASE_URL, listOf("a,b")) }
    }
}
