package io.valroot.core.exchange

/** HMAC-SHA256 of [message] under [key], as exchanges use to sign API requests. */
expect fun hmacSha256(
    key: ByteArray,
    message: ByteArray,
): ByteArray

/** Lowercase hexadecimal, as exchanges expect signatures. */
fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
