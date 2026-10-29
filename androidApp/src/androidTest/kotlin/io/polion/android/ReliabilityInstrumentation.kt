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
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val results = Bundle()
        var failures = 0
        val checks =
            listOf<Pair<String, () -> Unit>>(
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
            )
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
