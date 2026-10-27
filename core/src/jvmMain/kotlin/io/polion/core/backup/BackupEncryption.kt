package io.polion.core.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

actual object BackupEncryption {
    private const val ITERATIONS = 310_000
    private val magic = byteArrayOf(80, 66, 75, 1)

    private fun key(
        password: CharArray,
        salt: ByteArray,
    ): SecretKeySpec {
        require(password.isNotEmpty())
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    actual fun encrypt(
        plaintext: ByteArray,
        password: CharArray,
    ): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(16)
        val nonce = ByteArray(12)
        random.nextBytes(salt)
        random.nextBytes(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, nonce))
        val header = magic + salt + nonce
        cipher.updateAAD(header)
        return header + cipher.doFinal(plaintext)
    }

    actual fun decrypt(
        ciphertext: ByteArray,
        password: CharArray,
    ): ByteArray {
        require(ciphertext.size in 48..100_000_000 && ciphertext.copyOfRange(0, 4).contentEquals(magic))
        val header = ciphertext.copyOfRange(0, 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(password, ciphertext.copyOfRange(4, 20)),
            GCMParameterSpec(128, ciphertext.copyOfRange(20, 32)),
        )
        cipher.updateAAD(header)
        return cipher.doFinal(ciphertext, 32, ciphertext.size - 32)
    }
}
