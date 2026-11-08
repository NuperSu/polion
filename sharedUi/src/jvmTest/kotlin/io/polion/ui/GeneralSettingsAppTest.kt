package io.polion.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.polion.core.application.LandingPage
import io.polion.core.application.PortfolioApplication
import io.polion.core.model.AssetId
import io.polion.core.onboarding.OnboardingAction
import io.polion.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class GeneralSettingsAppTest {
    @Test
    fun savedLandingChartAndSecondaryCurrencyApplyOnLaunch() {
        JvmSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { host ->
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                app.updateOnboarding(OnboardingAction.Skip)
                app.seedDemo()
                app.setLandingPage(LandingPage.PORTFOLIO)
                app.setShowPortfolioChart(false)
                app.setSecondaryCurrency(AssetId("EUR"))
                runDesktopComposeUiTest(width = 600, height = 900) {
                    setContent { PolionApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithTag("portfolio-secondary-balance").fetchSemanticsNodes().isNotEmpty()
                    }
                    // The example data has no euro price, so the secondary total says so instead of guessing.
                    onNodeWithTag("portfolio-secondary-balance").assertExists()
                    onNodeWithTag("portfolio-chart").assertDoesNotExist()
                    openSettings()
                    openSettingsRow("settings-portfolio")
                    openSettingsRow("settings-show-chart")
                    waitUntil(timeoutMillis = 10_000) { app.generalPreferences().showPortfolioChart }
                    onNodeWithTag("settings-back").performClick()
                    onNodeWithTag("model-PORTFOLIO").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("portfolio-chart").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(LandingPage.PORTFOLIO, app.generalPreferences().landing)
                }
            }
        }
    }
}
