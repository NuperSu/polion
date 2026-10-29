package io.polion.desktop

import io.polion.core.market.CoingeckoProviderException
import io.polion.core.market.CoingeckoProviderFailure
import io.polion.core.market.CoingeckoTransport
import io.polion.core.market.PacedCoingeckoTransport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI

/** Requests historical CoinGecko prices over HTTPS. Only the coin-history endpoint is reachable from this transport. */
class DesktopCoingeckoTransport(
    private val baseUrl: String = "https://api.coingecko.com/api/v3",
    private val openConnection: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection },
) : CoingeckoTransport {
    init {
        val uri = URI(baseUrl)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null)
    }

    private companion object {
        val HISTORY_PATH = Regex("[a-z0-9][a-z0-9-]*/history\\?date=[0-9]{2}-[0-9]{2}-[0-9]{4}&localization=false")
    }

    private val paced = PacedCoingeckoTransport(CoingeckoTransport(::request), { System.nanoTime() / 1_000_000L })

    private var activeApiKey = ""

    override fun get(url: String): String = get(url, "")

    @Synchronized
    fun get(
        url: String,
        apiKey: String,
    ): String {
        require(apiKey.length <= 512 && apiKey.all { it.code in 33..126 }) { "Invalid API key" }
        activeApiKey = apiKey
        try {
            return paced.get(url)
        } finally {
            activeApiKey = ""
        }
    }

    private fun request(url: String): String {
        val prefix = "${baseUrl.trimEnd('/')}/coins/"
        require(url.startsWith(prefix) && url.removePrefix(prefix).matches(HISTORY_PATH)) { "Unsupported CoinGecko request" }
        val connection = openConnection(url)
        try {
            if (activeApiKey.isNotEmpty()) connection.setRequestProperty("x-cg-demo-api-key", activeApiKey)
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            val response = connection.responseCode
            if (response == 401 || response == 403) throw CoingeckoProviderException(CoingeckoProviderFailure.ACCESS_DENIED)
            if (response == 429) throw CoingeckoProviderException(CoingeckoProviderFailure.RATE_LIMITED)
            if (response !in 200..299) throw CoingeckoProviderException(CoingeckoProviderFailure.UNAVAILABLE)
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 1024 * 1024) { "CoinGecko response too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray().toString(Charsets.UTF_8)
            }
        } catch (_: SocketTimeoutException) {
            throw CoingeckoProviderException(CoingeckoProviderFailure.OFFLINE)
        } catch (_: IOException) {
            throw CoingeckoProviderException(CoingeckoProviderFailure.OFFLINE)
        } finally {
            connection.disconnect()
        }
    }
}
