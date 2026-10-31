package io.polion.android

import java.net.HttpURLConnection
import java.net.URI

internal class KrakenPriceTransport(
    private val openConnection: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val beforeRequest: () -> Unit = {},
) {
    private var lastRequestAt: Long? = null

    @Synchronized
    fun get(url: String): String {
        require(url.matches(Regex("https://api\\.kraken\\.com/0/public/OHLC\\?pair=XBT(USD|EUR|GBP)&interval=(60|1440)")))
        lastRequestAt?.let { previous ->
            val remaining = 1_100L - (clock() - previous)
            if (remaining > 0) sleep(remaining)
        }
        beforeRequest()
        lastRequestAt = clock()
        val connection = openConnection(url)
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            require(connection.responseCode in 200..299) { "Price provider unavailable" }
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
}
