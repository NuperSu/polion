package io.valroot.core.exchange.binance

import io.valroot.core.exchange.binance.Binance
import io.valroot.core.exchange.binance.BinanceRequest
import io.valroot.core.model.AccountId
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BinanceTransportTest {
    private class Response(
        private val code: Int,
        private val body: String = "[]",
    ) : HttpURLConnection(URI("https://api.binance.com").toURL()) {
        var disconnected = false

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy() = false

        override fun getResponseCode() = code

        override fun getInputStream() = body.byteInputStream()

        override fun getErrorStream() = body.byteInputStream()
    }

    private val account = AccountId("binance")
    private val key = "TESTKEY0123"
    private val secret = "TESTSECRET0123456789"

    @Test fun requestsAreSignedAndOnlyReadPathsAreSent() {
        val responses = mutableListOf<Response>()
        val urls = mutableListOf<String>()
        val transport =
            BinanceTransport({ key to secret }, openConnection = { url ->
                urls += url
                Response(200).also { responses += it }
            }, sleep = {}, wallClock = { 1_700_000_000_000L })
        val request = Binance.trades("BTCUSDT", 0)
        transport.get(account, request)
        assertEquals(Binance.url(request, secret, 1_700_000_000_000L), urls.single())
        assertEquals(key, responses.single().getRequestProperty("X-MBX-APIKEY"))
        assertFalse(urls.single().contains(secret) || urls.single().contains(key))
        assertTrue(responses.single().disconnected)
        // Public symbols are sent without a key.
        transport.get(account, Binance.tickers())
        assertEquals(null, responses.last().getRequestProperty("X-MBX-APIKEY"))
        assertFailsWith<IllegalArgumentException> { transport.get(account, BinanceRequest("/api/v3/order")) }
        assertFailsWith<IllegalArgumentException> { transport.get(account, BinanceRequest("/sapi/v1/capital/withdraw/apply")) }
    }

    @Test fun binanceErrorsReachTheCallerAndTheClockIsCorrected() {
        var calls = 0
        val transport =
            BinanceTransport({ key to secret }, openConnection = { url ->
                calls++
                when {
                    url.contains("/api/v3/time") -> Response(200, """{"serverTime":1700000005000}""")
                    calls == 1 -> Response(400, """{"code":-1021,"msg":"Timestamp for this request is outside of the recvWindow."}""")
                    else -> Response(400, """{"code":-2015,"msg":"Invalid API-key, IP, or permissions for action."}""")
                }
            }, sleep = {}, wallClock = { 1_700_000_000_000L })
        assertEquals(
            """{"code":-2015,"msg":"Invalid API-key, IP, or permissions for action."}""",
            transport.get(account, Binance.restrictions()),
        )
        assertEquals(3, calls)
    }

    @Test fun failuresUseFixedMessagesWithoutTheKey() {
        val failure =
            assertFailsWith<IllegalStateException> {
                BinanceTransport({ key to secret }, openConnection = { Response(500) }, sleep = {}).get(account, Binance.restrictions())
            }
        assertFalse(failure.message!!.contains(key) || failure.message!!.contains(secret))
        assertFailsWith<IllegalStateException> { BinanceTransport({ null }, sleep = {}).get(account, Binance.restrictions()) }
    }
}
