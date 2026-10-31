package io.polion.desktop

import io.polion.core.market.KrakenRateLimitedException
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KrakenPriceTransportTest {
    @Test fun rateLimitStopsNewRequestsUntilCooldownExpires() {
        var now = 0L
        var requests = 0
        var responseCode = 429
        val response =
            object : HttpURLConnection(URI("https://api.kraken.com").toURL()) {
                override fun connect() = Unit

                override fun disconnect() = Unit

                override fun usingProxy() = false

                override fun getResponseCode() = responseCode

                override fun getInputStream() = "{}".byteInputStream()
            }
        val pacer = KrakenPricePacer(clock = { now }, sleep = { now += it })
        val first =
            KrakenPriceTransport(openConnection = {
                requests++
                response
            }, pacer = pacer)
        val second =
            KrakenPriceTransport(openConnection = {
                requests++
                response
            }, pacer = pacer)
        val url = "https://api.kraken.com/0/public/OHLC?pair=XBTUSD&interval=60"
        assertFailsWith<KrakenRateLimitedException> { first.get(url) }
        responseCode = 200
        assertFailsWith<KrakenRateLimitedException> { second.get(url) }
        assertEquals(1, requests)
        now += 60_000
        assertEquals("{}", second.get(url))
        assertEquals(2, requests)
    }

    @Test fun cancellationDuringPacingPreventsNextRequest() {
        var now = 0L
        var active = true
        var requests = 0
        val response =
            object : HttpURLConnection(URI("https://api.kraken.com").toURL()) {
                override fun connect() = Unit

                override fun disconnect() = Unit

                override fun usingProxy() = false

                override fun getResponseCode() = 200

                override fun getInputStream() = "{}".byteInputStream()
            }
        val transport =
            KrakenPriceTransport(
                openConnection = {
                    requests++
                    response
                },
                pacer =
                    KrakenPricePacer(clock = { now }, sleep = { delay ->
                        now += delay
                        active = false
                    }),
                beforeRequest = { check(active) },
            )
        val url = "https://api.kraken.com/0/public/OHLC?pair=XBTUSD&interval=60"
        transport.get(url)
        assertFailsWith<IllegalStateException> { transport.get(url) }
        assertEquals(1, requests)
    }

    @Test fun approvedRequestWorksAndOtherUrlsNeverReachNetwork() {
        val response =
            object : HttpURLConnection(URI("https://api.kraken.com").toURL()) {
                var closed = false

                override fun connect() = Unit

                override fun disconnect() {
                    closed = true
                }

                override fun usingProxy() = false

                override fun getResponseCode() = 200

                override fun getInputStream() = "{}".byteInputStream()
            }
        var requests = 0
        var now = 0L
        val waits = mutableListOf<Long>()
        val pacer =
            KrakenPricePacer(clock = { now }, sleep = { delay ->
                waits.add(delay)
                now += delay
            })
        val transport =
            KrakenPriceTransport(
                openConnection = {
                    requests++
                    response
                },
                pacer = pacer,
            )
        val nextTransport =
            KrakenPriceTransport(openConnection = {
                requests++
                response
            }, pacer = pacer)
        assertEquals("{}", transport.get("https://api.kraken.com/0/public/OHLC?pair=XBTUSD&interval=60"))
        assertEquals("{}", nextTransport.get("https://api.kraken.com/0/public/OHLC?pair=XBTUSD&interval=1440"))
        assertEquals(listOf(1_100L), waits)
        assertTrue(response.closed)
        assertFailsWith<IllegalArgumentException> {
            transport.get(
                "https://api.kraken.com/0/public/OHLC?pair=XBTUSD&interval=60&address=private",
            )
        }
        assertEquals(2, requests)
    }
}
