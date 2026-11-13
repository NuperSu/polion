package io.valroot.core.evm

import io.valroot.core.evm.Alchemy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI

private const val RATE_LIMIT_MESSAGE = "Alchemy's request limit was reached. Try again shortly."

/** Spaces Alchemy requests at least 200 ms apart across the process (five per second) and holds a cooldown after a rate limit. */
class AlchemyPacer(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    private var lastRequestAt: Long? = null
    private var rateLimitedUntil: Long? = null

    @Synchronized
    fun await(beforeRequest: () -> Unit) {
        val cooldown = (rateLimitedUntil ?: 0L) - clock()
        if (cooldown > 0) throw IllegalStateException(RATE_LIMIT_MESSAGE)
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
        const val MIN_INTERVAL_MILLIS = 200L
    }
}

/**
 * POSTs JSON to Alchemy's Portfolio APIs and per-network JSON-RPC. [post] accepts only the URL templates from [Alchemy]
 * with their literal `{key}` placeholder; the API key from [apiKey] is inserted into the path and nowhere else.
 * [beforeRequest] runs before each request and may throw to stop a refresh that was cancelled.
 */
class AlchemyTransport(
    private val apiKey: () -> String?,
    private val beforeRequest: () -> Unit = {},
    private val openConnection: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection },
    private val pacer: AlchemyPacer = sharedPacer,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private companion object {
        val sharedPacer = AlchemyPacer()

        val TEMPLATES: Set<String> =
            setOf(Alchemy.transfersUrl(), Alchemy.tokensUrl()) + Alchemy.NETWORKS.map { Alchemy.rpcUrl(it.chainId) }
        val API_KEY = Regex("[A-Za-z0-9_-]{16,100}")
        const val KEY_PLACEHOLDER = "{key}"
        const val MISSING_KEY_MESSAGE = "Add a free Alchemy API key in Settings › API Keys."
        const val INVALID_KEY_MESSAGE = "The Alchemy API key is not valid. Replace it in Settings › API Keys."
        const val REJECTED_KEY_MESSAGE =
            "Alchemy rejected the API key, or this network is not enabled for it in the Alchemy dashboard."
        const val MAX_REQUEST_BYTES = 1024 * 1024
        const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024
        const val MAX_RETRY_AFTER_MILLIS = 60_000L
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 60_000
    }

    fun post(
        url: String,
        body: String,
    ): String {
        require(url in TEMPLATES) { "Unsupported Alchemy request" }
        val payload = body.toByteArray(Charsets.UTF_8)
        require(payload.size <= MAX_REQUEST_BYTES) { "Alchemy request is too large." }
        val key = apiKey()?.takeIf { it.isNotEmpty() } ?: throw IllegalStateException(MISSING_KEY_MESSAGE)
        check(key.matches(API_KEY)) { INVALID_KEY_MESSAGE }
        val keyed = url.replace(KEY_PLACEHOLDER, key)
        pacer.await(beforeRequest)
        // Failure messages from the connection layer may contain the keyed URL, so only fixed messages leave this function.
        val connection =
            try {
                openConnection(keyed)
            } catch (_: Exception) {
                throw IllegalStateException("Could not reach Alchemy.")
            }
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            when (connection.responseCode) {
                429 -> {
                    val delay = retryAfterMillis(connection)
                    pacer.rateLimited(delay)
                    throw IllegalStateException(RATE_LIMIT_MESSAGE)
                }
                401, 403 -> throw IllegalStateException(REJECTED_KEY_MESSAGE)
                !in 200..299 -> throw IllegalStateException("Alchemy is unavailable.")
            }
            return connection.inputStream.use { readCapped(it) }
        } catch (_: SocketTimeoutException) {
            throw IllegalStateException("Alchemy did not respond in time.")
        } catch (_: IOException) {
            throw IllegalStateException("Could not reach Alchemy.")
        } finally {
            connection.disconnect()
        }
    }

    private fun readCapped(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            check(output.size() + count <= MAX_RESPONSE_BYTES) { "Alchemy returned a response that is too large." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray().toString(Charsets.UTF_8)
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
