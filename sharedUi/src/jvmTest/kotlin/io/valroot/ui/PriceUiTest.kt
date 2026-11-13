package io.valroot.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.onboarding.OnboardingAction
import io.valroot.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PriceUiTest {
    @Test
    fun assetDetailCanSwitchFromManualToProviderPrices() {
        JvmSqliteDatabase(":memory:").use { db ->
            TestHost().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.demo.seed()
                runDesktopComposeUiTest(width = 360, height = 800) {
                    setContent { ValrootApp(app, host) }
                    openAdvancedTools()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("destination-OVERVIEW").performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Bitcoin"))
                    onNodeWithText("Bitcoin").performClick()
                    onNodeWithTag("use-provider-prices").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Provider prices selected; manual history retained").fetchSemanticsNodes().isNotEmpty()
                    }
                    assertEquals(
                        null,
                        app
                            .snapshot()
                            .valuations
                            .single()
                            .price,
                    )
                    assertEquals("4", db.query("SELECT COUNT(*) FROM market_prices WHERE manual=1").single().single())
                }
            }
        }
    }

    @Test
    fun portfolioTotalWarnsWhenCurrentPriceIsStale() {
        JvmSqliteDatabase(":memory:").use { db ->
            TestHost().use { host ->
                var clock = System.currentTimeMillis()
                val app = PortfolioApplication(db) { clock }
                app.demo.seed()
                clock += 3 * 3_600_000L
                runDesktopComposeUiTest(width = 360, height = 800) {
                    setContent { ValrootApp(app, host) }
                    openAdvancedTools()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("destination-OVERVIEW").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("1 asset uses stale prices").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("page-content").performScrollToNode(hasText("1 asset uses stale prices"))
                    onNodeWithText("1 asset uses stale prices").assertIsDisplayed()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Stale · manual quote"))
                    onNodeWithText("Stale · manual quote").assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun automaticPricesRequireConsentAndScheduleRefresh() {
        JvmSqliteDatabase(":memory:").use { db ->
            TestHost().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                val data = app.snapshot()
                runDesktopComposeUiTest(width = 360, height = 800) {
                    setContent { WorkflowDialog("price", data, app, host, {}, { _, operation -> operation() }, {}, { _, _ -> }) }
                    assertEquals(false, app.prices.automaticConsent())
                    onNodeWithTag("automatic-price-consent").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) { host.automaticPrices }
                    assertTrue(app.prices.automaticConsent())
                    onNodeWithTag("refresh-prices-now").performScrollTo().performClick()
                    onAllNodesWithText("Price refresh queued.").onFirst().assertIsDisplayed()
                    onNodeWithTag("refresh-prices-now").assertIsNotEnabled()
                    host.priceRefreshStatus.value = PriceRefreshPhase.RUNNING
                    onNodeWithText("Refreshing prices…").assertIsDisplayed()
                    host.priceRefreshStatus.value = PriceRefreshPhase.IDLE
                    onNodeWithTag("automatic-price-consent").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) { !host.automaticPrices }
                    assertEquals(false, app.prices.automaticConsent())
                }
            }
        }
    }

    @Test
    fun historicalPriceRangeRequiresExplicitConsent() {
        JvmSqliteDatabase(":memory:").use { db ->
            TestHost().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                val data = app.snapshot()
                runDesktopComposeUiTest(width = 800, height = 900) {
                    setContent {
                        WorkflowDialog("price", data, app, host, {}, { _, operation -> operation() }, {}, { _, _ -> })
                    }
                    onNodeWithTag("fetch-historical-price").performScrollTo().assertIsNotEnabled()
                    onNodeWithTag("price-source-consent").performScrollTo().performClick()
                    onNodeWithTag("fetch-historical-price").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) { host.priceRequests == 1 }
                    onNodeWithText("Saved Bitcoin price history.").performScrollTo().assertIsDisplayed()
                }
                assertTrue(app.prices.sourceConsent())
                assertEquals(1, host.priceRequests)
            }
        }
    }
}
