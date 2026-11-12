package io.valroot.desktop

import io.valroot.core.exchange.bybit.Bybit
import io.valroot.core.exchange.bybit.BybitRequest
import io.valroot.core.model.AccountId
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BybitTransportTest {
    private class Response(
        private val code: Int,
        private val body: String = """{"retCode":0,"retMsg":"OK","result":{}}""",
    ) : HttpURLConnection(URI("https://api.bybit.com").toURL()) {
        var disconnected = false

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy() = false

        override fun getResponseCode() = code

        override fun getInputStream() = body.byteInputStream()
    }

    private val account = AccountId("bybit")
    private val key = "TESTKEY0123"
    private val secret = "TESTSECRET0123456789"

    @Test fun requestsAreSignedAndOnlyAccountPathsAreSent() {
        val responses = mutableListOf<Response>()
        val urls = mutableListOf<String>()
        val transport =
            BybitTransport({ key to secret }, openConnection = { url ->
                urls += url
                Response(200).also { responses += it }
            }, sleep = {}, wallClock = { 1_700_000_000_000L })
        val request = Bybit.transactionLog(1, 2)
        transport.get(account, request)
        assertEquals("https://api.bybit.com/v5/account/transaction-log?${request.queryString}", urls.single())
        val sent = responses.single()
        assertEquals(key, sent.getRequestProperty("X-BAPI-API-KEY"))
        assertEquals("1700000000000", sent.getRequestProperty("X-BAPI-TIMESTAMP"))
        assertEquals(Bybit.signature(secret, key, 1_700_000_000_000L, request), sent.getRequestProperty("X-BAPI-SIGN"))
        assertFalse(urls.single().contains(secret) || urls.single().contains(key))
        assertTrue(sent.disconnected)
        assertFailsWith<IllegalArgumentException> { transport.get(account, BybitRequest("/v5/order/create")) }
        assertFailsWith<IllegalArgumentException> { transport.get(account, BybitRequest("/v5/asset/withdraw/create")) }
    }

    @Test fun aClockOffsetIsLearnedFromBybitsTime() {
        val stamps = mutableListOf<String?>()
        var calls = 0
        val transport =
            BybitTransport({ key to secret }, openConnection = { url ->
                calls++
                when {
                    url.endsWith("/v5/market/time") -> Response(200, """{"retCode":0,"result":{"timeSecond":"1700000005"}}""")
                    calls == 1 ->
                        Response(
                            200,
                            """{"retCode":10002,"retMsg":"invalid request, please check your server timestamp","result":{}}""",
                        )
                    else -> Response(200)
                }.also { if (!url.endsWith("time")) stamps += null }
            }, sleep = {}, wallClock = { 1_700_000_000_000L })
        assertEquals(0, Regex("\"retCode\":(\\d+)").find(transport.get(account, Bybit.keyInfo()))!!.groupValues[1].toInt())
        assertEquals(3, calls)
    }

    @Test fun failuresUseFixedMessagesWithoutTheKey() {
        for (code in listOf(401, 500)) {
            val failure =
                assertFailsWith<IllegalStateException> {
                    BybitTransport({ key to secret }, openConnection = { Response(code) }, sleep = {}).get(account, Bybit.keyInfo())
                }
            assertFalse(failure.message!!.contains(key) || failure.message!!.contains(secret))
        }
        assertFailsWith<IllegalStateException> { BybitTransport({ null }, sleep = {}).get(account, Bybit.keyInfo()) }
    }
}
