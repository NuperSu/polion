package io.polion.android

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import io.polion.core.application.PortfolioApplication
import io.polion.core.bitcoin.bitcoinMainnetAddress
import io.polion.core.bitcoin.bitcoinWallet
import io.polion.core.bitcoin.descriptorChecksum
import io.polion.core.model.OwnerId
import io.polion.core.ownership.Owner
import io.polion.core.storage.AndroidSqliteDatabase
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
