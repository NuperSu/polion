package io.polion.desktop

import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KrakenPriceTransportTest {
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
        val transport =
            KrakenPriceTransport {
                requests++
                response
            }
        assertEquals("{}", transport.get("https://api.kraken.com/0/public/OHLC?pair=XBTUSD&interval=60"))
        assertTrue(response.closed)
        assertFailsWith<IllegalArgumentException> {
            transport.get(
                "https://api.kraken.com/0/public/OHLC?pair=XBTUSD&interval=60&address=private",
            )
        }
        assertEquals(1, requests)
    }
}
