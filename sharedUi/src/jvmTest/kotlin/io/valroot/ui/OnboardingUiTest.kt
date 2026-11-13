package io.valroot.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.onboarding.OnboardingAction
import io.valroot.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class OnboardingUiTest {
    @Test
    fun erasingLocalDataReturnsToFreshOnboardingWithoutHostRevision() {
        JvmSqliteDatabase(":memory:").use { db ->
            TestHost().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                app.demo.seed()
                runDesktopComposeUiTest(width = 1000, height = 900) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty()
                    }
                    openAdvancedTools()
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Settings").onLast().performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Erase local portfolio data"))
                    onNodeWithText("Erase local portfolio data").performScrollTo().performClick()
                    onNodeWithText("Type ERASE to confirm").performTextInput("ERASE")
                    onNodeWithText("Erase this device’s data").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Set up your space").fetchSemanticsNodes().isNotEmpty()
                    }
                    assertEquals(0L, host.dataRevision.value)
                    assertEquals(
                        io.valroot.core.onboarding
                            .OnboardingState(),
                        app.settings.onboarding(),
                    )
                    assertTrue(
                        app
                            .snapshot()
                            .ownership.accounts
                            .isEmpty(),
                    )
                    assertTrue(!app.snapshot().sampleData)
                }
            }
        }
    }

    @Test
    fun freshInstallCanCompleteWithoutAccountsOrNetworkRequests() {
        JvmSqliteDatabase(":memory:").use { db ->
            TestHost().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                runDesktopComposeUiTest(width = 420, height = 900) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Set up your space").fetchSemanticsNodes().isNotEmpty()
                    }
                    for (title in listOf("Choose your interests", "Explore supported sources", "Ready at your pace")) {
                        onNodeWithText("Continue").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 10_000) {
                            onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
                        }
                    }
                    onNodeWithTag("onboarding-finish").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty()
                    }
                    assertTrue(app.settings.onboarding()!!.completed)
                    assertTrue(
                        app
                            .snapshot()
                            .ownership.accounts
                            .isEmpty(),
                    )
                    assertEquals(0, host.refreshRequests)
                    assertEquals(0, host.priceRequests)
                    assertTrue(!app.snapshot().sampleData)
                }
            }
        }
    }

    @Test
    fun freshInstallCanSkipOfflineAndResumeSavedSetupFromSettings() {
        JvmSqliteDatabase(":memory:").use { db ->
            TestHost().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                runDesktopComposeUiTest(width = 1000, height = 900) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Set up your space").fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithText("Continue").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Choose your interests").fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithTag("onboarding-goal-BALANCES").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        io.valroot.core.onboarding.OnboardingGoal.BALANCES in app.settings.onboarding()!!.goals
                    }
                    onNodeWithText("Skip setup for now").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty()
                    }
                    openAdvancedTools()
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Settings").onLast().performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Resume setup"))
                    onNodeWithText("Resume setup").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Choose your interests").fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithTag("onboarding-goal-BALANCES").performScrollTo().assertIsSelected()
                    assertEquals(0, host.refreshRequests)
                    assertEquals(0, host.priceRequests)
                    assertTrue(
                        app
                            .snapshot()
                            .ownership.accounts
                            .isEmpty(),
                    )
                    assertTrue(!app.snapshot().sampleData)
                }
            }
        }
    }
}
