package io.polion.ui

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

    fun saveFile(
        name: String,
        bytes: ByteArray,
        onResult: (String) -> Unit,
    )

    fun openFile(onResult: (ByteArray?) -> Unit)

    fun restoreBackup(
        bytes: ByteArray,
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

    fun setBackgroundRefresh(enabled: Boolean)

    fun setAutomaticPriceRefresh(enabled: Boolean) {}

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
}
