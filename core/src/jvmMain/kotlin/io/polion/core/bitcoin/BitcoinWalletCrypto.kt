package io.polion.core.bitcoin

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.RIPEMD160Digest
import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
private val curve = SECNamedCurves.getByName("secp256k1")

private fun decodeXpub(value: String): ByteArray {
    require(
        value.length == 111 && value.startsWith("xpub") && value.all { it in alphabet },
    ) { "Only mainnet xpub public keys are supported" }
    var number = BigInteger.ZERO
    value.forEach { number = number.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(alphabet.indexOf(it).toLong())) }
    val encoded = number.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
    val bytes = ByteArray(value.takeWhile { it == '1' }.length) + encoded
    require(bytes.size == 82 && bytes.sliceArray(0..3).contentEquals(byteArrayOf(0x04, 0x88.toByte(), 0xb2.toByte(), 0x1e))) {
        "Invalid mainnet xpub"
    }
    require(bitcoinDoubleSha256(bytes.copyOfRange(0, 78)).copyOfRange(0, 4).contentEquals(bytes.copyOfRange(78, 82))) {
        "Invalid xpub checksum"
    }
    val key = bytes.copyOfRange(45, 78)
    require(key[0].toInt() in 2..3) { "Invalid public key" }
    val point =
        try {
            curve.curve.decodePoint(key)
        } catch (
            _: IllegalArgumentException,
        ) {
            throw IllegalArgumentException("Invalid public key")
        }
    require(!point.isInfinity && point.isValid) { "Invalid public key" }
    return bytes
}

actual fun bitcoinValidateXpub(key: String) {
    decodeXpub(key)
}

actual fun bitcoinPublicAddress(
    key: String,
    path: List<Int>,
    type: String,
): String {
    val data = decodeXpub(key)
    var point = curve.curve.decodePoint(data.copyOfRange(45, 78))
    var chain = data.copyOfRange(13, 45)
    for (index in path) {
        require(index >= 0)
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(chain, "HmacSHA512"))
        val i = byteArrayOf((index ushr 24).toByte(), (index ushr 16).toByte(), (index ushr 8).toByte(), index.toByte())
        val digest = mac.doFinal(point.getEncoded(true) + i)
        val scalar = BigInteger(1, digest.copyOfRange(0, 32))
        require(scalar < curve.n) { "Invalid BIP32 child; use another index" }
        point =
            curve.g
                .multiply(scalar)
                .add(point)
                .normalize()
        require(!point.isInfinity) { "Invalid BIP32 child; use another index" }
        chain = digest.copyOfRange(32, 64)
    }
    val hash = hash160(point.getEncoded(true))
    return when (type) {
        "pkh" -> base58Check(byteArrayOf(0) + hash)
        "sh-wpkh" -> base58Check(byteArrayOf(5) + hash160(byteArrayOf(0, 20) + hash))
        "wpkh" -> segwit(hash)
        else -> error("Unsupported address type")
    }
}

private fun hash160(bytes: ByteArray): ByteArray {
    val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
    val digest = RIPEMD160Digest()
    digest.update(sha, 0, sha.size)
    return ByteArray(20).also { digest.doFinal(it, 0) }
}

private fun base58Check(body: ByteArray): String {
    val bytes = body + bitcoinDoubleSha256(body).copyOfRange(0, 4)
    var value = BigInteger(1, bytes)
    val base = BigInteger.valueOf(58)
    val out = StringBuilder()
    while (value.signum() != 0) {
        val pair = value.divideAndRemainder(base)
        out.append(alphabet[pair[1].toInt()])
        value = pair[0]
    }
    repeat(bytes.takeWhile { it == 0.toByte() }.size) { out.append('1') }
    return out.reverse().toString()
}

private fun segwit(program: ByteArray): String {
    val alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    val values = mutableListOf(0)
    var acc = 0
    var bits = 0
    program.forEach {
        acc = ((acc shl 8) or (it.toInt() and 255)) and 4095
        bits += 8
        while (bits >= 5) {
            bits -= 5
            values.add((acc shr bits) and 31)
        }
    }
    if (bits > 0) values.add((acc shl (5 - bits)) and 31)
    var check = 1
    val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
    (listOf(3, 3, 0, 2, 3) + values + List(6) { 0 }).forEach { value ->
        val top = check ushr 25
        check = ((check and 0x1ffffff) shl 5) xor value
        for (j in 0..4) if ((top ushr j) and 1 != 0) check = check xor generators[j]
    }
    check = check xor 1
    return "bc1" + values.joinToString("") { alphabet[it].toString() } +
        (0..5).joinToString("") { alphabet[(check ushr (5 * (5 - it))) and 31].toString() }
}
