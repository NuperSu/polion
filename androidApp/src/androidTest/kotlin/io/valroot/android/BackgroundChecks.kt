package io.valroot.android

import android.app.Instrumentation
import io.valroot.core.market.KrakenPricePacer
import io.valroot.core.market.KrakenPriceTransport

/** Foreground-service, WorkManager scheduling, price-refresh and Kraken rate-limit checks. */
internal fun Instrumentation.backgroundChecks(): List<ReliabilityCheck> =
    listOf<ReliabilityCheck>(
        "queuedWalletDiscoveryService" to {
            val previousStatus = RefreshWorker.syncStatus.value
            check(
                previousStatus.phase != io.valroot.ui.SyncPhase.SYNCING,
            ) { "Wait for existing Bitcoin work before running this fixture" }
            val scenario = SyncServiceProbeState()
            SyncServiceProbe.state = scenario
            val activity =
                startActivitySync(
                    android.content
                        .Intent(targetContext, SyncServiceProbeActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            val service = android.content.Intent(targetContext, SyncServiceProbe::class.java)
            try {
                runOnMainSync { targetContext.startForegroundService(service) }
                check(scenario.firstStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))
                runOnMainSync {
                    targetContext.startForegroundService(android.content.Intent(service).putExtra("wallet_id", "wallet-a"))
                    targetContext.startForegroundService(android.content.Intent(service).putExtra("wallet_id", "wallet-b"))
                }
                check(scenario.delivered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                scenario.releaseFirst.countDown()
                check(scenario.secondStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))
                waitForIdleSync()
                check(scenario.destroyed.count == 1L)
                scenario.releaseSecond.countDown()
                check(scenario.thirdStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))
                waitForIdleSync()
                check(scenario.destroyed.count == 1L)
                check(scenario.started.toList() == listOf("history", "wallet-a", "wallet-b"))
                check(scenario.instances == 1)
                scenario.releaseThird.countDown()
                check(scenario.destroyed.await(10, java.util.concurrent.TimeUnit.SECONDS))
            } finally {
                scenario.releaseAll()
                runOnMainSync {
                    targetContext.stopService(service)
                    activity.finish()
                }
                if (scenario.instances > 0) scenario.destroyed.await(10, java.util.concurrent.TimeUnit.SECONDS)
                RefreshWorker.syncStatus.value = previousStatus
            }
        },
        "priceRateLimit" to {
            var now = 0L
            var requests = 0
            var code = 429
            val response =
                object : java.net.HttpURLConnection(java.net.URI("https://api.kraken.com").toURL()) {
                    override fun connect() = Unit

                    override fun disconnect() = Unit

                    override fun usingProxy() = false

                    override fun getResponseCode() = code

                    override fun getHeaderField(name: String?) = if (name == "Retry-After") "120" else null

                    override fun getInputStream() = "{}".byteInputStream()
                }
            val pacer = KrakenPricePacer(clock = { now }, sleep = { now += it })
            val transport =
                KrakenPriceTransport(openConnection = {
                    requests++
                    response
                }, pacer = pacer)
            val url = "https://api.kraken.com/0/public/OHLC?pair=XBTUSD&interval=60"
            check(runCatching { transport.get(url) }.exceptionOrNull() is io.valroot.core.market.KrakenRateLimitedException)
            code = 200
            check(runCatching { transport.get(url) }.exceptionOrNull() is io.valroot.core.market.KrakenRateLimitedException)
            check(requests == 1)
            now += 120_000
            check(transport.get(url) == "{}" && requests == 2)
        },
        "refreshSchedulingConstraints" to {
            val manager = androidx.work.WorkManager.getInstance(targetContext)
            val suffix =
                java.util.UUID
                    .randomUUID()
                    .toString()
            val wallet = "valroot-fixture-history-$suffix"
            val price = "valroot-fixture-price-$suffix"
            val deferred =
                java.util.concurrent.TimeUnit.DAYS
                    .toMillis(1)
            try {
                AndroidRefreshScheduling
                    .wallets(
                        targetContext,
                        true,
                        true,
                        wallet,
                        deferred,
                    ).result
                    .get(10, java.util.concurrent.TimeUnit.SECONDS)
                AndroidRefreshScheduling
                    .prices(
                        targetContext,
                        true,
                        true,
                        price,
                        deferred,
                    ).result
                    .get(10, java.util.concurrent.TimeUnit.SECONDS)
                val history = manager.getWorkInfosForUniqueWork(wallet).get().single()
                val prices = manager.getWorkInfosForUniqueWork(price).get().single()
                check(history.constraints.requiredNetworkType == androidx.work.NetworkType.UNMETERED)
                check(prices.constraints.requiredNetworkType == androidx.work.NetworkType.UNMETERED)
                check(
                    history.periodicityInfo?.repeatIntervalMillis ==
                        java.util.concurrent.TimeUnit.MINUTES
                            .toMillis(30),
                )
                check(
                    prices.periodicityInfo?.repeatIntervalMillis ==
                        java.util.concurrent.TimeUnit.HOURS
                            .toMillis(1),
                )
                check(history.initialDelayMillis == deferred && prices.initialDelayMillis == deferred)
                check(
                    androidx.work.WorkManager
                        .getInstance(targetContext)
                        .getWorkInfosForUniqueWork(wallet)
                        .get()
                        .single()
                        .id ==
                        history.id,
                )
                AndroidRefreshScheduling.wallets(targetContext, true, false, wallet, deferred).result.get()
                AndroidRefreshScheduling.prices(targetContext, true, false, price, deferred).result.get()
                check(
                    manager
                        .getWorkInfosForUniqueWork(wallet)
                        .get()
                        .single()
                        .constraints.requiredNetworkType ==
                        androidx.work.NetworkType.CONNECTED,
                )
                check(
                    manager
                        .getWorkInfosForUniqueWork(price)
                        .get()
                        .single()
                        .constraints.requiredNetworkType ==
                        androidx.work.NetworkType.CONNECTED,
                )
                AndroidRefreshScheduling.wallets(targetContext, false, false, wallet).result.get()
                AndroidRefreshScheduling.prices(targetContext, false, false, price).result.get()
                check(manager.getWorkInfosForUniqueWork(wallet).get().all { it.state == androidx.work.WorkInfo.State.CANCELLED })
                check(manager.getWorkInfosForUniqueWork(price).get().all { it.state == androidx.work.WorkInfo.State.CANCELLED })
            } finally {
                manager.cancelUniqueWork(wallet).result.get()
                manager.cancelUniqueWork(price).result.get()
            }
        },
        "priceRefreshProgress" to {
            val tracker = PriceRefreshTracker()
            check(tracker.status.value == io.valroot.ui.PriceRefreshPhase.IDLE)
            tracker.setWorkState(queued = true, running = false)
            check(tracker.status.value == io.valroot.ui.PriceRefreshPhase.QUEUED)
            tracker.setWorkerRunning(true)
            check(tracker.status.value == io.valroot.ui.PriceRefreshPhase.RUNNING)
            tracker.setWorkerRunning(false)
            check(tracker.status.value == io.valroot.ui.PriceRefreshPhase.QUEUED)
            tracker.setWorkState(queued = false, running = true)
            check(tracker.status.value == io.valroot.ui.PriceRefreshPhase.RUNNING)
            tracker.setWorkState(queued = false, running = false)
            check(tracker.status.value == io.valroot.ui.PriceRefreshPhase.IDLE)
        },
        "priceRefreshQueuedWork" to {
            val manager = androidx.work.WorkManager.getInstance(targetContext)
            val name = "valroot-test-price-" + java.util.UUID.randomUUID()
            try {
                val request =
                    androidx.work.OneTimeWorkRequest
                        .Builder(AutomaticPriceWorker::class.java)
                        .setInitialDelay(1, java.util.concurrent.TimeUnit.DAYS)
                        .build()
                manager
                    .enqueueUniqueWork(name, androidx.work.ExistingWorkPolicy.KEEP, request)
                    .result
                    .get(10, java.util.concurrent.TimeUnit.SECONDS)
                val saved = manager.getWorkInfosForUniqueWork(name).get(10, java.util.concurrent.TimeUnit.SECONDS)
                check(saved.single().state == androidx.work.WorkInfo.State.ENQUEUED)
                val reopened =
                    androidx.work.WorkManager
                        .getInstance(targetContext)
                        .getWorkInfosForUniqueWork(name)
                        .get(10, java.util.concurrent.TimeUnit.SECONDS)
                check(reopened.single().id == request.id && reopened.single().state == androidx.work.WorkInfo.State.ENQUEUED)
            } finally {
                manager.cancelUniqueWork(name).result.get(10, java.util.concurrent.TimeUnit.SECONDS)
            }
        },
    )
