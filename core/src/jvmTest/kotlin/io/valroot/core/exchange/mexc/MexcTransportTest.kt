package io.valroot.core.exchange.mexc

import io.valroot.core.exchange.mexc.Mexc
import io.valroot.core.exchange.mexc.MexcRequest
import io.valroot.core.model.AccountId
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MexcTransportTest {
    private class Response(
        private val code: Int,
        private val body: String = "[]",
    ) : HttpURLConnection(URI("https://api.mexc.com").toURL()) {
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

    private val account = AccountId("mexc")
    private val key = "TESTKEY0123"
    private val secret = "TESTSECRET0123456789"

    @Test fun requestsAreSignedAndOnlyReadPathsAreSent() {
        val responses = mutableListOf<Response>()
        val urls = mutableListOf<String>()
        val transport =
            MexcTransport({ key to secret }, openConnection = { url ->
                urls += url
                Response(200).also { responses += it }
            }, sleep = {}, wallClock = { 1_700_000_000_000L })
        val request = Mexc.trades("BTCUSDT", 1, 2)
        transport.get(account, request)
        assertEquals(Mexc.url(request, secret, 1_700_000_000_000L), urls.single())
        assertEquals(key, responses.single().getRequestProperty("X-MEXC-APIKEY"))
        assertFalse(urls.single().contains(secret) || urls.single().contains(key))
        assertTrue(responses.single().disconnected)
        // Public symbols are sent without a key.
        transport.get(account, Mexc.tickers())
        assertEquals(null, responses.last().getRequestProperty("X-MEXC-APIKEY"))
        assertFailsWith<IllegalArgumentException> { transport.get(account, MexcRequest("/api/v3/order")) }
        assertFailsWith<IllegalArgumentException> { transport.get(account, MexcRequest("/api/v3/capital/withdraw/apply")) }
    }

    @Test fun mexcErrorsReachTheCallerAndTheClockIsCorrected() {
        var calls = 0
        val transport =
            MexcTransport({ key to secret }, openConnection = { url ->
                calls++
                when {
                    url.contains("/api/v3/time") -> Response(200, """{"serverTime":1700000005000}""")
                    calls == 1 -> Response(400, """{"code":700003,"msg":"Timestamp for this request is outside of the recvWindow."}""")
                    else -> Response(400, """{"code":10072,"msg":"Api key info invalid"}""")
                }
            }, sleep = {}, wallClock = { 1_700_000_000_000L })
        assertEquals(
            """{"code":10072,"msg":"Api key info invalid"}""",
            transport.get(account, Mexc.uid()),
        )
        assertEquals(3, calls)
    }

    @Test fun failuresUseFixedMessagesWithoutTheKey() {
        val failure =
            assertFailsWith<IllegalStateException> {
                MexcTransport({ key to secret }, openConnection = { Response(500) }, sleep = {}).get(account, Mexc.uid())
            }
        assertFalse(failure.message!!.contains(key) || failure.message!!.contains(secret))
        assertFailsWith<IllegalStateException> { MexcTransport({ null }, sleep = {}).get(account, Mexc.uid()) }
    }
}
