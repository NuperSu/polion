package io.valroot.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.valroot.core.application.LandingPage
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.onboarding.OnboardingAction
import io.valroot.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class InsightsScreenTest {
    @Test fun everySectionHasDataAndDecisionsIsGone() {
        JvmSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { host ->
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                app.updateOnboarding(OnboardingAction.Skip)
                app.seedDemo()
                app.setLandingPage(LandingPage.INSIGHTS)
                runDesktopComposeUiTest(width = 600, height = 3200) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("insights-performance").fetchSemanticsNodes().isNotEmpty() }
                    // The return either shows a rate or says why it cannot; it never stays "Calculating…".
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Calculating…").fetchSemanticsNodes().isEmpty() }
                    for (tag in listOf(
                        "insights-history",
                        "insights-diversity",
                        "insights-gains",
                        "insights-location",
                        "insights-trades",
                        "insights-fees",
                        "insights-worth",
                    )) {
                        onNodeWithTag(tag).performScrollTo()
                    }
                    assertTrue(onAllNodesWithText("Decisions").fetchSemanticsNodes().isEmpty())
                    assertTrue(onAllNodesWithText("Unavailable").fetchSemanticsNodes().isEmpty())
                    onNodeWithTag("insights-range-1Y").performScrollTo().performClick()
                    waitUntil(
                        timeoutMillis = 10_000,
                    ) { onAllNodesWithText("MONEY-WEIGHTED RETURN · 1Y").fetchSemanticsNodes().isNotEmpty() }
                }
            }
        }
    }
}
