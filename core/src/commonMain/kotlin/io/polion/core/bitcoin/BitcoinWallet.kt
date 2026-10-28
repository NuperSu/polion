package io.polion.core.bitcoin

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
            require(it.matches(Regex("0|[1-9][0-9]*"))) { "Only unhardened numeric derivation and a final wildcard are supported" }
            it.toLongOrNull()?.takeIf { n -> n < 0x80000000L }?.toInt()
                ?: throw IllegalArgumentException("Derivation index is out of range")
        }
    return BitcoinWallet(body, type, key, listOf(path), ranged)
}

expect fun bitcoinValidateXpub(key: String)

expect fun bitcoinPublicAddress(
    key: String,
    path: List<Int>,
    type: String,
): String

/** BIP-380 checksum for the descriptor body. */
fun descriptorChecksum(body: String): String {
    val input = "0123456789()[],'/*abcdefgh@:\$%{}IJKLMNOPQRSTUVWXYZ&+-.;<=>?!^_|~ijklmnopqrstuvwxyzABCDEFGH`#\"\\ "
    val output = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    val generators = longArrayOf(0xf5dee51989, 0xa9fdca3312, 0x1bab10e32d, 0x3706b1677a, 0x644d626ffd)
    var check = 1L

    fun update(value: Int) {
        val top = check shr 35
        check = ((check and 0x7ffffffff) shl 5) xor value.toLong()
        for (i in 0..4) if ((top shr i) and 1L != 0L) check = check xor generators[i]
    }
    var group = 0
    var count = 0
    body.forEach {
        val value = input.indexOf(it)
        require(value >= 0) { "Invalid descriptor character" }
        update(value and 31)
        group = group * 3 + (value shr 5)
        if (++count == 3) {
            update(group)
            group = 0
            count = 0
        }
    }
    if (count > 0) update(group)
    repeat(8) { update(0) }
    check = check xor 1
    return (0..7).map { output[((check shr (5 * (7 - it))) and 31).toInt()] }.joinToString("")
}
