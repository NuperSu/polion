package io.valroot.core.market

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.bitcoin.BITCOIN_ASSET
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarketFeedsTest {
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/coingecko/$name")).readText()

    private val markets = fixture("markets.json")
    private val trending = fixture("trending.json")

    @Test fun parsesRealMarketListSkippingMalformedCoins() {
        val listings = CoingeckoMarkets.parseMarkets(markets)
        assertEquals(listOf("bitcoin", "ethereum", "tether", "binancecoin"), listings.map { it.coinId })
        val btc = listings.first()
        assertEquals("BTC", btc.symbol)
        assertEquals(1, btc.rank)
        assertTrue(btc.price.coefficient.sign > 0)
        assertEquals(168, btc.sparkline.size)
        assertNotNull(btc.change1h)
        assertNotNull(btc.change7d)
    }

    @Test fun trendingPricesComeFromBtcPriceAndChangeFromTheRequestedCurrency() {
        val coins = CoingeckoMarkets.parseTrending(trending, "usd", ExactDecimal.parse("100000"))
        assertEquals(3, coins.size)
        val first = coins.first()
        assertEquals("dacity-coin-4", first.coinId)
        // price_btc 2.6815381131835186e-07 × 100000
        assertEquals("0.026815381132", first.price.toString())
        assertNotNull(first.change24h)
        assertNull(CoingeckoMarkets.parseTrending(trending, "usd", null).first().price)
    }

    @Test fun refreshNeedsConsentCachesFeedsAndSkipsFreshOnes() {
        JvmSqliteDatabase(":memory:").use { db ->
            var now = 1_800_000_000_000L
            val app = PortfolioApplication(db) { now }
            val requests = mutableListOf<Pair<String, String?>>()
            val transport = { url: String, key: String? ->
                requests += url to key
                if (url.contains("/coins/markets")) markets else trending
            }
            assertFails { app.market.refreshFeeds(transport) }
            assertTrue(requests.isEmpty())
            app.market.setConsent(true)
            assertEquals(2, app.market.refreshFeeds(transport).updatedFeeds)
            assertTrue(requests[0].first.contains("vs_currency=usd&order=market_cap_desc&per_page=250"))
            assertTrue(requests[0].first.contains("sparkline=true&price_change_percentage=1h,24h,7d"))
            assertTrue(requests[1].first.endsWith("/search/trending"))
            assertNull(requests[0].second)
            val overview = app.market.overview()
            assertEquals(4, overview.markets?.items?.size)
            assertEquals(now, overview.markets?.observedAtMillis)
            assertEquals(BITCOIN_ASSET, overview.assetKeys["bitcoin"])
            assertEquals(AssetId("coingecko:ethereum"), overview.assetKeys["ethereum"])
            assertNotNull(
                overview.trending
                    ?.items
                    ?.first()
                    ?.price,
            )

            // Within a minute nothing is requested; after it only the market list is due, trending waits ten minutes.
            now += 30_000
            assertEquals(0, app.market.refreshFeeds(transport).updatedFeeds)
            now += 60_000
            app.market.setCoingeckoApiKey("CG-abcdefghijklmnop")
            assertEquals(1, app.market.refreshFeeds(transport).updatedFeeds)
            assertEquals("CG-abcdefghijklmnop", requests.last().second)
            assertEquals(3, requests.size)
        }
    }

    @Test fun rateLimitPausesRequestsAndKeepsCachedFeeds() {
        JvmSqliteDatabase(":memory:").use { db ->
            var now = 1_800_000_000_000L
            val app = PortfolioApplication(db) { now }
            app.market.setConsent(true)
            app.market.refreshFeeds({ url, _ -> if (url.contains("/coins/markets")) markets else trending })
            now += 120_000
            var calls = 0
            val limited =
                app.market.refreshFeeds({ _, _ ->
                    calls++
                    throw CoingeckoProviderException(CoingeckoProviderFailure.RATE_LIMITED)
                })
            assertEquals(CoingeckoProviderFailure.RATE_LIMITED, limited.failure)
            assertEquals(now + 60_000, limited.retryAfterMillis)
            val overview = app.market.overview()
            assertEquals("RATE_LIMITED", overview.lastFailure)
            assertEquals(4, overview.markets?.items?.size)
            // Paused: no request until the minute passes.
            assertEquals(CoingeckoProviderFailure.RATE_LIMITED, app.market.refreshFeeds({ _, _ -> error("not called") }).failure)
            assertEquals(1, calls)
            now += 61_000
            val offline = app.market.refreshFeeds({ _, _ -> throw CoingeckoProviderException(CoingeckoProviderFailure.OFFLINE) })
            assertEquals(CoingeckoProviderFailure.OFFLINE, offline.failure)
            assertNull(offline.retryAfterMillis)
            val invalid = app.market.refreshFeeds({ _, _ -> "{\"error\":\"nope\"}" })
            assertEquals(CoingeckoProviderFailure.INVALID_RESPONSE, invalid.failure)
            assertEquals(
                4,
                app
                    .market
                    .overview()
                    .markets
                    ?.items
                    ?.size,
            )
            app.market.refreshFeeds({ url, _ -> if (url.contains("/coins/markets")) markets else trending })
            assertNull(app.market.overview().lastFailure)
        }
    }

    @Test fun apiKeyAndFeedsStayOutOfBackupsWhileConsentIsKept() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1_800_000_000_000L }
            app.market.setConsent(true)
            app.market.setCoingeckoApiKey("CG-abcdefghijklmnop")
            assertFails { app.market.setCoingeckoApiKey("bad key!") }
            app.market.refreshFeeds({ url, _ -> if (url.contains("/coins/markets")) markets else trending })
            val backup = app.backup.export("fixture-password".toCharArray())
            JvmSqliteDatabase(":memory:").use { staging ->
                app.backup.restoreToStaging(staging, backup, "fixture-password".toCharArray())
                val restored = PortfolioApplication(staging) { 1_800_000_000_000L }
                assertNull(restored.market.coingeckoApiKey())
                assertNull(restored.market.overview().markets)
                assertTrue(restored.market.consent())
            }
            app.market.setCoingeckoApiKey(null)
            assertNull(app.market.coingeckoApiKey())
        }
    }

    @Test fun autoSyncGrantsMarketConsent() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1_800_000_000_000L }
            app.prices.setAutomaticRefresh(true)
            assertTrue(app.market.consent())
            app.prices.setAutomaticRefresh(false)
            assertTrue(app.market.consent())
        }
    }

    @Test fun logosUseCoingeckoSmallImagesAndStayOnTheDevice() {
        val btc = CoingeckoMarkets.parseMarkets(markets).first()
        assertTrue(btc.image!!.startsWith("https://coin-images.coingecko.com/coins/images/1/small/bitcoin.png"))
        assertNotNull(CoingeckoMarkets.parseTrending(trending, "usd", null).first().image)
        assertNull(CoingeckoMarkets.logoUrl("https://evil.example/coins/images/1/small/x.png"))
        assertNull(CoingeckoMarkets.logoUrl("http://coin-images.coingecko.com/coins/images/1/small/x.png"))
        JvmSqliteDatabase(":memory:").use { db ->
            var now = 1_800_000_000_000L
            val app = PortfolioApplication(db) { now }
            app.market.setConsent(true)
            app.market.refreshFeeds({ url, _ -> if (url.contains("/coins/markets")) markets else trending })
            val url =
                app
                    .market
                    .overview()
                    .markets!!
                    .items
                    .first()
                    .image!!
            assertFails { app.market.saveLogo("https://evil.example/x.png", byteArrayOf(1)) }
            app.market.saveLogo(url, byteArrayOf(1, 2, 3))
            assertTrue(app.market.cachedLogo(url)!!.contentEquals(byteArrayOf(1, 2, 3)))
            val backup = app.backup.export("fixture-password".toCharArray())
            JvmSqliteDatabase(":memory:").use { staging ->
                app.backup.restoreToStaging(staging, backup, "fixture-password".toCharArray())
                assertNull(PortfolioApplication(staging) { now }.market.cachedLogo(url))
            }
            now += 31L * 86_400_000L
            assertNull(app.market.cachedLogo(url))
        }
    }
}
