package io.polion.core.bitcoin

import java.security.MessageDigest

actual fun bitcoinDoubleSha256(bytes: ByteArray): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(digest.digest(bytes))
}
