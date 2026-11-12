package io.valroot.desktop

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.bitcoin.BlockstreamTransport
import io.valroot.core.market.KrakenPriceTransport
import io.valroot.core.model.AssetId
import io.valroot.core.model.TrackingTargetId
import io.valroot.core.storage.JvmSqliteDatabase
import io.valroot.ui.OrderBookResult
import io.valroot.ui.PlatformActions
import io.valroot.ui.RefreshPreferences
import io.valroot.ui.SyncStatus
import io.valroot.ui.UnlockResult
import io.valroot.ui.WALLET_REFRESH_INTERVAL_MILLIS
import io.valroot.ui.cooldownDelayMillis
import io.valroot.ui.refreshSchedulePlan
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The desktop [PlatformActions]. It builds the shared [DesktopState] and the helpers that own each area, keeps the
 * wallet-history schedule, and forwards every action to the helper that owns it.
 */
internal class DesktopHost(
    private val directory: Path,
    demo: Boolean,
    private val automaticPriceTransport: (
        () -> Unit,
    ) -> (String) -> String = { before -> KrakenPriceTransport(beforeRequest = before)::get },
) : PlatformActions {
    private val state = DesktopState(directory)
    private val prices = DesktopPrices(state)
    private val notifications =
        DesktopNotificationRunner(state, prices, automaticPriceTransport) { refreshPreferences().automaticRefresh }
    private val sync = DesktopSync(state, prices)
    private val privacyLock = DesktopLock(state) { reconcileRefreshPreferences() }
    private val backups = DesktopBackups(state, { reconcileRefreshPreferences() }, { notifications.reconcileChecks() })
    private var refreshTask: ScheduledFuture<*>? = null
    private val refreshPreference = directory.resolve("background-refresh")

    override val dispatcher: CoroutineDispatcher = state.executor.asCoroutineDispatcher()
    override val dataRevision = state.dataRevision
    override val syncStatus = state.syncStatus
    override val priceRefreshStatus = state.priceRefreshStatus
    override val hasLock: Boolean get() = privacyLock.hasLock
    override val lockDelayMillis: Long get() = privacyLock.delayMillis

    /** Read once from the environment or /etc/hostname; a DNS lookup could block the UI thread. */
    override val defaultDeviceName: String by lazy {
        (
            System.getenv("HOSTNAME")
                ?: runCatching {
                    java.nio.file.Files
                        .readString(
                            java.nio.file.Path
                                .of("/etc/hostname"),
                        )
                }.getOrNull()
        )?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 } ?: "This computer"
    }

    internal val priceReplacementInProgress: Boolean get() = state.priceRestorePaused
    val app: MutableState<PortfolioApplication> get() = state.app
    val locked: MutableState<Boolean> get() = privacyLock.locked

    init {
        Files.createDirectories(directory)
        runCatching {
            Files.setPosixFilePermissions(
                directory,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
            )
        }
        state.db = JvmSqliteDatabase(state.file.toString())
        state.app =
            try {
                mutableStateOf(PortfolioApplication(state.db, System::currentTimeMillis))
            } catch (failure: Exception) {
                runCatching { state.db.close() }
                throw failure
            }
        if (demo) app.value.demo.seed()
        privacyLock.delayMillis = app.value.settings.lockDelayMillis() ?: DEFAULT_LOCK_DELAY_MILLIS
        if (runCatching { reconcileRefreshPreferences() }.isFailure) {
            runCatching { app.value.prices.recordAutomaticSchedulingFailure() }
        }
        notifications.reconcileChecks()
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

    internal fun restoreBackup(
        path: Path,
        password: String,
        onResult: (String) -> Unit,
    ) = backups.restoreBackup(path, password, onResult)

    internal fun runSync(
        start: SyncStatus,
        onResult: (String) -> Unit,
        task: (PortfolioApplication) -> SyncStatus,
    ) = sync.runSync(start, onResult, task)

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
    ) = sync.fetchHistoricalPrice(asset, quote, dayUtcMillis, apiKey, onResult)

    override fun fetchPriceHistory(
        asset: AssetId,
        quote: AssetId,
        days: Int,
        apiKey: String,
        onResult: (String) -> Unit,
    ) = sync.fetchPriceHistory(asset, quote, days, apiKey, onResult)

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

    override fun refreshPreferences(): RefreshPreferences =
        RefreshPreferences(
            app.value.settings.automaticRefresh() ?: (Files.exists(refreshPreference) || app.value.prices.automaticConsent()),
            app.value.settings.wifiOnlyRefresh(),
        )

    override fun updateRefreshPreferences(preferences: RefreshPreferences) {
        app.value.settings.setRefreshPreferences(preferences.automaticRefresh, preferences.wifiOnly)
        reconcileRefreshPreferences()
    }

    private fun reconcileRefreshPreferences() {
        val preference = refreshPreferences()
        app.value.settings.setRefreshPreferences(preference.automaticRefresh, preference.wifiOnly)
        val plan = refreshSchedulePlan(preference, app.value.sources.consent(), app.value.prices.automaticConsent())
        scheduleWalletRefresh(plan.walletHistory)
        notifications.schedulePrices(plan.prices)
    }

    override fun setBackgroundRefresh(enabled: Boolean) = updateRefreshPreferences(refreshPreferences().copy(automaticRefresh = enabled))

    private fun scheduleWalletRefresh(enabled: Boolean) {
        refreshTask?.cancel(false)
        refreshTask =
            if (enabled) {
                // The first run waits out the rest of the interval after a success, so a restart does not repeat it.
                val delay =
                    cooldownDelayMillis(app.value.sources.lastSuccessMillis(), WALLET_REFRESH_INTERVAL_MILLIS, System.currentTimeMillis())
                state.executor.scheduleWithFixedDelay({
                    sync.refresh { }
                }, delay, WALLET_REFRESH_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
            } else {
                null
            }
    }

    override fun backgroundRefreshEnabled(): Boolean = refreshPreferences().automaticRefresh

    override fun refreshMarkets(
        maxAgeMillis: Long,
        onResult: (String) -> Unit,
    ) = prices.refreshMarkets(maxAgeMillis, onResult)

    override fun refreshEvmCatalog(onResult: (String?) -> Unit) = prices.refreshEvmCatalog(onResult)

    override fun refreshAutomaticPrices(onResult: (String) -> Unit) = notifications.refreshAutomaticPrices(onResult)

    override fun fetchOrderBook(
        coinId: String,
        onResult: (OrderBookResult) -> Unit,
    ) = prices.fetchOrderBook(coinId, onResult)

    override fun checkNotifications(onResult: (Int) -> Unit) = notifications.checkNotifications(onResult)

    override fun notificationSettingsChanged() = notifications.notificationSettingsChanged()

    override fun removeLockPassword(onResult: (String) -> Unit) = privacyLock.removeLockPassword(onResult)

    override fun checkBitcoinEndpoint(endpoint: String): String? =
        try {
            io.valroot.core.bitcoin
                .requireMainnetEndpoint(BlockstreamTransport(endpoint))
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

    /** The window lost focus: lock now, or remember when, depending on the delay. */
    fun left() = privacyLock.left()

    /** The window regained focus: lock if it was away at least as long as the delay. */
    fun returned() = privacyLock.returned()

    fun close() {
        if (!state.closing.compareAndSet(false, true)) return
        refreshTask?.cancel(false)
        notifications.cancelSchedules()
        state.priceExecutor.shutdownNow()
        state.historyExecutor.shutdownNow()
        state.syncQueue.shutdown()
        state.executor.execute {
            // The network worker must finish cancellation and release SQLite before either connection closes.
            while (!state.syncQueue.awaitTermination(1_000)) Unit
            state.closeSyncConnection()
            state.db.close()
        }
        state.executor.shutdown()
        state.unlockExecutor.shutdown()
    }
}
