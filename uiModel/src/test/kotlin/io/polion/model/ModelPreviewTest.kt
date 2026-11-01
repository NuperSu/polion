package io.polion.model

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test

/** Exports reviewable renders of the model; no application data is opened. */
@OptIn(ExperimentalTestApi::class)
class ModelPreviewTest {
    @Test
    fun renderPhoneDesktopAndFullPages() {
        val output = File("build/previews").apply { mkdirs() }
        ModelPage.entries.forEach { page ->
            runDesktopComposeUiTest(width = 420, height = 900) {
                setContent { PortfolioModel(page) }
                onNodeWithTag("model-${page.name}").assertIsDisplayed()
                val bytes = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData()!!.bytes
                File(output, "${page.name.lowercase()}-phone.png").writeBytes(bytes)
                onNodeWithTag("model-FOLLOWING").performClick()
                onNodeWithText("Highest Cap", substring = true).assertIsDisplayed()
            }
        }
        listOf(ModelPage.HOME to 3500, ModelPage.MARKETS to 2600, ModelPage.INSIGHTS to 2650).forEach { (page, height) ->
            runDesktopComposeUiTest(width = 420, height = height) {
                setContent { PortfolioModel(page) }
                File(output, "${page.name.lowercase()}-full.png").writeBytes(
                    Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData()!!.bytes,
                )
            }
        }
        runDesktopComposeUiTest(width = 1280, height = 900) {
            setContent { PortfolioModel() }
            onNodeWithTag("model-PORTFOLIO").assertIsDisplayed()
            File(output, "portfolio-desktop.png").writeBytes(
                Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData()!!.bytes,
            )
        }
        runDesktopComposeUiTest(width = 360, height = 800) {
            setContent { PortfolioModel() }
            onNodeWithTag("model-PORTFOLIO").assertIsDisplayed()
            File(output, "portfolio-small-phone.png").writeBytes(
                Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData()!!.bytes,
            )
        }
    }
}
