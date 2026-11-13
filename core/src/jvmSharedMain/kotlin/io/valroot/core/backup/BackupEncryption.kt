package io.valroot.core.backup

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Shared by the desktop and Android builds; see `docs/backup-format.md`. */
actual object BackupEncryption {
    private val magic = "VLRBK".encodeToByteArray()

    /** Backups made before the app was renamed from Polion; still decrypted. */
    private val legacyMagic = "PLNBK".encodeToByteArray()
    private const val VERSION: Byte = 3
    private const val KDF_PBKDF2_SHA256: Byte = 1
    private const val ITERATIONS = 600_000
    private const val HEADER = 5 + 1 + 1 + 4 + 16 + 7
    private const val CHUNK = 64 * 1024
    private const val TAG = 16

    private fun key(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
    ): SecretKeySpec {
        require(password.isNotEmpty())
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun nonce(
        prefix: ByteArray,
        counter: Int,
        last: Boolean,
    ): ByteArray = prefix + int(counter) + byteArrayOf(if (last) 1 else 0)

    private fun int(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private fun int(
        bytes: ByteArray,
        at: Int,
    ) = ((bytes[at].toInt() and 0xff) shl 24) or ((bytes[at + 1].toInt() and 0xff) shl 16) or
        ((bytes[at + 2].toInt() and 0xff) shl 8) or (bytes[at + 3].toInt() and 0xff)

    actual fun encrypt(
        output: ByteSink,
        password: CharArray,
    ): EncryptingSink {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val prefix = ByteArray(7).also(random::nextBytes)
        val header = magic + byteArrayOf(VERSION, KDF_PBKDF2_SHA256) + int(ITERATIONS) + salt + prefix
        check(header.size == HEADER)
        val key = key(password, salt, ITERATIONS)
        output.write(header)
        return object : EncryptingSink {
            private val buffer = ByteArray(CHUNK)
            private var filled = 0
            private var counter = 0
            private var closed = false

            private fun emit(last: Boolean) {
                check(counter != Int.MAX_VALUE) { "Backup is too large" }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG * 8, nonce(prefix, counter, last)))
                cipher.updateAAD(header)
                val sealed = cipher.doFinal(buffer, 0, filled)
                output.write(int(sealed.size))
                output.write(sealed)
                counter++
                filled = 0
            }

            override fun write(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ) {
                check(!closed)
                var at = offset
                val end = offset + length
                while (at < end) {
                    // A full chunk is sealed only once more data arrives, so the final chunk is always flagged as last.
                    if (filled == CHUNK) emit(last = false)
                    val count = minOf(CHUNK - filled, end - at)
                    bytes.copyInto(buffer, filled, at, at + count)
                    filled += count
                    at += count
                }
            }

            override fun close() {
                if (closed) return
                closed = true
                emit(last = true)
            }
        }
    }

    actual fun decrypt(
        input: ByteSource,
        password: CharArray,
    ): ByteSource {
        val header = ByteArray(HEADER)
        // A file shorter than the header is not a backup, rather than a damaged one.
        require(runCatching { input.readFully(header) }.getOrDefault(false)) { "This is not a Valroot backup" }
        require(
            header.copyOfRange(0, 5).let { it.contentEquals(magic) || it.contentEquals(legacyMagic) },
        ) { "This is not a Valroot backup" }
        require(header[5] == VERSION) { "This backup was made by an unsupported Valroot version" }
        require(header[6] == KDF_PBKDF2_SHA256) { "Unsupported backup key derivation" }
        val iterations = int(header, 7)
        require(iterations in 100_000..10_000_000) { "Unsupported backup key derivation" }
        val salt = header.copyOfRange(11, 27)
        val prefix = header.copyOfRange(27, 34)
        val key = key(password, salt, iterations)
        return object : ByteSource {
            private var plain = ByteArray(0)
            private var position = 0
            private var counter = 0
            private var finished = false
            private var nextLength: Int? = readLength()

            private fun readLength(): Int? {
                val bytes = ByteArray(4)
                if (!input.readFully(bytes)) return null
                return int(bytes, 0).also { require(it in TAG..CHUNK + TAG) { "Damaged backup" } }
            }

            private fun nextChunk() {
                val length = requireNotNull(nextLength) { "Backup ends unexpectedly" }
                val sealed = ByteArray(length)
                require(input.readFully(sealed)) { "Backup ends unexpectedly" }
                nextLength = readLength()
                val last = nextLength == null
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG * 8, nonce(prefix, counter, last)))
                cipher.updateAAD(header)
                plain =
                    try {
                        cipher.doFinal(sealed)
                    } catch (_: GeneralSecurityException) {
                        throw IllegalArgumentException(if (counter == 0) "Wrong password or damaged backup" else "Damaged backup")
                    }
                position = 0
                counter++
                finished = last
            }

            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                while (position == plain.size) {
                    if (finished) return -1
                    nextChunk()
                }
                val count = minOf(length, plain.size - position)
                plain.copyInto(buffer, offset, position, position + count)
                position += count
                return count
            }
        }
    }
}
