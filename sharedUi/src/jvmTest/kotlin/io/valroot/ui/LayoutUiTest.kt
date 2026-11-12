package io.valroot.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.onboarding.OnboardingAction
import io.valroot.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LayoutUiTest {
    @Test
    fun assetDisplayChoiceIsSavedFromPortfolio() {
        JvmSqliteDatabase(":memory:").use { db ->
            TestHost().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.demo.seed()
                runDesktopComposeUiTest(width = 1000, height = 900) {
                    setContent { ValrootApp(app, host) }
                    openAdvancedTools()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("destination-OVERVIEW").performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasTestTag("hide-empty-assets"))
                    waitUntil(conditionDescription = "hide empty control enabled", timeoutMillis = 10_000) {
                        onAllNodes(hasTestTag("hide-empty-assets") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithTag("hide-empty-assets").performScrollTo().assertIsEnabled()
                    onNodeWithTag("hide-empty-assets").performClick()
                    waitForIdle()
                    waitUntil(
                        conditionDescription = "hide empty preference persisted",
                        timeoutMillis = 10_000,
                    ) { app.settings.assetList().hideEmpty }
                    assertEquals(true, PortfolioApplication(db) { System.currentTimeMillis() }.settings.assetList().hideEmpty)
                    onNodeWithTag("page-content").performScrollToNode(hasTestTag("hide-small-assets"))
                    waitUntil(conditionDescription = "hide small control enabled", timeoutMillis = 10_000) {
                        onAllNodes(hasTestTag("hide-small-assets") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithTag("hide-small-assets").performScrollTo().assertIsEnabled()
                    onNodeWithTag("hide-small-assets").performClick()
                    waitForIdle()
                    waitUntil(
                        conditionDescription = "hide small preference persisted",
                        timeoutMillis = 10_000,
                    ) { app.settings.assetList().hideSmall }
                    assertEquals(true, PortfolioApplication(db) { System.currentTimeMillis() }.settings.assetList().hideSmall)
                }
            }
        }
    }

    @Test
    fun phoneNavigationFitsTheViewport() {
        JvmSqliteDatabase(":memory:").use { db ->
            TestHost().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                app.demo.seed()
                runDesktopComposeUiTest(width = 360, height = 800) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Opening your portfolio").fetchSemanticsNodes().isEmpty() &&
                            onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty()
                    }
                    openAdvancedTools()
                    onNodeWithTag("destination-HOME").performClick()
                    onNodeWithTag("main-market").assertIsDisplayed()
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Advanced tools").onLast().performClick()
                    onNodeWithTag("more-ACTIVITY").performClick()
                    onNodeWithText("Search activity").assertIsDisplayed()
                    val searchBounds = onNodeWithText("Search activity").fetchSemanticsNode().boundsInRoot
                    assertTrue(searchBounds.left >= 0f && searchBounds.right <= 360f && searchBounds.bottom <= 800f)
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Advanced tools").onLast().performClick()
                    onNodeWithTag("more-ACCOUNTS").performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Watch-only accounts"))
                    onNodeWithText("Watch-only accounts").assertIsDisplayed()
                    val image = onRoot().captureToImage()
                    assertEquals(360, image.width)
                    assertEquals(800, image.height)
                }
            }
        }
    }

    @Test
    fun narrowPhoneNavigationWorksWithLargeText() {
        JvmSqliteDatabase(":memory:").use { db ->
            TestHost().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                app.demo.seed()
                runDesktopComposeUiTest(width = 320, height = 800) {
                    setContent {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                            ValrootApp(app, host)
                        }
                    }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Opening your portfolio").fetchSemanticsNodes().isEmpty() }
                    openAdvancedTools()
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Advanced tools").onLast().performClick()
                    onNodeWithTag("more-ACTIVITY").performClick()
                    onNodeWithText("Search activity").assertIsDisplayed()
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Advanced tools").onLast().performClick()
                    onNodeWithTag("more-ACCOUNTS").performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Watch-only accounts"))
                    onNodeWithText("Watch-only accounts").assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun shortLandscapeWindowKeepsEveryNavigationDestinationReachable() {
        JvmSqliteDatabase(":memory:").use { db ->
            TestHost().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                runDesktopComposeUiTest(width = 800, height = 360) {
                    setContent { ValrootApp(app, host) }
                    openAdvancedTools()
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Settings").onLast().performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Make it yours"))
                    onNodeWithText("Make it yours").performScrollTo().assertIsDisplayed()
                    onAllNodesWithText("Portfolio")
                        .filter(hasClickAction())
                        .onFirst()
                        .performScrollTo()
                        .performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("PRICED VALUE"))
                    onNodeWithText("PRICED VALUE").assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun compactAddAndAppearancePreferencesRemainReachable() {
        JvmSqliteDatabase(":memory:").use { db ->
            TestHost().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                app.demo.seed()
                runDesktopComposeUiTest(width = 360, height = 800) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty() }
                    openAdvancedTools()
                    onNodeWithTag("compact-navigation").assertIsDisplayed()
                    onNodeWithTag("destination-OVERVIEW").performClick()
                    onNodeWithTag("add-action").performClick()
                    onNodeWithText("Add to your portfolio").assertIsDisplayed()
                    onNodeWithTag("source-choice-bitcoin-address").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("source-choice-bitcoin-public-wallet").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("source-choice-manual-activity").performScrollTo().assertIsDisplayed()
                    onNodeWithText("Close").performClick()
                    onNodeWithTag("destination-OVERVIEW").performClick()
                    onNodeWithText("PRICED VALUE").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Advanced tools").onLast().performClick()
                    onNodeWithTag("more-SETTINGS").performClick()
                    for (key in listOf("light", "dark", "system")) {
                        val tag = "appearance-$key"
                        onNodeWithTag("page-content").performScrollToNode(hasTestTag(tag))
                        onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
                        waitUntil(timeoutMillis = 10_000) {
                            app.settings.theme() == key && onAllNodes(hasTestTag(tag) and isSelected()).fetchSemanticsNodes().isNotEmpty()
                        }
                        onNodeWithTag("page-content").performScrollToNode(hasTestTag(tag))
                        onNodeWithTag(tag).performScrollTo().assertIsSelected()
                    }
                    onNodeWithTag("destination-HOME").performClick()
                    onNodeWithText("Market").assertIsDisplayed()
                }
            }
        }
    }
}
