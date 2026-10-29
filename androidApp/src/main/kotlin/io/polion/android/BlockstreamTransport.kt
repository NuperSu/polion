package io.polion.android

import io.polion.core.bitcoin.BitcoinProviderException
import io.polion.core.bitcoin.BitcoinProviderFailure
import io.polion.core.bitcoin.EsploraTransport
import io.polion.core.bitcoin.PacedEsploraTransport
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI

/** Requests public Bitcoin data from Blockstream without forwarding requests to redirects. */
class BlockstreamTransport(
    private val endpoint: String = "https://blockstream.info/api",
    onProgress: (String) -> Unit = {},
    private val ensureActive: () -> Unit = {},
) : EsploraTransport {
    init {
        val uri = URI(endpoint)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null)
    }

    private val paced =
        PacedEsploraTransport(
            delegate = EsploraTransport(::request),
            nowMillis = { System.nanoTime() / 1_000_000 },
            sleepMillis = { delay ->
                try {
                    Thread.sleep(delay)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw BitcoinProviderException(BitcoinProviderFailure.CANCELLED)
                }
            },
            ensureActive = ::ensureRequestActive,
            onRetry = { onProgress(it.userMessage) },
        )

    override fun get(path: String): String = paced.get(path)

    private fun ensureRequestActive() {
        ensureActive()
        if (Thread.currentThread().isInterrupted) throw BitcoinProviderException(BitcoinProviderFailure.CANCELLED)
    }

    private fun request(path: String): String {
        require(
            (
                path.startsWith("/address/") || path == "/blocks/tip/hash" || path == "/blocks/tip/height" ||
                    path.matches(Regex("/block/[0-9a-f]{64}(/txids)?")) ||
                    path.matches(Regex("/block-height/[0-9]+"))
            ) &&
                !path.contains("..") &&
                !path.contains('?') &&
                !path.contains('#'),
        )
        val connection = URI("${endpoint.trimEnd('/')}$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            val response = connection.responseCode
            if (response == 429) {
                throw BitcoinProviderException(BitcoinProviderFailure.RATE_LIMITED, retryAfterMillis(connection))
            }
            if (response !in 200..299) throw BitcoinProviderException(BitcoinProviderFailure.UNAVAILABLE)
            val bytes =
                connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        ensureRequestActive()
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
            ensureRequestActive()
            throw BitcoinProviderException(BitcoinProviderFailure.TIMEOUT)
        } catch (_: IOException) {
            ensureRequestActive()
            throw BitcoinProviderException(BitcoinProviderFailure.OFFLINE)
        } finally {
            connection.disconnect()
        }
    }

    private fun retryAfterMillis(connection: HttpURLConnection): Long? {
        val value = connection.getHeaderField("Retry-After")?.trim() ?: return null
        if (value.isNotEmpty() && value.all { it in '0'..'9' }) {
            val seconds = value.toLongOrNull() ?: return Long.MAX_VALUE
            return if (seconds > Long.MAX_VALUE / 1_000) Long.MAX_VALUE else seconds * 1_000
        }
        val timestamp = connection.getHeaderFieldDate("Retry-After", -1)
        return if (timestamp < 0) null else (timestamp - System.currentTimeMillis()).coerceAtLeast(0)
    }
}
