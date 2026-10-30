package io.polion.core.application

import io.polion.core.market.CoingeckoProviderException
import io.polion.core.market.CoingeckoProviderFailure
import io.polion.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AutomaticPriceWorkflowTest {
    private val hour = 3_600_000L
    private val now = 1_700_002_800_000L / hour * hour + 100_000

    private fun candle(
        start: Long,
        price: String,
    ) = "[${start / 1000},\"1\",\"2\",\"1\",\"$price\",\"1\",\"1\",1]"

    @Test fun fetchRequiresConsentStoresClosedHoursAndRollsBackOnCancellation() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { now }
            val current = now / hour * hour
            val payload = """{"error":[],"result":{"XXBTZUSD":[${candle(
                current - 2 * hour,
                "40000.10",
            )},${candle(current - hour, "41000.20")},${candle(current, "99999")}],"last":0}}"""
            var requests = 0
            val transport: (String) -> String = { url ->
                requests++
                assertEquals("https://api.kraken.com/0/public/OHLC?pair=XBTUSD&interval=60", url)
                payload
            }
            assertFailsWith<IllegalArgumentException> { app.refreshAutomaticPrices(transport) }
            assertEquals(0, requests)
            app.setAutomaticPriceConsent(true)
            assertEquals(2, app.refreshAutomaticPrices(transport))
            val stored = db.query("SELECT valid_at,value FROM market_prices ORDER BY valid_at")
            assertEquals(listOf((current - hour).toString(), "40000.10"), stored[0])
            assertEquals(listOf(current.toString(), "41000.20"), stored[1])
            var calls = 0
            assertFailsWith<CoingeckoProviderException> {
                app.refreshAutomaticPrices(transport) {
                    if (++calls ==
                        3
                    ) {
                        throw CoingeckoProviderException(CoingeckoProviderFailure.CANCELLED)
                    }
                }
            }
            assertEquals(stored, db.query("SELECT valid_at,value FROM market_prices ORDER BY valid_at"))
            assertFailsWith<IllegalArgumentException> {
                app.refreshAutomaticPrices(transport = { """{"error":["EGeneral:Invalid arguments"],"result":{}}""" })
            }
            assertEquals(stored, db.query("SELECT valid_at,value FROM market_prices ORDER BY valid_at"))
        }
    }
}
