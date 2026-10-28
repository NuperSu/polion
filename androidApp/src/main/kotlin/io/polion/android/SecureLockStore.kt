package io.polion.android

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the verifier under an Android Keystore key; the portfolio database remains plaintext. */
internal class SecureLockStore(
    private val preferences: SharedPreferences,
    private val alias: String = "io.polion.privacy-lock.v1",
) {
    val hasLock: Boolean get() = preferences.contains(SECURE) || preferences.contains(LEGACY)

    fun read(): String {
        val encoded = preferences.getString(SECURE, null)
        if (encoded == null) return preferences.getString(LEGACY, null) ?: error("Missing privacy lock")
        val data = Base64.getDecoder().decode(encoded)
        require(data.size > 12 + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return cipher.doFinal(data.copyOfRange(12, data.size)).toString(Charsets.UTF_8)
    }

    @SuppressLint("UseKtx") // The commit result confirms the verifier was persisted.
    fun write(verifier: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        val encoded = Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(verifier.toByteArray(Charsets.UTF_8)))
        check(
            preferences
                .edit()
                .putString(SECURE, encoded)
                .remove(LEGACY)
                .commit(),
        )
    }

    fun migrate(verifier: String) {
        if (!preferences.contains(SECURE)) write(verifier)
    }

    @SuppressLint("UseKtx") // The commit result confirms the verifier was removed.
    fun remove() {
        check(
            preferences
                .edit()
                .remove(SECURE)
                .remove(LEGACY)
                .commit(),
        )
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create && !preferences.contains(SECURE)) { "Privacy lock key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec
                    .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        private const val SECURE = "secure_verifier_v1"
        private const val LEGACY = "verifier"
    }
}
