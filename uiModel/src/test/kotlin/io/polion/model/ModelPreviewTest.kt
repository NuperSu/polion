package io.polion.model

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ModelPreviewTest {
    @Test
    fun normalViewportsAndDetailFlows() {
        val output = File("build/previews").apply { mkdirs() }
        output.listFiles()?.filter { it.extension == "png" }?.forEach { it.delete() }
        for ((width, height) in listOf(390 to 844, 360 to 800, 1440 to 900)) {
            for (page in ModelPage.entries) {
                runDesktopComposeUiTest(width = width, height = height) {
                    fun capture(name: String) {
                        File(
                            output,
                            "$name-${width}x$height.png",
                        ).writeBytes(
                            Image.makeFromBitmap(onAllNodes(isRoot()).onLast().captureToImage().asSkiaBitmap()).encodeToData()!!.bytes,
                        )
                    }
                    setContent { PortfolioModel(page) }
                    onNodeWithTag("model-${page.name}").assertIsDisplayed()
                    capture(page.name.lowercase())
                    if (page == ModelPage.MAIN) {
                        onNodeWithText("Your Portfolio").assertDoesNotExist()
                        onNodeWithTag("model-content").performScrollToNode(hasText("Top Losers"))
                        capture("main-scrolled")
                    }
                    if (page == ModelPage.INSIGHTS) {
                        onNodeWithTag("model-content").performScrollToNode(hasText("Gains Reporting"))
                        capture("insights-scrolled")
                    }
                    if (page == ModelPage.PORTFOLIO) {
                        onNodeWithTag("coin-BTC").performClick()
                        onNodeWithText("Owned").assertIsDisplayed()
                        capture("coin-general")
                        for (tab in listOf("Transactions", "Alerts", "Order book")) {
                            onNodeWithTag("detail-$tab").performScrollTo().performClick()
                            capture("coin-${tab.lowercase().replace(' ', '-')}")
                        }
                        onNodeWithTag("model-back").performClick()
                        onNodeWithTag("add-transaction").performClick()
                        capture("add-method")
                        onNodeWithTag("choose-manual").performClick()
                        capture("manual-transaction")
                        onNodeWithTag("preview-save-transaction").performClick()
                        onNodeWithTag("model-menu").performClick()
                        onNodeWithText("Settings").performClick()
                        onNodeWithTag("automatic-refresh").assertIsDisplayed().performClick()
                        capture("settings")
                        onNodeWithTag("model-back").performClick()
                        onNodeWithTag("model-menu").performClick()
                        onNodeWithText("Accounts").performClick()
                        onNodeWithText("Your accounts").assertIsDisplayed()
                    }
                    onNodeWithTag("model-MAIN").performClick()
                    onNodeWithTag("model-content").performScrollToNode(hasText("Top Coins"))
                    onNodeWithText("Top Coins").assertIsDisplayed()
                }
            }
        }
    }
}
