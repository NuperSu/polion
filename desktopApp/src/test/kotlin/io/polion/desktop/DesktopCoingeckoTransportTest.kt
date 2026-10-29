package io.polion.desktop

import io.polion.core.market.CoingeckoProviderException
import io.polion.core.market.CoingeckoProviderFailure
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopCoingeckoTransportTest {
    private val url = "https://api.coingecko.com/api/v3/coins/bitcoin/history?date=29-02-2024&localization=false"

    private class Response(
        private val code: Int,
    ) : HttpURLConnection(URI("https://api.coingecko.com").toURL()) {
        var disconnected = false

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy() = false

        override fun getResponseCode() = code

        override fun getInputStream() = "{}".byteInputStream()
    }

    @Test fun credentialsAreHeadersAndAccessDenialIsExplicit() {
        for (code in listOf(401, 403)) {
            val response = Response(code)
            var requested = ""
            val transport =
                DesktopCoingeckoTransport(openConnection = {
                    requested = it
                    response
                })
            val failure = assertFailsWith<CoingeckoProviderException> { transport.get(url, "disposable-test-key") }
            assertEquals(CoingeckoProviderFailure.ACCESS_DENIED, failure.failure)
            assertEquals("disposable-test-key", response.getRequestProperty("x-cg-demo-api-key"))
            assertFalse(requested.contains("disposable-test-key"))
            assertEquals(url, requested)
            assertTrue(response.disconnected)
        }
    }

    @Test fun successAndRateLimitsRetainTypedResults() {
        val success = Response(200)
        assertEquals("{}", DesktopCoingeckoTransport(openConnection = { success }).get(url))
        assertTrue(success.disconnected)
        val limited = Response(429)
        assertEquals(
            CoingeckoProviderFailure.RATE_LIMITED,
            assertFailsWith<CoingeckoProviderException> {
                DesktopCoingeckoTransport(openConnection = { limited }).get(url)
            }.failure,
        )
    }

    @Test fun rejectsUnapprovedUrlsBeforeOpeningConnection() {
        listOf(
            "http://api.coingecko.com/api/v3/coins/bitcoin/history?date=29-02-2024&localization=false",
            "https://other.invalid/api/v3/coins/bitcoin/history?date=29-02-2024&localization=false",
            "https://api.coingecko.com/api/v3/coins/bitcoin/history?date=29-02-2024&localization=false&address=private",
            "https://api.coingecko.com/api/v3/coins/../history?date=29-02-2024&localization=false",
        ).forEach { url -> assertFailsWith<IllegalArgumentException> { DesktopCoingeckoTransport().get(url) } }
    }
}
