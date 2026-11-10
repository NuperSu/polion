package io.valroot.core.connectors

import io.valroot.core.ledger.Activity
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityRepository
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.ledger.LedgerDeltas
import io.valroot.core.ledger.Movement
import io.valroot.core.ledger.MovementRole
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.EventId
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.NetworkId
import io.valroot.core.model.ProviderId
import io.valroot.core.model.SourceId
import io.valroot.core.model.SourceRecordId
import io.valroot.core.model.SyncJobId
import io.valroot.core.storage.JvmSqliteDatabase
import io.valroot.core.storage.RawObservation
import io.valroot.core.storage.Store
import io.valroot.core.storage.sha256
import io.valroot.core.sync.JobState
import io.valroot.core.sync.SyncClock
import io.valroot.core.sync.SyncJob
import io.valroot.core.sync.SyncOrchestrator
import io.valroot.core.sync.SyncProvider
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncPipelineTest {
    private class FakeConnector(
        private val family: String,
    ) : HistoryFetcher,
        RawRecordDecoder {
        override val version = "1"

        override fun fetch(request: HistoryRequest): HistoryPage {
            val payload =
                when (family) {
                    "btc" -> "12"
                    "evm" -> "24"
                    else -> "36"
                }
            val raw =
                RawObservation(
                    family,
                    family,
                    "external",
                    request.network.value,
                    request.source.value,
                    "2026-01-01T00:00:00Z",
                    payload,
                    "1",
                    sha256(payload),
                    "fixture",
                )
            return HistoryPage(listOf(raw), null, Coverage.COMPLETE)
        }

        override fun decode(observation: RawObservation): List<Activity> =
            listOf(
                Activity(
                    ActivityId(family),
                    0,
                    family,
                    ActivityKind.DEPOSIT,
                    ActivityStatus.CONFIRMED,
                    listOf(
                        Movement(
                            EventId(family),
                            AccountId(family),
                            AtomicAmount(AssetId(family), ExactInteger.parse(observation.payload)),
                            MovementRole.TRANSFER,
                        ),
                    ),
                    setOf(SourceRecordId(observation.id)),
                    version,
                ),
            )
    }

    @Test
    fun fetchPersistDecodeAndReplayThreeFamilies() {
        val path = Files.createTempFile("valroot-pipeline", ".sqlite")
        val families = listOf("btc", "evm", "exchange")
        val connectors = families.associateWith(::FakeConnector)
        try {
            JvmSqliteDatabase(path.toString()).use { db ->
                val store = Store(db)
                val providers =
                    families.map { family ->
                        SyncProvider(ProviderId(family), connectors.getValue(family), setOf(NetworkId(family)), setOf(SourceId(family)))
                    }
                val sync = SyncOrchestrator(db, store, providers, SyncClock { 1000 })
                families.forEach { family ->
                    sync.enqueue(SyncJob(SyncJobId(family), SourceId(family), NetworkId(family), AccountId(family)))
                }
                repeat(3) { assertTrue(sync.runOne()) }
                families.forEach { assertEquals(JobState.COMPLETED, sync.status(SyncJobId(it))?.state) }
                assertEquals(3, store.records().size)
                val ledger = ActivityRepository(db)
                val rows =
                    db.query(
                        "SELECT r.id, r.namespace, r.external_id, r.network, o.source_id, o.retrieved_at, v.payload, v.payload_version, v.checksum, o.context FROM raw_records r JOIN raw_revisions v ON v.record_id=r.id JOIN raw_observations o ON o.record_id=v.record_id AND o.checksum=v.checksum ORDER BY r.id",
                    )
                repeat(2) {
                    rows.forEach { row ->
                        val raw =
                            RawObservation(
                                row[0]!!,
                                row[1]!!,
                                row[2]!!,
                                row[3]!!,
                                row[4]!!,
                                row[5]!!,
                                row[6]!!,
                                row[7]!!,
                                row[8]!!,
                                row[9]!!,
                            )
                        connectors.getValue(raw.namespace).decode(raw).forEach(ledger::save)
                    }
                }
                assertEquals(3, ledger.current().size)
            }
            JvmSqliteDatabase(path.toString()).use { db ->
                Store(db)
                val balances = LedgerDeltas.calculate(ActivityRepository(db).current())
                listOf("btc" to "12", "evm" to "24", "exchange" to "36").forEach { (family, amount) ->
                    assertEquals(amount, balances.getValue(AccountId(family)).getValue(AssetId(family)).toString())
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
