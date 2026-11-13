package io.valroot.android

import android.annotation.SuppressLint
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import io.valroot.core.backup.LockPassword
import io.valroot.ui.BiometricUnlock
import io.valroot.ui.UnlockResult
import kotlinx.coroutines.flow.update

/**
 * The privacy lock: the password verifier, fingerprint unlock, the lock delay and the moments the app locks. Verifier
 * reads and writes run on [AndroidState.executor]; state changes return to the main thread.
 */
internal class AndroidLock(
    private val state: AndroidState,
    private val sync: AndroidSync,
) {
    private val lockStore = SecureLockStore(state.preferences)
    private val biometrics = BiometricLock(state.activity, state.preferences)
    private var leftAt: Long? = null

    val locked: MutableState<Boolean> = mutableStateOf(lockStore.hasLock)

    /** Kept in memory so leaving and returning to the app never waits on the database. */
    @Volatile var delayMillis = 0L

    val hasLock: Boolean get() = lockStore.hasLock

    /** True while the fingerprint prompt is showing, so the app does not lock behind it. */
    val isPrompting: Boolean get() = biometrics.prompting

    fun biometricUnlock(): BiometricUnlock = biometrics.status(lockStore.hasLock)

    fun setBiometricUnlock(
        enabled: Boolean,
        onResult: (String) -> Unit,
    ) {
        if (!enabled) {
            biometrics.disable()
            onResult("Fingerprint unlock off")
        } else if (!lockStore.hasLock || locked.value) {
            onResult("Turn on Passcode Lock first")
        } else {
            biometrics.enable { error ->
                onResult(if (error == null) "Fingerprint unlock on" else error.ifEmpty { "Fingerprint unlock not changed" })
            }
        }
    }

    fun unlockWithBiometrics(onResult: (UnlockResult) -> Unit) {
        if (biometricUnlock() != BiometricUnlock.ON) {
            onResult(UnlockResult(false, "Fingerprint unlock is off. Unlock with your password."))
            return
        }
        biometrics.unlock { error ->
            if (error == null) locked.value = false
            // An empty message means the user chose the password instead; nothing to report.
            onResult(UnlockResult(error == null, error?.ifEmpty { null }))
        }
    }

    fun setLockDelay(millis: Long) {
        delayMillis = millis
        state.executor.execute { runCatching { state.app.value?.settings?.setLockDelay(millis) } }
    }

    /** The app went to the background: lock now, or remember when, depending on the delay. */
    fun left() {
        if (delayMillis == 0L) lock() else leftAt = android.os.SystemClock.elapsedRealtime()
    }

    /** The app came back: lock if it was away at least as long as the delay. */
    fun returned() {
        val away = leftAt?.let { android.os.SystemClock.elapsedRealtime() - it }
        leftAt = null
        if (away != null && away >= delayMillis) lock()
    }

    fun lock() {
        if (lockStore.hasLock) locked.value = true
    }

    fun removeLockPassword(onResult: (String) -> Unit) {
        if (locked.value) {
            onResult("Unlock first")
            return
        }
        val success = runCatching { lockStore.remove() }.isSuccess
        if (success) biometrics.disable()
        onResult(if (success) "Privacy lock removed" else "Could not remove privacy lock")
    }

    /**
     * Recovery for a forgotten lock password or a lost lock key: erases this device's Valroot data, turns automatic refresh
     * off and removes the lock.
     */
    fun eraseAndRemoveLock(onResult: (String) -> Unit) {
        state.executor.execute {
            val success =
                runCatching {
                    state.app.value?.let { application ->
                        application.backup.wipe()
                        application.prices.setAutomaticRefresh(false)
                        sync.reconcileRefreshPreferences(application)
                    }
                    lockStore.remove()
                }.isSuccess
            state.main.post {
                if (success) {
                    biometrics.disable()
                    locked.value = false
                    state.dataRevision.update { it + 1 }
                }
                onResult(if (success) "Local data erased and privacy lock removed" else "Could not finish erasing; retry")
            }
        }
    }

    fun unlock(
        password: String,
        onResult: (UnlockResult) -> Unit,
    ) {
        state.executor.execute {
            val result =
                runCatching {
                    val verifier = lockStore.read()
                    if (LockPassword.verify(password, verifier)) {
                        lockStore.migrate(verifier)
                        UnlockResult(true)
                    } else {
                        UnlockResult(false, "Incorrect password")
                    }
                }.getOrElse { UnlockResult(false, "Secure privacy lock storage is unavailable or damaged") }
            state.main.post {
                if (result.success) locked.value = false
                onResult(result)
            }
        }
    }

    @SuppressLint("UseKtx") // The commit result is needed to report persistence failure.
    fun setLockPassword(
        password: String,
        onResult: (String) -> Unit,
    ) {
        state.executor.execute {
            val success =
                runCatching {
                    check(!lockStore.hasLock)
                    lockStore.write(LockPassword.create(password))
                }.isSuccess
            state.main.post {
                onResult(
                    if (success) {
                        "Privacy lock enabled"
                    } else if (password.length < 8) {
                        "Use a password of at least 8 characters"
                    } else {
                        "Secure storage unavailable; privacy lock was not changed"
                    },
                )
            }
        }
    }
}
