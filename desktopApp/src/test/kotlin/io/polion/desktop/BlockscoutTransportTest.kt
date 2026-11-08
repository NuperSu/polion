package io.polion.desktop

import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BlockscoutTransportTest {
    private val address = "0x" + "a".repeat(40)
    private val url = "https://api.blockscout.com/8453/api/v2/addresses/$address/transactions?limit=50"
    private val key = "disposable-test-key"
    private var now = 0L

    private class Response(
        private val code: Int,
        private val body: String = "{}",
        private val retryAfter: String? = null,
    ) : HttpURLConnection(URI("https://api.blockscout.com").toURL()) {
        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy() = false

        override fun getResponseCode() = code

        override fun getInputStream() = body.byteInputStream()

        override fun getHeaderField(name: String?): String? = if (name == "Retry-After") retryAfter else null
    }

    private fun pacer() = BlockscoutPacer(clock = { now }, sleep = { now += it })

    @Test fun acceptsAddressRequestsAndAppendsTheKey() {
        val requested = mutableListOf<String>()
        val transport =
            BlockscoutTransport(
                apiKey = { key },
                openConnection = {
                    requested += it
                    Response(200, body = "{\"items\":[]}")
                },
                pacer = pacer(),
            )
        assertEquals("{\"items\":[]}", transport.get(url))
        assertEquals("$url&apikey=$key", requested.single())

        listOf(
            "https://api.blockscout.com/1/api/v2/addresses/$address",
            "https://api.blockscout.com/1/api/v2/addresses/$address/internal-transactions?next=1",
            "https://api.blockscout.com/1/api/v2/addresses/$address/token-transfers",
            "https://api.blockscout.com/1/api/v2/addresses/$address/tokens?limit=50",
            "https://api.blockscout.com/1/api/v2/addresses/$address/withdrawals",
        ).forEach { allowed -> assertEquals("{\"items\":[]}", transport.get(allowed)) }
        assertEquals("https://api.blockscout.com/1/api/v2/addresses/$address?apikey=$key", requested[1])
    }

    @Test fun rejectsOtherHostsPathsAndSchemesBeforeOpeningConnection() {
        val opened = mutableListOf<String>()
        val transport =
            BlockscoutTransport(apiKey = { key }, openConnection = {
                opened += it
                Response(200)
            }, pacer = pacer())
        listOf(
            "http://api.blockscout.com/1/api/v2/addresses/$address",
            "https://blockscout.example/1/api/v2/addresses/$address",
            "https://api.blockscout.com.example.test/1/api/v2/addresses/$address",
            "https://user@api.blockscout.com/1/api/v2/addresses/$address",
            "https://api.blockscout.com:8443/1/api/v2/addresses/$address",
            "https://api.blockscout.com/0/api/v2/addresses/$address",
            "https://api.blockscout.com/01/api/v2/addresses/$address",
            "https://api.blockscout.com/1/api/v2/addresses/0x" + "A".repeat(40),
            "https://api.blockscout.com/1/api/v2/addresses/$address/logs",
            "https://api.blockscout.com/1/api/v2/addresses/$address/transactions/../tokens",
            "https://api.blockscout.com/1/api/v2/addresses/$address?apikey=other",
            "https://api.blockscout.com/1/api/v2/addresses/$address?next=a#fragment",
        ).forEach { bad -> assertFailsWith<IllegalArgumentException> { transport.get(bad) } }
        assertTrue(opened.isEmpty())
    }

    @Test fun missingKeyFailsBeforeAnyRequest() {
        val opened = mutableListOf<String>()
        val failure =
            assertFailsWith<IllegalStateException> {
                BlockscoutTransport(apiKey = { null }, openConnection = {
                    opened += it
                    Response(200)
                }, pacer = pacer()).get(url)
            }
        assertEquals("Add a free Blockscout API key in Settings › API Keys.", failure.message)
        assertTrue(opened.isEmpty())
    }

    @Test fun statusCodesMapToSafeMessages() {
        mapOf(
            401 to "Blockscout rejected the API key.",
            402 to "Blockscout rejected the API key.",
            403 to "Blockscout rejected the API key.",
            302 to "Blockscout is unavailable.",
            500 to "Blockscout is unavailable.",
        ).forEach { (code, message) ->
            val failure =
                assertFailsWith<IllegalStateException> {
                    BlockscoutTransport(apiKey = { key }, openConnection = { Response(code) }, pacer = pacer()).get(url)
                }
            assertEquals(message, failure.message)
        }
    }

    @Test fun rateLimitHonoursRetryAfterAndBlocksLaterRequests() {
        val shared = pacer()
        val limited = BlockscoutTransport(apiKey = { key }, openConnection = { Response(429, retryAfter = "600") }, pacer = shared)
        val failure = assertFailsWith<IllegalStateException> { limited.get(url) }
        assertEquals("Blockscout is rate limited. Try again in 60 s.", failure.message)

        val opened = mutableListOf<String>()
        val later =
            BlockscoutTransport(apiKey = { key }, openConnection = {
                opened += it
                Response(200)
            }, pacer = shared)
        assertFailsWith<IllegalStateException> { later.get(url) }
        assertTrue(opened.isEmpty())
    }

    @Test fun requestsAreSpacedAtQuarterSecond() {
        val sleeps = mutableListOf<Long>()
        val paced =
            BlockscoutPacer(clock = { now }, sleep = {
                sleeps += it
                now += it
            })
        val transport = BlockscoutTransport(apiKey = { key }, openConnection = { Response(200) }, pacer = paced)
        transport.get(url)
        transport.get(url)
        assertEquals(listOf(250L), sleeps)
    }
}
