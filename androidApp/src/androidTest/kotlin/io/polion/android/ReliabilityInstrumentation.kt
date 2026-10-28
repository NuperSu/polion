package io.polion.android

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import io.polion.core.application.PortfolioApplication
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
