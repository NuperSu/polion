package io.valroot.ui

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.storage.JvmSqliteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CoinLogoLoaderTest {
    private val url = "https://coin-images.coingecko.com/coins/images/1/small/bitcoin.png"

    private fun png(): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray()

    @Test fun downloadsOnceCachesOnTheDeviceAndNeverFetchesOtherHosts() {
        runBlocking {
            JvmSqliteDatabase(":memory:").use { db ->
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                app.market.setConsent(true)
                val requested = mutableListOf<String>()
                val loader = CoinLogoLoader(app, Dispatchers.Unconfined) { target, _ -> png().also { requested += target } }
                assertNotNull(loader.load(url))
                assertNotNull(loader.load(url))
                assertNull(loader.load("https://evil.example/coins/images/1/small/bitcoin.png"))
                assertEquals(listOf(url), requested)
                // A new launch finds the logo in the device cache without a request.
                val next = CoinLogoLoader(app, Dispatchers.Unconfined) { _, _ -> error("not requested") }
                assertNotNull(next.load(url))
            }
        }
    }

    @Test fun aFailedLogoIsNotRetriedInTheSameSession() {
        runBlocking {
            JvmSqliteDatabase(":memory:").use { db ->
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                app.market.setConsent(true)
                var calls = 0
                val loader =
                    CoinLogoLoader(app, Dispatchers.Unconfined) { _, _ ->
                        calls++
                        byteArrayOf(1, 2, 3)
                    }
                assertNull(loader.load(url))
                assertNull(loader.load(url))
                assertEquals(1, calls)
                assertNull(app.market.cachedLogo(url))
            }
        }
    }
}
