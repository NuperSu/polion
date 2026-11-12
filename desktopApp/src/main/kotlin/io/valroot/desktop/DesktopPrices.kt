package io.valroot.desktop

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.market.KrakenPriceTransport
import io.valroot.core.storage.JvmSqliteDatabase
import io.valroot.ui.OrderBookResult
import io.valroot.ui.loadOrderBook
import io.valroot.ui.marketRefreshMessage
import kotlinx.coroutines.flow.update
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/** CoinGecko linked-coin history requests (currencies other than USD) per backfill run, spaced past its transport's 6-second pacing. */
private const val LINKED_HISTORY_REQUESTS_PER_RUN = 24
private const val LINKED_HISTORY_INTERVAL_MILLIS = 6_100L
private const val LINKED_HISTORY_RATE_LIMIT_WAIT_MILLIS = 61_000L

/** DefiLlama USD history requests per backfill run, one a second: enough for a few years of thirty coins. */
private const val LINKED_USD_HISTORY_REQUESTS_PER_RUN = 600
private const val LINKED_USD_HISTORY_INTERVAL_MILLIS = 1_000L

private const val MARKET_PAUSED_MESSAGE = "Market data is paused while data is restored."

/**
 * Market feeds, the chain catalog, order books, and linked-coin prices and history. Work runs on the price worker, or on the
 * history worker for backfills, and holds the price gate while it touches the database file.
 */
internal class DesktopPrices(
    private val state: DesktopState,
) {
    private val historyBackfillQueued = AtomicBoolean(false)

    fun refreshMarkets(
        maxAgeMillis: Long,
        onResult: (String) -> Unit,
    ) {
        if (state.closing.get()) {
            onResult("The app is closing.")
            return
        }
        try {
            state.priceExecutor.execute {
                val message =
                    synchronized(state.priceGate) {
                        if (state.priceRestorePaused || state.closing.get()) {
                            "Market refresh is paused while data is restored."
                        } else {
                            runCatching {
                                JvmSqliteDatabase(state.file.toString()).use { connection ->
                                    val application = PortfolioApplication(connection, System::currentTimeMillis)
                                    val message =
                                        marketRefreshMessage(application.market.refreshFeeds(state.priceTransport::feed, maxAgeMillis))
                                    refreshEvmCatalogAndPrices(application)
                                    message
                                }
                            }.getOrElse { "Could not refresh market data." }
                        }
                    }
                state.dataRevision.update { it + 1 }
                SwingUtilities.invokeLater { onResult(message) }
            }
        } catch (_: RejectedExecutionException) {
            onResult("Could not schedule a market refresh.")
        }
    }

    /** Loads the CoinGecko chain catalog on the market worker, under the same pause and closing guards as market refreshes. */
    fun refreshEvmCatalog(onResult: (String?) -> Unit) {
        if (state.closing.get()) {
            onResult("The app is closing.")
            return
        }
        try {
            state.priceExecutor.execute {
                val message =
                    synchronized(state.priceGate) {
                        if (state.priceRestorePaused || state.closing.get()) {
                            MARKET_PAUSED_MESSAGE
                        } else {
                            runCatching {
                                JvmSqliteDatabase(state.file.toString()).use { connection ->
                                    val application = PortfolioApplication(connection, System::currentTimeMillis)
                                    application.evm.refreshCatalog(state.priceTransport::feed)
                                }
                                null
                            }.getOrElse { "Could not load the chain list from CoinGecko." }
                        }
                    }
                state.dataRevision.update { it + 1 }
                SwingUtilities.invokeLater { onResult(message) }
            }
        } catch (_: RejectedExecutionException) {
            onResult("Could not schedule a chain list refresh.")
        }
    }

    fun fetchOrderBook(
        coinId: String,
        onResult: (OrderBookResult) -> Unit,
    ) {
        try {
            state.priceExecutor.execute {
                val result =
                    synchronized(state.priceGate) {
                        if (state.priceRestorePaused || state.closing.get()) {
                            OrderBookResult.Failed(MARKET_PAUSED_MESSAGE)
                        } else {
                            runCatching {
                                JvmSqliteDatabase(state.file.toString()).use { connection ->
                                    loadOrderBook(
                                        PortfolioApplication(connection, System::currentTimeMillis),
                                        coinId,
                                        state.priceTransport::feed,
                                        // Checked before each request, so a restore or close stops the book request early.
                                        KrakenPriceTransport(
                                            beforeRequest = { check(!state.priceRestorePaused && !state.closing.get()) },
                                        )::get,
                                    )
                                }
                            }.getOrElse { OrderBookResult.Failed("Could not load Kraken's order book.") }
                        }
                    }
                SwingUtilities.invokeLater { onResult(result) }
            }
        } catch (_: RejectedExecutionException) {
            SwingUtilities.invokeLater { onResult(OrderBookResult.Failed("Could not schedule an order book request.")) }
        }
    }

    /**
     * Refreshes the CoinGecko chain catalog, then linked-coin prices (USD from DefiLlama, other currencies from
     * CoinGecko), logos and missing price history. Each step is isolated, so a failure leaves the caller's refresh
     * successful. Does nothing without market data consent.
     */
    internal fun refreshEvmCatalogAndPrices(application: PortfolioApplication) {
        if (!application.market.consent()) return
        runCatching { application.evm.refreshCatalog(state.priceTransport::feed) }
        runCatching { application.linkedCoins.refreshUsdPrices(state.defiLlamaTransport::get) }
        runCatching { application.linkedCoins.refreshPrices(state.priceTransport::feed) }
        runCatching { application.linkedCoins.refreshLogos(state.priceTransport::feed) }
        backfillLinkedCoinHistory()
    }

    /**
     * Fills missing linked-coin price history in the background: USD from DefiLlama, then other currencies from
     * CoinGecko. Later calls while one is queued do nothing.
     */
    private fun backfillLinkedCoinHistory() {
        if (state.closing.get() || !historyBackfillQueued.compareAndSet(false, true)) return
        runCatching {
            state.historyExecutor.execute {
                historyBackfillQueued.set(false)
                // An interrupted pause (the app closing) ends the run.
                val usd =
                    runCatching {
                        backfillSteps(LINKED_USD_HISTORY_REQUESTS_PER_RUN, LINKED_USD_HISTORY_INTERVAL_MILLIS) { application ->
                            application.linkedCoins.backfillUsdHistory(state.defiLlamaTransport::get, maxRequests = 1)
                        }
                    }.getOrDefault(true)
                val other =
                    !state.closing.get() &&
                        runCatching {
                            backfillSteps(LINKED_HISTORY_REQUESTS_PER_RUN, LINKED_HISTORY_INTERVAL_MILLIS) { application ->
                                application.linkedCoins.backfillHistory(
                                    { url, key -> state.priceTransport.get(url, key.orEmpty()) },
                                    maxRequests = 1,
                                )
                            }
                        }.getOrDefault(true)
                if (usd || other) state.dataRevision.update { it + 1 }
            }
        }.onFailure { historyBackfillQueued.set(false) }
    }

    /**
     * Runs [step] up to [steps] times, [intervalMillis] apart, until it reports no work. Each step and its write run under
     * the price gate on a fresh connection, so a restore never meets an open connection; the pauses run outside it. A
     * rate limit is waited out twice at most; any other failure ends the run. Returns whether any step did work.
     */
    private fun backfillSteps(
        steps: Int,
        intervalMillis: Long,
        step: (PortfolioApplication) -> Int,
    ): Boolean {
        var changed = false
        var rateLimits = 0
        repeat(steps) {
            Thread.sleep(intervalMillis)
            val done =
                try {
                    synchronized(state.priceGate) {
                        if (state.closing.get() || state.priceRestorePaused) return changed
                        JvmSqliteDatabase(state.file.toString()).use { connection ->
                            val application = PortfolioApplication(connection, System::currentTimeMillis)
                            if (!application.market.consent()) return changed
                            step(application)
                        }
                    }
                } catch (failure: Exception) {
                    if (failure is InterruptedException) throw failure
                    val limited =
                        (failure as? io.valroot.core.market.CoingeckoProviderException)?.failure ==
                            io.valroot.core.market.CoingeckoProviderFailure.RATE_LIMITED ||
                            (failure as? io.valroot.core.market.DefiLlamaException)?.rateLimited == true
                    if (!limited || ++rateLimits > 2) return changed
                    Thread.sleep(LINKED_HISTORY_RATE_LIMIT_WAIT_MILLIS)
                    return@repeat
                }
            if (done == 0) return changed
            changed = true
        }
        return changed
    }
}
