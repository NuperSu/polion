package io.valroot.desktop

import androidx.compose.runtime.MutableState
import io.valroot.core.alerts.NotificationMessage
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.storage.JvmSqliteDatabase
import io.valroot.ui.MARKET_SCHEDULED_AGE_MILLIS
import io.valroot.ui.PRICE_REFRESH_INTERVAL_MILLIS
import io.valroot.ui.PriceRefreshPhase
import io.valroot.ui.cooldownDelayMillis
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/** Notification checks start this long after launch while an alert or notification setting is on, then repeat. */
private const val NOTIFICATION_FIRST_CHECK_MILLIS = 30_000L
private const val NOTIFICATION_INTERVAL_MILLIS = 300_000L

/**
 * Background alert checks and the scheduled automatic price refresh. Both run on the price worker under the price gate,
 * and each is scheduled only while its setting allows. [automaticPriceTransport] builds the Kraken transport for a run;
 * [automaticRefreshOn] reports the automatic-refresh preference.
 */
internal class DesktopNotificationRunner(
    private val state: DesktopState,
    private val prices: DesktopPrices,
    private val automaticPriceTransport: (() -> Unit) -> (String) -> String,
    private val automaticRefreshOn: () -> Boolean,
) {
    private val app: MutableState<PortfolioApplication> get() = state.app
    private val manualPricePending = AtomicBoolean(false)
    private var automaticPriceTask: ScheduledFuture<*>? = null
    private var notificationTask: ScheduledFuture<*>? = null

    fun checkNotifications(onResult: (Int) -> Unit = {}) {
        try {
            state.priceExecutor.execute { notificationCheck(onResult) }
        } catch (_: RejectedExecutionException) {
            SwingUtilities.invokeLater { onResult(0) }
        }
    }

    fun notificationSettingsChanged() {
        if (reconcileChecks()) checkNotifications()
    }

    /** One check on the price worker with its own connection, then shows what it found and reports the count on the Swing thread. */
    private fun notificationCheck(onResult: (Int) -> Unit) {
        val messages: List<NotificationMessage> =
            runCatching {
                synchronized(state.priceGate) {
                    if (state.priceRestorePaused || state.closing.get()) {
                        emptyList()
                    } else {
                        JvmSqliteDatabase(state.file.toString()).use { connection ->
                            PortfolioApplication(connection, System::currentTimeMillis).notifications.runCheck(
                                state.priceTransport::feed,
                                LocalDate.now().toString(),
                                LocalTime.now().hour,
                            )
                        }
                    }
                }
            }.getOrDefault(emptyList())
        messages.forEach { DesktopNotifications.show(it.title, it.body) }
        state.dataRevision.update { it + 1 }
        SwingUtilities.invokeLater { onResult(messages.size) }
    }

    /**
     * Runs the periodic check only while an alert or notification setting is on. Returns whether checks are running.
     * Called at launch, when settings change, and after a restore; the schedule is guarded by [DesktopState.hostLock].
     */
    internal fun reconcileChecks(): Boolean {
        val active = !state.closing.get() && runCatching { app.value.notifications.active() }.getOrDefault(false)
        synchronized(state.hostLock) {
            val running = notificationTask?.isDone == false
            if (!active) {
                notificationTask?.cancel(false)
                notificationTask = null
            } else if (!running) {
                notificationTask =
                    runCatching {
                        state.priceExecutor.scheduleWithFixedDelay(
                            { runCatching { notificationCheck {} } },
                            NOTIFICATION_FIRST_CHECK_MILLIS,
                            NOTIFICATION_INTERVAL_MILLIS,
                            TimeUnit.MILLISECONDS,
                        )
                    }.getOrNull()
            }
        }
        return active
    }

    fun refreshAutomaticPrices(onResult: (String) -> Unit) {
        if (state.closing.get() || !manualPricePending.compareAndSet(false, true)) {
            onResult("A price refresh is already queued or the app is closing.")
            return
        }
        if (state.priceRefreshStatus.value == PriceRefreshPhase.IDLE) {
            state.priceRefreshStatus.value = PriceRefreshPhase.QUEUED
        }
        try {
            state.priceExecutor.execute {
                try {
                    runAutomaticPriceRefresh()
                } finally {
                    manualPricePending.set(false)
                }
            }
            onResult("Price refresh queued.")
        } catch (_: RejectedExecutionException) {
            manualPricePending.set(false)
            state.priceRefreshStatus.value = PriceRefreshPhase.IDLE
            onResult("Could not schedule a price refresh.")
        }
    }

    internal fun schedulePrices(enabled: Boolean) {
        automaticPriceTask?.cancel(false)
        automaticPriceTask =
            if (enabled && automaticRefreshOn() && app.value.prices.automaticConsent() && !state.closing.get()) {
                val delay =
                    cooldownDelayMillis(
                        app.value.prices.automaticLastRunMillis(),
                        PRICE_REFRESH_INTERVAL_MILLIS,
                        System.currentTimeMillis(),
                    )
                state.priceExecutor.scheduleWithFixedDelay(
                    ::runAutomaticPriceRefresh,
                    delay,
                    PRICE_REFRESH_INTERVAL_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
            } else {
                null
            }
    }

    /** Stops the scheduled price refresh and the alert checks; called when the host closes. */
    internal fun cancelSchedules() {
        automaticPriceTask?.cancel(false)
        synchronized(state.hostLock) { notificationTask?.cancel(false) }
    }

    private fun runAutomaticPriceRefresh() {
        synchronized(state.priceGate) {
            if (state.priceRestorePaused) {
                state.priceRefreshStatus.value = PriceRefreshPhase.IDLE
                return
            }
            state.priceRefreshStatus.value = PriceRefreshPhase.RUNNING
            try {
                JvmSqliteDatabase(state.file.toString()).use { connection ->
                    val application = PortfolioApplication(connection, System::currentTimeMillis)
                    if (application.prices.automaticConsent()) {
                        try {
                            val generation = application.prices.automaticGeneration()
                            val transport =
                                automaticPriceTransport {
                                    check(
                                        !state.closing.get() &&
                                            !state.priceRestorePaused &&
                                            application.prices.automaticConsent() &&
                                            application.prices.automaticGeneration() == generation,
                                    )
                                }
                            application.prices.refreshAutomaticSet(transport) { check(!state.closing.get() && !state.priceRestorePaused) }
                            application.prices.recordAutomaticStatus(true)
                            // Market lists ride on the same schedule; a failure here never fails the price refresh.
                            if (application.market.consent()) {
                                runCatching { application.market.refreshFeeds(state.priceTransport::feed, MARKET_SCHEDULED_AGE_MILLIS) }
                                prices.refreshEvmCatalogAndPrices(application)
                            }
                        } catch (failure: Exception) {
                            if (!state.closing.get() &&
                                !state.priceRestorePaused
                            ) {
                                application.prices.recordAutomaticStatus(
                                    false,
                                    failure is io.valroot.core.market.KrakenRateLimitedException,
                                )
                            }
                            throw failure
                        } finally {
                            state.dataRevision.update { it + 1 }
                        }
                    }
                }
            } catch (_: Exception) {
                // Saved observations remain available until a later refresh succeeds.
            } finally {
                state.priceRefreshStatus.value = PriceRefreshPhase.IDLE
            }
        }
    }
}
