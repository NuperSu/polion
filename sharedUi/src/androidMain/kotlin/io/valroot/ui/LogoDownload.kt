package io.valroot.ui

import io.valroot.core.market.CoingeckoMarkets
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

/** Same on JVM and Android: one HTTPS GET for an approved CoinGecko image URL, without redirects or cookies. */
internal object LogoDownload {
    fun get(
        url: String,
        maxBytes: Int,
    ): ByteArray? {
        if (CoingeckoMarkets.logoUrl(url) != url) return null
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            if (connection.responseCode !in 200..299) return null
            if (connection.contentType?.startsWith("image/") != true) return null
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > maxBytes) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
