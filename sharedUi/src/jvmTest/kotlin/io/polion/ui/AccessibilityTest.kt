package io.polion.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.polion.core.application.PortfolioApplication
import io.polion.core.onboarding.OnboardingAction
import io.polion.core.storage.JvmSqliteDatabase
import io.polion.ui.model.ModelPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AccessibilityTest {
    @Test
    fun everyScreenNamesItsClickableControlsAndKeepsDestinationsInWindow() {
        for ((width, height) in listOf(360 to 800, 390 to 844, 1440 to 900)) {
            for (fontScale in listOf(1f, 1.5f)) {
                val label = "${width}x$height@$fontScale"
                JvmSqliteDatabase(":memory:").use { db ->
                    ModelWorkspaceRestorationTest.RestorationHost().use { host ->
                        val app = PortfolioApplication(db) { 1_800_000_000_000L }
                        app.updateOnboarding(OnboardingAction.Skip)
                        app.seedDemo()
                        runDesktopComposeUiTest(width = width, height = height) {
                            setContent {
                                val density = LocalDensity.current
                                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { PolionApp(app, host) }
                            }
                            waitUntil(timeoutMillis = 30_000) {
                                onAllNodesWithText("EXAMPLE DATA · stored only on this device").fetchSemanticsNodes().isNotEmpty()
                            }
                            assertClickableControlsAreNamed("$label main")
                            assertDestinationsInWindow(width, height, "$label main")
                            assertTouchTarget("model-menu")

                            onNodeWithTag("model-PORTFOLIO").performClick()
                            waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("coin-BTC").fetchSemanticsNodes().isNotEmpty() }
                            assertClickableControlsAreNamed("$label portfolio")
                            assertDestinationsInWindow(width, height, "$label portfolio")
                            assertTouchTarget("balance-visibility")
                            assertEyeTogglePublishesState()

                            onNodeWithTag("model-INSIGHTS").performClick()
                            waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("insights-trades").fetchSemanticsNodes().isNotEmpty() }
                            assertClickableControlsAreNamed("$label insights")
                            assertDestinationsInWindow(width, height, "$label insights")

                            openSettings()
                            waitUntil(
                                timeoutMillis = 30_000,
                            ) { onAllNodesWithTag("settings-connections").fetchSemanticsNodes().isNotEmpty() }
                            assertClickableControlsAreNamed("$label settings")
                            assertDestinationsInWindow(width, height, "$label settings")

                            openSettingsRow("settings-connections")
                            waitUntil(timeoutMillis = 30_000) {
                                onAllNodesWithTag("settings-connections-help").fetchSemanticsNodes().isNotEmpty()
                            }
                            assertClickableControlsAreNamed("$label connections")
                            assertDestinationsInWindow(width, height, "$label connections")
                            assertTouchTarget("settings-back")
                            // A switch row is one toggle: its title and description are read with the switch state.
                            val autoSync = onNode(hasText("Auto-sync") and isToggleable()).fetchSemanticsNode()
                            assertTrue(hasClickAction().matches(autoSync), "Auto-sync should be one clickable toggle")
                        }
                    }
                }
            }
        }
    }

    /** Every control with a click action needs text or a content description, so TalkBack can name it. */
    private fun ComposeUiTest.assertClickableControlsAreNamed(screen: String) {
        val unnamed =
            onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { node ->
                val hasText = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.isNotBlank() } == true
                val hasDescription = node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.isNotBlank() } == true
                !hasText && !hasDescription
            }
        assertTrue(
            unnamed.isEmpty(),
            "$screen: clickable nodes without text or content description: " +
                unnamed.map { it.config.getOrNull(SemanticsProperties.TestTag) to it.boundsInRoot },
        )
    }

    /** The main destinations (bottom bar on phones, side rail when wide) must be visible inside the window. */
    private fun ComposeUiTest.assertDestinationsInWindow(
        width: Int,
        height: Int,
        screen: String,
    ) {
        for (page in ModelPage.entries) {
            val bounds = onNodeWithTag("model-${page.name}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(
                bounds.left >= 0f && bounds.top >= 0f && bounds.right <= width && bounds.bottom <= height,
                "$screen: ${page.name} destination $bounds is outside ${width}x$height",
            )
        }
    }

    /** Icon-only controls keep a 48dp touch target even though the drawn icon is smaller. */
    private fun ComposeUiTest.assertTouchTarget(tag: String) {
        onNodeWithTag(tag).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    /** The eye publishes its action as the description and its current state as the state description. */
    private fun ComposeUiTest.assertEyeTogglePublishesState() {
        assertEquals("Hide amounts" to "Amounts shown", eyeDescriptions())
        onNodeWithTag("balance-visibility").performClick()
        waitUntil(timeoutMillis = 10_000) { eyeDescriptions().second == "Amounts hidden" }
        assertEquals("Show amounts" to "Amounts hidden", eyeDescriptions())
        onNodeWithTag("balance-visibility").performClick()
        waitUntil(timeoutMillis = 10_000) { eyeDescriptions().second == "Amounts shown" }
        assertEquals("Hide amounts" to "Amounts shown", eyeDescriptions())
    }

    private fun ComposeUiTest.eyeDescriptions(): Pair<String?, String?> {
        val config = onNodeWithTag("balance-visibility").fetchSemanticsNode().config
        return config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull() to
            config.getOrNull(SemanticsProperties.StateDescription)
    }
}
