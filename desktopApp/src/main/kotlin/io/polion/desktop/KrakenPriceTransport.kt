package io.polion.desktop

import io.polion.core.market.KrakenRateLimitedException
import java.net.HttpURLConnection
import java.net.URI

internal class KrakenPricePacer(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    private var lastRequestAt: Long? = null
    private var rateLimitedUntil: Long? = null

    @Synchronized
    fun await(beforeRequest: () -> Unit) {
        val remaining = (rateLimitedUntil ?: 0L) - clock()
        if (remaining > 0) throw KrakenRateLimitedException(remaining)
        lastRequestAt?.let { previous ->
            val remaining = 1_100L - (clock() - previous)
            if (remaining > 0) sleep(remaining)
        }
        beforeRequest()
        lastRequestAt = clock()
    }

    @Synchronized
    fun rateLimited(delay: Long) {
        rateLimitedUntil = clock() + delay
    }
}

internal class KrakenPriceTransport(
    private val openConnection: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection },
    private val pacer: KrakenPricePacer = sharedPacer,
    private val beforeRequest: () -> Unit = {},
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private val sharedPacer = KrakenPricePacer()
    }

    fun get(url: String): String {
        require(url.matches(Regex("https://api\\.kraken\\.com/0/public/OHLC\\?pair=XBT(USD|EUR|GBP)&interval=(60|1440)")))
        pacer.await(beforeRequest)
        val connection = openConnection(url)
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            val response = connection.responseCode
            if (response == 429) {
                val delay = retryAfterMillis(connection)
                pacer.rateLimited(delay)
                throw KrakenRateLimitedException(delay)
            }
            require(response in 200..299) { "Price provider unavailable" }
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 1_048_576) { "Price response too large" }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun retryAfterMillis(connection: HttpURLConnection): Long {
        val value = connection.getHeaderField("Retry-After")?.trim()
        val numeric = value?.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
        val delay =
            if (numeric != null) {
                (numeric.toLongOrNull() ?: 86_400L).coerceAtMost(86_400L) * 1000
            } else {
                val at = connection.getHeaderFieldDate("Retry-After", -1)
                if (at < 0) 60_000L else (at - wallClock()).coerceAtLeast(0)
            }
        return delay.coerceIn(60_000L, 86_400_000L)
    }
}
