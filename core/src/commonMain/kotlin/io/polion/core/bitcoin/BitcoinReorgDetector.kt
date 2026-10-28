package io.polion.core.bitcoin

/** Default maximum depth, in blocks below the tip, that an automatic rewind may reach. */
const val BITCOIN_MAX_REWIND_DEPTH = 100L

/**
 * Result of checking stored block anchors against the provider's active chain.
 * [rewindFromHeight] is the lowest height whose stored data must be discarded and rescanned; null means none.
 * [aboveTip] anchors are higher than the provider's tip, as with a lagging provider; they are unverified, not stale.
 */
data class BitcoinReorgCheck(
    val tipHeight: Long,
    val commonAncestor: BitcoinBlockAnchor?,
    val staleAnchors: List<BitcoinBlockAnchor>,
    val aboveTip: List<BitcoinBlockAnchor> = emptyList(),
) {
    val reorganized: Boolean get() = staleAnchors.isNotEmpty()
    val rewindFromHeight: Long? get() = staleAnchors.minOfOrNull { it.height }
}

class BitcoinReorgBeyondBoundException(
    val tipHeight: Long,
    val deepestStaleHeight: Long,
    val maxDepth: Long,
) : IllegalStateException("Chain reorganization exceeds the automatic rewind bound")

/**
 * Finds the deepest stored anchor that is still canonical. Anchors are checked from the highest down, stopping at
 * the first canonical one: lower anchors are then canonical by chain linkage. Anchors deeper than [maxDepth] below
 * the tip are never fetched; reaching one while every higher anchor is stale throws [BitcoinReorgBeyondBoundException].
 */
class BitcoinReorgDetector(
    private val transport: EsploraTransport,
    private val maxDepth: Long = BITCOIN_MAX_REWIND_DEPTH,
) {
    init {
        require(maxDepth >= 0)
    }

    fun check(anchors: Collection<BitcoinBlockAnchor>): BitcoinReorgCheck {
        require(anchors.map { it.height }.distinct().size == anchors.distinct().size) { "Conflicting anchors at one height" }
        val tip = transport.get("/blocks/tip/height").trim()
        require(tip.matches(Regex("0|[1-9][0-9]{0,18}"))) { "Invalid tip height" }
        val tipHeight = tip.toLong()
        val stale = mutableListOf<BitcoinBlockAnchor>()
        val sorted = anchors.distinct().sortedByDescending { it.height }
        val above = sorted.filter { it.height > tipHeight }
        for (anchor in sorted.drop(above.size)) {
            if (tipHeight - anchor.height > maxDepth) {
                if (stale.isEmpty()) return BitcoinReorgCheck(tipHeight, anchor, stale, above)
                throw BitcoinReorgBeyondBoundException(tipHeight, anchor.height, maxDepth)
            }
            val hash = transport.get("/block-height/${anchor.height}").trim()
            require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid block hash" }
            if (hash == anchor.hash) return BitcoinReorgCheck(tipHeight, anchor, stale, above)
            stale.add(anchor)
        }
        return BitcoinReorgCheck(tipHeight, null, stale, above)
    }
}
