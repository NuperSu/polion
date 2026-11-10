package io.valroot.core.bitcoin

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.Descriptor
import fr.acinq.bitcoin.DeterministicWallet
import io.valroot.core.model.isCanonicalUnsigned

/** A deliberately limited mainnet public wallet expression. */
class BitcoinWallet internal constructor(
    val canonical: String,
    private val type: String,
    private val key: String,
    private val paths: List<List<Int>>,
    val ranged: Boolean,
) {
    val branchCount: Int get() = paths.size

    fun address(
        branch: Int,
        index: Int,
    ): String {
        require(branch in paths.indices && index >= 0)
        if (type == "addr") {
            require(index == 0)
            return key
        }
        require(ranged || index == 0)
        return bitcoinPublicAddress(key, paths[branch] + if (ranged) listOf(index) else emptyList(), type)
    }
}

/** Bare xpubs are interpreted as native SegWit account keys with receive and change branches. */
fun bitcoinWallet(input: String): BitcoinWallet {
    require(input == input.trim() && input.isNotEmpty() && input.length <= 4096) { "Enter a public wallet expression" }
    val parts = input.split('#')
    require(parts.size <= 2 && (parts.size == 1 || descriptorChecksum(parts[0]) == parts[1])) { "Invalid descriptor checksum" }
    val body = parts[0]
    if (body.startsWith("xpub")) {
        require(parts.size == 1) { "A bare xpub cannot have a descriptor checksum" }
        bitcoinValidateXpub(body)
        return BitcoinWallet(body, "wpkh", body, listOf(listOf(0), listOf(1)), true)
    }
    if (body.startsWith("addr(") && body.endsWith(')')) {
        val address = bitcoinMainnetAddress(body.substring(5, body.length - 1))
        return BitcoinWallet("addr($address)", "addr", address, listOf(emptyList()), false)
    }
    val type: String
    val keyExpression: String
    when {
        body.startsWith("sh(wpkh(") && body.endsWith("))") -> {
            type = "sh-wpkh"
            keyExpression = body.substring(8, body.length - 2)
        }
        body.startsWith("wpkh(") && body.endsWith(')') -> {
            type = "wpkh"
            keyExpression = body.substring(5, body.length - 1)
        }
        body.startsWith("pkh(") && body.endsWith(')') -> {
            type = "pkh"
            keyExpression = body.substring(4, body.length - 1)
        }
        else -> throw IllegalArgumentException("Unsupported descriptor; use addr, pkh, wpkh or sh(wpkh)")
    }
    val expression =
        if (keyExpression.startsWith('[')) {
            val end = keyExpression.indexOf(']')
            require(end > 0 && keyExpression.substring(1, end).matches(Regex("[0-9a-fA-F]{8}(/[0-9]+[hH']?)*"))) {
                "Invalid key origin"
            }
            keyExpression.substring(end + 1)
        } else {
            keyExpression
        }
    val segments = expression.split('/')
    val key = segments.first()
    bitcoinValidateXpub(key)
    val suffix = segments.drop(1)
    require(suffix.size <= 255) { "Derivation path is too long" }
    val ranged = suffix.lastOrNull() == "*"
    val fixed = if (ranged) suffix.dropLast(1) else suffix
    val path =
        fixed.map {
            require(it.isCanonicalUnsigned()) { "Only unhardened numeric derivation and a final wildcard are supported" }
            it.toLongOrNull()?.takeIf { n -> n < 0x80000000L }?.toInt()
                ?: throw IllegalArgumentException("Derivation index is out of range")
        }
    return BitcoinWallet(body, type, key, listOf(path), ranged)
}

private val MAINNET = Block.LivenetGenesisBlock.hash

private fun decodeXpub(key: String): DeterministicWallet.ExtendedPublicKey {
    val (prefix, xpub) =
        try {
            DeterministicWallet.ExtendedPublicKey.decode(key)
        } catch (failure: Exception) {
            throw IllegalArgumentException("Invalid mainnet xpub")
        }
    require(prefix == DeterministicWallet.xpub && key.startsWith("xpub")) { "Only mainnet xpub public keys are supported" }
    return xpub
}

/** Checks that [key] is a valid mainnet xpub (version, checksum and public key). */
fun bitcoinValidateXpub(key: String) {
    decodeXpub(key)
}

/** The address of the key at [path] below [key], by bitcoin-kmp's BIP32 derivation and address encoding. */
fun bitcoinPublicAddress(
    key: String,
    path: List<Int>,
    type: String,
): String {
    require(path.all { it >= 0 })
    val publicKey =
        try {
            decodeXpub(key).derivePublicKey(path.map { it.toLong() }).publicKey
        } catch (failure: IllegalArgumentException) {
            throw failure
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid BIP32 child; use another index")
        }
    return when (type) {
        "pkh" -> Bitcoin.computeP2PkhAddress(publicKey, MAINNET)
        "sh-wpkh" -> Bitcoin.computeP2ShOfP2WpkhAddress(publicKey, MAINNET)
        "wpkh" -> Bitcoin.computeP2WpkhAddress(publicKey, MAINNET)
        else -> error("Unsupported address type")
    }
}

/** BIP-380 checksum for the descriptor body. */
fun descriptorChecksum(body: String): String {
    require(body.all { it.code in 32..126 }) { "Invalid descriptor character" }
    return try {
        Descriptor.checksum(body)
    } catch (_: Exception) {
        throw IllegalArgumentException("Invalid descriptor character")
    }
}
