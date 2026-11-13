package io.valroot.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.bitcoin.BlockstreamTransport
import io.valroot.core.model.AssetId
import io.valroot.core.model.TrackingTargetId
import io.valroot.core.storage.AndroidSqliteDatabase
import io.valroot.ui.BiometricUnlock
import io.valroot.ui.OrderBookResult
import io.valroot.ui.PlatformActions
import io.valroot.ui.RefreshPreferences
import io.valroot.ui.UnlockResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.nio.file.Files

/**
 * The Android [PlatformActions]. It builds the shared [AndroidState] and the helpers that own each area, opens the
 * database on the background executor, and forwards every action to the helper that owns it.
 */
internal class AndroidHost(
    private val activity: ComponentActivity,
) : PlatformActions {
    private val state = AndroidState(activity)
    private val prices = AndroidPrices(state)
    private val sync = AndroidSync(state, prices)
    private val backups = AndroidBackups(state, sync)
    private val privacyLock = AndroidLock(state, sync)

    override val dispatcher: CoroutineDispatcher = state.executor.asCoroutineDispatcher()
    override val dataRevision = state.dataRevision
    override val syncStatus = state.syncStatus
    override val priceRefreshStatus = state.priceRefreshStatus
    override val hasLock: Boolean get() = privacyLock.hasLock
    override val lockDelayMillis: Long get() = privacyLock.delayMillis
    override val wifiOnlyRefreshSupported: Boolean = true
    override val defaultDeviceName: String
        get() =
            android.provider.Settings.Global.getString(activity.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
                ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL

    val app: MutableState<PortfolioApplication?> get() = state.app
    val openFailed: MutableState<Boolean> get() = state.openFailed
    val locked: MutableState<Boolean> get() = privacyLock.locked

    /** True while a file picker or the fingerprint prompt is showing, so the activity does not lock behind it. */
    val isChoosing: Boolean get() = state.files.isChoosing || privacyLock.isPrompting

    init {
        runCatching {
            androidx.work.WorkManager
                .getInstance(activity)
                .getWorkInfosForUniqueWorkLiveData("valroot-price-refresh-now")
                .observe(activity) { work ->
                    AutomaticPriceWorker.tracker.setWorkState(
                        queued =
                            work.any {
                                it.state == androidx.work.WorkInfo.State.ENQUEUED ||
                                    it.state == androidx.work.WorkInfo.State.BLOCKED
                            },
                        running = work.any { it.state == androidx.work.WorkInfo.State.RUNNING },
                    )
                }
        }
        state.executor.execute {
            try {
                Files.createDirectories(state.file.parent)
                state.db = AndroidSqliteDatabase(state.file.toString())
                val opened = PortfolioApplication(state.db, System::currentTimeMillis)
                opened.settings.lockDelayMillis()?.let { privacyLock.delayMillis = it }
                if (runCatching { sync.reconcileRefreshPreferences(opened) }.isFailure) {
                    runCatching { opened.prices.recordAutomaticSchedulingFailure() }
                }
                runCatching { NotificationWorker.schedule(activity, opened.notifications.active()) }
                state.main.post { if (!state.closed) state.app.value = opened }
            } catch (_: Exception) {
                if (state.dbOpen) runCatching { state.db.close() }
                state.main.post { state.openFailed.value = true }
            }
        }
    }

    override fun saveFile(
        name: String,
        bytes: ByteArray,
        onResult: (String) -> Unit,
    ) = backups.saveFile(name, bytes, onResult)

    override fun openTextFile(
        maxChars: Int,
        onResult: (name: String, text: String?) -> Unit,
    ) = backups.openTextFile(maxChars, onResult)

    override fun saveBackup(
        password: String,
        onResult: (String) -> Unit,
    ) = backups.saveBackup(password, onResult)

    override fun restoreBackup(
        password: String,
        onResult: (String) -> Unit,
    ) = backups.restoreBackup(password, onResult)

    override fun verifyAccountingOrder(onResult: (String) -> Unit) = sync.verifyAccountingOrder(onResult)

    override fun discoverWallet(
        walletId: TrackingTargetId,
        onResult: (String) -> Unit,
    ) = sync.discoverWallet(walletId, onResult)

    override fun fetchHistoricalPrice(
        asset: AssetId,
        quote: AssetId,
        dayUtcMillis: Long,
        apiKey: String,
        onResult: (String) -> Unit,
    ) = prices.fetchHistoricalPrice(asset, quote, dayUtcMillis, apiKey, onResult)

    override fun fetchPriceHistory(
        asset: AssetId,
        quote: AssetId,
        days: Int,
        apiKey: String,
        onResult: (String) -> Unit,
    ) = prices.fetchPriceHistory(asset, quote, days, apiKey, onResult)

    override fun refresh(onResult: (String) -> Unit) = sync.refresh(onResult)

    override fun recoverBitcoinHistory(onResult: (String) -> Unit) = sync.recoverBitcoinHistory(onResult)

    override fun refreshWithoutBitcoin(onResult: (String) -> Unit) = sync.refreshWithoutBitcoin(onResult)

    override fun exchangeKeyInfo(
        kind: io.valroot.core.exchange.ExchangeKind,
        key: String,
        secret: String,
    ): io.valroot.core.exchange.ExchangeKeyInfo =
        io.valroot.core.exchange.JvmExchanges
            .of(kind)
            .checkKey(key, secret)

    override fun refreshPreferences(): RefreshPreferences = sync.refreshPreferences()

    override fun updateRefreshPreferences(preferences: RefreshPreferences) = sync.updateRefreshPreferences(preferences)

    override fun setBackgroundRefresh(enabled: Boolean) = sync.setBackgroundRefresh(enabled)

    override fun backgroundRefreshEnabled(): Boolean = sync.backgroundRefreshEnabled()

    override fun refreshAutomaticPrices(onResult: (String) -> Unit) = prices.refreshAutomaticPrices(onResult)

    override fun refreshMarkets(
        maxAgeMillis: Long,
        onResult: (String) -> Unit,
    ) = prices.refreshMarkets(maxAgeMillis, onResult)

    override fun refreshEvmCatalog(onResult: (String?) -> Unit) = prices.refreshEvmCatalog(onResult)

    override fun fetchOrderBook(
        coinId: String,
        onResult: (OrderBookResult) -> Unit,
    ) = prices.fetchOrderBook(coinId, onResult)

    override fun checkNotifications(onResult: (Int) -> Unit) = prices.checkNotifications(onResult)

    override fun notificationSettingsChanged() = prices.notificationSettingsChanged()

    override fun biometricUnlock(): BiometricUnlock = privacyLock.biometricUnlock()

    override fun setBiometricUnlock(
        enabled: Boolean,
        onResult: (String) -> Unit,
    ) = privacyLock.setBiometricUnlock(enabled, onResult)

    override fun unlockWithBiometrics(onResult: (UnlockResult) -> Unit) = privacyLock.unlockWithBiometrics(onResult)

    override fun removeLockPassword(onResult: (String) -> Unit) = privacyLock.removeLockPassword(onResult)

    override fun checkBitcoinEndpoint(endpoint: String): String? =
        try {
            io.valroot.core.bitcoin.requireMainnetEndpoint(BlockstreamTransport(endpoint))
            null
        } catch (failure: io.valroot.core.bitcoin.BitcoinProviderException) {
            failure.reason.userMessage
        } catch (_: Exception) {
            "Could not check this endpoint. Use an HTTPS Esplora API address."
        }

    override fun lock() = privacyLock.lock()

    override fun eraseAndRemoveLock(onResult: (String) -> Unit) = privacyLock.eraseAndRemoveLock(onResult)

    override fun unlock(
        password: String,
        onResult: (UnlockResult) -> Unit,
    ) = privacyLock.unlock(password, onResult)

    override fun setLockPassword(
        password: String,
        onResult: (String) -> Unit,
    ) = privacyLock.setLockPassword(password, onResult)

    override fun setLockDelay(millis: Long) = privacyLock.setLockDelay(millis)

    /** The app went to the background: lock now, or remember when, depending on the delay. */
    fun left() = privacyLock.left()

    /** The app came back: lock if it was away at least as long as the delay. */
    fun returned() = privacyLock.returned()

    /** Marks the host closed and releases the database on the background executor once queued work has run. */
    fun close() {
        state.closed = true
        state.executor.execute { if (state.dbOpen) state.db.close() }
    }
}
