package io.polion.android

import android.content.Context
import io.polion.core.application.PortfolioApplication
import io.polion.core.market.CoingeckoProviderException
import io.polion.core.market.CoingeckoProviderFailure
import io.polion.core.market.PriceStatus
import io.polion.core.model.AssetId
import io.polion.core.storage.AndroidSqliteDatabase
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** User-requested daily quotes use a separate worker and database connection. */
internal object PriceHistoryWorker {
    private val executor = Executors.newSingleThreadExecutor()
    private val transport = AndroidCoingeckoTransport()
    private val generation = AtomicLong()

    /** Invalidates queued and in-flight requests without waiting for network I/O. */
    fun invalidate() {
        generation.incrementAndGet()
    }

    fun fetch(
        context: Context,
        asset: AssetId,
        quote: AssetId,
        dayUtcMillis: Long,
        apiKey: String,
        onResult: (String) -> Unit,
    ) {
        val requestGeneration = generation.get()
        executor.execute {
            val message =
                try {
                    AndroidSqliteDatabase(context.getDatabasePath("portfolio.sqlite").path).use { db ->
                        val result =
                            PortfolioApplication(db, System::currentTimeMillis).fetchHistoricalPrice(
                                { url ->
                                    checkGeneration(requestGeneration)
                                    transport.get(url, apiKey)
                                },
                                asset,
                                quote,
                                dayUtcMillis,
                                authorize = { checkGeneration(requestGeneration) },
                            )
                        if (result.status == PriceStatus.MISSING) {
                            "No historical quote was returned. Saved prices are unchanged."
                        } else {
                            "Daily historical price saved from CoinGecko."
                        }
                    }
                } catch (failure: CoingeckoProviderException) {
                    failure.failure.message
                } catch (_: Exception) {
                    "Could not fetch a historical price. Check consent, date, connection and storage."
                }
            RefreshWorker.dataRevision.update { it + 1 }
            onResult(message)
        }
    }

    private fun checkGeneration(expected: Long) {
        if (generation.get() != expected) throw CoingeckoProviderException(CoingeckoProviderFailure.CANCELLED)
    }
}
