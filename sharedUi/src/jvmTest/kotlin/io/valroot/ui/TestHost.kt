package io.valroot.ui

import io.valroot.core.model.AssetId
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors

/** Fake desktop host for the UI tests. Counters and fields record what the UI asked the host to do. */
internal class TestHost :
    PlatformActions,
    AutoCloseable {
    override val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    override val dataRevision = MutableStateFlow(0L)
    override val syncStatus = MutableStateFlow(SyncStatus())
    override val priceRefreshStatus = MutableStateFlow(PriceRefreshPhase.IDLE)
    override val hasLock = false

    @Volatile var priceRequests = 0

    @Volatile var automaticPrices = false

    @Volatile var recoveryRequests = 0

    @Volatile var refreshRequests = 0

    @Volatile var exportedName: String? = null

    @Volatile var exported: ByteArray? = null

    override fun saveFile(
        name: String,
        bytes: ByteArray,
        onResult: (String) -> Unit,
    ) {
        exportedName = name
        exported = bytes
        onResult("Exported")
    }

    override fun saveBackup(
        password: String,
        onResult: (String) -> Unit,
    ) = onResult("Not used")

    override fun restoreBackup(
        password: String,
        onResult: (String) -> Unit,
    ) = onResult("Not used")

    override fun verifyAccountingOrder(onResult: (String) -> Unit) = onResult("Not used")

    override fun discoverWallet(
        walletId: io.valroot.core.model.TrackingTargetId,
        onResult: (String) -> Unit,
    ) = onResult("Not used")

    override fun fetchHistoricalPrice(
        asset: io.valroot.core.model.AssetId,
        quote: io.valroot.core.model.AssetId,
        dayUtcMillis: Long,
        apiKey: String,
        onResult: (String) -> Unit,
    ) {
        priceRequests++
        onResult("Daily historical price saved from CoinGecko.")
    }

    override fun fetchPriceHistory(
        asset: AssetId,
        quote: AssetId,
        days: Int,
        apiKey: String,
        onResult: (String) -> Unit,
    ) {
        priceRequests++
        onResult("Saved Bitcoin price history.")
    }

    override fun updateRefreshPreferences(preferences: RefreshPreferences) {
        automaticPrices = preferences.automaticRefresh
    }

    override fun refreshAutomaticPrices(onResult: (String) -> Unit) {
        priceRefreshStatus.value = PriceRefreshPhase.QUEUED
        onResult("Price refresh queued.")
    }

    override fun refresh(onResult: (String) -> Unit) {
        refreshRequests++
        onResult("Not used")
    }

    override fun recoverBitcoinHistory(onResult: (String) -> Unit) {
        recoveryRequests++
        onResult("Recovery started")
    }

    override fun setBackgroundRefresh(enabled: Boolean) = Unit

    override fun backgroundRefreshEnabled() = false

    override fun lock() = Unit

    override fun unlock(
        password: String,
        onResult: (UnlockResult) -> Unit,
    ) = onResult(UnlockResult(false))

    override fun setLockPassword(
        password: String,
        onResult: (String) -> Unit,
    ) = onResult("Not used")

    override fun removeLockPassword(onResult: (String) -> Unit) = onResult("Not used")

    override fun close() = dispatcher.close()
}
