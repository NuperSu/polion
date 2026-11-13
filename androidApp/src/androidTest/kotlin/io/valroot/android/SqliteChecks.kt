package io.valroot.android

import android.content.Context
import io.valroot.core.backup.PortableBackup
import io.valroot.core.storage.AndroidSqliteDatabase
import io.valroot.core.storage.RawObservation
import io.valroot.core.storage.Store
import io.valroot.core.storage.sha256

/** Repository, classification and raw-revision checks on disposable SQLite fixtures. */
internal fun sqliteChecks(context: Context): List<ReliabilityCheck> =
    listOf<ReliabilityCheck>(
        "boundedDuplicateProjection" to {
            withTemporaryDatabase(context.cacheDir) { file -> AndroidSqliteDatabase(file.path).use(::verifyBoundedDuplicateNative) }
        },
        "batchedClassificationRollback" to {
            withTemporaryDatabase(context.cacheDir) { file ->
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
            withTemporaryDatabase(context.cacheDir) { file ->
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
            withTemporaryDatabase(context.cacheDir) { file ->
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
                        withTemporaryDatabase(context.cacheDir) { restoredFile ->
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
    )
