package io.valroot.core.exchange.bybit

import io.valroot.core.exchange.ExchangeTransport
import io.valroot.core.exchange.bybit.Bybit
import io.valroot.core.exchange.bybit.BybitException
import io.valroot.core.exchange.bybit.BybitRequest
import io.valroot.core.model.AccountId
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI

/**
 * GETs Bybit's read-only account paths, signed with the account's key and secret from [credentials]; any other path
 * is refused, and neither the key nor the secret appears in a URL, log or error.
 *
 * - **Pacing:** requests are spaced 150 ms apart across the process, and deposit records 650 ms (Bybit allows 100 a
 *   minute). When Bybit answers HTTP 403 for too many requests, nothing is sent for 10 minutes.
 * - **Limits per account:** a `10006` answer waits a second, Bybit's limit window, and tries once more.
 * - **Clock:** a `10002` answer (timestamp outside the receive window) reads Bybit's time, keeps the offset and tries
 *   once more.
 *
 * [beforeRequest] runs before each request and may throw to stop a refresh that was cancelled.
 */
class BybitTransport(
    private val credentials: (AccountId) -> Pair<String, String>?,
    private val beforeRequest: () -> Unit = {},
    private val openConnection: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val wallClock: () -> Long = System::currentTimeMillis,
) : ExchangeTransport<BybitRequest> {
    private companion object {
        val PATHS =
            setOf(
                "/v5/user/query-api",
                "/v5/account/wallet-balance",
                "/v5/asset/transfer/query-account-coins-balance",
                "/v5/asset/asset-overview",
                "/v5/asset/coin/query-info",
                "/v5/account/transaction-log",
                "/v5/asset/fundinghistory",
                "/v5/asset/deposit/query-record",
                "/v5/asset/withdraw/query-record",
                "/v5/asset/exchange/query-convert-history",
            )
        const val TIME_PATH = "/v5/market/time"
        const val SLOW_PATH = "/v5/asset/deposit/query-record"
        const val INTERVAL_MILLIS = 150L
        const val SLOW_INTERVAL_MILLIS = 650L
        const val BLOCKED_MILLIS = 600_000L
        const val LIMIT_WAIT_MILLIS = 1_000L
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        val lock = Any()
        var lastRequestAt: Long? = null
        var lastSlowRequestAt: Long? = null
        var blockedUntil: Long? = null
    }

    /** Bybit's clock less this device's, learned when Bybit rejects a timestamp. */
    @Volatile private var clockOffset = 0L

    override fun get(
        account: AccountId,
        request: BybitRequest,
    ): String {
        require(request.path in PATHS) { "Unsupported Bybit request" }
        val (key, secret) = credentials(account) ?: throw IllegalStateException("Add this Bybit account's API key again.")
        var answer = send(request, key to secret)
        if (code(answer) == BybitException.CLOCK_SKEW) {
            clockOffset = Bybit.parseServerTime(send(BybitRequest(TIME_PATH), null)) - wallClock()
            answer = send(request, key to secret)
        }
        if (code(answer) == BybitException.RATE_LIMITED) {
            sleep(LIMIT_WAIT_MILLIS)
            answer = send(request, key to secret)
        }
        return answer
    }

    private fun code(payload: String) =
        Regex("\"retCode\"\\s*:\\s*(-?\\d+)")
            .find(payload)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

    private fun send(
        request: BybitRequest,
        signer: Pair<String, String>?,
    ): String {
        synchronized(lock) {
            blockedUntil?.let { if (clock() < it) throw IllegalStateException("Bybit asked to slow down. It is tried again later.") }
            val now = clock()
            val waits =
                listOfNotNull(
                    lastRequestAt?.let { INTERVAL_MILLIS - (now - it) },
                    if (request.path == SLOW_PATH) lastSlowRequestAt?.let { SLOW_INTERVAL_MILLIS - (now - it) } else null,
                )
            waits.maxOrNull()?.takeIf { it > 0 }?.let(sleep)
            beforeRequest()
            lastRequestAt = clock()
            if (request.path == SLOW_PATH) lastSlowRequestAt = lastRequestAt
        }
        val url = Bybit.BASE_URL + request.path + if (request.query.isEmpty()) "" else "?" + request.queryString
        val connection =
            try {
                openConnection(url)
            } catch (_: Exception) {
                throw IllegalStateException("Could not reach Bybit.")
            }
        try {
            if (signer != null) {
                val timestamp = wallClock() + clockOffset
                Bybit.headers(signer.second, signer.first, timestamp, request).forEach { (name, value) ->
                    connection.setRequestProperty(name, value)
                }
            }
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = false
            when (connection.responseCode) {
                403 -> {
                    synchronized(lock) { blockedUntil = clock() + BLOCKED_MILLIS }
                    throw IllegalStateException("Bybit asked to slow down. It is tried again later.")
                }
                401 -> throw IllegalStateException("Bybit did not accept this API key.")
                !in 200..299 -> throw IllegalStateException("Bybit is unavailable.")
            }
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= MAX_RESPONSE_BYTES) { "Bybit returned a response that is too large." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray().toString(Charsets.UTF_8)
            }
        } catch (_: SocketTimeoutException) {
            throw IllegalStateException("Bybit did not respond in time.")
        } catch (_: IOException) {
            throw IllegalStateException("Could not reach Bybit.")
        } finally {
            connection.disconnect()
        }
    }
}
