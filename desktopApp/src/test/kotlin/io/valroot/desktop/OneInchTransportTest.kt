package io.valroot.desktop

import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OneInchTransportTest {
    private class Response(
        private val code: Int,
    ) : HttpURLConnection(URI("https://api.1inch.com").toURL()) {
        var disconnected = false

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy() = false

        override fun getResponseCode() = code

        override fun getInputStream() = "{}".byteInputStream()
    }

    private val key = "disposable-test-key-0123456789"
    private val history = "/history/v2.0/history/0x${"1".repeat(40)}/events?chainId=56&limit=10000"

    @Test fun theKeyIsABearerTokenAndOnlyHistoryAndTracesAreRequested() {
        val response = Response(200)
        var requested = ""
        val transport =
            OneInchTransport({ key }, openConnection = {
                requested = it
                response
            }, sleep = {})
        assertEquals("{}", transport.get(history))
        assertEquals("https://api.1inch.com$history", requested)
        assertEquals("Bearer $key", response.getRequestProperty("Authorization"))
        assertFalse(requested.contains(key))
        assertTrue(response.disconnected)
        assertEquals("{}", transport.get("/traces/v1.0/chain/56/block-trace/123/tx-hash/0x${"a".repeat(64)}"))
        for (path in listOf("/swap/v6.0/56/swap", "$history&extra=1", "https://evil.example$history")) {
            assertFailsWith<IllegalArgumentException>(path) { transport.get(path) }
        }
    }

    @Test fun failuresUseFixedMessagesWithoutTheKey() {
        for (code in listOf(401, 429, 500)) {
            val failure =
                assertFailsWith<IllegalStateException> {
                    OneInchTransport(
                        { key },
                        openConnection = { Response(code) },
                        sleep = {},
                    ).get(history)
                }
            assertFalse(failure.message!!.contains(key))
        }
        assertFailsWith<IllegalStateException> { OneInchTransport({ null }, sleep = {}).get(history) }
    }
}
