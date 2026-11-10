package io.valroot.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.model.AssetId
import io.valroot.core.model.TrackingTargetId
import io.valroot.core.storage.JvmSqliteDatabase
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AdaptiveLayoutUiTest {
    @Test
    fun resizingAcrossNavigationBreakpointsPreservesActivitySearchAndDestination() {
        JvmSqliteDatabase(":memory:").use { db ->
            LayoutHost().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.seedDemo()
                runDesktopComposeUiTest(width = 1200, height = 900) {
                    var viewportWidth by mutableStateOf(699)
                    setContent {
                        Box(Modifier.size(viewportWidth.dp, 900.dp)) { ValrootApp(app, host) }
                    }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Opening your portfolio").fetchSemanticsNodes().isEmpty()
                    }
                    openAdvancedTools()
                    onNodeWithTag("compact-navigation").assertIsDisplayed()
                    onNodeWithTag("workspace-rail").assertDoesNotExist()
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Advanced tools").onLast().performClick()
                    onNodeWithTag("more-ACTIVITY").performClick()
                    onNodeWithText("Search activity").performTextInput("demo-deposit")
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("1 activities", substring = true).fetchSemanticsNodes().isNotEmpty()
                    }
                    for (width in listOf(700, 1120, 360)) {
                        runOnIdle { viewportWidth = width }
                        onNodeWithTag(if (width < 700) "compact-navigation" else "workspace-rail").assertIsDisplayed()
                        onNodeWithTag(if (width < 700) "workspace-rail" else "compact-navigation").assertDoesNotExist()
                        onNodeWithText("Activity").assertIsDisplayed()
                        onNodeWithText("demo-deposit").assertIsDisplayed()
                        onNodeWithTag("page-content").performScrollToNode(hasText("1 activities", substring = true))
                        onNodeWithText("1 activities", substring = true).assertIsDisplayed()
                        onNodeWithTag("page-content").performScrollToNode(hasText("Search activity"))
                    }
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Advanced tools").onLast().performClick()
                    onNodeWithTag("more-ACCOUNTS").performClick()
                    onNodeWithText("Watch-only accounts").assertIsDisplayed()
                    runOnIdle { viewportWidth = 700 }
                    onNodeWithTag("workspace-menu").assertIsDisplayed()
                    onNodeWithText("Watch-only accounts").assertIsDisplayed()
                    runOnIdle { viewportWidth = 360 }
                    onNodeWithTag("workspace-menu").assertIsDisplayed()
                    onNodeWithText("Watch-only accounts").assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun enlargedTextInCompactLandscapeKeepsNavigationSettingsAndExportReachable() {
        JvmSqliteDatabase(":memory:").use { db ->
            LayoutHost().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.seedDemo()
                runDesktopComposeUiTest(width = 640, height = 360) {
                    setContent {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                            ValrootApp(app, host)
                        }
                    }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Opening your portfolio").fetchSemanticsNodes().isEmpty()
                    }
                    openAdvancedTools()
                    for (destination in compactDestinations) {
                        val node = onNodeWithTag("destination-${destination.name}")
                        node.assertIsDisplayed()
                        val bounds = node.fetchSemanticsNode().boundsInRoot
                        assertTrue(bounds.left >= 0f && bounds.right <= 640f && bounds.top >= 0f && bounds.bottom <= 360f)
                    }
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Advanced tools").onLast().performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasTestTag("more-SETTINGS"))
                    onNodeWithTag("more-SETTINGS").performClick()
                    for (key in listOf("light", "dark")) {
                        val tag = "appearance-$key"
                        onNodeWithTag("page-content").performScrollToNode(hasTestTag(tag))
                        onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
                        waitUntil(timeoutMillis = 10_000) {
                            app.appearanceTheme() == key && onAllNodes(hasTestTag(tag) and isSelected()).fetchSemanticsNodes().isNotEmpty()
                        }
                        onNodeWithTag("page-content").performScrollToNode(hasTestTag(tag))
                        onNodeWithTag(tag).performScrollTo().assertIsSelected()
                    }
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Advanced tools").onLast().performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasTestTag("more-REPORTS"))
                    onNodeWithTag("more-REPORTS").performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("↓  Export all active activity CSV"))
                    onNodeWithText("↓  Export all active activity CSV").assertIsDisplayed().performClick()
                    waitUntil(timeoutMillis = 10_000) { host.exportedName != null }
                    assertTrue(host.exportedName == "valroot-activities.csv")
                    onNodeWithTag("destination-HOME").performClick()
                    onNodeWithTag("destination-HOME").assertIsSelected()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Market"))
                    onNodeWithText("Market").assertIsDisplayed()
                }
            }
        }
    }

    private class LayoutHost :
        PlatformActions,
        AutoCloseable {
        override val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        override val dataRevision = MutableStateFlow(0L)
        override val syncStatus = MutableStateFlow(SyncStatus())
        override val hasLock = false

        @Volatile var exportedName: String? = null

        override fun saveFile(
            name: String,
            bytes: ByteArray,
            onResult: (String) -> Unit,
        ) {
            exportedName = name
            onResult("Exported")
        }

        override fun saveBackup(
            password: String,
            onResult: (String) -> Unit,
        ) = Unit

        override fun restoreBackup(
            password: String,
            onResult: (String) -> Unit,
        ) = Unit

        override fun verifyAccountingOrder(onResult: (String) -> Unit) = Unit

        override fun discoverWallet(
            walletId: TrackingTargetId,
            onResult: (String) -> Unit,
        ) = Unit

        override fun fetchHistoricalPrice(
            asset: AssetId,
            quote: AssetId,
            dayUtcMillis: Long,
            apiKey: String,
            onResult: (String) -> Unit,
        ) = Unit

        override fun refresh(onResult: (String) -> Unit) = Unit

        override fun recoverBitcoinHistory(onResult: (String) -> Unit) = Unit

        override fun setBackgroundRefresh(enabled: Boolean) = Unit

        override fun backgroundRefreshEnabled() = false

        override fun lock() = Unit

        override fun unlock(
            password: String,
            onResult: (UnlockResult) -> Unit,
        ) = Unit

        override fun setLockPassword(
            password: String,
            onResult: (String) -> Unit,
        ) = Unit

        override fun removeLockPassword(onResult: (String) -> Unit) = Unit

        override fun close() = dispatcher.close()
    }
}
