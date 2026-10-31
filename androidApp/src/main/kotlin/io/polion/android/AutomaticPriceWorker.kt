package io.polion.android

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.polion.core.application.PortfolioApplication
import io.polion.core.storage.AndroidSqliteDatabase
import kotlinx.coroutines.flow.update

internal object AutomaticPriceGate {
    val lock = Any()

    @Volatile var paused = false
}

class AutomaticPriceWorker(
    context: Context,
    parameters: WorkerParameters,
) : Worker(context, parameters) {
    override fun doWork(): Result =
        synchronized(AutomaticPriceGate.lock) {
            if (AutomaticPriceGate.paused) Result.retry() else refresh()
        }

    private fun refresh(): Result =
        try {
            AndroidSqliteDatabase(applicationContext.getDatabasePath("portfolio.sqlite").path).use { db ->
                val app = PortfolioApplication(db, System::currentTimeMillis)
                if (!app.automaticPriceConsent()) return Result.success()
                try {
                    val generation = app.automaticPriceGeneration()
                    val transport =
                        KrakenPriceTransport(beforeRequest = {
                            check(
                                !isStopped && !AutomaticPriceGate.paused && app.automaticPriceConsent() &&
                                    app.automaticPriceGeneration() == generation,
                            )
                        })
                    app.refreshAutomaticPriceSet(transport::get) { check(!isStopped && !AutomaticPriceGate.paused) }
                    app.recordAutomaticPriceStatus(true)
                } catch (failure: Exception) {
                    if (!isStopped && !AutomaticPriceGate.paused) app.recordAutomaticPriceStatus(false)
                    RefreshWorker.dataRevision.update { it + 1 }
                    throw failure
                }
            }
            RefreshWorker.dataRevision.update { it + 1 }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
}
