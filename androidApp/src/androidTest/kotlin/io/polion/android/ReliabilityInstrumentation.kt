package io.polion.android

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import io.polion.core.application.PortfolioApplication
import io.polion.core.backup.PortableBackup
import io.polion.core.bitcoin.bitcoinMainnetAddress
import io.polion.core.bitcoin.bitcoinWallet
import io.polion.core.bitcoin.descriptorChecksum
import io.polion.core.model.OwnerId
import io.polion.core.ownership.Owner
import io.polion.core.storage.AndroidSqliteDatabase
import io.polion.core.storage.RawObservation
import io.polion.core.storage.Store
import io.polion.core.storage.sha256
import io.polion.testing.verifyBitcoinAccountingWorkflow
import java.io.File

/** Exercises the actual Android SQLite and crypto bindings using disposable fixture databases. */
class ReliabilityInstrumentation : Instrumentation() {
    private var livePrices = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        livePrices = arguments?.getString("live_prices") == "true"
        start()
    }

    override fun onStart() {
        val results = Bundle()
        var failures = 0
        val checks =
            listOf<Pair<String, () -> Unit>>(
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
                    val transport = KrakenPriceTransport(openConnection = {
                        requests++
                        response
                    }, pacer = pacer)
                    val url = "https://api.kraken.com/0/public/OHLC?pair=XBTUSD&interval=60"
                    check(runCatching { transport.get(url) }.exceptionOrNull() is io.polion.core.market.KrakenRateLimitedException)
                    code = 200
                    check(runCatching { transport.get(url) }.exceptionOrNull() is io.polion.core.market.KrakenRateLimitedException)
                    check(requests == 1)
                    now += 120_000
                    check(transport.get(url) == "{}" && requests == 2)
                },
                "priceRefreshProgress" to {
                    val tracker = PriceRefreshTracker()
                    check(tracker.status.value == io.polion.ui.PriceRefreshPhase.IDLE)
                    tracker.setWorkState(queued = true, running = false)
                    check(tracker.status.value == io.polion.ui.PriceRefreshPhase.QUEUED)
                    tracker.setWorkerRunning(true)
                    check(tracker.status.value == io.polion.ui.PriceRefreshPhase.RUNNING)
                    tracker.setWorkerRunning(false)
                    check(tracker.status.value == io.polion.ui.PriceRefreshPhase.QUEUED)
                    tracker.setWorkState(queued = false, running = true)
                    check(tracker.status.value == io.polion.ui.PriceRefreshPhase.RUNNING)
                    tracker.setWorkState(queued = false, running = false)
                    check(tracker.status.value == io.polion.ui.PriceRefreshPhase.IDLE)
                },
                "priceRefreshQueuedWork" to {
                    val manager = androidx.work.WorkManager.getInstance(targetContext)
                    val name = "polion-test-price-" + java.util.UUID.randomUUID()
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
                                io.polion.core.reconcile
                                    .OverlayRepository(db)
                            val account =
                                io.polion.core.model
                                    .AccountId("account")
                            val asset =
                                io.polion.core.model
                                    .AssetId("asset")
                            val entries =
                                (0 until 130).map { index ->
                                    val id = "batch-$index"
                                    io.polion.core.ledger.Activity(
                                        io.polion.core.model
                                            .ActivityId(id),
                                        index.toLong(),
                                        id,
                                        io.polion.core.ledger.ActivityKind.DEPOSIT,
                                        io.polion.core.ledger.ActivityStatus.CONFIRMED,
                                        listOf(
                                            io.polion.core.ledger.Movement(
                                                io.polion.core.model
                                                    .EventId(id),
                                                account,
                                                io.polion.core.model
                                                    .AtomicAmount(
                                                        asset,
                                                        io.polion.core.model.ExactInteger
                                                            .parse("1"),
                                                    ),
                                                io.polion.core.ledger.MovementRole.TRANSFER,
                                            ),
                                        ),
                                        emptySet(),
                                        null,
                                        manual = true,
                                    )
                                }
                            overlays.saveOverlay(
                                io.polion.core.reconcile
                                    .ActivityOverlay(entries.last().id, label = "checked"),
                            )
                            val expected = overlays.replay(entries)
                            val actual = mutableListOf<io.polion.core.reconcile.EffectiveActivity>()
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
                                io.polion.core.holdings
                                    .HoldingsRepository(db)
                            val account =
                                io.polion.core.model
                                    .AccountId("account")
                            val asset =
                                io.polion.core.model
                                    .AssetId("asset")
                            val entries =
                                (0 until 130).map { index ->
                                    val id = "fixture-$index"
                                    io.polion.core.ledger.Activity(
                                        io.polion.core.model
                                            .ActivityId(id),
                                        index.toLong(),
                                        id,
                                        io.polion.core.ledger.ActivityKind.DEPOSIT,
                                        io.polion.core.ledger.ActivityStatus.CONFIRMED,
                                        listOf(
                                            io.polion.core.ledger.Movement(
                                                io.polion.core.model
                                                    .EventId(id),
                                                account,
                                                io.polion.core.model.AtomicAmount(
                                                    asset,
                                                    io.polion.core.model.ExactInteger
                                                        .parse("100000000000000000000"),
                                                ),
                                                io.polion.core.ledger.MovementRole.TRANSFER,
                                            ),
                                        ),
                                        emptySet(),
                                        null,
                                        manual = true,
                                    )
                                }
                            repo.refreshStream(entries.asSequence())
                            val before = repo.current(io.polion.core.holdings.BalanceCoverage.PARTIAL, 1)
                            val interrupted =
                                sequence {
                                    yield(entries.first().copy(status = io.polion.core.ledger.ActivityStatus.REVERSED))
                                    error("interrupted")
                                }
                            check(runCatching { repo.refreshStream(interrupted) }.isFailure)
                            check(before == repo.current(io.polion.core.holdings.BalanceCoverage.PARTIAL, 1))
                            repo.refreshStream(entries.drop(1).asSequence())
                            val incremental = repo.current(io.polion.core.holdings.BalanceCoverage.PARTIAL, 1)
                            repo.refresh(entries.drop(1), forceReplay = true)
                            check(incremental == repo.current(io.polion.core.holdings.BalanceCoverage.PARTIAL, 1))
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
                "bitcoinAccountingWorkflow" to {
                    val files = mutableListOf<File>()
                    try {
                        verifyBitcoinAccountingWorkflow {
                            val file = File.createTempFile("polion-chain-fixture-", ".sqlite", targetContext.cacheDir)
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
                                app.seedDemo()
                                val canary = "PRIVATE_ANDROID_ERASURE_CANARY_"
                                app.saveOverlay(
                                    io.polion.core.reconcile.ActivityOverlay(
                                        io.polion.core.model
                                            .ActivityId("demo-deposit"),
                                        note = canary.repeat(1000),
                                    ),
                                )
                                app.wipe()
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
                            app.setAutomaticPriceConsent(true)
                            check(app.refreshAutomaticPrices({ payload }) == 2)
                            val prices = db.query("SELECT valid_at,value FROM market_prices ORDER BY valid_at")
                            check(prices == listOf(listOf((current - hour).toString(), "40000.12"), listOf(current.toString(), "41000.34")))
                            var calls = 0
                            check(
                                runCatching {
                                    app.refreshAutomaticPrices({ payload }) {
                                        if (++calls ==
                                            3
                                        ) {
                                            error("cancelled")
                                        }
                                    }
                                }.isFailure,
                            )
                            check(db.query("SELECT valid_at,value FROM market_prices ORDER BY valid_at") == prices)
                            app.setAutomaticPriceConsent(false)
                            check(runCatching { app.refreshAutomaticPrices({ error("must not fetch") }) }.isFailure)
                        }
                    }
                },
                "historicalPriceWorkflow" to {
                    fixture { file ->
                        AndroidSqliteDatabase(file.path).use { db ->
                            val at = System.currentTimeMillis()
                            val day = at / 86_400_000L * 86_400_000L
                            val app = PortfolioApplication(db) { at }
                            app.setPriceSourceConsent(true)
                            val result =
                                app.fetchHistoricalPrice(
                                    { """{"market_data":{"current_price":{"usd":42000.123456789}}}""" },
                                    io.polion.core.model
                                        .AssetId("bitcoin:btc"),
                                    io.polion.core.model
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
                            app.setPriceSourceConsent(false)
                            check(db.query("SELECT COUNT(*) FROM market_prices").single().single() == "1")
                            val btc =
                                io.polion.core.model
                                    .AssetId("bitcoin:btc")
                            val usd =
                                io.polion.core.model
                                    .AssetId("USD")
                            app.recordManualPrice(
                                io.polion.core.market.PriceObservation(
                                    "manual-fixture",
                                    io.polion.core.model
                                        .Price(
                                            btc,
                                            usd,
                                            io.polion.core.model.ExactDecimal
                                                .parse("50000"),
                                        ),
                                    null,
                                    day,
                                    at,
                                    true,
                                ),
                            )
                            check(app.useProviderPrices(btc, usd) == 1)
                            check(db.query("SELECT COUNT(*) FROM market_override_withdrawals").single().single() == "1")
                        }
                    }
                },
                "persistedProjections" to {
                    fixture { file ->
                        AndroidSqliteDatabase(file.path).use { db ->
                            val app = PortfolioApplication(db, System::currentTimeMillis)
                            app.seedDemo()
                            check(app.snapshot().activityTotal == 4)
                            check(
                                app
                                    .historySeries(listOf(System.currentTimeMillis()))
                                    .single()
                                    .valuations
                                    .isNotEmpty(),
                            )
                            check(app.analytics(System.currentTimeMillis()).categories.isNotEmpty())
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
                                app.seedDemo()
                                val archive = app.exportBackup("fixture-password".toCharArray())
                                AndroidSqliteDatabase(target.path).use { targetDb ->
                                    val staging = PortfolioApplication(targetDb, System::currentTimeMillis)
                                    check(runCatching { app.restoreToStaging(targetDb, archive, "wrong-password".toCharArray()) }.isFailure)
                                    check(targetDb.query("SELECT COUNT(*) FROM ledger_activities").single().single() == "0")
                                    app.restoreToStaging(targetDb, archive, "fixture-password".toCharArray())
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
                            app.seedDemo()
                            val before = app.snapshot().ownership
                            val pages =
                                db
                                    .query("PRAGMA page_count")
                                    .single()
                                    .single()!!
                                    .toInt()
                            db.query("PRAGMA max_page_count = ${pages + 1}")
                            check(runCatching { app.saveOwner(Owner(OwnerId("overflow"), "x".repeat(1_000_000))) }.isFailure)
                            db.query("PRAGMA max_page_count = 1073741823")
                            check(app.snapshot().ownership == before)
                            check(db.query("PRAGMA integrity_check").single().single() == "ok")
                        }
                    }
                },
                "secureLockRoundTripAndTamper" to {
                    val alias = "io.polion.test." + java.util.UUID.randomUUID()
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
                                    app.setAutomaticPriceConsent(true)
                                    val transport = KrakenPriceTransport()
                                    val count = app.refreshAutomaticPrices(transport::get)
                                    check(count in 1..720)
                                    val dailyCount = app.refreshAutomaticDailyPricesIfDue(transport::get)
                                    check(dailyCount in 1..720)
                                    check(app.refreshAutomaticDailyPricesIfDue({ error("Daily fetch should be skipped") }) == 0)
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
        val file = File.createTempFile("polion-reliability-", ".sqlite", targetContext.cacheDir)
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
