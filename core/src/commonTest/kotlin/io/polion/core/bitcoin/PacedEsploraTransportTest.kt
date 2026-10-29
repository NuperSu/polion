package io.polion.core.bitcoin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PacedEsploraTransportTest {
    private class Clock {
        var now = 0L
        val sleeps = mutableListOf<Long>()

        fun sleep(delay: Long) {
            sleeps += delay
            now += delay
        }
    }

    private fun transport(
        clock: Clock,
        policy: EsploraPacingPolicy = EsploraPacingPolicy(),
        ensureActive: () -> Unit = {},
        onRetry: (EsploraRetryProgress) -> Unit = {},
        request: (String) -> String,
    ) = PacedEsploraTransport(EsploraTransport(request), { clock.now }, clock::sleep, ensureActive, onRetry, policy)

    @Test
    fun spacesRequestsWithoutDelayingTheFirstOrAnAlreadySlowRequest() {
        val clock = Clock()
        val attempts = mutableListOf<Long>()
        val paced =
            transport(clock) {
                attempts += clock.now
                "ok"
            }
        repeat(2) { assertEquals("ok", paced.get("/blocks/tip/hash")) }
        clock.now += 3_000
        paced.get("/blocks/tip/hash")
        assertEquals(listOf(0L, 1_000L, 4_000L), attempts)
        assertTrue(clock.sleeps.all { it in 1..100 })
    }

    @Test
    fun retriesOnlyRateLimitsWithBoundedExponentialDelaysAndSafeProgress() {
        val clock = Clock()
        val attempts = mutableListOf<Long>()
        val progress = mutableListOf<EsploraRetryProgress>()
        val failure = BitcoinProviderException(BitcoinProviderFailure.RATE_LIMITED)
        val paced =
            transport(clock, onRetry = progress::add) {
                attempts += clock.now
                throw failure
            }
        assertSame(failure, assertFailsWith<BitcoinProviderException> { paced.get("/private-address") })
        assertEquals(listOf(0L, 2_000L, 6_000L), attempts)
        assertEquals(listOf(2_000L, 4_000L), progress.map { it.delayMillis })
        assertEquals(listOf(1, 2), progress.map { it.retry })
        assertTrue(progress.all { !it.userMessage.contains("private-address") })
        assertSame(failure, assertFailsWith<BitcoinProviderException> { paced.get("/another-address") })
        assertEquals(3, attempts.size)
    }

    @Test
    fun observesProviderCooldownAndAbortsInsteadOfShorteningExcessiveDelays() {
        val clock = Clock()
        var calls = 0
        val paced =
            transport(clock) {
                if (calls++ == 0) throw BitcoinProviderException(BitcoinProviderFailure.RATE_LIMITED, 10_000)
                "ok"
            }
        assertEquals("ok", paced.get("/blocks/tip/hash"))
        assertEquals(10_000L, clock.now)
        for (delay in listOf(30_001L, Long.MAX_VALUE)) {
            val excessive = BitcoinProviderException(BitcoinProviderFailure.RATE_LIMITED, delay)
            val otherClock = Clock()
            val refuses = transport(otherClock) { throw excessive }
            assertSame(excessive, assertFailsWith<BitcoinProviderException> { refuses.get("/blocks/tip/hash") })
            assertTrue(otherClock.sleeps.isEmpty())
        }
    }

    @Test
    fun totalRetryCountAndWaitBudgetApplyAcrossSuccessfulRequests() {
        val clock = Clock()
        var calls = 0
        val failure = BitcoinProviderException(BitcoinProviderFailure.RATE_LIMITED, 3_000)
        val paced =
            transport(clock, EsploraPacingPolicy(maxRetriesPerScan = 1)) {
                if (calls++ % 2 == 0) throw failure
                "ok"
            }
        assertEquals("ok", paced.get("/blocks/tip/hash"))
        assertSame(failure, assertFailsWith<BitcoinProviderException> { paced.get("/blocks/tip/hash") })
        assertEquals(3, calls)

        val budgetClock = Clock()
        var budgetCalls = 0
        val budget =
            transport(budgetClock, EsploraPacingPolicy(maxRetryWaitPerScanMillis = 5_000)) {
                if (budgetCalls++ % 2 == 0) throw failure
                "ok"
            }
        assertEquals("ok", budget.get("/blocks/tip/hash"))
        assertSame(failure, assertFailsWith<BitcoinProviderException> { budget.get("/blocks/tip/hash") })
        assertEquals(3, budgetCalls)
    }

    @Test
    fun freshRefreshTransportHasItsOwnRetryBudget() {
        val clock = Clock()
        var calls = 0
        val failure = BitcoinProviderException(BitcoinProviderFailure.RATE_LIMITED)
        val request: (String) -> String = {
            calls++
            throw failure
        }
        val policy = EsploraPacingPolicy(maxRetriesPerRequest = 1)
        val first = transport(clock, policy, request = request)
        assertFailsWith<BitcoinProviderException> { first.get("/blocks/tip/hash") }
        assertEquals(2, calls)
        assertFailsWith<BitcoinProviderException> { first.get("/blocks/tip/hash") }
        assertEquals(2, calls)
        val second = transport(clock, policy, request = request)
        assertFailsWith<BitcoinProviderException> { second.get("/blocks/tip/hash") }
        assertEquals(4, calls)
    }

    @Test
    fun cancellationOrConsentRevocationDuringWaitPreventsAnotherRequest() {
        for (rateLimited in listOf(false, true)) {
            val clock = Clock()
            var calls = 0
            val cancelled = BitcoinProviderException(BitcoinProviderFailure.CANCELLED)
            val paced =
                transport(clock, ensureActive = { if (clock.now >= 100) throw cancelled }) {
                    calls++
                    if (rateLimited) throw BitcoinProviderException(BitcoinProviderFailure.RATE_LIMITED)
                    "ok"
                }
            if (!rateLimited) assertEquals("ok", paced.get("/blocks/tip/hash"))
            assertSame(cancelled, assertFailsWith<BitcoinProviderException> { paced.get("/blocks/tip/hash") })
            assertEquals(1, calls)
            assertEquals(100L, clock.now)
        }
    }

    @Test
    fun preservesNonRateLimitFailuresAndMalformedResponsesWithoutRetry() {
        for (reason in BitcoinProviderFailure.entries.filter { it != BitcoinProviderFailure.RATE_LIMITED }) {
            val clock = Clock()
            val failure = BitcoinProviderException(reason)
            val paced = transport(clock) { throw failure }
            assertSame(failure, assertFailsWith<BitcoinProviderException> { paced.get("/blocks/tip/hash") })
            assertTrue(clock.sleeps.isEmpty())
        }
        val failure = IllegalArgumentException("invalid payload")
        val paced = transport(Clock()) { throw failure }
        assertSame(failure, assertFailsWith<IllegalArgumentException> { paced.get("/blocks/tip/hash") })
    }
}
