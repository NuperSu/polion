package io.valroot.core.exchange.binance

import io.valroot.core.exchange.ExchangeTransport
import io.valroot.core.exchange.binance.Binance
import io.valroot.core.exchange.binance.BinanceParsers
import io.valroot.core.exchange.binance.BinanceRequest
import io.valroot.core.model.AccountId
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI

/**
 * GETs Binance's read-only account paths, signed with the account's key and secret from [credentials]; any other path
 * is refused. The key travels only in the `X-MBX-APIKEY` header and the secret never leaves this process.
 *
 * - **Pacing:** each path keeps its own spacing, from Binance's weights: withdrawal history 6.5 s (a tenth of the
 *   per-minute allowance each), converts and Pay 1.1 s, Simple Earn 0.8 s, trades 0.25 s, everything else 0.15 s.
 * - **Limits:** HTTP 429 or 418 stops requests for Binance's `Retry-After` (at least a minute, at most an hour).
 * - **Clock:** a `-1021` answer reads Binance's time, keeps the offset and tries once more.
 * - **Errors:** Binance's error answers are returned as they are, so the caller sees their codes; other failures have
 *   fixed messages.
 *
 * [beforeRequest] runs before each request and may throw to stop a refresh that was cancelled.
 */
class BinanceTransport(
    private val credentials: (AccountId) -> Pair<String, String>?,
    private val beforeRequest: () -> Unit = {},
    private val openConnection: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val wallClock: () -> Long = System::currentTimeMillis,
) : ExchangeTransport<BinanceRequest> {
    private companion object {
        val SPACING =
            mapOf(
                "/sapi/v1/account/apiRestrictions" to 150L,
                "/api/v3/account" to 250L,
                "/sapi/v1/asset/wallet/balance" to 150L,
                "/sapi/v1/simple-earn/flexible/position" to 800L,
                "/sapi/v1/simple-earn/locked/position" to 800L,
                "/api/v3/ticker/price" to 250L,
                "/api/v3/time" to 150L,
                "/sapi/v1/capital/config/getall" to 150L,
                "/api/v3/myTrades" to 250L,
                "/sapi/v1/capital/deposit/hisrec" to 150L,
                "/sapi/v1/capital/withdraw/history" to 6_500L,
                "/sapi/v1/convert/tradeFlow" to 1_100L,
                "/sapi/v1/asset/dribblet" to 150L,
                "/sapi/v1/asset/assetDividend" to 150L,
                "/sapi/v1/simple-earn/flexible/history/rewardsRecord" to 800L,
                "/sapi/v1/simple-earn/locked/history/rewardsRecord" to 800L,
                "/sapi/v1/pay/transactions" to 1_100L,
                "/sapi/v1/fiat/payments" to 150L,
                "/sapi/v1/c2c/orderMatch/listUserOrderHistory" to 150L,
            )
        const val MIN_GAP_MILLIS = 150L
        const val MIN_BLOCK_MILLIS = 60_000L
        const val MAX_BLOCK_MILLIS = 3_600_000L
        const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024
        val lock = Any()
        var lastRequestAt: Long? = null
        val lastByPath = mutableMapOf<String, Long>()
        var blockedUntil: Long? = null
    }

    /** Binance's clock less this device's, learned when Binance rejects a timestamp. */
    @Volatile private var clockOffset = 0L

    override fun get(
        account: AccountId,
        request: BinanceRequest,
    ): String {
        require(request.path in SPACING) { "Unsupported Binance request" }
        val credentials =
            if (request.signed) {
                credentials(account)
                    ?: throw IllegalStateException("Add this Binance account's API key again.")
            } else {
                null
            }
        var answer = send(request, credentials)
        if (answer.contains("\"code\":-1021")) {
            clockOffset = BinanceParsers.parseServerTime(send(Binance.time(), null)) - wallClock()
            answer = send(request, credentials)
        }
        return answer
    }

    private fun send(
        request: BinanceRequest,
        credentials: Pair<String, String>?,
    ): String {
        synchronized(lock) {
            blockedUntil?.let { if (clock() < it) throw IllegalStateException("Binance asked to slow down. It is tried again later.") }
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
        val url = Binance.url(request, credentials?.second, wallClock() + clockOffset)
        val connection =
            try {
                openConnection(url)
            } catch (_: Exception) {
                throw IllegalStateException("Could not reach Binance.")
            }
        try {
            credentials?.let { connection.setRequestProperty("X-MBX-APIKEY", it.first) }
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            when {
                code == 429 || code == 418 -> {
                    val wait =
                        (connection.getHeaderField("Retry-After")?.toLongOrNull()?.times(1000) ?: MIN_BLOCK_MILLIS)
                            .coerceIn(MIN_BLOCK_MILLIS, MAX_BLOCK_MILLIS)
                    synchronized(lock) { blockedUntil = clock() + wait }
                    throw IllegalStateException("Binance asked to slow down. It is tried again later.")
                }
                code in 200..299 -> return read(connection.inputStream)
                // Binance explains a refused request in its body (a code and a message), which the caller reads.
                code in 400..499 -> return connection.errorStream?.let(::read)
                    ?: throw IllegalStateException("Binance refused the request.")
                else -> throw IllegalStateException("Binance is unavailable.")
            }
        } catch (_: SocketTimeoutException) {
            throw IllegalStateException("Binance did not respond in time.")
        } catch (_: IOException) {
            throw IllegalStateException("Could not reach Binance.")
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
                check(output.size() + count <= MAX_RESPONSE_BYTES) { "Binance returned a response that is too large." }
                output.write(buffer, 0, count)
            }
            output.toByteArray().toString(Charsets.UTF_8)
        }
}
