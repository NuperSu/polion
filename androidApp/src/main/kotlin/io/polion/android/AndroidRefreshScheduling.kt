package io.polion.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Host startup, settings and restore share these consent-gated periodic requests. */
internal object AndroidRefreshScheduling {
    fun walletRequest(
        wifiOnly: Boolean,
        initialDelayMillis: Long = 0,
    ): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES)
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(constraints(wifiOnly))
            .build()

    fun priceRequest(
        wifiOnly: Boolean,
        initialDelayMillis: Long = 0,
    ): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<AutomaticPriceWorker>(1, TimeUnit.HOURS)
            .setInputData(workDataOf("automatic" to true))
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(constraints(wifiOnly))
            .build()

    private fun constraints(wifiOnly: Boolean) =
        Constraints
            .Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

    fun wallets(
        context: Context,
        enabled: Boolean,
        wifiOnly: Boolean,
        name: String = "polion-refresh",
        initialDelayMillis: Long = 0,
    ): Operation {
        val manager = WorkManager.getInstance(context)
        return if (enabled) {
            manager.enqueueUniquePeriodicWork(
                name,
                ExistingPeriodicWorkPolicy.UPDATE,
                walletRequest(wifiOnly, initialDelayMillis),
            )
        } else {
            manager.cancelUniqueWork(name)
        }
    }

    fun prices(
        context: Context,
        enabled: Boolean,
        wifiOnly: Boolean,
        name: String = "polion-price-refresh",
        initialDelayMillis: Long = 0,
    ): Operation {
        val manager = WorkManager.getInstance(context)
        return if (enabled) {
            manager.enqueueUniquePeriodicWork(
                name,
                ExistingPeriodicWorkPolicy.UPDATE,
                priceRequest(wifiOnly, initialDelayMillis),
            )
        } else {
            manager.cancelUniqueWork(name)
        }
    }
}
