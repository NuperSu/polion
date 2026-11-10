package io.valroot.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.SharedPreferences
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import io.valroot.ui.BiometricUnlock
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Fingerprint unlock over the privacy lock. Each unlock proves a strong biometric by using a Keystore key that needs
 * one; enrolling a new fingerprint invalidates the key, which turns fingerprint unlock off until it is enabled again.
 * Main thread only.
 */
internal class BiometricLock(
    private val activity: Activity,
    private val preferences: SharedPreferences,
    private val alias: String = "io.valroot.biometric-unlock.v1",
) {
    /** True while the system prompt is showing, so the host does not lock behind it. */
    var prompting = false
        private set

    fun status(hasLock: Boolean): BiometricUnlock {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return BiometricUnlock.UNSUPPORTED
        val manager = activity.getSystemService(BiometricManager::class.java) ?: return BiometricUnlock.UNSUPPORTED
        // A status check must never take the app down; any platform failure means fingerprint unlock is unavailable.
        val result =
            runCatching { manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) }
                .getOrElse { return BiometricUnlock.UNSUPPORTED }
        return when (result) {
            BiometricManager.BIOMETRIC_SUCCESS ->
                if (hasLock && preferences.getBoolean(ENABLED, false)) BiometricUnlock.ON else BiometricUnlock.OFF
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricUnlock.NOT_ENROLLED
            else -> BiometricUnlock.UNSUPPORTED
        }
    }

    /** Creates a fresh key and asks for a fingerprint; [onResult] receives null on success or a message. */
    fun enable(onResult: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return onResult("Fingerprint unlock needs Android 11 or later")
        val cipher =
            runCatching {
                deleteKey()
                createKey()
                encryptingCipher()
            }.getOrElse { return onResult("Could not set up fingerprint unlock") }
        authenticate("Turn on fingerprint unlock", cipher) { error ->
            if (error == null) save(true)
            onResult(error)
        }
    }

    fun disable() {
        save(false)
        runCatching { deleteKey() }
    }

    /** [onResult] receives null when the fingerprint was accepted. */
    fun unlock(onResult: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return onResult("Fingerprint unlock needs Android 11 or later")
        val cipher =
            try {
                encryptingCipher()
            } catch (_: KeyPermanentlyInvalidatedException) {
                disable()
                return onResult("Fingerprints on this device changed. Unlock with your password, then turn fingerprint unlock on again.")
            } catch (_: Exception) {
                disable()
                return onResult("Fingerprint unlock is unavailable. Unlock with your password.")
            }
        authenticate("Unlock Valroot", cipher, onResult)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun authenticate(
        title: String,
        cipher: Cipher,
        result: (String?) -> Unit,
    ) {
        // The negative button and the error callback can both fire; report only the first outcome.
        var finished = false
        val onResult = { message: String? ->
            prompting = false
            if (!finished) {
                finished = true
                result(message)
            }
        }
        val prompt =
            BiometricPrompt
                .Builder(activity)
                .setTitle(title)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("Use password", activity.mainExecutor) { _, _ -> onResult("") }
                .build()
        prompting = true
        prompt.authenticate(
            BiometricPrompt.CryptoObject(cipher),
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    // Using the key is what proves the fingerprint; a forged callback without it fails here.
                    val proven = runCatching { result.cryptoObject?.cipher?.doFinal(byteArrayOf(1)) }.getOrNull() != null
                    onResult(if (proven) null else "Fingerprint could not be verified. Unlock with your password.")
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    val cancelled =
                        errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                            errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                    onResult(if (cancelled) "" else errString.toString())
                }
            },
        )
    }

    private fun encryptingCipher(): Cipher {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = checkNotNull(store.getKey(alias, null) as? SecretKey) { "Missing fingerprint key" }
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun createKey() {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec
                    .Builder(alias, KeyProperties.PURPOSE_ENCRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun deleteKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @SuppressLint("UseKtx") // The commit result confirms the choice was persisted.
    private fun save(enabled: Boolean) {
        check(preferences.edit().putBoolean(ENABLED, enabled).commit())
    }

    private companion object {
        const val ENABLED = "biometric_unlock"
    }
}
