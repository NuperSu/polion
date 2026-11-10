package io.valroot.core.bitcoin

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.Block

/**
 * Validates a Bitcoin mainnet address (Base58Check or SegWit, by bitcoin-kmp) and returns its canonical form: SegWit
 * addresses in lower case, Base58 addresses unchanged.
 */
fun bitcoinMainnetAddress(value: String): String {
    require(value.isNotEmpty() && value == value.trim() && value.length <= 90) { "Invalid Bitcoin address" }
    if (value.startsWith("bc1", true)) require(value == value.lowercase() || value == value.uppercase()) { "Mixed case Bitcoin address" }
    val chain = Block.LivenetGenesisBlock.hash
    val script = Bitcoin.addressToPublicKeyScript(chain, if (value.startsWith("bc1", true)) value.lowercase() else value)
    require(script.isRight) { if (value.startsWith("bc1", true)) "Invalid SegWit checksum" else "Invalid Bitcoin address" }
    val canonical = Bitcoin.addressFromPublicKeyScript(chain, script.right!!)
    require(canonical.isRight) { "Invalid Bitcoin address" }
    return canonical.right!!
}
