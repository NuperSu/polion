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

/** Requests approved CoinGecko price-history and market-feed endpoints over HTTPS. */
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
        val CHART_PATH = Regex("[a-z0-9][a-z0-9-]*/market_chart\\?vs_currency=[a-z]{3,4}&days=(1|7|30|90|365)")
        val MARKETS_PATH =
            Regex(
                "coins/markets\\?vs_currency=[a-z]{3,4}&order=market_cap_desc&per_page=250&page=1&sparkline=true&price_change_percentage=1h,24h,7d",
            )
        const val TRENDING_PATH = "search/trending"

        /** The top-250 list with sparklines is about 1 MB. */
        const val FEED_LIMIT = 4 * 1024 * 1024
        const val HISTORY_LIMIT = 1024 * 1024
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

    /**
     * Market feeds are not paced here: the application refreshes them at most once a minute and pauses after a rate limit.
     */
    @Synchronized
    fun feed(
        url: String,
        apiKey: String?,
    ): String {
        val path = url.removePrefix("${baseUrl.trimEnd('/')}/")
        require(url != path && (path.matches(MARKETS_PATH) || path == TRENDING_PATH)) { "Unsupported CoinGecko request" }
        require(apiKey == null || apiKey.length <= 512 && apiKey.all { it.code in 33..126 }) { "Invalid API key" }
        activeApiKey = apiKey.orEmpty()
        try {
            return fetch(url, FEED_LIMIT)
        } finally {
            activeApiKey = ""
        }
    }

    private fun request(url: String): String {
        val prefix = "${baseUrl.trimEnd('/')}/coins/"
        require(
            url.startsWith(prefix) && (url.removePrefix(prefix).matches(HISTORY_PATH) || url.removePrefix(prefix).matches(CHART_PATH)),
        ) {
            "Unsupported CoinGecko request"
        }
        return fetch(url, HISTORY_LIMIT)
    }

    private fun fetch(
        url: String,
        limit: Int,
    ): String {
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
                    if (total > limit) throw CoingeckoProviderException(CoingeckoProviderFailure.INVALID_RESPONSE)
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
