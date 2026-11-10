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

/** Scheduled and on-open market refreshes skip feeds younger than this; a manual refresh uses one minute. */
const val MARKET_SCHEDULED_AGE_MILLIS = 15L * 60_000L

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

    /**
     * Asks for a text file, such as a CSV export, and reads it in the background. [onResult] runs on the main thread with
     * the file name and text, or with null text when the user cancelled, the file is over [maxChars] or is not UTF-8 text.
     */
    fun openTextFile(
        maxChars: Int,
        onResult: (name: String, text: String?) -> Unit,
    ) {
        onResult("", null)
    }

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

    /** Refreshes only EVM addresses, for example right after one is connected, without waiting for Bitcoin. */
    fun refreshEvm(onResult: (String) -> Unit) = refresh(onResult)

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

    /**
     * Refreshes CoinGecko market feeds older than [maxAgeMillis] in the background, then bumps [dataRevision]. Needs market
     * data consent.
     */
    fun refreshMarkets(
        maxAgeMillis: Long = 60_000L,
        onResult: (String) -> Unit,
    ) {
        onResult("Market data is unavailable on this host.")
    }

    /**
     * Runs a notification check in the background now (see [io.polion.core.application.PortfolioApplication.runNotificationCheck])
     * and shows what it finds as system notifications; [onResult] receives how many were shown, on the main thread.
     */
    fun checkNotifications(onResult: (Int) -> Unit = {}) {
        onResult(0)
    }

    /**
     * Alerts or notification settings changed. Hosts schedule background checks while
     * [io.polion.core.application.PortfolioApplication.notificationsActive] is true, cancel them otherwise, and on Android
     * ask for notification permission. Main thread.
     */
    fun notificationSettingsChanged() {}

    /** Loads Kraken's order book for [coinId] in the display currency in the background; [onResult] runs on the main thread. */
    fun fetchOrderBook(
        coinId: String,
        onResult: (OrderBookResult) -> Unit,
    ) {
        onResult(OrderBookResult.Failed("Order books are not available here."))
    }

    /**
     * Loads CoinGecko's chain and token catalog in the background when it is missing or older than a week, then links held
     * EVM assets to coins. [onResult] receives null on success or a message, on the main thread. Needs market data consent.
     */
    fun refreshEvmCatalog(onResult: (String?) -> Unit) {
        onResult("EVM chains are not available here.")
    }

    /** How long the app may be left before it locks. Main thread; hosts keep the saved value in memory. */
    val lockDelayMillis: Long get() = 0L

    /** Saves the lock delay for this device; one of [io.polion.core.application.LOCK_DELAYS]. Main thread. */
    fun setLockDelay(millis: Long) {}

    /**
     * Asks a custom Esplora endpoint for its block 0 and returns a message when it is unreachable or not Bitcoin mainnet,
     * or null when it is. Blocking; call off the main thread.
     */
    fun checkBitcoinEndpoint(endpoint: String): String? = null

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

/** Hosts report a market refresh with the same wording. */
fun marketRefreshMessage(result: io.polion.core.application.MarketRefreshResult): String =
    when (result.failure) {
        null -> if (result.updatedFeeds == 0) "Market data is up to date." else "Market data updated."
        io.polion.core.market.CoingeckoProviderFailure.RATE_LIMITED ->
            "CoinGecko is limiting requests. Try again in a minute, or add a free API key in Settings."
        io.polion.core.market.CoingeckoProviderFailure.ACCESS_DENIED -> "CoinGecko rejected the API key. Check it in Settings › API Keys."
        io.polion.core.market.CoingeckoProviderFailure.OFFLINE -> "Could not reach CoinGecko. Showing saved market data."
        else -> "CoinGecko returned data Polion could not read. Showing saved market data."
    }

/** The outcome of one order book request. */
sealed interface OrderBookResult {
    data class Loaded(
        val book: io.polion.core.market.OrderBookSnapshot,
    ) : OrderBookResult

    /** Kraken has no market for this coin in the display currency. */
    data class NoMarket(
        val message: String,
    ) : OrderBookResult

    data class Failed(
        val message: String,
    ) : OrderBookResult
}

/**
 * Resolves the Kraken market for [coinId] and loads its book, with the wording hosts show. Blocking; hosts call it on a
 * worker with their own database connection.
 */
fun loadOrderBook(
    app: io.polion.core.application.PortfolioApplication,
    coinId: String,
    coingecko: (url: String, apiKey: String?) -> String,
    kraken: (url: String) -> String,
): OrderBookResult =
    try {
        val pair = app.krakenPair(coinId, coingecko)
        if (pair == null) {
            OrderBookResult.NoMarket("Kraken has no ${app.currency().value} market for this coin.")
        } else {
            OrderBookResult.Loaded(app.orderBook(pair, kraken))
        }
    } catch (failure: io.polion.core.market.CoingeckoProviderException) {
        OrderBookResult.Failed(
            if (failure.failure == io.polion.core.market.CoingeckoProviderFailure.RATE_LIMITED) {
                "CoinGecko's request limit was reached. Try again in a minute."
            } else {
                "Could not find this coin's Kraken market."
            },
        )
    } catch (_: io.polion.core.market.KrakenRateLimitedException) {
        OrderBookResult.Failed("Kraken's request limit was reached. Try again shortly.")
    } catch (_: Exception) {
        OrderBookResult.Failed("Could not load Kraken's order book.")
    }
