package io.polion.core.bitcoin

/** Validates a Bitcoin mainnet Base58Check or SegWit address and returns its canonical form. */
fun bitcoinMainnetAddress(value: String): String {
    require(value.isNotEmpty() && value == value.trim()) { "Invalid Bitcoin address" }
    if (value.startsWith("bc1", true)) {
        require(value == value.lowercase() || value == value.uppercase()) { "Mixed case Bitcoin address" }
        val address = value.lowercase()
        require(address.length in 14..90 && address.startsWith("bc1")) { "Invalid Bitcoin address" }
        val alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        val data = address.substring(3).map { alphabet.indexOf(it).also { n -> require(n >= 0) } }
        require(data.size >= 7)
        var check = 1
        val expanded = listOf(3, 3, 0, 2, 3) + data
        val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        for (v in expanded) {
            val top = check ushr 25
            check = ((check and 0x1ffffff) shl 5) xor v
            for (i in 0..4) if ((top ushr i) and 1 != 0) check = check xor generators[i]
        }
        val version = data.first()
        require(version in 0..16 && check == if (version == 0) 1 else 0x2bc830a3) { "Invalid SegWit checksum" }
        val program = data.drop(1).dropLast(6)
        var accumulator = 0
        var bits = 0
        var bytes = 0
        for (v in program) {
            accumulator = ((accumulator shl 5) or v) and 0xfff
            bits += 5
            if (bits >= 8) {
                bits -= 8
                bytes++
            }
        }
        require(bits < 5 && (accumulator shl (8 - bits) and 0xff) == 0 && bytes in 2..40)
        require(version != 0 || bytes == 20 || bytes == 32)
        return address
    }
    val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    require(value.length in 26..35 && value.all { it in alphabet }) { "Invalid Bitcoin address" }
    val bytes = mutableListOf(0)
    for (character in value) {
        var carry = alphabet.indexOf(character)
        for (i in bytes.indices) {
            carry += bytes[i] * 58
            bytes[i] = carry and 255
            carry = carry ushr 8
        }
        while (carry > 0) {
            bytes.add(carry and 255)
            carry = carry ushr 8
        }
    }
    val decoded = List(value.takeWhile { it == '1' }.length) { 0 } + bytes.asReversed().dropWhile { it == 0 }
    require(decoded.size == 25 && decoded[0] in listOf(0, 5)) { "Not a mainnet address" }
    val body = decoded.take(21).map(Int::toByte).toByteArray()
    // sha256 accepts UTF-8 text, so Base58Check hashing is provided by the platform byte primitive.
    val checksum = bitcoinDoubleSha256(body)
    require(decoded.takeLast(4) == checksum.take(4).map { it.toInt() and 255 }) { "Invalid address checksum" }
    return value
}

expect fun bitcoinDoubleSha256(bytes: ByteArray): ByteArray
