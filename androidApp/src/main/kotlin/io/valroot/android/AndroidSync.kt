package io.valroot.android

import android.Manifest
import android.content.Intent
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.model.TrackingTargetId
import io.valroot.ui.PRICE_REFRESH_INTERVAL_MILLIS
import io.valroot.ui.RefreshPreferences
import io.valroot.ui.SyncOperation
import io.valroot.ui.SyncPhase
import io.valroot.ui.SyncStatus
import io.valroot.ui.WALLET_REFRESH_INTERVAL_MILLIS
import io.valroot.ui.cooldownDelayMillis
import io.valroot.ui.refreshSchedulePlan

/**
 * Background Bitcoin and account refresh, the accounting-order check, wallet discovery, and the refresh preferences that
 * decide which periodic WorkManager jobs are scheduled.
 */
internal class AndroidSync(
    private val state: AndroidState,
    private val prices: AndroidPrices,
) {
    fun refreshPreferences(): RefreshPreferences =
        state.app.value?.let(::readRefreshPreferences)
            ?: RefreshPreferences(state.preferences.getBoolean("background_refresh", false))

    fun updateRefreshPreferences(preferences: RefreshPreferences) {
        val application = state.app.value ?: return
        application.settings.setRefreshPreferences(preferences.automaticRefresh, preferences.wifiOnly)
        reconcileRefreshPreferences(application)
    }

    fun setBackgroundRefresh(enabled: Boolean) = updateRefreshPreferences(refreshPreferences().copy(automaticRefresh = enabled))

    fun backgroundRefreshEnabled(): Boolean = refreshPreferences().automaticRefresh

    /** Stores the effective preference and schedules wallet history and automatic prices from it, or cancels them. */
    fun reconcileRefreshPreferences(application: PortfolioApplication) {
        val preference = readRefreshPreferences(application)
        application.settings.setRefreshPreferences(preference.automaticRefresh, preference.wifiOnly)
        val plan = refreshSchedulePlan(preference, application.sources.consent(), application.prices.automaticConsent())
        val now = System.currentTimeMillis()
        scheduleWalletRefresh(
            plan.walletHistory,
            plan.wifiOnly,
            cooldownDelayMillis(application.sources.lastSuccessMillis(), WALLET_REFRESH_INTERVAL_MILLIS, now),
        )
        prices.scheduleAutomaticPrices(
            plan.prices,
            plan.wifiOnly,
            application.prices.automaticConsent(),
            cooldownDelayMillis(application.prices.automaticLastRunMillis(), PRICE_REFRESH_INTERVAL_MILLIS, now),
        )
    }

    private fun readRefreshPreferences(application: PortfolioApplication): RefreshPreferences =
        RefreshPreferences(
            application.settings.automaticRefresh()
                ?: (state.preferences.getBoolean("background_refresh", false) || application.prices.automaticConsent()),
            application.settings.wifiOnlyRefresh(),
        )

    private fun scheduleWalletRefresh(
        enabled: Boolean,
        wifiOnly: Boolean,
        initialDelayMillis: Long,
    ) {
        AndroidRefreshScheduling.wallets(state.activity, enabled, wifiOnly, initialDelayMillis = initialDelayMillis)
    }

    fun refresh(onResult: (String) -> Unit) = refresh(false, onResult)

    fun recoverBitcoinHistory(onResult: (String) -> Unit) = refresh(true, onResult)

    fun refreshWithoutBitcoin(onResult: (String) -> Unit) = refresh(false, onResult, withoutBitcoin = true)

    private fun refresh(
        recoverHistory: Boolean,
        onResult: (String) -> Unit,
        withoutBitcoin: Boolean = false,
    ) {
        state.main.post {
            try {
                if (state.notificationPermissionMissing()) {
                    state.notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                state.syncStatus.value =
                    SyncStatus(SyncPhase.SYNCING, if (recoverHistory) "Starting full history recovery…" else "Starting background sync…")
                state.activity.startForegroundService(
                    Intent(state.activity, BitcoinRefreshService::class.java)
                        .putExtra("recover_history", recoverHistory)
                        .putExtra("without_bitcoin", withoutBitcoin),
                )
                onResult("Sync continues when you switch apps. Progress appears in the notification.")
            } catch (_: Exception) {
                state.syncStatus.value = SyncStatus(SyncPhase.ERROR, "Android could not start background sync. Open Valroot and retry.")
                onResult(state.syncStatus.value.message)
            }
        }
    }

    fun verifyAccountingOrder(onResult: (String) -> Unit) {
        state.main.post {
            try {
                state.activity.startForegroundService(
                    Intent(state.activity, BitcoinRefreshService::class.java).putExtra("verify_order", true),
                )
                onResult("Order verification started in the background.")
            } catch (_: Exception) {
                onResult("Could not start order verification. Open Valroot and retry.")
            }
        }
    }

    fun discoverWallet(
        walletId: TrackingTargetId,
        onResult: (String) -> Unit,
    ) {
        state.main.post {
            try {
                state.activity.startForegroundService(
                    Intent(state.activity, BitcoinRefreshService::class.java).putExtra("wallet_id", walletId.value),
                )
                onResult("Wallet discovery requested. It runs after any current Bitcoin work; progress appears in the notification.")
            } catch (_: Exception) {
                val failure =
                    SyncStatus(
                        SyncPhase.ERROR,
                        "Could not request discovery. Open this wallet in Accounts and choose Discover addresses to retry.",
                        SyncOperation.DISCOVERY,
                    )
                if (state.syncStatus.value.phase != SyncPhase.SYNCING) state.syncStatus.value = failure
                onResult(failure.message)
            }
        }
    }
}
