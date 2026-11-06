package io.polion.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class RefreshCooldownTest {
    @Test fun neverSucceededRunsNow() {
        assertEquals(0L, cooldownDelayMillis(null, PRICE_REFRESH_INTERVAL_MILLIS, 5_000L))
    }

    @Test fun recentSuccessWaitsOutTheRestOfTheInterval() {
        val success = 1_000_000L
        assertEquals(
            PRICE_REFRESH_INTERVAL_MILLIS - 60_000L,
            cooldownDelayMillis(success, PRICE_REFRESH_INTERVAL_MILLIS, success + 60_000L),
        )
    }

    @Test fun dueSuccessRunsNow() {
        assertEquals(0L, cooldownDelayMillis(0L, WALLET_REFRESH_INTERVAL_MILLIS, WALLET_REFRESH_INTERVAL_MILLIS * 2))
    }
}
