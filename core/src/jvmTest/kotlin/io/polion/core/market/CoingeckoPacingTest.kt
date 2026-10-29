package io.polion.core.market

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CoingeckoPacingTest {
    @Test fun rapidRequestsAreRefusedAndProviderRateLimitExtendsCooldown() {
        var now = 0L
        var requests = 0
        var limited = false
        val transport =
            PacedCoingeckoTransport({
                requests++
                if (limited) throw CoingeckoProviderException(CoingeckoProviderFailure.RATE_LIMITED)
                "quote"
            }, { now })
        assertEquals("quote", transport.get("request"))
        assertEquals(
            CoingeckoProviderFailure.RATE_LIMITED,
            assertFailsWith<CoingeckoProviderException> { transport.get("request") }.failure,
        )
        assertEquals(1, requests)
        now = 6_000
        limited = true
        assertFailsWith<CoingeckoProviderException> { transport.get("request") }
        now = 65_999
        assertFailsWith<CoingeckoProviderException> { transport.get("request") }
        assertEquals(2, requests)
        now = 66_000
        limited = false
        assertEquals("quote", transport.get("request"))
        assertEquals(3, requests)
    }
}
