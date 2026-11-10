package io.valroot.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.valroot.ui.model.CoinLogoSource
import io.valroot.ui.model.LocalCoinLogos
import io.valroot.ui.model.ModelCoinIdentity
import io.valroot.ui.model.ModelCoinLogo
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CoinLogoReuseTest {
    @Test fun aRowThatChangesCoinLoadsTheNewCoinsLogo() {
        val loaded = mutableListOf<String>()
        val source =
            CoinLogoSource(
                mapOf("hype" to "https://img/hype.png", "pengu" to "https://img/pengu.png"),
                cached = { null },
                load = { url -> ImageBitmap(1, 1).also { loaded += url } },
            )
        var coin by mutableStateOf(ModelCoinIdentity("HYPE", "Hyperliquid", Color.Gray, "H", "hype"))
        runDesktopComposeUiTest(width = 100, height = 100) {
            setContent { CompositionLocalProvider(LocalCoinLogos provides source) { ModelCoinLogo(coin) } }
            waitForIdle()
            // The same slot now shows another coin, as when a list reorders after a refresh.
            coin = ModelCoinIdentity("PENGU", "Pudgy Penguins", Color.Gray, "P", "pengu")
            waitForIdle()
            assertEquals(listOf("https://img/hype.png", "https://img/pengu.png"), loaded)
        }
    }
}
