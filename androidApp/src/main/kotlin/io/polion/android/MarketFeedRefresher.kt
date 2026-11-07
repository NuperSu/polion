package io.polion.android

import android.content.Context
import io.polion.core.application.PortfolioApplication
import io.polion.core.storage.AndroidSqliteDatabase
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
}
