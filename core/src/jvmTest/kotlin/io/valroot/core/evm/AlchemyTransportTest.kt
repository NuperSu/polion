package io.valroot.core.evm

import io.valroot.core.evm.Alchemy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlchemyTransportTest {
    private val key = "disposable_test_key_0123456789"
    private val body = """{"addresses":[{"address":"0x${"a".repeat(40)}"}]}"""
    private val transfers = "https://api.g.alchemy.com/data/v1/{key}/transfers/by-address"
    private val ok = "{\"transfers\":[]}"
    private val rejected = "Alchemy rejected the API key, or this network is not enabled for it in the Alchemy dashboard."
    private var now = 0L

    private class Response(
        private val code: Int,
        private val payload: String = "{}",
        private val retryAfter: String? = null,
    ) : HttpURLConnection(URI("https://api.g.alchemy.com").toURL()) {
        val sent = ByteArrayOutputStream()

        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy() = false

        override fun getResponseCode() = code

        override fun getOutputStream() = sent

        override fun getInputStream() = payload.byteInputStream()

        override fun getHeaderField(name: String?): String? = if (name == "Retry-After") retryAfter else null
    }

    private fun pacer() = AlchemyPacer(clock = { now }, sleep = { now += it })

    private fun transport(
        apiKey: String? = key,
        pacer: AlchemyPacer = pacer(),
        open: (String) -> HttpURLConnection = { Response(200, payload = ok) },
    ) = AlchemyTransport(apiKey = { apiKey }, openConnection = open, pacer = pacer)

    @Test fun acceptsEveryAlchemyTemplateAndInsertsTheKeyOnlyInThePath() {
        val requested = mutableListOf<String>()
        val connections = mutableListOf<Response>()
        val transport =
            transport(open = {
                requested += it
                Response(200, payload = ok).also { connection -> connections += connection }
            })

        assertEquals(ok, transport.post(transfers, body))
        assertEquals("https://api.g.alchemy.com/data/v1/$key/transfers/by-address", requested.single())
        assertEquals("POST", connections.single().requestMethod)
        assertEquals("application/json", connections.single().getRequestProperty("Content-Type"))
        assertEquals(body, String(connections.single().sent.toByteArray(), Charsets.UTF_8))

        transport.post("https://api.g.alchemy.com/data/v1/{key}/assets/tokens/by-address", body)
        Alchemy.NETWORKS.forEach { network -> transport.post("https://${network.slug}.g.alchemy.com/v2/{key}", "[]") }
        assertEquals(2 + Alchemy.NETWORKS.size, requested.size)
        assertEquals("https://eth-mainnet.g.alchemy.com/v2/$key", requested[2])
        requested.forEach { url ->
            assertFalse(url.contains("?"), url)
            assertFalse(url.contains("apikey", ignoreCase = true), url)
        }
    }

    @Test fun rejectsOtherUrlsBeforeOpeningAConnection() {
        val opened = mutableListOf<String>()
        val transport =
            transport(open = {
                opened += it
                Response(200)
            })
        listOf(
            "http://api.g.alchemy.com/data/v1/{key}/transfers/by-address",
            "https://api.g.alchemy.com/data/v1/$key/transfers/by-address",
            "https://api.g.alchemy.com/data/v1/{key}/transfers/by-address?apikey=other",
            "https://api.g.alchemy.com/data/v2/{key}/transfers/by-address",
            "https://api.g.alchemy.com/data/v1/{key}/transfers/by-address/extra",
            "https://api.g.alchemy.com:8443/data/v1/{key}/transfers/by-address",
            "https://user@api.g.alchemy.com/data/v1/{key}/transfers/by-address",
            "https://eth-mainnet.g.alchemy.com.example.test/v2/{key}",
            "https://unknown-mainnet.g.alchemy.com/v2/{key}",
            "https://eth-mainnet.g.alchemy.com/v2/$key",
            "https://eth-mainnet.g.alchemy.com/v2/{key}/extra",
            "https://eth-mainnet.g.alchemy.com/v1/{key}",
        ).forEach { bad -> assertFailsWith<IllegalArgumentException> { transport.post(bad, body) } }
        assertTrue(opened.isEmpty())
    }

    @Test fun rejectsBodiesOverOneMebibyte() {
        val opened = mutableListOf<String>()
        val transport =
            transport(open = {
                opened += it
                Response(200, payload = ok)
            })
        assertEquals(ok, transport.post(transfers, "x".repeat(1024 * 1024)))
        assertFailsWith<IllegalArgumentException> { transport.post(transfers, "x".repeat(1024 * 1024 + 1)) }
        assertEquals(1, opened.size)
    }

    @Test fun missingOrInvalidKeyFailsBeforeAnyRequest() {
        val opened = mutableListOf<String>()
        val open: (String) -> HttpURLConnection = {
            opened += it
            Response(200)
        }
        val missing = assertFailsWith<IllegalStateException> { transport(apiKey = null, open = open).post(transfers, body) }
        assertEquals("Add a free Alchemy API key in Settings › API Keys.", missing.message)
        assertFailsWith<IllegalStateException> { transport(apiKey = "", open = open).post(transfers, body) }

        val invalid = assertFailsWith<IllegalStateException> { transport(apiKey = "short", open = open).post(transfers, body) }
        assertEquals("The Alchemy API key is not valid. Replace it in Settings › API Keys.", invalid.message)
        assertFailsWith<IllegalStateException> { transport(apiKey = "$key/../other", open = open).post(transfers, body) }
        assertTrue(opened.isEmpty())
    }

    @Test fun statusCodesMapToSafeMessages() {
        mapOf(
            401 to rejected,
            403 to rejected,
            302 to "Alchemy is unavailable.",
            404 to "Alchemy is unavailable.",
            500 to "Alchemy is unavailable.",
        ).forEach { (code, message) ->
            val failure =
                assertFailsWith<IllegalStateException> {
                    transport(open = { Response(code) }).post(transfers, body)
                }
            assertEquals(message, failure.message)
        }
    }

    @Test fun rateLimitCoolsDownWithCappedRetryAfterAndFailsFastMeanwhile() {
        val shared = pacer()
        val opened = mutableListOf<String>()
        val limited =
            transport(pacer = shared, open = {
                opened += it
                Response(429, retryAfter = "600")
            })
        val message = "Alchemy's request limit was reached. Try again shortly."
        assertEquals(message, assertFailsWith<IllegalStateException> { limited.post(transfers, body) }.message)
        assertEquals(1, opened.size)

        val later =
            transport(pacer = shared, open = {
                opened += it
                Response(200, payload = ok)
            })
        assertEquals(message, assertFailsWith<IllegalStateException> { later.post(transfers, body) }.message)
        assertEquals(1, opened.size)

        now += 60_000
        assertEquals(ok, later.post(transfers, body))
        assertEquals(2, opened.size)
    }

    @Test fun requestsAreSpacedAtFiveHertz() {
        val sleeps = mutableListOf<Long>()
        val paced =
            AlchemyPacer(clock = { now }, sleep = {
                sleeps += it
                now += it
            })
        val transport = transport(pacer = paced)
        transport.post(transfers, body)
        transport.post(transfers, body)
        assertEquals(listOf(200L), sleeps)
    }

    @Test fun failureMessagesNeverContainTheKey() {
        val failures =
            listOf<(String) -> HttpURLConnection>(
                { throw IOException("connection refused: $it") },
                { throw SocketTimeoutException("timed out: $it") },
                { Response(500) },
                { Response(401) },
            )
        failures.forEach { open ->
            val failure = assertFailsWith<IllegalStateException> { transport(open = open).post(transfers, body) }
            val message = failure.message.orEmpty()
            assertFalse(message.contains(key), message)
            assertFalse(message.contains("alchemy.com"), message)
        }
    }
}
