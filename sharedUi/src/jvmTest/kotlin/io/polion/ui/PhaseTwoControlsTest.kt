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
                    onNodeWithText("☷").performScrollTo().performClick()
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
