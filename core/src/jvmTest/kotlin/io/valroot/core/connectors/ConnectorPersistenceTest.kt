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
import io.valroot.core.model.SourceRecordId
import io.valroot.core.storage.JvmSqliteDatabase
import io.valroot.core.storage.RawObservation
import io.valroot.core.storage.Store
import io.valroot.core.storage.sha256
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectorPersistenceTest {
    @Test
    fun threeFamiliesPersistAndReplayThroughSameLedger() {
        val path = Files.createTempFile("connector", ".sqlite")
        try {
            val families = listOf("btc", "evm", "exchange")
            val db = JvmSqliteDatabase(path.toString())
            Store(db).use { store ->
                val ledger = ActivityRepository(db)
                for (family in families) {
                    val raw =
                        RawObservation(family, family, family, family, family, "2026-01-01T00:00:00Z", "42", "1", sha256("42"), "fixture")
                    store.ingest(raw)
                    store.ingest(raw)
                    val decoder =
                        object : RawRecordDecoder {
                            override val version = "1"

                            override fun decode(observation: RawObservation) =
                                listOf(
                                    Activity(
                                        ActivityId(observation.id),
                                        0,
                                        observation.id,
                                        ActivityKind.DEPOSIT,
                                        ActivityStatus.CONFIRMED,
                                        listOf(
                                            Movement(
                                                EventId(observation.id),
                                                AccountId(observation.id),
                                                AtomicAmount(AssetId(observation.id), ExactInteger.parse(observation.payload)),
                                                MovementRole.TRANSFER,
                                            ),
                                        ),
                                        setOf(SourceRecordId(observation.id)),
                                        version,
                                    ),
                                )
                        }
                    decoder.decode(raw).forEach(ledger::save)
                    decoder.decode(raw).forEach(ledger::save)
                }
                assertEquals(3, store.records().size)
                assertEquals(3, store.observations().size)
                assertEquals(3, ledger.current().size)
            }
            JvmSqliteDatabase(path.toString()).use { reopened ->
                Store(reopened)
                val balances = LedgerDeltas.calculate(ActivityRepository(reopened).current())
                families.forEach { assertEquals("42", balances.getValue(AccountId(it)).getValue(AssetId(it)).toString()) }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
