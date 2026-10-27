package io.polion.core.connectors

import io.polion.core.ledger.Activity
import io.polion.core.ledger.ActivityKind
import io.polion.core.ledger.ActivityStatus
import io.polion.core.ledger.LedgerDeltas
import io.polion.core.ledger.Movement
import io.polion.core.ledger.MovementRole
import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.EventId
import io.polion.core.model.ExactInteger
import io.polion.core.model.NetworkId
import io.polion.core.model.ProviderId
import io.polion.core.model.SourceId
import io.polion.core.model.SourceRecordId
import io.polion.core.storage.RawObservation
import io.polion.core.storage.sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConnectorTest {
    private class Fake(
        private val family: String,
    ) : HistoryFetcher,
        RawRecordDecoder {
        override val version = "1"

        override fun fetch(request: HistoryRequest) =
            HistoryPage(
                listOf(
                    RawObservation(
                        family,
                        family,
                        family,
                        request.network.value,
                        request.source.value,
                        "2026-01-01T00:00:00Z",
                        "42",
                        "1",
                        sha256("42"),
                        "fixture",
                    ),
                ),
                null,
                Coverage.COMPLETE,
            )

        override fun decode(observation: RawObservation) =
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

    @Test fun differentFamiliesFeedSameLedger() {
        val families = listOf("btc", "evm", "exchange")
        val registry =
            ConnectorRegistry(
                families.map {
                    IntegrationManifest(
                        it,
                        setOf(NetworkId(it)),
                        setOf("account"),
                        setOf(Capability.HISTORY, Capability.DECODING),
                        it == "exchange",
                        Coverage.COMPLETE,
                    )
                },
            )
        val activities =
            families.flatMap { family ->
                val connector = Fake(family)
                assertEquals(1, registry.supporting(NetworkId(family), Capability.HISTORY).size)
                connector
                    .fetch(
                        HistoryRequest(SourceId(family), ProviderId(family), NetworkId(family), AccountId(family)),
                    ).observations
                    .flatMap(connector::decode)
            }
        val balances = LedgerDeltas.calculate(activities)
        families.forEach { assertEquals("42", balances.getValue(AccountId(it)).getValue(AssetId(it)).toString()) }
        assertFailsWith<IllegalArgumentException> { ConnectorRegistry(listOf(registry.find("btc")!!, registry.find("btc")!!)) }
    }
}
