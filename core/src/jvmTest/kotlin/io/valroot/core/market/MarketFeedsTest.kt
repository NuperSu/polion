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
            assertFails { app.refreshMarketFeeds(transport) }
            assertTrue(requests.isEmpty())
            app.setMarketDataConsent(true)
            assertEquals(2, app.refreshMarketFeeds(transport).updatedFeeds)
            assertTrue(requests[0].first.contains("vs_currency=usd&order=market_cap_desc&per_page=250"))
            assertTrue(requests[0].first.contains("sparkline=true&price_change_percentage=1h,24h,7d"))
            assertTrue(requests[1].first.endsWith("/search/trending"))
            assertNull(requests[0].second)
            val overview = app.marketOverview()
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
            assertEquals(0, app.refreshMarketFeeds(transport).updatedFeeds)
            now += 60_000
            app.setCoingeckoApiKey("CG-abcdefghijklmnop")
            assertEquals(1, app.refreshMarketFeeds(transport).updatedFeeds)
            assertEquals("CG-abcdefghijklmnop", requests.last().second)
            assertEquals(3, requests.size)
        }
    }

    @Test fun rateLimitPausesRequestsAndKeepsCachedFeeds() {
        JvmSqliteDatabase(":memory:").use { db ->
            var now = 1_800_000_000_000L
            val app = PortfolioApplication(db) { now }
            app.setMarketDataConsent(true)
            app.refreshMarketFeeds({ url, _ -> if (url.contains("/coins/markets")) markets else trending })
            now += 120_000
            var calls = 0
            val limited =
                app.refreshMarketFeeds({ _, _ ->
                    calls++
                    throw CoingeckoProviderException(CoingeckoProviderFailure.RATE_LIMITED)
                })
            assertEquals(CoingeckoProviderFailure.RATE_LIMITED, limited.failure)
            assertEquals(now + 60_000, limited.retryAfterMillis)
            val overview = app.marketOverview()
            assertEquals("RATE_LIMITED", overview.lastFailure)
            assertEquals(4, overview.markets?.items?.size)
            // Paused: no request until the minute passes.
            assertEquals(CoingeckoProviderFailure.RATE_LIMITED, app.refreshMarketFeeds({ _, _ -> error("not called") }).failure)
            assertEquals(1, calls)
            now += 61_000
            val offline = app.refreshMarketFeeds({ _, _ -> throw CoingeckoProviderException(CoingeckoProviderFailure.OFFLINE) })
            assertEquals(CoingeckoProviderFailure.OFFLINE, offline.failure)
            assertNull(offline.retryAfterMillis)
            val invalid = app.refreshMarketFeeds({ _, _ -> "{\"error\":\"nope\"}" })
            assertEquals(CoingeckoProviderFailure.INVALID_RESPONSE, invalid.failure)
            assertEquals(
                4,
                app
                    .marketOverview()
                    .markets
                    ?.items
                    ?.size,
            )
            app.refreshMarketFeeds({ url, _ -> if (url.contains("/coins/markets")) markets else trending })
            assertNull(app.marketOverview().lastFailure)
        }
    }

    @Test fun apiKeyAndFeedsStayOutOfBackupsWhileConsentIsKept() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1_800_000_000_000L }
            app.setMarketDataConsent(true)
            app.setCoingeckoApiKey("CG-abcdefghijklmnop")
            assertFails { app.setCoingeckoApiKey("bad key!") }
            app.refreshMarketFeeds({ url, _ -> if (url.contains("/coins/markets")) markets else trending })
            val backup = app.exportBackup("fixture-password".toCharArray())
            JvmSqliteDatabase(":memory:").use { staging ->
                app.restoreToStaging(staging, backup, "fixture-password".toCharArray())
                val restored = PortfolioApplication(staging) { 1_800_000_000_000L }
                assertNull(restored.coingeckoApiKey())
                assertNull(restored.marketOverview().markets)
                assertTrue(restored.marketDataConsent())
            }
            app.setCoingeckoApiKey(null)
            assertNull(app.coingeckoApiKey())
        }
    }

    @Test fun autoSyncGrantsMarketConsent() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1_800_000_000_000L }
            app.setAutomaticRefresh(true)
            assertTrue(app.marketDataConsent())
            app.setAutomaticRefresh(false)
            assertTrue(app.marketDataConsent())
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
            app.setMarketDataConsent(true)
            app.refreshMarketFeeds({ url, _ -> if (url.contains("/coins/markets")) markets else trending })
            val url =
                app
                    .marketOverview()
                    .markets!!
                    .items
                    .first()
                    .image!!
            assertFails { app.saveLogo("https://evil.example/x.png", byteArrayOf(1)) }
            app.saveLogo(url, byteArrayOf(1, 2, 3))
            assertTrue(app.cachedLogo(url)!!.contentEquals(byteArrayOf(1, 2, 3)))
            val backup = app.exportBackup("fixture-password".toCharArray())
            JvmSqliteDatabase(":memory:").use { staging ->
                app.restoreToStaging(staging, backup, "fixture-password".toCharArray())
                assertNull(PortfolioApplication(staging) { now }.cachedLogo(url))
            }
            now += 31L * 86_400_000L
            assertNull(app.cachedLogo(url))
        }
    }
}
