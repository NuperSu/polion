package io.valroot.desktop

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import io.valroot.core.backup.LockPassword
import io.valroot.ui.UnlockResult
import kotlinx.coroutines.flow.update
import javax.swing.SwingUtilities

/** Switching windows is routine on desktop, so leaving locks after five minutes unless Settings says otherwise. */
const val DEFAULT_LOCK_DELAY_MILLIS = 300_000L

/**
 * The privacy lock: the password verifier, the locked state the UI shows, and the delay before a window that lost focus
 * locks. [reconcileRefreshPreferences] runs after an erase, which turns automatic refresh off.
 */
internal class DesktopLock(
    private val state: DesktopState,
    private val reconcileRefreshPreferences: () -> Unit,
) {
    private val lockStore = SecureLockStore(state.directory.resolve("lock-verifier"))
    val locked: MutableState<Boolean> = mutableStateOf(lockStore.hasLock)
    val hasLock: Boolean get() = lockStore.hasLock

    /** Lock delay in milliseconds; the host sets it from the saved setting once the database is open. */
    @Volatile var delayMillis = DEFAULT_LOCK_DELAY_MILLIS
    private var leftAt: Long? = null

    fun setLockDelay(millis: Long) {
        delayMillis = millis
        state.executor.execute {
            runCatching {
                state.app.value.settings
                    .setLockDelay(millis)
            }
        }
    }

    /** The window lost focus: lock now, or remember when, depending on the delay. */
    fun left() {
        if (delayMillis == 0L) lock() else leftAt = System.nanoTime() / 1_000_000L
    }

    /** The window regained focus: lock if it was away at least as long as the delay. */
    fun returned() {
        val away = leftAt?.let { System.nanoTime() / 1_000_000L - it }
        leftAt = null
        if (away != null && away >= delayMillis) lock()
    }

    fun lock() {
        if (lockStore.hasLock) locked.value = true
    }

    fun unlock(
        password: String,
        onResult: (UnlockResult) -> Unit,
    ) {
        state.unlockExecutor.execute {
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
            SwingUtilities.invokeLater {
                if (result.success) locked.value = false
                onResult(result)
            }
        }
    }

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
            SwingUtilities.invokeLater {
                onResult(
                    if (success) {
                        "Privacy lock enabled"
                    } else if (password.length <
                        8
                    ) {
                        "Use a password of at least 8 characters"
                    } else {
                        "Secret Service unavailable; privacy lock was not changed"
                    },
                )
            }
        }
    }

    fun removeLockPassword(onResult: (String) -> Unit) {
        if (locked.value) {
            onResult("Unlock first")
            return
        }
        state.executor.execute {
            val success = runCatching { lockStore.remove() }.isSuccess
            SwingUtilities.invokeLater {
                onResult(
                    if (success) "Privacy lock removed" else "Secret Service unavailable; privacy lock was not removed",
                )
            }
        }
    }

    fun eraseAndRemoveLock(onResult: (String) -> Unit) {
        state.executor.execute {
            val success =
                runCatching {
                    state.app.value.backup
                        .wipe()
                    state.app.value.prices
                        .setAutomaticRefresh(false)
                    reconcileRefreshPreferences()
                    lockStore.remove()
                }.isSuccess
            SwingUtilities.invokeLater {
                if (success) {
                    locked.value = false
                    state.dataRevision.update { it + 1 }
                }
                onResult(if (success) "Local data erased and privacy lock removed" else "Could not finish erasing; retry")
            }
        }
    }
}
