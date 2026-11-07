package io.polion.ui

import io.polion.core.application.PortfolioApplication
import io.polion.core.model.AssetId
import io.polion.core.model.TrackingTargetId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class SyncPhase { IDLE, SYNCING, SUCCESS, ERROR }

enum class SyncOperation { HISTORY, DISCOVERY, ORDER, PRICES }

enum class PriceRefreshPhase { IDLE, QUEUED, RUNNING }

private val idlePriceRefresh = MutableStateFlow(PriceRefreshPhase.IDLE)

data class SyncStatus(
    val phase: SyncPhase = SyncPhase.IDLE,
    val message: String = "Not synced yet",
    val operation: SyncOperation = SyncOperation.HISTORY,
)

data class RefreshPreferences(
    val automaticRefresh: Boolean = false,
    val wifiOnly: Boolean = false,
)

data class RefreshSchedulePlan(
    val walletHistory: Boolean,
    val prices: Boolean,
    val wifiOnly: Boolean,
)

fun refreshSchedulePlan(
    preferences: RefreshPreferences,
    historyConsent: Boolean,
    priceConsent: Boolean,
) = RefreshSchedulePlan(
    preferences.automaticRefresh && historyConsent,
    preferences.automaticRefresh && priceConsent,
    preferences.wifiOnly,
)

const val WALLET_REFRESH_INTERVAL_MILLIS = 30L * 60_000L
const val PRICE_REFRESH_INTERVAL_MILLIS = 60L * 60_000L

/**
 * Delay before the next scheduled run. It is zero when there is no successful run or the last one is due, so a run
 * never repeats on app start within the interval after a success.
 */
fun cooldownDelayMillis(
    lastSuccessMillis: Long?,
    intervalMillis: Long,
    nowMillis: Long,
): Long {
    if (lastSuccessMillis == null) return 0L
    return (lastSuccessMillis + intervalMillis - nowMillis).coerceAtLeast(0L)
}

/**
 * The single automatic-refresh switch. Core consent and the schedule preference change in one transaction, then the host
 * reschedules prices and wallet history from them. Callers run this on the host dispatcher.
 */
internal fun applyAutomaticRefresh(
    app: PortfolioApplication,
    host: PlatformActions,
    enabled: Boolean,
) {
    app.setAutomaticRefresh(enabled)
    host.updateRefreshPreferences(RefreshPreferences(enabled, host.refreshPreferences().wifiOnly))
}

data class UnlockResult(
    val success: Boolean,
    val message: String? = null,
)

/** Local application work uses dispatcher; hosts run provider requests on a separate worker. */
interface PlatformActions {
    val dispatcher: CoroutineDispatcher
    val dataRevision: StateFlow<Long>
    val syncStatus: StateFlow<SyncStatus>
    val priceRefreshStatus: StateFlow<PriceRefreshPhase> get() = idlePriceRefresh
    val hasLock: Boolean

    /** Saves a small generated file, such as a CSV export, to a location the user chooses. */
    fun saveFile(
        name: String,
        bytes: ByteArray,
        onResult: (String) -> Unit,
    )

    /** Asks where to save, then streams an encrypted backup there in the background. */
    fun saveBackup(
        password: String,
        onResult: (String) -> Unit,
    )

    /** Asks for a backup file, then streams it into a staging database and replaces local data only if it is valid. */
    fun restoreBackup(
        password: String,
        onResult: (String) -> Unit,
    )

    fun verifyAccountingOrder(onResult: (String) -> Unit)

    fun discoverWallet(
        walletId: TrackingTargetId,
        onResult: (String) -> Unit,
    )

    fun fetchHistoricalPrice(
        asset: AssetId,
        quote: AssetId,
        dayUtcMillis: Long,
        apiKey: String,
        onResult: (String) -> Unit,
    )

    fun fetchPriceHistory(
        asset: AssetId,
        quote: AssetId,
        days: Int,
        apiKey: String,
        onResult: (String) -> Unit,
    ) {
        onResult("Price history is unavailable on this host.")
    }

    fun refresh(onResult: (String) -> Unit)

    fun recoverBitcoinHistory(onResult: (String) -> Unit)

    val wifiOnlyRefreshSupported: Boolean get() = false

    fun refreshPreferences(): RefreshPreferences = RefreshPreferences(backgroundRefreshEnabled())

    fun updateRefreshPreferences(preferences: RefreshPreferences) {
        setBackgroundRefresh(preferences.automaticRefresh)
    }

    fun setBackgroundRefresh(enabled: Boolean)

    fun refreshAutomaticPrices(onResult: (String) -> Unit) {
        onResult("Price refresh is unavailable on this host.")
    }

    fun backgroundRefreshEnabled(): Boolean

    fun lock()

    fun unlock(
        password: String,
        onResult: (UnlockResult) -> Unit,
    )

    fun setLockPassword(
        password: String,
        onResult: (String) -> Unit,
    )

    fun removeLockPassword(onResult: (String) -> Unit)

    /**
     * Recovery for a forgotten lock password or a lost lock key: erases this device's Polion data, turns automatic refresh
     * off and removes the lock. Data can come back only from an encrypted backup.
     */
    fun eraseAndRemoveLock(onResult: (String) -> Unit) {
        onResult("Erasing locked data is not available here.")
    }

    /** Shown in Settings when the user has not named this device. */
    val defaultDeviceName: String get() = "This device"

    /** Fingerprint unlock over the privacy lock; a device setting, never part of backups. Main thread. */
    fun biometricUnlock(): BiometricUnlock = BiometricUnlock.UNSUPPORTED

    /** Turning fingerprint unlock on asks for a fingerprint first. Main thread. */
    fun setBiometricUnlock(
        enabled: Boolean,
        onResult: (String) -> Unit,
    ) {
        onResult("Fingerprint unlock is not available here.")
    }

    fun unlockWithBiometrics(onResult: (UnlockResult) -> Unit) {
        onResult(UnlockResult(false, "Fingerprint unlock is not available here."))
    }
}

enum class BiometricUnlock {
    /** The host or hardware has no strong biometric unlock. */
    UNSUPPORTED,

    /** Supported, but the device has no enrolled fingerprint. */
    NOT_ENROLLED,
    OFF,
    ON,
}
