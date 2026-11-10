package io.valroot.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.onboarding.OnboardingAction
import io.valroot.core.storage.JvmSqliteDatabase
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ModelProductionCaptureTest {
    @Test
    fun captureDefaultBindingAcrossViewports() {
        val output = File("build/phase1-model-production-previews").apply { mkdirs() }
        output.listFiles()?.filter { it.extension == "png" }?.forEach { it.delete() }
        for ((width, height, fontScale) in listOf(
            Triple(360, 800, 1f),
            Triple(390, 844, 1f),
            Triple(1440, 900, 1f),
            Triple(390, 844, 1.5f),
        )) {
            JvmSqliteDatabase(":memory:").use { db ->
                ModelWorkspaceRestorationTest.RestorationHost().use { host ->
                    val app = PortfolioApplication(db) { 1_800_000_000_000L }
                    app.updateOnboarding(OnboardingAction.Skip)
                    app.seedDemo()
                    runDesktopComposeUiTest(width = width, height = height) {
                        fun capture(name: String) {
                            ImageIO.write(
                                onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(),
                                "png",
                                File(output, "${width}x$height-$fontScale-$name.png"),
                            )
                        }
                        setContent {
                            val density = LocalDensity.current
                            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { ValrootApp(app, host) }
                        }
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("model-MAIN").fetchSemanticsNodes().isNotEmpty() }
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("model-refresh").fetchSemanticsNodes().isNotEmpty() }
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Saved prices").fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithText("EXAMPLE DATA · stored only on this device").assertIsDisplayed()
                        capture("main")
                        for (heading in listOf("Favorites", "Trending", "Top Gainers", "Top Losers")) {
                            onNodeWithTag("model-content").performScrollToNode(hasText(heading))
                            capture("main-${heading.lowercase().replace(' ', '-')}")
                        }
                        onNodeWithTag("model-PORTFOLIO").performClick()
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("coin-BTC").fetchSemanticsNodes().isNotEmpty() }
                        capture("portfolio")
                        onNodeWithTag("coin-BTC").performScrollTo()
                        capture("portfolio-holdings")
                        onNodeWithTag("coin-BTC").performClick()
                        waitUntil(
                            timeoutMillis = 10_000,
                        ) { onAllNodesWithTag("coin-price-history-ready").fetchSemanticsNodes().isNotEmpty() }
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("coin-accounting-ready").fetchSemanticsNodes().isNotEmpty() }
                        capture("coin-general")
                        for (tab in listOf("Transactions", "Alerts", "Order book")) {
                            onNodeWithTag("detail-$tab").performScrollTo().performClick()
                            capture("coin-${tab.lowercase().replace(' ', '-')}")
                            if (tab == "Transactions") {
                                waitUntil(
                                    timeoutMillis = 10000,
                                ) { onAllNodesWithTag("transaction-card-demo-pending").fetchSemanticsNodes().isNotEmpty() }
                                onNodeWithTag("transaction-card-demo-pending").performScrollTo()
                                capture("coin-transaction-cards")
                            }
                        }
                        onNodeWithTag("model-back").performClick()
                        onNodeWithTag("add-transaction").performClick()
                        capture("add-method")
                        onNodeWithTag("choose-manual").performScrollTo().performClick()
                        capture("manual-buy")
                        for (type in listOf("Sell", "Transfer")) {
                            onNodeWithText(type).performScrollTo().performClick()
                            capture("manual-${type.lowercase()}")
                        }
                        onNodeWithText("Notes").performScrollTo()
                        capture("manual-bottom")
                        onNodeWithContentDescription("Close").performClick()
                        onNodeWithTag("model-INSIGHTS").performClick()
                        capture("insights")
                        for (heading in listOf(
                            "Portfolio Performance",
                            "Portfolio Diversity",
                            "Gains Reporting",
                            "Asset Location",
                            "Trade Statistics",
                            "Fees",
                            "Asset Worth",
                        )) {
                            onNodeWithTag("model-content").performScrollToNode(hasText(heading))
                            capture("insights-${heading.lowercase().replace(' ', '-')}")
                        }
                        openConnections()
                        capture("accounts")
                        onNodeWithTag("model-back").performClick()
                        openSettings()
                        capture("settings")
                    }
                }
            }
        }
    }
}
