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
        val calls = mutableListOf<String>()
        val chain = anchorsChain(tip = 1200, canonicalFrom = 1050)
        val failure =
            assertFailsWith<BitcoinReorgBeyondBoundException> {
                BitcoinReorgDetector(
                    EsploraTransport { path ->
                        calls.add(path)
                        chain.get(path)
                    },
                    maxDepth = 100,
                ).check(anchors)
            }
        assertEquals(1200L, failure.tipHeight)
        assertEquals(1100L, failure.deepestStaleHeight)
        assertEquals(listOf("/blocks/tip/height", "/block-height/1100"), calls)
    }

    @Test
    fun oldAnchorsRemainUnverifiedWithoutClaimingACommonAncestor() {
        val anchors = listOf(BitcoinBlockAnchor(900, hashAt(900)), BitcoinBlockAnchor(1000, hashAt(1000)))
        val calls = mutableListOf<String>()
        val check =
            BitcoinReorgDetector(
                EsploraTransport { path ->
                    calls.add(path)
                    check(path == "/blocks/tip/height")
                    "1200"
                },
            ).check(anchors)
        assertNull(check.commonAncestor)
        assertEquals(listOf(1000L, 900L), check.unverifiedAnchors.map { it.height })
        assertEquals(false, check.reorganized)
        assertNull(check.rewindFromHeight)
        assertEquals(listOf("/blocks/tip/height"), calls)
    }

    @Test
    fun suppliedTipIsUsedWithoutRequestingAnotherProviderTip() {
        val anchor = BitcoinBlockAnchor(1000, hashAt(1000))
        val calls = mutableListOf<String>()
        val check =
            BitcoinReorgDetector(
                EsploraTransport { path ->
                    calls.add(path)
                    hashAt(1000)
                },
            ).check(listOf(anchor), tipHeight = 1100)
        assertEquals(anchor, check.commonAncestor)
        assertEquals(1100L, check.tipHeight)
        assertEquals(listOf("/block-height/1000"), calls)
    }

    @Test
    fun negativeSuppliedTipIsRejectedBeforeFetchingAnchors() {
        assertFailsWith<IllegalArgumentException> {
            BitcoinReorgDetector(EsploraTransport { error("Unexpected request") }).check(emptyList(), tipHeight = -1)
        }
    }

    @Test
    fun staleAnchorsWithoutAStoredCommonAncestorRemainExplicit() {
        val anchors = listOf(BitcoinBlockAnchor(1000, hashAt(1000)), BitcoinBlockAnchor(1010, hashAt(1010)))
        val check = BitcoinReorgDetector(anchorsChain(tip = 1020, canonicalFrom = 900)).check(anchors)
        assertNull(check.commonAncestor)
        assertEquals(listOf(1010L, 1000L), check.staleAnchors.map { it.height })
        assertEquals(1000L, check.rewindFromHeight)
        assertTrue(check.reorganized)
    }

    @Test
    fun anchorsAboveTipAreUnverifiedNotStale() {
        val anchors = listOf(BitcoinBlockAnchor(1000, hashAt(1000)), BitcoinBlockAnchor(1030, hashAt(1030)))
        val check = BitcoinReorgDetector(anchorsChain(tip = 1020, canonicalFrom = 2000)).check(anchors)
        assertEquals(listOf(1030L), check.aboveTip.map { it.height })
        assertEquals(false, check.reorganized)
    }

    @Test
    fun laggingProviderDoesNotFetchOrInvalidateHigherAnchors() {
        val anchors = listOf(BitcoinBlockAnchor(1030, hashAt(1030)), BitcoinBlockAnchor(1040, hashAt(1040)))
        val calls = mutableListOf<String>()
        val check =
            BitcoinReorgDetector(
                EsploraTransport { path ->
                    calls.add(path)
                    check(path == "/blocks/tip/height")
                    "1020"
                },
            ).check(anchors)
        assertEquals(listOf(1040L, 1030L), check.aboveTip.map { it.height })
        assertNull(check.commonAncestor)
        assertTrue(check.staleAnchors.isEmpty())
        assertTrue(check.unverifiedAnchors.isEmpty())
        assertNull(check.rewindFromHeight)
        assertEquals(listOf("/blocks/tip/height"), calls)
    }
}
