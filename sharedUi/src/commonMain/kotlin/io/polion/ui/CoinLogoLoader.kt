package io.polion.ui

import androidx.compose.ui.graphics.ImageBitmap
import io.polion.core.application.PortfolioApplication
import io.polion.core.market.CoingeckoMarkets
import io.polion.ui.model.CoinLogoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Decodes PNG, JPEG or WebP bytes; null when they are not an image. */
internal expect fun decodeLogo(bytes: ByteArray): ImageBitmap?

/** Blocking HTTPS download of a CoinGecko logo URL, at most [maxBytes]; null on any failure. */
internal expect fun downloadLogo(
    url: String,
    maxBytes: Int,
): ByteArray?

/**
 * Loads CoinGecko logos from memory, then the device cache, then the network. A logo that failed is not retried until
 * the app restarts, so a missing image never causes repeated requests.
 */
internal class CoinLogoLoader(
    private val app: PortfolioApplication,
    private val database: CoroutineDispatcher,
    private val download: (String, Int) -> ByteArray? = ::downloadLogo,
) {
    private val memory = mutableMapOf<String, ImageBitmap>()
    private val failed = mutableSetOf<String>()
    private val downloads = Semaphore(4)

    fun cached(url: String): ImageBitmap? = synchronized(memory) { memory[url] }

    suspend fun load(url: String): ImageBitmap? {
        cached(url)?.let { return it }
        if (synchronized(memory) { url in failed } || CoingeckoMarkets.logoUrl(url) != url) return null
        return try {
            val saved = withContext(database) { app.cachedLogo(url) }
            val bytes =
                saved ?: downloads.withPermit {
                    withContext(Dispatchers.IO) { download(url, io.polion.core.market.MarketFeedRepository.MAX_LOGO_BYTES) }
                }
            val image = bytes?.let(::decodeLogo)
            if (image != null && saved == null) runCatching { withContext(database) { app.saveLogo(url, bytes) } }
            synchronized(memory) { if (image == null) failed += url else memory[url] = image }
            image
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            synchronized(memory) { failed += url }
            null
        }
    }

    fun source(urls: Map<String, String>) = CoinLogoSource(urls, ::cached, ::load)
}

/** Local date and time for chart tooltips; longer ranges omit the clock time. */
internal expect fun chartTime(
    millis: Long,
    spanMillis: Long,
): String

/** Epoch milliseconds for a date and time in the device's time zone; a time skipped by a clock change moves forward. */
internal expect fun localToEpochMillis(stamp: io.polion.core.application.LocalStamp): Long

/** The device's time zone name, for example "Europe/Berlin". */
internal expect fun localZoneName(): String
