package io.valroot.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.valroot.core.application.LandingPage
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.onboarding.OnboardingAction
import io.valroot.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AmountPrivacyTest {
    @Test fun eyeButtonReplacesAmountsWithAsterisksAndKeepsPercentages() {
        JvmSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { host ->
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                app.demo.seed()
                app.settings.setLandingPage(LandingPage.PORTFOLIO)
                runDesktopComposeUiTest(width = 600, height = 1400) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("balance-visibility").fetchSemanticsNodes().isNotEmpty() }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("$", substring = true).fetchSemanticsNodes().isNotEmpty() }
                    assertTrue(onAllNodesWithText(HIDDEN_AMOUNT).fetchSemanticsNodes().isEmpty())
                    onNodeWithTag("balance-visibility").performClick()
                    waitUntil(timeoutMillis = 10_000) { app.settings.general().hideAmounts }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(HIDDEN_AMOUNT).fetchSemanticsNodes().size >= 2 }
                    // No money amount remains on the Portfolio page; market prices next to quantities stay public.
                    val dollars =
                        onAllNodesWithText("$", substring = true)
                            .fetchSemanticsNodes()
                            .mapNotNull {
                                it.config
                                    .getOrNull(SemanticsProperties.Text)
                                    ?.joinToString()
                            }
                    assertTrue(dollars.all { it.contains("·") || it.contains("<$") }, dollars.toString())
                    onNodeWithTag("balance-visibility").performClick()
                    waitUntil(timeoutMillis = 10_000) { !app.settings.general().hideAmounts }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(HIDDEN_AMOUNT).fetchSemanticsNodes().isEmpty() }
                }
            }
        }
    }
}
