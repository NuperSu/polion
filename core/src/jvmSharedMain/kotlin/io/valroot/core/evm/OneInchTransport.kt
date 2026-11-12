package io.valroot.core.evm

import io.valroot.core.evm.OneInch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI

/**
 * GETs 1inch's History and Traces paths with the key from [apiKey] as a bearer token; any other path is refused. Requests
 * are spaced at least 1.1 seconds apart across the process, within the free plan's 60 a minute. [beforeRequest] runs
 * before each request and may throw to stop a refresh that was cancelled.
 */
class OneInchTransport(
    private val apiKey: () -> String?,
    private val beforeRequest: () -> Unit = {},
    private val openConnection: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private companion object {
        val HISTORY =
            Regex("/history/v2\\.0/history/0x[0-9a-f]{40}/events\\?chainId=[0-9]{1,10}&limit=[0-9]{1,5}(&toTimestampMs=[0-9]{1,15})?")
        val TRACE = Regex("/traces/v1\\.0/chain/[0-9]{1,10}/block-trace/[0-9]{1,12}/tx-hash/0x[0-9a-f]{64}")
        val API_KEY = Regex("[A-Za-z0-9_-]{16,200}")
        const val MIN_INTERVAL_MILLIS = 1_100L
        const val MAX_RESPONSE_BYTES = 32 * 1024 * 1024
        val lock = Any()
        var lastRequestAt: Long? = null
    }

    fun get(path: String): String {
        require(path.matches(HISTORY) || path.matches(TRACE)) { "Unsupported 1inch request" }
        val key = apiKey()?.takeIf { it.isNotEmpty() } ?: throw IllegalStateException("Add a 1inch API key in Settings › API Keys.")
        check(key.matches(API_KEY)) { "The 1inch API key is not valid. Replace it in Settings › API Keys." }
        synchronized(lock) {
            lastRequestAt?.let { previous ->
                val remaining = MIN_INTERVAL_MILLIS - (clock() - previous)
                if (remaining > 0) sleep(remaining)
            }
            beforeRequest()
            lastRequestAt = clock()
        }
        val connection =
            try {
                openConnection(OneInch.BASE_URL + path)
            } catch (_: Exception) {
                throw IllegalStateException("Could not reach 1inch.")
            }
        try {
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = false
            when (connection.responseCode) {
                429 -> throw IllegalStateException("1inch's request limit was reached. Try again shortly.")
                401, 403 -> throw IllegalStateException("1inch rejected the API key. Replace it in Settings › API Keys.")
                !in 200..299 -> throw IllegalStateException("1inch is unavailable.")
            }
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= MAX_RESPONSE_BYTES) { "1inch returned a response that is too large." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray().toString(Charsets.UTF_8)
            }
        } catch (_: SocketTimeoutException) {
            throw IllegalStateException("1inch did not respond in time.")
        } catch (_: IOException) {
            throw IllegalStateException("Could not reach 1inch.")
        } finally {
            connection.disconnect()
        }
    }
}
