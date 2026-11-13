package io.valroot.android

import android.Manifest
import io.valroot.core.model.AssetId
import io.valroot.ui.OrderBookResult
import io.valroot.ui.PriceRefreshPhase

/**
 * Market feeds, order books, price history and the automatic price refresh, plus the alert checks that compare against
 * market prices. Provider requests run on their own workers; results return to the main thread.
 */
internal class AndroidPrices(
    private val state: AndroidState,
) {
    fun refreshMarkets(
        maxAgeMillis: Long,
        onResult: (String) -> Unit,
    ) {
        MarketFeedRefresher.refresh(state.activity, maxAgeMillis) { message ->
            state.main.post { if (!state.closed) onResult(message) }
        }
    }

    fun fetchOrderBook(
        coinId: String,
        onResult: (OrderBookResult) -> Unit,
    ) {
        MarketFeedRefresher.orderBook(state.activity, coinId, closing = { state.closed }) { result ->
            state.main.post { if (!state.closed) onResult(result) }
        }
    }

    /** Runs a notification check now and shows what it finds; [onResult] receives how many notifications were shown. */
    fun checkNotifications(onResult: (Int) -> Unit = {}) {
        MarketFeedRefresher.checkNotifications(state.activity, closing = { state.closed }) { count ->
            state.main.post { if (!state.closed) onResult(count) }
        }
    }

    /**
     * Reschedules the periodic check on the database thread. On the main thread, asks for notification permission when
     * it is missing, then runs a check now.
     */
    fun notificationSettingsChanged() {
        state.executor.execute {
            val active = runCatching { state.app.value?.notifications?.active() }.getOrNull() == true
            NotificationWorker.schedule(state.activity, active)
            state.main.post {
                if (active && !state.closed) {
                    if (state.notificationPermissionMissing()) {
                        state.notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    checkNotifications()
                }
            }
        }
    }

    fun fetchHistoricalPrice(
        asset: AssetId,
        quote: AssetId,
        dayUtcMillis: Long,
        apiKey: String,
        onResult: (String) -> Unit,
    ) {
        PriceHistoryWorker.fetch(state.activity.applicationContext, asset, quote, dayUtcMillis, apiKey) { message ->
            state.main.post { onResult(message) }
        }
    }

    fun fetchPriceHistory(
        asset: AssetId,
        quote: AssetId,
        days: Int,
        apiKey: String,
        onResult: (String) -> Unit,
    ) {
        PriceHistoryWorker.fetchRange(
            state.activity.applicationContext,
            asset,
            quote,
            days,
            apiKey,
        ) { message -> state.main.post { onResult(message) } }
    }

    fun refreshAutomaticPrices(onResult: (String) -> Unit) {
        if (state.priceRefreshStatus.value != PriceRefreshPhase.IDLE) {
            onResult("A price refresh is already queued or running.")
            return
        }
        try {
            AutomaticPriceWorker.tracker.setWorkState(queued = true, running = false)
            val work =
                androidx.work
                    .OneTimeWorkRequestBuilder<AutomaticPriceWorker>()
                    .setConstraints(
                        androidx.work.Constraints
                            .Builder()
                            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                            .build(),
                    ).build()
            androidx.work.WorkManager
                .getInstance(state.activity)
                .enqueueUniqueWork("valroot-price-refresh-now", androidx.work.ExistingWorkPolicy.KEEP, work)
            onResult("Price refresh queued; Android will run it when network access is available.")
        } catch (_: Exception) {
            AutomaticPriceWorker.tracker.setWorkState(queued = false, running = false)
            onResult("Could not schedule a price refresh.")
        }
    }

    fun scheduleAutomaticPrices(
        enabled: Boolean,
        wifiOnly: Boolean,
        consent: Boolean,
        initialDelayMillis: Long,
    ) {
        val manager = androidx.work.WorkManager.getInstance(state.activity)
        AndroidRefreshScheduling.prices(state.activity, enabled, wifiOnly, initialDelayMillis = initialDelayMillis)
        if (!consent) {
            manager.cancelUniqueWork("valroot-price-refresh-now")
            AutomaticPriceWorker.tracker.setWorkState(queued = false, running = false)
        }
    }
}
