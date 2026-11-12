package io.valroot.android

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.backup.PortableBackup
import io.valroot.core.bitcoin.bitcoinMainnetAddress
import io.valroot.core.bitcoin.bitcoinWallet
import io.valroot.core.bitcoin.descriptorChecksum
import io.valroot.core.model.OwnerId
import io.valroot.core.ownership.Owner
import io.valroot.core.storage.AndroidSqliteDatabase
import io.valroot.core.storage.RawObservation
import io.valroot.core.storage.Store
import io.valroot.core.storage.sha256
import io.valroot.testing.verifyBitcoinAccountingWorkflow
import java.io.File

/** Exercises the actual Android SQLite and crypto bindings using disposable fixture databases. */
class ReliabilityInstrumentation : Instrumentation() {
    private var livePrices = false
    private var benchmark = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        livePrices = arguments?.getString("live_prices") == "true"
        benchmark = arguments?.getString("benchmark") == "chart"
        start()
    }

    /**
     * Times the queries behind a chart period change on a copy of this user's database, without the UI. Run with
     * `-e benchmark chart`; results are milliseconds in the instrumentation output.
     */
    private fun chartBenchmark() {
        val results = Bundle()
        val source = targetContext.getDatabasePath("portfolio.sqlite")
        val copy = File(targetContext.cacheDir, "chart-benchmark.sqlite")
        try {
            check(source.exists()) { "No portfolio database for this user" }
            source.copyTo(copy, overwrite = true)
            listOf("-wal", "-shm").forEach { suffix ->
                File(source.path + suffix).takeIf { it.exists() }?.copyTo(File(copy.path + suffix), overwrite = true)
            }

            fun <T> time(
                name: String,
                block: () -> T,
            ): T {
                val start = System.nanoTime()
                val value = block()
                results.putLong(name, (System.nanoTime() - start) / 1_000_000L)
                return value
            }
            AndroidSqliteDatabase(copy.path).use { db ->
                val app = time("open") { PortfolioApplication(db, System::currentTimeMillis) }
                time("snapshot") { app.snapshot() }
                time("snapshotAgain") { app.snapshot() }
                time("trivialQueries100") { repeat(100) { db.query("SELECT value FROM application_settings WHERE key='currency'") } }
                val end = System.currentTimeMillis()
                repeat(2) { round ->
                    for ((label, days, intervals) in listOf(Triple("1D", 1L, 96), Triple("1W", 7L, 84), Triple("1M", 30L, 90), Triple("1Y", 365L, 104))) {
                        val start = end - days * 86_400_000L
                        val times = (0..intervals).map { start + (end - start) / intervals * it }
                        time("history$label.$round") { app.analytics.historySeries(times, null) }
                    }
                }
                time("currency") { app.assets.displayCurrency(null) }
                time("firstActivity") { app.analytics.firstHistoryActivity(null) }
                time("priceSeries97") {
                    app.analytics.priceSeries(io.valroot.core.bitcoin.BITCOIN_ASSET, app.assets.currency(), (0..96).map { end - 86_400_000L + 900_000L * it })
                }
                time("marketOverview") { app.market.overview() }
                time("marketOverviewAgain") { app.market.overview() }
                time("accountingReport") { app.accounting.report(null) }
                time("reload+snapshot") {
                    app.reload()
                    app.snapshot()
                }
            }
            finish(Activity.RESULT_OK, results)
        } catch (failure: Exception) {
            results.putString("error", failure.toString())
            finish(Activity.RESULT_CANCELED, results)
        } finally {
            listOf("", "-wal", "-shm", "-journal").forEach { File(copy.path + it).delete() }
        }
    }

    override fun onStart() {
        if (benchmark) {
            chartBenchmark()
            return
        }
        val results = Bundle()
        var failures = 0
        val checks =
            listOf<Pair<String, () -> Unit>>(
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
                "boundedDuplicateProjection" to {
                    fixture { file -> AndroidSqliteDatabase(file.path).use(::verifyBoundedDuplicateNative) }
                },
                "batchedClassificationRollback" to {
                    fixture { file ->
                        AndroidSqliteDatabase(file.path).use { db ->
                            Store(db)
                            val overlays =
                                io.valroot.core.reconcile
                                    .OverlayRepository(db)
                            val account =
                                io.valroot.core.model
                                    .AccountId("account")
                            val asset =
                                io.valroot.core.model
                                    .AssetId("asset")
                            val entries =
                                (0 until 130).map { index ->
                                    val id = "batch-$index"
                                    io.valroot.core.ledger.Activity(
                                        io.valroot.core.model
                                            .ActivityId(id),
                                        index.toLong(),
                                        id,
                                        io.valroot.core.ledger.ActivityKind.DEPOSIT,
                                        io.valroot.core.ledger.ActivityStatus.CONFIRMED,
                                        listOf(
                                            io.valroot.core.ledger.Movement(
                                                io.valroot.core.model
                                                    .EventId(id),
                                                account,
                                                io.valroot.core.model
                                                    .AtomicAmount(
                                                        asset,
                                                        io.valroot.core.model.ExactInteger
                                                            .parse("1"),
                                                    ),
                                                io.valroot.core.ledger.MovementRole.TRANSFER,
                                            ),
                                        ),
                                        emptySet(),
                                        null,
                                        manual = true,
                                    )
                                }
                            overlays.saveOverlay(
                                io.valroot.core.reconcile
                                    .ActivityOverlay(entries.last().id, label = "checked"),
                            )
                            val expected = overlays.replay(entries)
                            val actual = mutableListOf<io.valroot.core.reconcile.EffectiveActivity>()
                            overlays.replayClassificationsBatched(entries.chunked(128).asSequence()) { actual.addAll(it) }
                            check(expected == actual)
                            db.execute("CREATE TABLE batch_fixture (id TEXT PRIMARY KEY)")
                            check(
                                runCatching {
                                    overlays.replayClassificationsBatched(entries.chunked(128).asSequence()) { batch ->
                                        batch.forEach { db.execute("INSERT INTO batch_fixture VALUES (?)", listOf(it.activity.id.value)) }
                                        error("interrupted")
                                    }
                                }.isFailure,
                            )
                            check(db.query("SELECT COUNT(*) FROM batch_fixture").single().single() == "0")
                        }
                    }
                },
                "streamedHoldingsRollback" to {
                    fixture { file ->
                        AndroidSqliteDatabase(file.path).use { db ->
                            Store(db)
                            val repo =
                                io.valroot.core.holdings
                                    .HoldingsRepository(db)
                            val account =
                                io.valroot.core.model
                                    .AccountId("account")
                            val asset =
                                io.valroot.core.model
                                    .AssetId("asset")
                            val entries =
                                (0 until 130).map { index ->
                                    val id = "fixture-$index"
                                    io.valroot.core.ledger.Activity(
                                        io.valroot.core.model
                                            .ActivityId(id),
                                        index.toLong(),
                                        id,
                                        io.valroot.core.ledger.ActivityKind.DEPOSIT,
                                        io.valroot.core.ledger.ActivityStatus.CONFIRMED,
                                        listOf(
                                            io.valroot.core.ledger.Movement(
                                                io.valroot.core.model
                                                    .EventId(id),
                                                account,
                                                io.valroot.core.model.AtomicAmount(
                                                    asset,
                                                    io.valroot.core.model.ExactInteger
                                                        .parse("100000000000000000000"),
                                                ),
                                                io.valroot.core.ledger.MovementRole.TRANSFER,
                                            ),
                                        ),
                                        emptySet(),
                                        null,
                                        manual = true,
                                    )
                                }
                            repo.refreshStream(entries.asSequence())
                            val before = repo.current(io.valroot.core.holdings.BalanceCoverage.PARTIAL, 1)
                            val interrupted =
                                sequence {
                                    yield(entries.first().copy(status = io.valroot.core.ledger.ActivityStatus.REVERSED))
                                    error("interrupted")
                                }
                            check(runCatching { repo.refreshStream(interrupted) }.isFailure)
                            check(before == repo.current(io.valroot.core.holdings.BalanceCoverage.PARTIAL, 1))
                            repo.refreshStream(entries.drop(1).asSequence())
                            val incremental = repo.current(io.valroot.core.holdings.BalanceCoverage.PARTIAL, 1)
                            repo.refresh(entries.drop(1), forceReplay = true)
                            check(incremental == repo.current(io.valroot.core.holdings.BalanceCoverage.PARTIAL, 1))
                        }
                    }
                },
                "largeRawRevisionRepeatIngestion" to {
                    fixture { file ->
                        val payload = "\u20ac".repeat(1_100_000) + "\u0000\ud83d\ude80tail"
                        val record =
                            RawObservation(
                                "large-fixture",
                                "fixture",
                                "large",
                                "fixture",
                                "provider-a",
                                "2026-01-01T00:00:00Z",
                                payload,
                                "1",
                                sha256(payload),
                                "fixture",
                            )
                        Store(AndroidSqliteDatabase(file.path)).use { it.ingest(record) }
                        AndroidSqliteDatabase(file.path).use { db ->
                            Store(db).use { store ->
                                store.ingest(record)
                                store.ingest(record.copy(sourceId = "provider-b"))
                                check(db.query("SELECT COUNT(*) FROM raw_revisions").single().single() == "1")
                                check(store.observations().size == 2)
                                check(db.query("SELECT payload=? FROM raw_revisions", listOf(payload)).single().single() == "1")
                                check(store.revisions().single()[2] == payload)
                                val rows =
                                    db.query(
                                        "SELECT payload AS duplicate, NULL AS duplicate, '' AS empty, 1.25 AS real, 17 AS number " +
                                            "FROM raw_revisions WHERE record_id=? UNION ALL " +
                                            "SELECT payload, NULL, '', 1.25, 18 FROM raw_revisions WHERE record_id=? ORDER BY number DESC",
                                        listOf(record.id, record.id),
                                    )
                                check(rows == listOf(listOf(payload, null, "", "1.25", "18"), listOf(payload, null, "", "1.25", "17")))
                                check(
                                    runCatching {
                                        db.transaction {
                                            db.execute("UPDATE raw_revisions SET payload_version='rollback'")
                                            check(store.revisions().single()[3] == "rollback")
                                            error("fixture rollback")
                                        }
                                    }.isFailure,
                                )
                                check(store.revisions().single()[3] == "1")
                                val backup = PortableBackup.discover(db)
                                val archive = backup.export(db)
                                fixture { restoredFile ->
                                    AndroidSqliteDatabase(restoredFile.path).use { restoredDb ->
                                        Store(restoredDb).use { restored ->
                                            backup.restoreToStaging(restoredDb, archive)
                                            check(restored.revisions() == store.revisions())
                                            check(restored.observations() == store.observations())
                                        }
                                    }
                                }
                                check(db.query("SELECT name FROM sqlite_temp_master WHERE type='table'").isEmpty())
                                check(runCatching { store.ingest(record.copy(payloadVersion = "2")) }.isFailure)
                                db.execute("UPDATE raw_revisions SET payload=?", listOf(payload + "corrupt"))
                                check(runCatching { store.ingest(record.copy(sourceId = "provider-c")) }.isFailure)
                                check(store.observations().size == 2)
                                check(db.query("PRAGMA integrity_check").single().single() == "ok")
                            }
                        }
                    }
                },
                "accountHistoryWorkflow" to {
                    val files = mutableListOf<File>()
                    try {
                        io.valroot.testing.verifyAccountHistoryWorkflow {
                            val file = File.createTempFile("valroot-account-fixture-", ".sqlite", targetContext.cacheDir)
                            files.add(file)
                            AndroidSqliteDatabase(file.path)
                        }
                    } finally {
                        files.forEach { file ->
                            file.delete()
                            File(file.path + "-journal").delete()
                        }
                    }
                },
                "manualTransactionWorkflow" to {
                    val files = mutableListOf<File>()
                    try {
                        io.valroot.testing.verifyManualTransactionWorkflow {
                            val file = File.createTempFile("valroot-manual-fixture-", ".sqlite", targetContext.cacheDir)
                            files.add(file)
                            AndroidSqliteDatabase(file.path)
                        }
                    } finally {
                        files.forEach { file ->
                            file.delete()
                            File(file.path + "-journal").delete()
                        }
                    }
                },
                "bitcoinAccountingWorkflow" to {
                    val files = mutableListOf<File>()
                    try {
                        verifyBitcoinAccountingWorkflow {
                            val file = File.createTempFile("valroot-chain-fixture-", ".sqlite", targetContext.cacheDir)
                            files.add(file)
                            AndroidSqliteDatabase(file.path)
                        }
                    } finally {
                        files.forEach { file ->
                            file.delete()
                            File(file.path + "-journal").delete()
                        }
                    }
                },
                "dataErasure" to {
                    for (mode in listOf("DELETE", "PERSIST", "WAL")) {
                        fixture { file ->
                            AndroidSqliteDatabase(file.path).use { db ->
                                db.query("PRAGMA journal_mode = $mode")
                                val app = PortfolioApplication(db, System::currentTimeMillis)
                                app.demo.seed()
                                val canary = "PRIVATE_ANDROID_ERASURE_CANARY_"
                                app.transactions.saveOverlay(
                                    io.valroot.core.reconcile.ActivityOverlay(
                                        io.valroot.core.model
                                            .ActivityId("demo-deposit"),
                                        note = canary.repeat(1000),
                                    ),
                                )
                                app.backup.wipe()
                                check(app.snapshot().activities.isEmpty())
                                listOf("", "-journal", "-wal", "-shm").forEach { suffix ->
                                    val active = File(file.path + suffix)
                                    check(!active.exists() || !active.readBytes().toString(Charsets.ISO_8859_1).contains(canary))
                                }
                                check(db.query("PRAGMA integrity_check").single().single() == "ok")
                            }
                        }
                    }
                },
                "automaticHourlyPriceWorkflow" to {
                    fixture { file ->
                        AndroidSqliteDatabase(file.path).use { db ->
                            val hour = 3_600_000L
                            val current = System.currentTimeMillis() / hour * hour
                            val at = current + 30_000
                            val app = PortfolioApplication(db) { at }
                            val payload =
                                """{"error":[],"result":{"XXBTZUSD":[""" +
                                    """[${(current - 2 * hour) / 1000},"1","2","1","40000.12","1","1",1],""" +
                                    """[${(current - hour) / 1000},"1","2","1","41000.34","1","1",1],""" +
                                    """[${current / 1000},"1","2","1","99999","1","1",1]],"last":0}}"""
                            app.prices.setAutomaticConsent(true)
                            check(app.prices.refreshAutomatic({ payload }) == 2)
                            val prices = db.query("SELECT valid_at,value FROM market_prices ORDER BY valid_at")
                            check(prices == listOf(listOf((current - hour).toString(), "40000.12"), listOf(current.toString(), "41000.34")))
                            var calls = 0
                            check(
                                runCatching {
                                    app.prices.refreshAutomatic({ payload }) {
                                        if (++calls ==
                                            3
                                        ) {
                                            error("cancelled")
                                        }
                                    }
                                }.isFailure,
                            )
                            check(db.query("SELECT valid_at,value FROM market_prices ORDER BY valid_at") == prices)
                            app.prices.setAutomaticConsent(false)
                            check(runCatching { app.prices.refreshAutomatic({ error("must not fetch") }) }.isFailure)
                        }
                    }
                },
                "historicalPriceWorkflow" to {
                    fixture { file ->
                        AndroidSqliteDatabase(file.path).use { db ->
                            val at = System.currentTimeMillis()
                            val day = at / 86_400_000L * 86_400_000L
                            val app = PortfolioApplication(db) { at }
                            app.prices.setSourceConsent(true)
                            val result =
                                app.prices.fetchHistorical(
                                    { """{"market_data":{"current_price":{"usd":42000.123456789}}}""" },
                                    io.valroot.core.model
                                        .AssetId("bitcoin:btc"),
                                    io.valroot.core.model
                                        .AssetId("USD"),
                                    day,
                                )
                            check(
                                result.observation
                                    ?.price
                                    ?.value
                                    ?.toString() == "42000.123456789",
                            )
                            check(db.query("SELECT COUNT(*) FROM market_prices").single().single() == "1")
                            app.prices.setSourceConsent(false)
                            check(db.query("SELECT COUNT(*) FROM market_prices").single().single() == "1")
                            val btc =
                                io.valroot.core.model
                                    .AssetId("bitcoin:btc")
                            val usd =
                                io.valroot.core.model
                                    .AssetId("USD")
                            app.prices.recordManual(
                                io.valroot.core.market.PriceObservation(
                                    "manual-fixture",
                                    io.valroot.core.model
                                        .Price(
                                            btc,
                                            usd,
                                            io.valroot.core.model.ExactDecimal
                                                .parse("50000"),
                                        ),
                                    null,
                                    day,
                                    at,
                                    true,
                                ),
                            )
                            check(app.prices.useProvider(btc, usd) == 1)
                            check(db.query("SELECT COUNT(*) FROM market_override_withdrawals").single().single() == "1")
                        }
                    }
                },
                "persistedProjections" to {
                    fixture { file ->
                        AndroidSqliteDatabase(file.path).use { db ->
                            val app = PortfolioApplication(db, System::currentTimeMillis)
                            app.demo.seed()
                            check(app.snapshot().activityTotal == 4)
                            check(
                                app
                                    .analytics.historySeries(listOf(System.currentTimeMillis()))
                                    .single()
                                    .valuations
                                    .isNotEmpty(),
                            )
                            check(app.analytics.report(System.currentTimeMillis()).categories.isNotEmpty())
                        }
                        AndroidSqliteDatabase(file.path).use { db ->
                            check(PortfolioApplication(db, System::currentTimeMillis).snapshot().activityTotal == 4)
                        }
                    }
                },
                "wrongPasswordPreservesStaging" to {
                    fixture { source ->
                        fixture { target ->
                            AndroidSqliteDatabase(source.path).use { sourceDb ->
                                val app = PortfolioApplication(sourceDb, System::currentTimeMillis)
                                app.demo.seed()
                                val archive = app.backup.export("fixture-password".toCharArray())
                                AndroidSqliteDatabase(target.path).use { targetDb ->
                                    val staging = PortfolioApplication(targetDb, System::currentTimeMillis)
                                    check(runCatching { app.backup.restoreToStaging(targetDb, archive, "wrong-password".toCharArray()) }.isFailure)
                                    check(targetDb.query("SELECT COUNT(*) FROM ledger_activities").single().single() == "0")
                                    app.backup.restoreToStaging(targetDb, archive, "fixture-password".toCharArray())
                                    check(PortfolioApplication(targetDb, System::currentTimeMillis).snapshot().activityTotal == 4)
                                }
                            }
                        }
                    }
                },
                "fullDatabaseRollsBack" to {
                    fixture { file ->
                        AndroidSqliteDatabase(file.path).use { db ->
                            val app = PortfolioApplication(db, System::currentTimeMillis)
                            app.demo.seed()
                            val before = app.snapshot().ownership
                            val pages =
                                db
                                    .query("PRAGMA page_count")
                                    .single()
                                    .single()!!
                                    .toInt()
                            db.query("PRAGMA max_page_count = ${pages + 1}")
                            check(runCatching { app.accounts.saveOwner(Owner(OwnerId("overflow"), "x".repeat(1_000_000))) }.isFailure)
                            db.query("PRAGMA max_page_count = 1073741823")
                            check(app.snapshot().ownership == before)
                            check(db.query("PRAGMA integrity_check").single().single() == "ok")
                        }
                    }
                },
                "secureLockRoundTripAndTamper" to {
                    val alias = "io.valroot.test." + java.util.UUID.randomUUID()
                    val preferences = targetContext.getSharedPreferences(alias, 0)
                    try {
                        val store = SecureLockStore(preferences, alias)
                        val verifier = LockPassword.create("fixture-password")
                        store.write(verifier)
                        check(store.hasLock && store.read() == verifier)
                        val bytes =
                            java.util.Base64
                                .getDecoder()
                                .decode(preferences.getString("secure_verifier_v1", null))
                        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                        check(
                            preferences
                                .edit()
                                .putString(
                                    "secure_verifier_v1",
                                    java.util.Base64
                                        .getEncoder()
                                        .encodeToString(bytes),
                                ).commit(),
                        )
                        check(runCatching { store.read() }.isFailure)
                    } finally {
                        preferences.edit().clear().commit()
                        java.security.KeyStore.getInstance("AndroidKeyStore").apply {
                            load(null)
                            deleteEntry(alias)
                        }
                    }
                },
                "bitcoinPublicWalletDerivation" to {
                    // BIP 84 account zpub serialized with mainnet xpub version bytes.
                    val xpub =
                        "xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3XyuvP" +
                            "EbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V"
                    val wallet = bitcoinWallet(xpub)
                    check(wallet.address(0, 0) == "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu")
                    check(wallet.address(0, 1) == "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g")
                    check(wallet.address(1, 0) == "bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el")
                    check(bitcoinWallet("pkh($xpub/0/*)").address(0, 0) == "1JaUQDVNRdhfNsVncGkXedaPSM5Gc54Hso")
                    check(bitcoinWallet("sh(wpkh($xpub/0/*))").address(0, 0) == "3GtVZYzsKF6Feikdjd4bDyPdAiyeHANY9b")
                    check(descriptorChecksum("raw(deadbeef)") == "89f8spxm")
                    check(bitcoinMainnetAddress(wallet.address(0, 0)) == wallet.address(0, 0))
                    check(runCatching { bitcoinWallet("wpkh($xpub/0/*)#aaaaaaaa") }.isFailure)
                    check(runCatching { bitcoinMainnetAddress("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyq") }.isFailure)
                },
                "passwordVerification" to {
                    val verifier = LockPassword.create("fixture-password")
                    check(LockPassword.verify("fixture-password", verifier))
                    check(!LockPassword.verify("wrong-password", verifier))
                    check(!LockPassword.verify("fixture-password", "corrupt"))
                },
            ) +
                if (livePrices) {
                    listOf(
                        "liveHourlyPrice" to {
                            fixture { file ->
                                AndroidSqliteDatabase(file.path).use { db ->
                                    val app = PortfolioApplication(db, System::currentTimeMillis)
                                    app.prices.setAutomaticConsent(true)
                                    val transport = KrakenPriceTransport()
                                    val count = app.prices.refreshAutomatic(transport::get)
                                    check(count in 1..720)
                                    val dailyCount = app.prices.refreshAutomaticDailyIfDue(transport::get)
                                    check(dailyCount in 1..720)
                                    check(app.prices.refreshAutomaticDailyIfDue({ error("Daily fetch should be skipped") }) == 0)
                                    val rows =
                                        db.query(
                                            "SELECT valid_at, value FROM market_prices WHERE provider='kraken' ORDER BY valid_at",
                                        )
                                    check(rows.size in count..(count + dailyCount))
                                    check(rows.last()[0]!!.toLong() <= System.currentTimeMillis())
                                }
                            }
                        },
                    )
                } else {
                    emptyList()
                }
        checks.forEach { (name, check) ->
            try {
                check()
                results.putString(name, "PASS")
            } catch (failure: Throwable) {
                failures++
                results.putString(name, failure.stackTraceToString())
            }
        }
        results.putInt("checks", checks.size)
        results.putInt("failures", failures)
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
    }

    private fun fixture(block: (File) -> Unit) {
        val file = File.createTempFile("valroot-reliability-", ".sqlite", targetContext.cacheDir)
        try {
            block(file)
        } finally {
            file.delete()
            File(file.path + "-journal").delete()
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
        }
    }
}
