package io.valroot.core.connectors

import io.valroot.core.ledger.Activity
import io.valroot.core.ledger.ActivityKind
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
import io.valroot.core.storage.RawObservation
import io.valroot.core.storage.sha256
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
                    RegisteredConnector(
                        IntegrationManifest(
                            it,
                            setOf(NetworkId(it)),
                            setOf("account"),
                            setOf(Capability.HISTORY, Capability.DECODING),
                            it == "exchange",
                            Coverage.COMPLETE,
                        ),
                        Fake(it),
                    )
                },
            )
        val activities =
            families.flatMap { family ->
                val connector = Fake(family)
                assertEquals(1, registry.supporting(NetworkId(family), Capability.HISTORY).size)
                val request = HistoryRequest(SourceId(family), ProviderId(family), NetworkId(family), AccountId(family))
                assertEquals(connector.fetch(request), registry.historyFetcher(NetworkId(family)).fetch(request))
                connector
                    .fetch(request)
                    .observations
                    .flatMap(connector::decode)
            }
        val balances = LedgerDeltas.calculate(activities)
        families.forEach { assertEquals("42", balances.getValue(AccountId(it)).getValue(AssetId(it)).toString()) }
        val btc = registry.find("btc")!!
        assertFailsWith<IllegalArgumentException> {
            ConnectorRegistry(listOf(RegisteredConnector(btc, Fake("btc")), RegisteredConnector(btc, Fake("btc"))))
        }
        assertFailsWith<IllegalArgumentException> { registry.historyFetcher(NetworkId("missing")) }
    }
}
