package io.polion.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.polion.core.application.AssetSort
import io.polion.core.application.PortfolioApplication
import io.polion.core.model.AssetId
import io.polion.core.onboarding.OnboardingAction
import io.polion.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PhaseTwoControlsTest {
    @Test
    fun marketCoinUsesGlobalAccountingAndRestoresEarlierPortfolioScope() {
        JvmSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { host ->
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                app.updateOnboarding(OnboardingAction.Skip)
                app.seedDemo()
                app.savePortfolio(
                    io.polion.core.ownership
                        .Portfolio(
                            io.polion.core.model
                                .PortfolioId("euro"),
                            "Euro test",
                            "EUR",
                        ),
                )
                runDesktopComposeUiTest(width = 600, height = 900) {
                    setContent { PolionApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("model-PORTFOLIO").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("model-PORTFOLIO").performClick()
                    onNodeWithText("All portfolios").performClick()
                    onNodeWithText("Euro test").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("EUR").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("model-MAIN").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("BTC").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("BTC").performScrollTo().performClick()
                    onNodeWithText("All portfolios").assertIsDisplayed()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("coin-accounting-ready").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("93250.00 USD", substring = true).performScrollTo().assertIsDisplayed()
                    onNodeWithText("Review activity and corrections").performScrollTo().performClick()
                    onNodeWithText("Back").performClick()
                    onNodeWithText("All portfolios").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("model-PORTFOLIO").performClick()
                    onNodeWithText("Euro test").assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun modeledCurrencySortFiltersAndExplanationUseRealWorkflows() {
        JvmSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { host ->
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                app.updateOnboarding(OnboardingAction.Skip)
                app.seedDemo()
                runDesktopComposeUiTest(width = 390, height = 844) {
                    setContent { PolionApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("model-PORTFOLIO").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("model-PORTFOLIO").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("coin-BTC").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("✧  What's going on?").performScrollTo().performClick()
                    onNodeWithText("Portfolio value").assertIsDisplayed()
                    onNodeWithText("Close").performClick()
                    onNodeWithText("Highest Holdings").performScrollTo().performClick()
                    onNodeWithText("name").performClick()
                    waitUntil(timeoutMillis = 10_000) { app.assetListPreference().sort == AssetSort.NAME }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Name").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("☷").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Hide empty: false").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("Hide empty: false").performClick()
                    waitUntil(timeoutMillis = 10_000) { app.assetListPreference().hideEmpty }
                    onNodeWithText("USD").performScrollTo().performClick()
                    onNodeWithText("EUR").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("EUR").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(AssetId("EUR"), app.snapshot().currency)
                    assertTrue(PortfolioApplication(db) { 1_800_000_000_000L }.assetListPreference().hideEmpty)
                }
            }
        }
    }
}
