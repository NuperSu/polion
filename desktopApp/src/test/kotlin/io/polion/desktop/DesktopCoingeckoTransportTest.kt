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
        private val body: ByteArray = "{}".toByteArray(),
    ) : HttpURLConnection(URI("https://api.coingecko.com").toURL()) {
        var disconnected = false

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy() = false

        override fun getResponseCode() = code

        override fun getInputStream() = body.inputStream()
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

    @Test fun rangeEndpointIsAllowedWithoutExposingKeysInTheUrl() {
        val rangeUrl = "https://api.coingecko.com/api/v3/coins/bitcoin/market_chart?vs_currency=usd&days=90"
        val response = Response(200)
        assertEquals("{}", DesktopCoingeckoTransport(openConnection = { response }).get(rangeUrl, "key"))
        assertEquals("key", response.getRequestProperty("x-cg-demo-api-key"))
    }

    @Test fun rejectsUnapprovedUrlsBeforeOpeningConnection() {
        listOf(
            "http://api.coingecko.com/api/v3/coins/bitcoin/history?date=29-02-2024&localization=false",
            "https://other.invalid/api/v3/coins/bitcoin/history?date=29-02-2024&localization=false",
            "https://api.coingecko.com/api/v3/coins/bitcoin/history?date=29-02-2024&localization=false&address=private",
            "https://api.coingecko.com/api/v3/coins/../history?date=29-02-2024&localization=false",
            "https://api.coingecko.com/api/v3/coins/bitcoin/market_chart?vs_currency=usd&days=90&address=private",
        ).forEach { url -> assertFailsWith<IllegalArgumentException> { DesktopCoingeckoTransport().get(url) } }
    }

    @Test fun krakenTickerLookupIsAllowedOnlyAsAFeedRequest() {
        val tickers = "https://api.coingecko.com/api/v3/coins/chainlink/tickers?exchange_ids=kraken"
        val response = Response(200)
        assertEquals("{}", DesktopCoingeckoTransport(openConnection = { response }).feed(tickers, "key"))
        assertEquals("key", response.getRequestProperty("x-cg-demo-api-key"))
        listOf(
            "https://api.coingecko.com/api/v3/coins/chainlink/tickers?exchange_ids=binance",
            "https://api.coingecko.com/api/v3/coins/Chainlink/tickers?exchange_ids=kraken",
            "https://api.coingecko.com/api/v3/coins/chainlink/tickers?exchange_ids=kraken&page=2",
        ).forEach { bad -> assertFailsWith<IllegalArgumentException> { DesktopCoingeckoTransport().feed(bad, null) } }
        assertFailsWith<IllegalArgumentException> { DesktopCoingeckoTransport().get(tickers) }
    }

    private val base = "https://api.coingecko.com/api/v3"

    @Test fun chainCatalogAndLinkedPricesAreAllowedAsFeedRequests() {
        val ids = (1..50).joinToString(",") { "coin-$it" }
        listOf(
            "$base/asset_platforms",
            "$base/coins/list?include_platform=true",
            "$base/simple/price?ids=ethereum,usd-coin&vs_currencies=usd,eur&include_last_updated_at=true",
            "$base/simple/price?ids=$ids&vs_currencies=usd&include_last_updated_at=true",
        ).forEach { allowed ->
            val response = Response(200)
            assertEquals("{}", DesktopCoingeckoTransport(openConnection = { response }).feed(allowed, "key"))
            assertEquals("key", response.getRequestProperty("x-cg-demo-api-key"))
        }
    }

    @Test fun chainCatalogAndLinkedPricesRejectOtherShapes() {
        val tooManyIds = (1..51).joinToString(",") { "coin-$it" }
        listOf(
            "$base/asset_platforms?page=2",
            "$base/coins/list",
            "$base/coins/list?include_platform=true&page=2",
            "$base/simple/price?ids=bitcoin&vs_currencies=usd",
            "$base/simple/price?ids=Bitcoin&vs_currencies=usd&include_last_updated_at=true",
            "$base/simple/price?ids=$tooManyIds&vs_currencies=usd&include_last_updated_at=true",
            "$base/simple/price?ids=bitcoin&vs_currencies=usd,eur,gbp,jpy&include_last_updated_at=true",
            "$base/simple/price?ids=bitcoin&vs_currencies=us1&include_last_updated_at=true",
            "$base/simple/price?ids=bitcoin&vs_currencies=usd&include_last_updated_at=true&extra=1",
        ).forEach { bad -> assertFailsWith<IllegalArgumentException> { DesktopCoingeckoTransport().feed(bad, null) } }
    }

    @Test fun coinListUsesItsOwnSizeLimit() {
        val fiveMebibytes = ByteArray(5 * 1024 * 1024) { ' '.code.toByte() }
        val coins = DesktopCoingeckoTransport(openConnection = { Response(200, fiveMebibytes) })
        assertEquals(fiveMebibytes.size, coins.feed("$base/coins/list?include_platform=true", null).length)
        assertEquals(
            CoingeckoProviderFailure.INVALID_RESPONSE,
            assertFailsWith<CoingeckoProviderException> {
                DesktopCoingeckoTransport(openConnection = { Response(200, fiveMebibytes) }).feed("$base/asset_platforms", null)
            }.failure,
        )
    }
}
