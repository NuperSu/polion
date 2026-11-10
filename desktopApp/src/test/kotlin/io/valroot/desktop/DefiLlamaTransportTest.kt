package io.valroot.desktop

import io.valroot.core.market.DefiLlamaException
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefiLlamaTransportTest {
    private class Response(
        private val code: Int,
        private val body: ByteArray = "{}".toByteArray(),
    ) : HttpURLConnection(URI("https://coins.llama.fi").toURL()) {
        var disconnected = false

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy() = false

        override fun getResponseCode() = code

        override fun getInputStream() = body.inputStream()
    }

    @Test fun onlyPriceEndpointsAreRequested() {
        val response = Response(200)
        val transport = DefiLlamaTransport(openConnection = { response })
        assertEquals("{}", transport.get("https://coins.llama.fi/prices/current/coingecko:ethereum,coingecko:usd-coin"))
        assertEquals("{}", transport.get("https://coins.llama.fi/chart/coingecko:ethereum?start=1640995200&span=500&period=1d"))
        assertTrue(response.disconnected)
        for (url in listOf(
            "https://coins.llama.fi/prices/current/ethereum:0xabc",
            "https://coins.llama.fi/chart/coingecko:ethereum?start=1&span=5000&period=1d",
            "https://coins.llama.fi/chart/coingecko:ethereum?start=1&span=5&period=1w",
            "https://example.com/prices/current/coingecko:ethereum",
        )) {
            assertFailsWith<IllegalArgumentException>(url) { transport.get(url) }
        }
    }

    @Test fun rateLimitsAndFailuresAreTyped() {
        assertTrue(assertFailsWith<DefiLlamaException> { DefiLlamaTransport(openConnection = { Response(429) }).get(CURRENT) }.rateLimited)
        assertFalse(assertFailsWith<DefiLlamaException> { DefiLlamaTransport(openConnection = { Response(502) }).get(CURRENT) }.rateLimited)
        val large = Response(200, ByteArray(3 * 1024 * 1024))
        assertFailsWith<DefiLlamaException> { DefiLlamaTransport(openConnection = { large }).get(CURRENT) }
    }

    private companion object {
        const val CURRENT = "https://coins.llama.fi/prices/current/coingecko:ethereum"
    }
}
