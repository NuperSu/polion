package io.polion.core.bitcoin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BitcoinFailoverAndReorgTest {
    private val hashAt = { height: Long -> height.toString(16).padStart(64, 'f') }

    private fun endpoint(
        id: String,
        vararg failures: BitcoinProviderFailure,
        body: String = "ok",
    ): Pair<EsploraEndpoint, MutableList<String>> {
        val calls = mutableListOf<String>()
        val queue = failures.toMutableList()
        val transport =
            EsploraTransport { path ->
                calls.add(path)
                if (queue.isNotEmpty()) throw BitcoinProviderException(queue.removeAt(0))
                body
            }
        return EsploraEndpoint(id, transport) to calls
    }

    @Test
    fun failsOverOnAvailabilityFailureAndReportsServingEndpoint() {
        val (primary, primaryCalls) = endpoint("primary", BitcoinProviderFailure.TIMEOUT, BitcoinProviderFailure.TIMEOUT)
        val (secondary, secondaryCalls) = endpoint("secondary", body = "tip")
        val failovers = mutableListOf<Pair<String, BitcoinProviderFailure>>()
        val transport = FailoverEsploraTransport(listOf(primary, secondary)) { id, reason -> failovers.add(id to reason) }
        val response = transport.fetch("/blocks/tip/height")
        assertEquals("secondary", response.endpointId)
        assertEquals("tip", response.body)
        assertEquals(listOf("primary" to BitcoinProviderFailure.TIMEOUT), failovers)
        assertEquals(1, primaryCalls.size)
        assertEquals(1, secondaryCalls.size)
        assertEquals(setOf("secondary"), transport.servedBy)
        assertEquals("secondary", transport.lastServedBy)
        transport.fetch("/blocks/tip/height")
        assertEquals(2, secondaryCalls.size)
        assertEquals(1, primaryCalls.size)
    }

    @Test
    fun contentFailuresDoNotFailOver() {
        val (primary, _) = endpoint("primary", BitcoinProviderFailure.HISTORY_CHANGED)
        val (secondary, secondaryCalls) = endpoint("secondary")
        val transport = FailoverEsploraTransport(listOf(primary, secondary))
        assertFailsWith<BitcoinProviderException> { transport.get("/address/x/txs") }
        assertTrue(secondaryCalls.isEmpty())
    }

    @Test
    fun exhaustedEndpointsReportLastAvailabilityFailure() {
        val (primary, _) = endpoint("primary", BitcoinProviderFailure.OFFLINE)
        val (secondary, _) = endpoint("secondary", BitcoinProviderFailure.RATE_LIMITED)
        val transport = FailoverEsploraTransport(listOf(primary, secondary))
        val failure = assertFailsWith<BitcoinProviderException> { transport.get("/blocks/tip/hash") }
        assertEquals(BitcoinProviderFailure.RATE_LIMITED, failure.reason)
        assertNull(transport.lastServedBy)
    }

    @Test
    fun resetPrefersFirstEndpointAgain() {
        val (primary, primaryCalls) = endpoint("primary", BitcoinProviderFailure.OFFLINE)
        val (secondary, _) = endpoint("secondary")
        val transport = FailoverEsploraTransport(listOf(primary, secondary))
        transport.get("/a")
        transport.reset()
        transport.get("/b")
        assertEquals(listOf("/a", "/b"), primaryCalls)
        assertEquals(setOf("primary"), transport.servedBy)
    }

    @Test
    fun duplicateEndpointIdsAreRejected() {
        val (first, _) = endpoint("same")
        val (second, _) = endpoint("same")
        assertFailsWith<IllegalArgumentException> { FailoverEsploraTransport(listOf(first, second)) }
    }

    private fun anchorsChain(
        tip: Long,
        canonicalFrom: Long,
    ): EsploraTransport =
        EsploraTransport { path ->
            when {
                path == "/blocks/tip/height" -> tip.toString()
                path.startsWith("/block-height/") -> {
                    val height = path.removePrefix("/block-height/").toLong()
                    if (height <= canonicalFrom) hashAt(height) else hashAt(height).reversed()
                }
                else -> error("unexpected $path")
            }
        }

    @Test
    fun withinBoundFindsDeepestCanonicalAnchorAndRewindHeight() {
        val anchors =
            listOf(BitcoinBlockAnchor(1000, hashAt(1000)), BitcoinBlockAnchor(1005, hashAt(1005)), BitcoinBlockAnchor(1010, hashAt(1010)))
        val check = BitcoinReorgDetector(anchorsChain(tip = 1020, canonicalFrom = 1002)).check(anchors)
        assertEquals(1000L, check.commonAncestor?.height)
        assertTrue(check.reorganized)
        assertEquals(1005L, check.rewindFromHeight)
        assertEquals(listOf(1005L, 1010L), check.staleAnchors.map { it.height }.sorted())
    }

    @Test
    fun unchangedChainReportsNoRewind() {
        val anchors = listOf(BitcoinBlockAnchor(1000, hashAt(1000)), BitcoinBlockAnchor(1010, hashAt(1010)))
        val check = BitcoinReorgDetector(anchorsChain(tip = 1020, canonicalFrom = 2000)).check(anchors)
        assertEquals(1010L, check.commonAncestor?.height)
        assertEquals(false, check.reorganized)
        assertNull(check.rewindFromHeight)
    }

    @Test
    fun reorgBeyondBoundThrowsInsteadOfFetchingDeeperHistory() {
        val anchors = listOf(BitcoinBlockAnchor(1000, hashAt(1000)), BitcoinBlockAnchor(1100, hashAt(1100)))
        val failure =
            assertFailsWith<BitcoinReorgBeyondBoundException> {
                BitcoinReorgDetector(anchorsChain(tip = 1200, canonicalFrom = 1050), maxDepth = 100).check(anchors)
            }
        assertEquals(1200L, failure.tipHeight)
        assertEquals(1000L, failure.deepestStaleHeight)
    }

    @Test
    fun anchorsAboveTipAreUnverifiedNotStale() {
        val anchors = listOf(BitcoinBlockAnchor(1000, hashAt(1000)), BitcoinBlockAnchor(1030, hashAt(1030)))
        val check = BitcoinReorgDetector(anchorsChain(tip = 1020, canonicalFrom = 2000)).check(anchors)
        assertEquals(listOf(1030L), check.aboveTip.map { it.height })
        assertEquals(false, check.reorganized)
    }
}
