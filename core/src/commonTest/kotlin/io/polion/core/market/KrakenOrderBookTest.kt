package io.polion.core.market

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KrakenOrderBookTest {
    private val pair = KrakenPair("XBT", "USD")

    private fun level(
        price: String,
        volume: String = "1.0",
    ) = """["$price","$volume",1731000000]"""

    private fun depthPayload(
        bids: List<String>,
        asks: List<String>,
        error: String = "",
    ): String = """{"error":[$error],"result":{"XXBTZUSD":{"asks":[${asks.joinToString(",")}],"bids":[${bids.joinToString(",")}]}}}"""

    private fun parseBook(
        bids: List<String>,
        asks: List<String>,
    ) = KrakenOrderBook.parseDepth(depthPayload(bids, asks), pair, observedAtMillis = 0L)

    @Test
    fun parsesBookInOrderAndLimitsDepth() {
        val payload =
            depthPayload(
                bids = listOf(level("68000.00000", "1.2"), level("67999.90000", "2.0"), level("67990.00000")),
                asks = listOf(level("68001.10000", "0.5"), level("68002.00000"), level("68010.00000")),
            )
        val book = KrakenOrderBook.parseDepth(payload, pair, observedAtMillis = 1_700L, depth = 2)

        assertEquals("Kraken", book.venue)
        assertEquals("XBT/USD", book.pair)
        assertEquals(1_700L, book.observedAtMillis)
        assertEquals(listOf("68000.00000", "67999.90000"), book.bids.map { it.price.toString() })
        assertEquals("1.2", book.bids[0].volume.toString())
        assertEquals(listOf("68001.10000", "68002.00000"), book.asks.map { it.price.toString() })

        val full = KrakenOrderBook.parseDepth(payload, pair, observedAtMillis = 0L)
        assertEquals(3, full.bids.size)
        assertEquals(3, full.asks.size)
    }

    @Test
    fun emptySideIsAllowed() {
        val book = KrakenOrderBook.parseDepth(depthPayload(listOf(level("68000.0")), emptyList()), pair, observedAtMillis = 0L)

        assertEquals(1, book.bids.size)
        assertEquals(emptyList<OrderLevel>(), book.asks)
    }

    @Test
    fun krakenErrorFailsWithFirstErrorText() {
        val payload = depthPayload(listOf(level("68000.0")), listOf(level("68001.0")), error = "\"EQuery:Unknown asset pair\"")

        val error = assertFailsWith<IllegalArgumentException> { KrakenOrderBook.parseDepth(payload, pair, observedAtMillis = 0L) }
        assertContains(error.message.orEmpty(), "EQuery:Unknown asset pair")
    }

    @Test
    fun crossedAndLockedBooksFail() {
        assertFailsWith<IllegalArgumentException> { parseBook(listOf(level("68002.0")), listOf(level("68001.1"))) }
        assertFailsWith<IllegalArgumentException> { parseBook(listOf(level("68001.10")), listOf(level("68001.1"))) }
    }

    @Test
    fun unsortedOrDuplicatePricesFail() {
        assertFailsWith<IllegalArgumentException> { parseBook(listOf(level("67999.9"), level("68000.0")), listOf(level("68001.0"))) }
        assertFailsWith<IllegalArgumentException> { parseBook(listOf(level("68000.0")), listOf(level("68002.0"), level("68001.0"))) }
        assertFailsWith<IllegalArgumentException> { parseBook(listOf(level("68000.0"), level("68000.00")), listOf(level("68001.0"))) }
    }

    @Test
    fun zeroOrNegativeValuesFail() {
        assertFailsWith<IllegalArgumentException> { parseBook(listOf(level("68000.0", "0")), listOf(level("68001.0"))) }
        assertFailsWith<IllegalArgumentException> { parseBook(listOf(level("68000.0", "-0.5")), listOf(level("68001.0"))) }
        assertFailsWith<IllegalArgumentException> { parseBook(listOf(level("0.0")), listOf(level("68001.0"))) }
    }

    @Test
    fun multipleResultEntriesFail() {
        val payload =
            """{"error":[],"result":{"XXBTZUSD":{"asks":[${level("68001.0")}],"bids":[${level("68000.0")}]},""" +
                """"XXBTZEUR":{"asks":[],"bids":[]}}}"""

        assertFailsWith<IllegalArgumentException> { KrakenOrderBook.parseDepth(payload, pair, observedAtMillis = 0L) }
    }

    @Test
    fun depthUrlUsesRestNameAndBoundedCount() {
        assertEquals("https://api.kraken.com/0/public/Depth?pair=XBTUSD&count=10", KrakenOrderBook.depthUrl(pair))
        assertEquals("https://api.kraken.com/0/public/Depth?pair=XBTUSD&count=25", KrakenOrderBook.depthUrl(pair, 25))
        assertFailsWith<IllegalArgumentException> { KrakenOrderBook.depthUrl(pair, 0) }
        assertFailsWith<IllegalArgumentException> { KrakenOrderBook.depthUrl(pair, 26) }
        assertFailsWith<IllegalArgumentException> { KrakenOrderBook.depthUrl(KrakenPair("xbt", "USD")) }
    }

    @Test
    fun tickersUrlRequiresCoinIdShape() {
        assertEquals(
            "https://api.coingecko.com/api/v3/coins/bitcoin/tickers?exchange_ids=kraken",
            KrakenOrderBook.tickersUrl("https://api.coingecko.com/api/v3", "bitcoin"),
        )
        assertFailsWith<IllegalArgumentException> { KrakenOrderBook.tickersUrl("https://api.coingecko.com/api/v3", "Bitcoin") }
        assertFailsWith<IllegalArgumentException> { KrakenOrderBook.tickersUrl("https://api.coingecko.com/api/v3", "bitcoin/../x") }
    }

    private fun ticker(
        base: String,
        target: String,
        identifier: String = "kraken",
        coinId: String = "bitcoin",
        anomaly: Boolean = false,
        stale: Boolean = false,
    ) = """{"base":"$base","target":"$target","market":{"name":"x","identifier":"$identifier"},""" +
        """"is_anomaly":$anomaly,"is_stale":$stale,"coin_id":"$coinId"}"""

    private fun tickersPayload(vararg tickers: String) = """{"name":"Bitcoin","tickers":[${tickers.joinToString(",")}]}"""

    @Test
    fun parsesKrakenPairsForCoinOnlyAndMapsBitcoinToXbt() {
        val payload =
            tickersPayload(
                ticker("BTC", "USD"),
                ticker("BTC", "USD", identifier = "binance"),
                ticker("BTC", "USDT", stale = true),
                ticker("BTC", "EUR", anomaly = true),
                ticker("ETH", "USD", coinId = "ethereum"),
                ticker("BTC", "GBP"),
                ticker("btc", "usd"),
            )

        assertEquals(
            listOf(KrakenPair("XBT", "USD"), KrakenPair("XBT", "GBP")),
            KrakenOrderBook.parseKrakenPairs(payload, "bitcoin"),
        )
    }

    @Test
    fun malformedTickersAreSkippedIndividually() {
        val payload =
            tickersPayload(
                """{"base":42,"target":"USD","market":{"identifier":"kraken"},"coin_id":"bitcoin"}""",
                """{"base":"BTC","target":"USD","is_anomaly":false,"is_stale":false,"coin_id":"bitcoin"}""",
                "\"not an object\"",
                ticker("B", "USD"),
                ticker("BTC", "U.D"),
                ticker("BTC", "USD"),
            )

        assertEquals(listOf(KrakenPair("XBT", "USD")), KrakenOrderBook.parseKrakenPairs(payload, "bitcoin"))
    }

    @Test
    fun documentWithoutTickersArrayFails() {
        assertFailsWith<IllegalArgumentException> { KrakenOrderBook.parseKrakenPairs("""{"name":"Bitcoin"}""", "bitcoin") }
    }

    @Test
    fun pairForMatchesQuoteCaseInsensitively() {
        val pairs = listOf(KrakenPair("XBT", "EUR"), KrakenPair("XBT", "USD"))

        assertEquals(KrakenPair("XBT", "USD"), KrakenOrderBook.pairFor(pairs, "usd"))
        assertEquals(KrakenPair("XBT", "USD"), KrakenOrderBook.pairFor(pairs, "Usd"))
        assertNull(KrakenOrderBook.pairFor(pairs, "JPY"))
    }
}
