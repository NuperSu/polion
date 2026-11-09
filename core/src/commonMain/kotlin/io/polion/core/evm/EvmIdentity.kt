package io.polion.core.evm

import io.polion.core.model.AssetId
import io.polion.core.model.ExactInteger
import io.polion.core.model.NetworkId
import org.kotlincrypto.hash.sha3.Keccak256

/** The EVM family: a tracked EVM address covers every chain it is found on. */
val EVM_FAMILY = NetworkId("eip155")

/** CAIP-2 network for one EVM chain, for example `eip155:8453` for Base. */
fun evmNetwork(chainId: Long): NetworkId {
    require(chainId > 0) { "Invalid chain ID" }
    return NetworkId("eip155:$chainId")
}

fun evmChainId(network: NetworkId): Long? =
    network.value
        .removePrefix("eip155:")
        .takeIf { network.value.startsWith("eip155:") }
        ?.toLongOrNull()
        ?.takeIf { it > 0 }

private val HEX40 = Regex("0x[0-9a-fA-F]{40}")

/**
 * A valid EVM address in lower case. An address in mixed case must carry a correct EIP-55 checksum, so a mistyped
 * character is caught; all-lower and all-upper addresses have no checksum to check.
 */
fun evmAddress(input: String): String {
    val value = input.trim()
    require(value.matches(HEX40)) { "Enter an address like 0x followed by 40 hexadecimal characters" }
    val body = value.drop(2)
    val mixed = body.any { it.isLowerCase() } && body.any { it.isUpperCase() }
    require(!mixed || eip55(body.lowercase()) == value) { "This address has a typo: its capital letters do not match its checksum" }
    return "0x" + body.lowercase()
}

/** The EIP-55 checksummed form of a lower-case address, for display. */
fun eip55(address: String): String {
    val body = address.removePrefix("0x").lowercase()
    require(body.length == 40 && body.all { it in '0'..'9' || it in 'a'..'f' })
    val hash = Keccak256().digest(body.encodeToByteArray())
    return "0x" +
        body
            .mapIndexed { index, char ->
                val nibble = (hash[index / 2].toInt() shr (if (index % 2 == 0) 4 else 0)) and 0xf
                if (char in 'a'..'f' && nibble >= 8) char.uppercaseChar() else char
            }.joinToString("")
}

/** Asset identities: one per chain, so the same token on two chains stays two assets linked to one coin. */
object EvmAssets {
    fun native(chainId: Long) = AssetId("eip155:$chainId/native")

    fun token(
        chainId: Long,
        contract: String,
    ) = AssetId("eip155:$chainId/erc20:${evmAddress(contract)}")

    fun nft(
        chainId: Long,
        contract: String,
        tokenId: ExactInteger,
    ): AssetId {
        require(tokenId.sign >= 0)
        return AssetId("eip155:$chainId/nft:${evmAddress(contract)}:$tokenId")
    }

    /**
     * Rebasing tokens: balances change without transfers, so the ledger follows the provider's balance. The value says
     * whether a growing balance is yield (a reward); Ampleforth's supply adjustments are not.
     */
    val REBASING: Map<AssetId, Boolean> =
        mapOf(
            // Lido stETH
            token(1, "0xae7ab96520de3a18e5e111b5eaab095312d7fe84") to true,
            // ether.fi eETH
            token(1, "0x35fa164735182de50811e8e2e824cfb9b6118ac2") to true,
            // Origin Dollar (OUSD)
            token(1, "0x2a8e1e676ec238d8a992307b495b45b3feaa5e86") to true,
            // Ampleforth (AMPL)
            token(1, "0xd46ba6d942050d489dbd938a2c909a5d5039a161") to false,
        )

    /** The chain an EVM asset ID belongs to, or null for other assets. */
    fun chainOf(asset: AssetId): Long? =
        asset.value
            .takeIf { it.startsWith("eip155:") }
            ?.substringAfter("eip155:")
            ?.substringBefore('/')
            ?.toLongOrNull()
}

/** EVM quantities arrive as decimal strings or `0x` hex; both become exact integers of any size (uint256 included). */
fun evmQuantity(text: String): ExactInteger {
    val value = text.trim()
    if (!value.startsWith("0x")) {
        require(value.isNotEmpty() && value.length <= 80 && value.all { it in '0'..'9' }) { "Invalid quantity" }
        return ExactInteger.parse(value.trimStart('0').ifEmpty { "0" })
    }
    val digits = value.drop(2)
    require(digits.isNotEmpty() && digits.length <= 64) { "Invalid quantity" }
    val sixteen = ExactInteger.parse("16")
    return digits.fold(ExactInteger.ZERO) { acc, c ->
        val nibble = c.digitToIntOrNull(16) ?: throw IllegalArgumentException("Invalid quantity")
        acc * sixteen + ExactInteger.parse(nibble.toString())
    }
}
