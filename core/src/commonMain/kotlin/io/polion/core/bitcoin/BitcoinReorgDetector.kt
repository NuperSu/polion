package io.polion.core.bitcoin

/** Default maximum depth, in blocks below the tip, that an automatic rewind may reach. */
const val BITCOIN_MAX_REWIND_DEPTH = 100L

/**
 * Result of checking stored block anchors against the provider's active chain.
 * [rewindFromHeight] is the lowest detected stale height; a bounded automatic rewind also requires [commonAncestor].
 * [aboveTip] anchors are higher than the provider's tip, as with a lagging provider; they are unverified, not stale.
 * [unverifiedAnchors] are below the automatic check bound and have not been fetched. Their hashes are not evidence
 * of a common ancestor. [commonAncestor] is set only after a provider hash matches a stored anchor.
 */
data class BitcoinReorgCheck(
    val tipHeight: Long,
    val commonAncestor: BitcoinBlockAnchor?,
    val staleAnchors: List<BitcoinBlockAnchor>,
    val aboveTip: List<BitcoinBlockAnchor> = emptyList(),
    val unverifiedAnchors: List<BitcoinBlockAnchor> = emptyList(),
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
 * Finds the highest stored anchor that is still canonical. Anchors are checked from the highest down, stopping at
 * the first canonical one: lower anchors are then canonical by chain linkage. Anchors deeper than [maxDepth] below
 * the tip are never fetched; reaching one after detecting stale anchors throws [BitcoinReorgBeyondBoundException].
 * With no detected stale anchors, the unchecked anchors are returned as [BitcoinReorgCheck.unverifiedAnchors].
 */
class BitcoinReorgDetector(
    private val transport: EsploraTransport,
    private val maxDepth: Long = BITCOIN_MAX_REWIND_DEPTH,
) {
    init {
        require(maxDepth >= 0)
    }

    /** Uses a caller-validated [tipHeight] when supplied, otherwise requests the provider's current height. */
    fun check(
        anchors: Collection<BitcoinBlockAnchor>,
        tipHeight: Long? = null,
    ): BitcoinReorgCheck {
        require(anchors.map { it.height }.distinct().size == anchors.distinct().size) { "Conflicting anchors at one height" }
        val checkedTipHeight =
            tipHeight ?: run {
                val tip = transport.get("/blocks/tip/height").trim()
                require(tip.matches(Regex("0|[1-9][0-9]{0,18}"))) { "Invalid tip height" }
                tip.toLong()
            }
        require(checkedTipHeight >= 0) { "Invalid tip height" }
        val stale = mutableListOf<BitcoinBlockAnchor>()
        val sorted = anchors.distinct().sortedByDescending { it.height }
        val above = sorted.filter { it.height > checkedTipHeight }
        val eligible = sorted.drop(above.size)
        for ((index, anchor) in eligible.withIndex()) {
            if (checkedTipHeight - anchor.height > maxDepth) {
                if (stale.isEmpty()) return BitcoinReorgCheck(checkedTipHeight, null, stale, above, eligible.drop(index))
                throw BitcoinReorgBeyondBoundException(checkedTipHeight, stale.last().height, maxDepth)
            }
            val hash = transport.get("/block-height/${anchor.height}").trim()
            require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid block hash" }
            if (hash == anchor.hash) return BitcoinReorgCheck(checkedTipHeight, anchor, stale, above)
            stale.add(anchor)
        }
        return BitcoinReorgCheck(checkedTipHeight, null, stale, above)
    }
}
