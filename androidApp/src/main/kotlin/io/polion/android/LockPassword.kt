package io.polion.android

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** A password verifier for the privacy screen; it does not encrypt the database. */
object LockPassword {
    fun create(password: String): String {
        require(password.length >= 8)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(derive(password, salt))
    }

    fun verify(
        password: String,
        verifier: String,
    ): Boolean =
        runCatching {
            val fields = verifier.split(':')
            require(fields.size == 2)
            val salt = Base64.getDecoder().decode(fields[0])
            val expected = Base64.getDecoder().decode(fields[1])
            require(salt.size == 16 && expected.size == 32)
            MessageDigest.isEqual(expected, derive(password, salt))
        }.getOrDefault(false)

    private fun derive(
        password: String,
        salt: ByteArray,
    ): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, 210_000, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
