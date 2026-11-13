package io.valroot.android

import android.content.Context
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.market.KrakenPriceTransport
import io.valroot.core.storage.AndroidSqliteDatabase
import io.valroot.testing.verifyBitcoinAccountingWorkflow
import java.io.File

/** Account, transaction, price, erasure and projection workflow checks on disposable SQLite fixtures. */
internal fun applicationChecks(context: Context): List<ReliabilityCheck> =
    listOf<ReliabilityCheck>(
        "accountHistoryWorkflow" to {
            val files = mutableListOf<File>()
            try {
                io.valroot.testing.verifyAccountHistoryWorkflow {
                    val file = File.createTempFile("valroot-account-fixture-", ".sqlite", context.cacheDir)
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
                    val file = File.createTempFile("valroot-manual-fixture-", ".sqlite", context.cacheDir)
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
                    val file = File.createTempFile("valroot-chain-fixture-", ".sqlite", context.cacheDir)
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
                withTemporaryDatabase(context.cacheDir) { file ->
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
            withTemporaryDatabase(context.cacheDir) { file ->
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
            withTemporaryDatabase(context.cacheDir) { file ->
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
            withTemporaryDatabase(context.cacheDir) { file ->
                AndroidSqliteDatabase(file.path).use { db ->
                    val app = PortfolioApplication(db, System::currentTimeMillis)
                    app.demo.seed()
                    check(app.snapshot().activityTotal == 4)
                    check(
                        app
                            .analytics
                            .historySeries(listOf(System.currentTimeMillis()))
                            .single()
                            .valuations
                            .isNotEmpty(),
                    )
                    check(
                        app.analytics
                            .report(System.currentTimeMillis())
                            .categories
                            .isNotEmpty(),
                    )
                }
                AndroidSqliteDatabase(file.path).use { db ->
                    check(PortfolioApplication(db, System::currentTimeMillis).snapshot().activityTotal == 4)
                }
            }
        },
    )

/** Kraken price checks that use the network; run only with `-e live_prices true`. */
internal fun livePriceChecks(context: Context): List<ReliabilityCheck> =
    listOf<ReliabilityCheck>(
        "liveHourlyPrice" to {
            withTemporaryDatabase(context.cacheDir) { file ->
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
