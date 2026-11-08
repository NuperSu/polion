package io.polion.core.application

import io.polion.core.bitcoin.BITCOIN_MAINNET
import io.polion.core.bitcoin.EsploraTransport
import io.polion.core.connectors.Capability
import io.polion.core.connectors.Coverage
import io.polion.core.connectors.HistoryFetcher
import io.polion.core.connectors.HistoryPage
import io.polion.core.connectors.HistoryRequest
import io.polion.core.connectors.IntegrationManifest
import io.polion.core.connectors.RawRecordDecoder
import io.polion.core.ledger.Activity
import io.polion.core.ledger.ActivityKind
import io.polion.core.ledger.ActivityStatus
import io.polion.core.ledger.Movement
import io.polion.core.ledger.MovementRole
import io.polion.core.market.AssetDefinition
import io.polion.core.market.AssetIdentity
import io.polion.core.market.AssetKind
import io.polion.core.market.AssetMetadata
import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.EventId
import io.polion.core.model.ExactInteger
import io.polion.core.model.NetworkId
import io.polion.core.model.OwnerId
import io.polion.core.model.SourceRecordId
import io.polion.core.model.TrackingTargetId
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import io.polion.core.ownership.Owner
import io.polion.core.ownership.TargetIdentity
import io.polion.core.ownership.TargetRole
import io.polion.core.ownership.TrackingTarget
import io.polion.core.storage.JvmSqliteDatabase
import io.polion.core.storage.RawObservation
import io.polion.core.storage.sha256
import io.polion.core.sync.JobState
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
        ) = SourceRefreshOutcome(manifest.id, app.ingestHistory(manifest.id, this, this, network, listOf(account)))

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
            app.saveOwner(Owner(OwnerId("owner"), "Owner"))
            app.saveAccount(Account(account, OwnerId("owner"), "EVM wallet", AccountKind.WALLET))
            app.saveTarget(
                TrackingTarget(
                    TrackingTargetId("evm-target"),
                    account,
                    TargetIdentity(network, "address", "0xabc"),
                    TargetRole.LEAF,
                    "EVM",
                ),
            )
            app.registerAsset(
                AssetDefinition(token, AssetIdentity(AssetKind.TOKEN, network, "0xtoken")),
                AssetMetadata("TKN", "Token", 2, 0),
            )
            val fake = FakeEvmConnector()
            val registry = SourceRegistry(listOf(fake))
            assertSame(fake, registry.forNetwork(network))

            val outcome = app.refreshSources(registry).single()
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
            assertTrue(app.exportCsv().contains("fake-evm:tx-1"))
            // A second refresh reads the saved cursor's completed job and does not duplicate the activity.
            app.refreshSources(registry)
            assertEquals(1, app.transactionEntries(ActivityFilter()).size)
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
            app.saveOwner(Owner(OwnerId("owner"), "Owner"))
            app.saveAccount(Account(account, OwnerId("owner"), "EVM wallet", AccountKind.WALLET))
            app.saveTarget(
                TrackingTarget(
                    TrackingTargetId("evm-target"),
                    account,
                    TargetIdentity(network, "address", "0xabc"),
                    TargetRole.LEAF,
                    "EVM",
                ),
            )
            // A tracked network without a connector is reported, not skipped silently.
            assertEquals(listOf("No connector is available for fake-evm."), app.refreshSources(SourceRegistry(emptyList())).single().notes)
            assertFails { app.ingestHistory("bitcoin-esplora", FakeEvmConnector(), FakeEvmConnector(), network, listOf(account)) }
        }
    }
}
