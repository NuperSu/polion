package io.polion.model

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ModelPreviewTest {
    @Test
    fun normalViewportsAndDetailFlows() {
        val output = File("build/previews").apply { mkdirs() }
        output.listFiles()?.filter { it.extension == "png" }?.forEach { it.delete() }
        for ((width, height, fontScale) in listOf(
            Triple(390, 844, 1f),
            Triple(360, 800, 1f),
            Triple(1440, 900, 1f),
            Triple(390, 844, 1.5f),
        )) {
            for (page in ModelPage.entries) {
                runDesktopComposeUiTest(width = width, height = height) {
                    fun capture(name: String) {
                        File(
                            output,
                            "${width}x$height-$fontScale-$name.png",
                        ).writeBytes(
                            Image.makeFromBitmap(onAllNodes(isRoot()).onLast().captureToImage().asSkiaBitmap()).encodeToData()!!.bytes,
                        )
                    }
                    setContent {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { PortfolioModel(page) }
                    }
                    onNodeWithTag("model-${page.name}").assertIsDisplayed()
                    capture(page.name.lowercase())
                    if (page == ModelPage.MAIN) {
                        onNodeWithText("Your Portfolio").assertDoesNotExist()
                        for (heading in listOf(
                            "Favorites",
                            "Trending",
                            "Top Gainers",
                            "Top Losers",
                            "POLION · DESIGN PREVIEW · SAMPLE DATA",
                        )) {
                            onNodeWithTag("model-content").performScrollToNode(hasText(heading))
                            capture("main-${heading.lowercase().replace(' ', '-').take(20)}")
                        }
                    }
                    if (page == ModelPage.INSIGHTS) {
                        for (heading in listOf(
                            "Portfolio Performance",
                            "Decisions",
                            "Portfolio Diversity",
                            "Gains Reporting",
                            "Asset Location",
                            "Trade Statistics",
                            "Fees",
                            "Asset Worth",
                            "POLION · DESIGN PREVIEW · SAMPLE DATA",
                        )) {
                            onNodeWithTag("model-content").performScrollToNode(hasText(heading))
                            capture("insights-${heading.lowercase().replace(' ', '-').take(20)}")
                        }
                    }
                    if (page == ModelPage.PORTFOLIO) {
                        onNodeWithTag("coin-BTC").performClick()
                        onNodeWithText("Owned").assertIsDisplayed()
                        capture("coin-general")
                        onNodeWithTag("model-content").performScrollToNode(hasText("POLION · DESIGN PREVIEW · SAMPLE DATA"))
                        capture("coin-general-bottom")
                        for (tab in listOf("Alerts", "Order book", "Transactions")) {
                            onNodeWithTag("model-content").performScrollToNode(hasTestTag("detail-$tab"))
                            onNodeWithTag("detail-$tab").performScrollTo().performClick()
                            capture("coin-${tab.lowercase().replace(' ', '-')}")
                            if (tab == "Order book") {
                                onNodeWithTag("model-content").performScrollToNode(hasText("POLION · DESIGN PREVIEW · SAMPLE DATA"))
                                capture("coin-order-book-bottom")
                                onNodeWithTag("model-back").performClick()
                                onNodeWithTag("coin-BTC").performClick()
                            }
                            if (tab == "Transactions") {
                                onAllNodesWithText("Worth Then").onLast().performScrollTo()
                                capture("coin-transactions-scrolled")
                                onNodeWithTag("model-content").performScrollToNode(hasText("POLION · DESIGN PREVIEW · SAMPLE DATA"))
                                capture("coin-transactions-bottom")
                                onNodeWithTag("detail-add-transaction").performScrollTo().performClick()
                                capture("coin-manual-transaction")
                                onNodeWithTag("preview-save-transaction").performClick()
                            }
                            if (tab == "Alerts") {
                                onNodeWithText("＋  Add New Alert").performScrollTo().performClick()
                                onNodeWithText("New price alert").performScrollTo()
                                capture("coin-alert-editor")
                                onNodeWithText("Done").performScrollTo().performClick()
                            }
                        }
                        onNodeWithTag("model-back").performClick()
                        onNodeWithTag("add-transaction").performClick()
                        capture("add-method")
                        onNodeWithTag("choose-manual").performClick()
                        capture("manual-transaction")
                        for (type in listOf("Sell", "Transfer")) {
                            onNodeWithText(type).performScrollTo().performClick()
                            capture("manual-${type.lowercase()}")
                        }
                        onNodeWithText("Notes").performScrollTo()
                        capture("manual-scrolled")
                        onNodeWithTag("preview-save-transaction").performClick()
                        onNodeWithTag("model-menu").performClick()
                        capture("menu")
                        onNodeWithText("Settings").performClick()
                        onNodeWithTag("automatic-refresh").assertIsDisplayed().performClick()
                        capture("settings")
                        onNodeWithTag("model-back").performClick()
                        onNodeWithTag("model-menu").performClick()
                        onNodeWithText("Accounts").performClick()
                        onNodeWithText("Your accounts").assertIsDisplayed()
                        capture("accounts")
                    }
                    onNodeWithTag("model-MAIN").performClick()
                    onNodeWithTag("model-content").performScrollToNode(hasText("Top Coins"))
                    onNodeWithText("Top Coins").assertIsDisplayed()
                }
            }
        }
    }
}
