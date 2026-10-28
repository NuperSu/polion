package io.polion.android

import io.polion.core.bitcoin.EsploraTransport
import io.polion.core.bitcoin.BitcoinProviderException
import io.polion.core.bitcoin.BitcoinProviderFailure
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.HttpURLConnection
import java.net.URI

/** Requests public Bitcoin data from Blockstream without forwarding requests to redirects. */
class BlockstreamTransport(private val endpoint: String = "https://blockstream.info/api") : EsploraTransport {
    init {
        val uri = URI(endpoint)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null)
    }
    override fun get(path: String): String {
        require((path.startsWith("/address/") || path == "/blocks/tip/hash" || path.matches(Regex("/block/[0-9a-f]{64}(/txids)?")) || path.matches(Regex("/block-height/[0-9]+"))) && !path.contains("..") && !path.contains('?') && !path.contains('#'))
        val connection = URI("${endpoint.trimEnd('/')}$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            val response = connection.responseCode
            if (response == 429) throw BitcoinProviderException(BitcoinProviderFailure.RATE_LIMITED)
            if (response !in 200..299) throw BitcoinProviderException(BitcoinProviderFailure.UNAVAILABLE)
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 5 * 1024 * 1024) { "Provider response too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return bytes.toString(Charsets.UTF_8)
        } catch (_: SocketTimeoutException) {
            throw BitcoinProviderException(BitcoinProviderFailure.TIMEOUT)
        } catch (_: IOException) {
            throw BitcoinProviderException(BitcoinProviderFailure.OFFLINE)
        } finally {
            connection.disconnect()
        }
    }
}
