package io.valroot.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.market.CoingeckoProviderException
import io.valroot.core.market.CoingeckoProviderFailure
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.onboarding.OnboardingAction
import io.valroot.core.storage.JvmSqliteDatabase
import kotlinx.coroutines.flow.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MarketDiscoveryTest {
    private fun coin(
        id: String,
        symbol: String,
        rank: Int,
        price: String,
        change: Double,
    ) = """{"id":"$id","symbol":"$symbol","name":"${id.replaceFirstChar { it.uppercase() }}","market_cap_rank":$rank,
        "current_price":$price,"market_cap":${rank * 1000},"price_change_percentage_1h_in_currency":0.1,
        "price_change_percentage_24h_in_currency":$change,"price_change_percentage_7d_in_currency":${change * 2},
        "sparkline_in_7d":{"price":[1.0,2.0,1.5,3.0]}}"""

    private val markets =
        "[" +
            listOf(
                coin("bitcoin", "btc", 1, "75000", 1.5),
                coin("ethereum", "eth", 2, "3000.5", -2.25),
                coin("solana", "sol", 3, "150", 8.0),
                coin("dogecoin", "doge", 4, "0.0123456", -6.5),
            ).joinToString(",") + "]"

    private val trending =
        """{"coins":[{"item":{"id":"pepe","name":"Pepe","symbol":"PEPE","market_cap_rank":30,"price_btc":1.0e-10,
            "data":{"price_change_percentage_24h":{"usd":12.5}}}}]}"""

    private fun feeds(
        url: String,
        @Suppress("UNUSED_PARAMETER") key: String?,
    ) = if (url.contains("/coins/markets")) markets else trending

    @Test fun formattingNamesSignsAndSmallPrices() {
        assertEquals("+1.50%", percentChange(1.5))
        assertEquals("-2.25%", percentChange(-2.25))
        assertEquals("0.00%", percentChange(0.001))
        assertEquals("—", percentChange(null))
        assertEquals("$0.01235", marketPrice(ExactDecimal.parse("0.0123456"), AssetId("USD")))
        assertEquals("€3,000.50", marketPrice(ExactDecimal.parse("3000.5"), AssetId("EUR")))
        assertEquals(listOf(0f, 1f), sparklinePoints(listOf(1.0, 2.0)))
        assertTrue(sparklinePoints(listOf(1.0)).isEmpty())
    }

    @Test fun moversAreRankedWithinTheNamedUniverse() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { System.currentTimeMillis() }
            app.market.setConsent(true)
            app.market.refreshFeeds(::feeds)
            val state = marketMainState(app.market.overview(), emptyList(), setOf(AssetId("coingecko:solana")), System.currentTimeMillis())
            assertEquals(listOf("SOL", "BTC"), state.gainers.map { it.identity.ticker })
            assertEquals(listOf("DOGE", "ETH"), state.losers.map { it.identity.ticker })
            assertEquals(MARKET_UNIVERSE, state.moversEyebrow)
            assertEquals(listOf("SOL"), state.favorites.map { it.identity.ticker })
            assertEquals(
                "bitcoin:btc",
                state.topCoins
                    .first()
                    .identity.id,
            )
            assertEquals("$0.000007500", state.trending.single().price)
            assertTrue(state.status!!.endsWith("Data by CoinGecko"))
        }
    }

    @Test fun mainLoadsAfterConsentOpensMarketOnlyCoinsAndKeepsFavorites() {
        JvmSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { delegate ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                var refreshes = 0
                val host =
                    object : PlatformActions by delegate {
                        override fun refreshMarkets(
                            maxAgeMillis: Long,
                            onResult: (String) -> Unit,
                        ) {
                            refreshes++
                            val message = marketRefreshMessage(app.market.refreshFeeds(::feeds, maxAgeMillis))
                            delegate.dataRevision.update { it + 1 }
                            onResult(message)
                        }
                    }
                runDesktopComposeUiTest(width = 600, height = 1400) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("market-load").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("Market data is off").assertExists()
                    onNodeWithTag("market-load").performClick()
                    onNodeWithTag("market-consent-confirm").performClick()
                    waitUntil(
                        timeoutMillis = 10_000,
                    ) { onAllNodesWithTag("market-top-coingecko:ethereum").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(1, refreshes)
                    assertTrue(app.market.consent())
                    onNodeWithTag("market-load").assertDoesNotExist()
                    assertEquals(2, onAllNodesWithText(MARKET_UNIVERSE).fetchSemanticsNodes().size)
                    onNodeWithTag("model-content").performScrollToNode(hasTestTag("favorite-coingecko:pepe"))
                    onNodeWithTag("favorite-coingecko:pepe").performClick()
                    waitUntil(timeoutMillis = 10_000) { AssetId("coingecko:pepe") in app.market.favorites() }
                    onNodeWithTag("model-content").performScrollToNode(hasTestTag("market-top-coingecko:ethereum"))
                    onNodeWithTag("market-top-coingecko:ethereum").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("coin-market-source").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("Market Cap Rank").assertExists()
                    onNodeWithText("#2").assertExists()
                }
            }
        }
    }

    @Test fun rateLimitedAndOfflineStatesKeepTheLastListsVisible() {
        JvmSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { host ->
                var now = System.currentTimeMillis()
                val app = PortfolioApplication(db) { now }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                app.market.setConsent(true)
                app.market.refreshFeeds(::feeds)
                now += 120_000
                app.market.refreshFeeds({ _, _ -> throw CoingeckoProviderException(CoingeckoProviderFailure.RATE_LIMITED) })
                runDesktopComposeUiTest(width = 600, height = 1400) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("CoinGecko is limiting requests", substring = true).fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithText("add a free API key in Settings", substring = true).assertExists()
                    onNodeWithTag("market-top-coingecko:ethereum").assertExists()
                    now += 61_000
                    app.market.refreshFeeds({ _, _ -> throw CoingeckoProviderException(CoingeckoProviderFailure.OFFLINE) })
                    host.dataRevision.update { it + 1 }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Offline", substring = true).fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithTag("market-top-coingecko:ethereum").assertExists()
                }
            }
        }
    }

    @Test fun emptyFeedWithConsentInvitesARefresh() {
        JvmSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                app.market.setConsent(true)
                runDesktopComposeUiTest(width = 600, height = 1400) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("No market data yet. Refresh to load it.").fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithText("Not loaded yet · Data by CoinGecko").assertExists()
                }
            }
        }
    }
}
