package io.polion.desktop

import io.polion.core.market.DefiLlamaException
import io.polion.core.market.DefiLlamaPrices
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Requests DefiLlama's current-price and price-chart endpoints over HTTPS; any other URL is refused. */
class DefiLlamaTransport(
    private val baseUrl: String = DefiLlamaPrices.BASE_URL,
    private val openConnection: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection },
) {
    init {
        val uri = URI(baseUrl)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null)
    }

    private companion object {
        val CURRENT_PATH = Regex("prices/current/coingecko:[a-z0-9-]{1,100}(,coingecko:[a-z0-9-]{1,100}){0,49}")
        val CHART_PATH = Regex("chart/coingecko:[a-z0-9-]{1,100}\\?start=[0-9]{1,12}&span=[0-9]{1,3}&period=(1d|1h)")

        /** 500 points are about 30 KB. */
        const val LIMIT = 2 * 1024 * 1024
    }

    @Synchronized
    fun get(url: String): String {
        val path = url.removePrefix("${baseUrl.trimEnd('/')}/")
        require(url != path && (path.matches(CURRENT_PATH) || path.matches(CHART_PATH))) { "Unsupported DefiLlama request" }
        val connection = openConnection(url)
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            val response = connection.responseCode
            if (response == 429) throw DefiLlamaException(true, "DefiLlama rate limit reached")
            if (response !in 200..299) throw DefiLlamaException(false, "DefiLlama is unavailable")
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > LIMIT) throw DefiLlamaException(false, "DefiLlama response is too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray().toString(Charsets.UTF_8)
            }
        } catch (_: IOException) {
            throw DefiLlamaException(false, "DefiLlama could not be reached")
        } finally {
            connection.disconnect()
        }
    }
}
