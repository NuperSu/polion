package io.valroot.core.application

import io.valroot.core.bitcoin.BITCOIN_MAINNET
import io.valroot.core.bitcoin.EsploraTransport
import io.valroot.core.connectors.Capability
import io.valroot.core.connectors.Coverage
import io.valroot.core.connectors.HistoryFetcher
import io.valroot.core.connectors.HistoryPage
import io.valroot.core.connectors.HistoryRequest
import io.valroot.core.connectors.IntegrationManifest
import io.valroot.core.connectors.RawRecordDecoder
import io.valroot.core.ledger.Activity
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.ledger.Movement
import io.valroot.core.ledger.MovementRole
import io.valroot.core.market.AssetDefinition
import io.valroot.core.market.AssetIdentity
import io.valroot.core.market.AssetKind
import io.valroot.core.market.AssetMetadata
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.EventId
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.NetworkId
import io.valroot.core.model.OwnerId
import io.valroot.core.model.SourceRecordId
import io.valroot.core.model.TrackingTargetId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.ownership.TargetIdentity
import io.valroot.core.ownership.TargetRole
import io.valroot.core.ownership.TrackingTarget
import io.valroot.core.storage.JvmSqliteDatabase
import io.valroot.core.storage.RawObservation
import io.valroot.core.storage.sha256
import io.valroot.core.sync.JobState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A fake EVM-style family added purely by implementing [SourceConnector]: no change to holdings, accounting, export or
 * the application's refresh entry point.
 */
class SourceConnectorTest {
    private val network = NetworkId("fake-evm")
    private val account = AccountId("evm-wallet")
    private val token = AssetId("fake-evm:token")

    private inner class FakeEvmConnector :
        SourceConnector,
        HistoryFetcher,
        RawRecordDecoder {
        var pages = 0
        override val manifest =
            IntegrationManifest(
                "fake-evm",
                setOf(network),
                setOf("address"),
                setOf(Capability.HISTORY, Capability.DECODING),
                false,
                Coverage.COMPLETE,
            )
        override val version = "fake-evm-1"

        override fun refresh(
            app: PortfolioApplication,
            options: SourceRefreshOptions,
        ) = SourceRefreshOutcome(manifest.id, app.sources.ingestHistory(manifest.id, this, this, network, listOf(account)))

        override fun fetch(request: HistoryRequest): HistoryPage {
            pages++
            val payload = "250"
            val raw =
                RawObservation(
                    "evm-tx-1",
                    "fake-evm",
                    "tx-1",
                    network.value,
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
                    ActivityId("fake-evm:${observation.externalId}"),
                    1_767_225_600_000L,
                    observation.externalId,
                    ActivityKind.DEPOSIT,
                    ActivityStatus.CONFIRMED,
                    listOf(
                        Movement(
                            EventId("fake-evm:${observation.externalId}:0"),
                            account,
                            AtomicAmount(token, ExactInteger.parse(observation.payload)),
                            MovementRole.TRANSFER,
                        ),
                    ),
                    setOf(SourceRecordId(observation.id)),
                    version,
                ),
            )
    }

    @Test fun aNewFamilyRefreshesThroughTheRegistryIntoHoldingsAndExport() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1_800_000_000_000L }
            app.snapshot()
            app.accounts.saveOwner(Owner(OwnerId("owner"), "Owner"))
            app.accounts.saveAccount(Account(account, OwnerId("owner"), "EVM wallet", AccountKind.WALLET))
            app.accounts.saveTarget(
                TrackingTarget(
                    TrackingTargetId("evm-target"),
                    account,
                    TargetIdentity(network, "address", "0xabc"),
                    TargetRole.LEAF,
                    "EVM",
                ),
            )
            app.assets.register(
                AssetDefinition(token, AssetIdentity(AssetKind.TOKEN, network, "0xtoken")),
                AssetMetadata("TKN", "Token", 2, 0),
            )
            val fake = FakeEvmConnector()
            val registry = SourceRegistry(listOf(fake))
            assertSame(fake, registry.forNetwork(network))

            val outcome = app.sources.refresh(registry).single()
            assertEquals(listOf(JobState.COMPLETED), outcome.jobs)
            assertEquals(
                "250",
                app
                    .snapshot()
                    .valuations
                    .single { it.asset == token }
                    .amount.atomicUnits
                    .toString(),
            )
            assertTrue(app.csv.export().contains("fake-evm:tx-1"))
            // A second refresh reads the saved cursor's completed job and does not duplicate the activity.
            app.sources.refresh(registry)
            assertEquals(1, app.transactions.entries(ActivityFilter()).size)
            assertEquals(
                "250",
                app
                    .snapshot()
                    .valuations
                    .single { it.asset == token }
                    .amount.atomicUnits
                    .toString(),
            )
        }
    }

    @Test fun routingIsExplicit() {
        val bitcoin = BitcoinConnector(EsploraTransport { error("not called") }, DEFAULT_ENDPOINT)
        assertSame(bitcoin, SourceRegistry(listOf(bitcoin)).forNetwork(BITCOIN_MAINNET))
        assertFails { SourceRegistry(listOf(bitcoin, BitcoinConnector(EsploraTransport { error("x") }, DEFAULT_ENDPOINT))) }
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1_800_000_000_000L }
            app.snapshot()
            app.accounts.saveOwner(Owner(OwnerId("owner"), "Owner"))
            app.accounts.saveAccount(Account(account, OwnerId("owner"), "EVM wallet", AccountKind.WALLET))
            app.accounts.saveTarget(
                TrackingTarget(
                    TrackingTargetId("evm-target"),
                    account,
                    TargetIdentity(network, "address", "0xabc"),
                    TargetRole.LEAF,
                    "EVM",
                ),
            )
            // A tracked network without a connector is reported, not skipped silently.
            assertEquals(
                listOf("No connector is available for fake-evm."),
                app.sources
                    .refresh(SourceRegistry(emptyList()))
                    .single()
                    .notes,
            )
            assertFails { app.sources.ingestHistory("bitcoin-esplora", FakeEvmConnector(), FakeEvmConnector(), network, listOf(account)) }
        }
    }
}
