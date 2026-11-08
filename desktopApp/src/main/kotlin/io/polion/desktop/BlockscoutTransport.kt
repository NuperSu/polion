package io.polion.desktop

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI

/** Spaces Blockscout requests at least 250 ms apart across the process and holds a cooldown after a rate limit. */
internal class BlockscoutPacer(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    private var lastRequestAt: Long? = null
    private var rateLimitedUntil: Long? = null

    @Synchronized
    fun await(beforeRequest: () -> Unit) {
        val cooldown = (rateLimitedUntil ?: 0L) - clock()
        if (cooldown > 0) throw IllegalStateException(rateLimitMessage(cooldown))
        lastRequestAt?.let { previous ->
            val remaining = MIN_INTERVAL_MILLIS - (clock() - previous)
            if (remaining > 0) sleep(remaining)
        }
        beforeRequest()
        lastRequestAt = clock()
    }

    @Synchronized
    fun rateLimited(delayMillis: Long) {
        rateLimitedUntil = clock() + delayMillis
    }

    private companion object {
        const val MIN_INTERVAL_MILLIS = 250L
    }
}

/**
 * Reads Blockscout address data for EVM history and balances over HTTPS. Only one address on one chain is accepted; the
 * API key comes from [apiKey] and is appended as the `apikey` query parameter. [beforeRequest] runs before each request and
 * may throw to stop a refresh that was cancelled.
 */
internal class BlockscoutTransport(
    private val apiKey: () -> String?,
    private val beforeRequest: () -> Unit = {},
    private val openConnection: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection },
    private val pacer: BlockscoutPacer = sharedPacer,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private companion object {
        val sharedPacer = BlockscoutPacer()

        val ADDRESS_REQUEST =
            Regex(
                "https://api\\.blockscout\\.com/[1-9][0-9]{0,9}/api/v2/addresses/0x[0-9a-f]{40}" +
                    "(/(transactions|internal-transactions|token-transfers|tokens|withdrawals))?" +
                    "(\\?[A-Za-z0-9_.:%=&-]+)?",
            )
        val API_KEY = Regex("[A-Za-z0-9_-]{10,100}")
        const val MISSING_KEY_MESSAGE = "Add a free Blockscout API key in Settings › API Keys."
        const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024
        const val MAX_RETRY_AFTER_MILLIS = 60_000L
    }

    fun get(url: String): String {
        require(url.matches(ADDRESS_REQUEST) && !url.substringAfter('?', "").contains("apikey", ignoreCase = true)) {
            "Unsupported Blockscout request"
        }
        val key = apiKey()?.takeIf { it.isNotEmpty() } ?: throw IllegalStateException(MISSING_KEY_MESSAGE)
        check(key.matches(API_KEY)) { "The Blockscout API key is not valid. Replace it in Settings › API Keys." }
        val keyed = url + (if ('?' in url) "&" else "?") + "apikey=" + key
        pacer.await(beforeRequest)
        // A connection failure message may contain the keyed URL, so only the fixed message leaves this function.
        val connection =
            try {
                openConnection(keyed)
            } catch (_: IOException) {
                throw IllegalStateException("Could not reach Blockscout.")
            }
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            when (connection.responseCode) {
                429 -> {
                    val delay = retryAfterMillis(connection)
                    pacer.rateLimited(delay)
                    throw IllegalStateException(rateLimitMessage(delay))
                }
                401, 402, 403 -> throw IllegalStateException("Blockscout rejected the API key.")
                !in 200..299 -> throw IllegalStateException("Blockscout is unavailable.")
            }
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= MAX_RESPONSE_BYTES) { "Blockscout returned a response that is too large." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray().toString(Charsets.UTF_8)
            }
        } catch (_: SocketTimeoutException) {
            throw IllegalStateException("Blockscout did not respond in time.")
        } catch (_: IOException) {
            throw IllegalStateException("Could not reach Blockscout.")
        } finally {
            connection.disconnect()
        }
    }

    /** Retry-After in seconds or as an HTTP date, capped at one minute; a missing or unreadable value uses the cap. */
    private fun retryAfterMillis(connection: HttpURLConnection): Long {
        val value = connection.getHeaderField("Retry-After")?.trim()
        val delay =
            if (value != null && value.isNotEmpty() && value.all { it in '0'..'9' }) {
                minOf(value.toLongOrNull() ?: MAX_RETRY_AFTER_MILLIS / 1000, MAX_RETRY_AFTER_MILLIS / 1000) * 1000
            } else {
                val at = connection.getHeaderFieldDate("Retry-After", -1)
                if (at < 0) MAX_RETRY_AFTER_MILLIS else at - wallClock()
            }
        return delay.coerceIn(0L, MAX_RETRY_AFTER_MILLIS)
    }
}

private fun rateLimitMessage(delayMillis: Long): String {
    val seconds = ((delayMillis + 999) / 1000).coerceAtLeast(1)
    return "Blockscout is rate limited. Try again in $seconds s."
}
