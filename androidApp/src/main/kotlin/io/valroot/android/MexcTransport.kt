package io.valroot.android

import io.valroot.core.exchange.Mexc
import io.valroot.core.exchange.MexcRequest
import io.valroot.core.model.AccountId
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI

/**
 * GETs MEXC's read-only account paths, signed with the account's key and secret from [credentials]; any other path is
 * refused. The key travels only in the `X-MEXC-APIKEY` header and the secret never leaves this process.
 *
 * - **Pacing:** requests are at least 0.25 s apart, and account balances 0.6 s (MEXC allows 2 a second).
 * - **Limits:** HTTP 429 stops requests for 10 minutes, as long as MEXC locks an IP that went over its limit.
 * - **Clock:** a `700003` answer reads MEXC's time, keeps the offset and tries once more.
 * - **Errors:** MEXC's error answers are returned as they are, so the caller sees their codes; other failures have
 *   fixed messages.
 *
 * [beforeRequest] runs before each request and may throw to stop a refresh that was cancelled.
 */
internal class MexcTransport(
    private val credentials: (AccountId) -> Pair<String, String>?,
    private val beforeRequest: () -> Unit = {},
    private val openConnection: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private companion object {
        val SPACING =
            mapOf(
                "/api/v3/uid" to 250L,
                "/api/v3/account" to 600L,
                "/api/v3/ticker/price" to 250L,
                "/api/v3/time" to 250L,
                "/api/v3/capital/config/getall" to 250L,
                "/api/v3/myTrades" to 250L,
                "/api/v3/capital/deposit/hisrec" to 250L,
                "/api/v3/capital/withdraw/history" to 250L,
                "/api/v3/capital/transfer/internal" to 250L,
                "/api/v3/capital/transfer" to 250L,
                "/api/v3/capital/convert" to 250L,
            )
        const val MIN_GAP_MILLIS = 250L
        const val BLOCK_MILLIS = 600_000L
        const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024
        val lock = Any()
        var lastRequestAt: Long? = null
        val lastByPath = mutableMapOf<String, Long>()
        var blockedUntil: Long? = null
    }

    /** MEXC's clock less this device's, learned when MEXC rejects a timestamp. */
    @Volatile private var clockOffset = 0L

    fun get(
        account: AccountId,
        request: MexcRequest,
    ): String {
        require(request.path in SPACING) { "Unsupported MEXC request" }
        val credentials =
            if (request.signed) {
                credentials(account)
                    ?: throw IllegalStateException("Add this MEXC account's API key again.")
            } else {
                null
            }
        var answer = send(request, credentials)
        if (answer.contains("700003")) {
            clockOffset = Mexc.parseServerTime(send(Mexc.time(), null)) - wallClock()
            answer = send(request, credentials)
        }
        return answer
    }

    private fun send(
        request: MexcRequest,
        credentials: Pair<String, String>?,
    ): String {
        synchronized(lock) {
            blockedUntil?.let { if (clock() < it) throw IllegalStateException("MEXC asked to slow down. It is tried again later.") }
            val now = clock()
            val waits =
                listOfNotNull(
                    lastRequestAt?.let { MIN_GAP_MILLIS - (now - it) },
                    lastByPath[request.path]?.let { SPACING.getValue(request.path) - (now - it) },
                )
            waits.maxOrNull()?.takeIf { it > 0 }?.let(sleep)
            beforeRequest()
            lastRequestAt = clock()
            lastByPath[request.path] = lastRequestAt!!
        }
        val url = Mexc.url(request, credentials?.second, wallClock() + clockOffset)
        val connection =
            try {
                openConnection(url)
            } catch (_: Exception) {
                throw IllegalStateException("Could not reach MEXC.")
            }
        try {
            credentials?.let { connection.setRequestProperty("X-MEXC-APIKEY", it.first) }
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            when {
                code == 429 -> {
                    synchronized(lock) { blockedUntil = clock() + BLOCK_MILLIS }
                    throw IllegalStateException("MEXC asked to slow down. It is tried again later.")
                }
                code in 200..299 -> return read(connection.inputStream)
                // MEXC explains a refused request in its body (a code and a message), which the caller reads.
                code in 400..499 -> return connection.errorStream?.let(::read)
                    ?: throw IllegalStateException("MEXC refused the request.")
                else -> throw IllegalStateException("MEXC is unavailable.")
            }
        } catch (_: SocketTimeoutException) {
            throw IllegalStateException("MEXC did not respond in time.")
        } catch (_: IOException) {
            throw IllegalStateException("Could not reach MEXC.")
        } finally {
            connection.disconnect()
        }
    }

    private fun read(stream: InputStream): String =
        stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= MAX_RESPONSE_BYTES) { "MEXC returned a response that is too large." }
                output.write(buffer, 0, count)
            }
            output.toByteArray().toString(Charsets.UTF_8)
        }
}
