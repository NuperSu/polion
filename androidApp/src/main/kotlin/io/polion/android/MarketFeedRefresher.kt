package io.polion.android

import android.content.Context
import io.polion.core.application.PortfolioApplication
import io.polion.core.storage.AndroidSqliteDatabase
import io.polion.ui.OrderBookResult
import io.polion.ui.loadOrderBook
import io.polion.ui.marketRefreshMessage
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

/** On-demand CoinGecko market refreshes on their own connection, so network waits never hold the UI database thread. */
internal object MarketFeedRefresher {
    private val executor = Executors.newSingleThreadExecutor()
    val transport = AndroidCoingeckoTransport()

    fun refresh(
        context: Context,
        maxAgeMillis: Long,
        onResult: (String) -> Unit,
    ) {
        val path = context.applicationContext.getDatabasePath("portfolio.sqlite").path
        executor.execute {
            val message =
                synchronized(AutomaticPriceGate.lock) {
                    if (AutomaticPriceGate.paused) {
                        "Market refresh is paused while data is restored."
                    } else {
                        runCatching {
                            AndroidSqliteDatabase(path).use { db ->
                                marketRefreshMessage(
                                    PortfolioApplication(db, System::currentTimeMillis).refreshMarketFeeds(transport::feed, maxAgeMillis),
                                )
                            }
                        }.getOrElse { "Could not refresh market data." }
                    }
                }
            RefreshWorker.dataRevision.update { it + 1 }
            onResult(message)
        }
    }

    /**
     * Runs a price alert and notification check on this executor, posts what it finds and reports how many were posted.
     * Nothing runs while a restore is in progress or [closing] is true.
     */
    fun checkNotifications(
        context: Context,
        closing: () -> Boolean,
        onResult: (Int) -> Unit,
    ) {
        val app = context.applicationContext
        executor.execute {
            val posted =
                synchronized(AutomaticPriceGate.lock) {
                    if (AutomaticPriceGate.paused || closing()) {
                        0
                    } else {
                        runCatching { PriceAlertNotifications.post(app, checkPriceAlerts(app)) }.getOrDefault(0)
                    }
                }
            RefreshWorker.dataRevision.update { it + 1 }
            onResult(posted)
        }
    }

    /** Loads Kraken's order book for [coinId] on this executor, under the same pause gate as market refreshes. */
    fun orderBook(
        context: Context,
        coinId: String,
        closing: () -> Boolean,
        onResult: (OrderBookResult) -> Unit,
    ) {
        val path = context.applicationContext.getDatabasePath("portfolio.sqlite").path
        executor.execute {
            val result =
                synchronized(AutomaticPriceGate.lock) {
                    if (AutomaticPriceGate.paused || closing()) {
                        OrderBookResult.Failed("Market data is paused while data is restored.")
                    } else {
                        runCatching {
                            AndroidSqliteDatabase(path).use { db ->
                                loadOrderBook(
                                    PortfolioApplication(db, System::currentTimeMillis),
                                    coinId,
                                    transport::feed,
                                    // Checked before each request, so a restore or close stops the book request early.
                                    KrakenPriceTransport(beforeRequest = { check(!AutomaticPriceGate.paused && !closing()) })::get,
                                )
                            }
                        }.getOrElse { OrderBookResult.Failed("Could not load Kraken's order book.") }
                    }
                }
            onResult(result)
        }
    }
}
