package io.valroot.core.market

import io.valroot.core.alerts.AlertCondition
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.model.ExactDecimal
import io.valroot.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrderBookApplicationTest {
    private val tickers =
        """{"tickers":[{"base":"ETH","target":"EUR","market":{"identifier":"kraken"},"coin_id":"ethereum"},
        |{"base":"ETH","target":"USD","market":{"identifier":"kraken"},"coin_id":"ethereum"}]}
        """.trimMargin()

    private val depth = """{"error":[],"result":{"XETHZUSD":{"bids":[["3000.1","2",1]],"asks":[["3000.2","1.5",1]]}}}"""

    @Test fun coinsUseCoingeckosKrakenListingsCachedForAWeek() {
        JvmSqliteDatabase(":memory:").use { db ->
            var now = 1_800_000_000_000L
            val app = PortfolioApplication(db) { now }
            val requests = mutableListOf<String>()
            val coingecko = { url: String, _: String? -> tickers.also { requests += url } }
            assertEquals(KrakenPair("XBT", "USD"), app.krakenPair("bitcoin", coingecko))
            assertFails { app.krakenPair("ethereum", coingecko) }
            app.setMarketDataConsent(true)
            assertEquals(KrakenPair("ETH", "USD"), app.krakenPair("ethereum", coingecko))
            assertEquals(KrakenPair("ETH", "USD"), app.krakenPair("ethereum", coingecko))
            assertEquals(1, requests.size)
            now += 8 * 86_400_000L
            app.krakenPair("ethereum", coingecko)
            assertEquals(2, requests.size)
            assertNull(app.krakenPair("dogecoin", { _, _ -> """{"tickers":[]}""" }))

            val pair = KrakenPair("ETH", "USD")
            assertFails { app.orderBook(pair, { depth }) }
            app.setOrderBookConsent(true)
            val book = app.orderBook(pair, { url -> depth.also { assertTrue(url.endsWith("pair=ETHUSD&count=10")) } })
            assertEquals("ETH/USD", book.pair)
            assertEquals(now, book.observedAtMillis)
        }
    }

    @Test fun aNotificationCheckRefreshesOnlyWhenSomethingIsOn() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1_800_000_000_000L }
            app.setMarketDataConsent(true)
            var requests = 0
            val markets = """[{"id":"bitcoin","symbol":"btc","name":"Bitcoin","current_price":90000}]"""
            val transport = { url: String, _: String? ->
                requests++
                if (url.contains("/coins/markets")) markets else """{"coins":[]}"""
            }
            assertTrue(app.runNotificationCheck(transport, "2027-01-15", 12).isEmpty())
            assertEquals(0, requests)
            app.refreshMarketFeeds(transport)
            app.savePriceAlert("bitcoin", AlertCondition.BELOW, ExactDecimal.parse("80000"))
            requests = 0
            app.runNotificationCheck(transport, "2027-01-15", 12)
            // The cached list is younger than ten minutes, so no request is made.
            assertEquals(0, requests)
        }
    }
}
