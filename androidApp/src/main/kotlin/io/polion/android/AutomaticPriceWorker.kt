package io.polion.android

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.polion.core.application.PortfolioApplication
import io.polion.core.storage.AndroidSqliteDatabase
import kotlinx.coroutines.flow.update

class AutomaticPriceWorker(
    context: Context,
    parameters: WorkerParameters,
) : Worker(context, parameters) {
    override fun doWork(): Result =
        try {
            AndroidSqliteDatabase(applicationContext.getDatabasePath("portfolio.sqlite").path).use { db ->
                val app = PortfolioApplication(db, System::currentTimeMillis)
                if (!app.automaticPriceConsent()) return Result.success()
                try {
                    app.refreshAutomaticPrices(KrakenPriceTransport()::get) { check(!isStopped) }
                    app.recordAutomaticPriceStatus(true)
                } catch (failure: Exception) {
                    if (!isStopped) app.recordAutomaticPriceStatus(false)
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
