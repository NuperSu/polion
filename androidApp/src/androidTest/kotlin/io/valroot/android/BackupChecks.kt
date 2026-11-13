package io.valroot.android

import android.content.Context
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.model.OwnerId
import io.valroot.core.ownership.Owner
import io.valroot.core.storage.AndroidSqliteDatabase

/** Restore staging and full-database rollback checks on disposable SQLite fixtures. */
internal fun backupChecks(context: Context): List<ReliabilityCheck> =
    listOf<ReliabilityCheck>(
        "wrongPasswordPreservesStaging" to {
            withTemporaryDatabase(context.cacheDir) { source ->
                withTemporaryDatabase(context.cacheDir) { target ->
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
            withTemporaryDatabase(context.cacheDir) { file ->
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
    )
