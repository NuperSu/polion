package io.polion.core.harness

import io.polion.core.accounting.AccountingAction
import io.polion.core.accounting.FifoAccounting
import io.polion.core.accounting.LedgerAccounting
import io.polion.core.accounting.QuoteValue
import io.polion.core.accounting.Treatment
import io.polion.core.accounting.TreatmentRepository
import io.polion.core.holdings.BalanceCoverage
import io.polion.core.holdings.BalanceKey
import io.polion.core.holdings.HoldingsRepository
import io.polion.core.ledger.Activity
import io.polion.core.ledger.ActivityKind
import io.polion.core.ledger.ActivityRepository
import io.polion.core.ledger.ActivityStatus
import io.polion.core.ledger.Movement
import io.polion.core.ledger.MovementRole
import io.polion.core.market.AssetDefinition
import io.polion.core.market.AssetIdentity
import io.polion.core.market.AssetKind
import io.polion.core.market.AssetMetadata
import io.polion.core.market.MarketRepository
import io.polion.core.market.PriceObservation
import io.polion.core.market.PricePolicy
import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.EventId
import io.polion.core.model.ExactDecimal
import io.polion.core.model.ExactInteger
import io.polion.core.model.NetworkId
import io.polion.core.model.OwnerId
import io.polion.core.model.Price
import io.polion.core.model.ProviderId
import io.polion.core.model.Rational
import io.polion.core.model.SourceId
import io.polion.core.model.SourceRecordId
import io.polion.core.model.SyncJobId
import io.polion.core.reconcile.ActivityOverlay
import io.polion.core.reconcile.OverlayRepository
import io.polion.core.storage.JvmSqliteDatabase
import io.polion.core.storage.Store
import io.polion.core.sync.SyncJob
import io.polion.core.sync.SyncOrchestrator
import io.polion.core.sync.SyncProvider
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FinancialHarnessTest {
    @Test fun fakeChainAndExchangeFlowAcrossEntireFinancialCore() {
        val path = Files.createTempFile("polion-financial-harness", ".db")
        try {
            JvmSqliteDatabase(path.toString()).use { db ->
                val store = Store(db)
                val fixtures =
                    checkNotNull(javaClass.getResource("/financial-families.tsv")).readText().trim().lines().associate { line ->
                        val fields = line.split('\t')
                        fields[0] to fields[1]
                    }
                val names = fixtures.keys.toList()
                val providers =
                    names.map { family ->
                        SyncProvider(
                            ProviderId(family),
                            FixtureHistory(family, fixtures.getValue(family)),
                            setOf(NetworkId(family)),
                            setOf(SourceId(family)),
                        )
                    }
                val sync = SyncOrchestrator(db, store, providers, FixtureClock())
                names.forEach { sync.enqueue(SyncJob(SyncJobId(it), SourceId(it), NetworkId(it), AccountId(it))) }
                repeat(2) { assertTrue(sync.runOne()) }
                val ledger = ActivityRepository(db)
                val rows =
                    db.query(
                        "SELECT r.id, r.network, v.payload FROM raw_records r JOIN raw_revisions v ON r.id=v.record_id ORDER BY r.id",
                    )
                rows.forEach { row ->
                    val family = row[1]!!
                    val activity =
                        Activity(
                            ActivityId(family),
                            1,
                            family,
                            ActivityKind.DEPOSIT,
                            ActivityStatus.CONFIRMED,
                            listOf(
                                Movement(
                                    EventId(family),
                                    AccountId(family),
                                    AtomicAmount(AssetId(family), ExactInteger.parse(row[2]!!)),
                                    MovementRole.TRANSFER,
                                ),
                            ),
                            setOf(SourceRecordId(row[0]!!)),
                            "1",
                        )
                    ledger.save(activity)
                    TreatmentRepository(
                        db,
                    ).save(
                        activity.id,
                        listOf(Treatment.Acquire(activity.movements.single().id, QuoteValue(AssetId("usd"), Rational.of("2")))),
                    )
                }
                val overlays = OverlayRepository(db)
                overlays.saveOverlay(
                    ActivityOverlay(
                        ActivityId("new-chain"),
                        label = "my chain",
                        note = "reviewed",
                        amounts =
                            mapOf(
                                EventId("new-chain") to AtomicAmount(AssetId("new-chain"), ExactInteger.parse("5")),
                            ),
                    ),
                )
                val original = ledger.current().single { it.id.value == "new-chain" }
                ledger.save(original.copy(decoderVersion = "2"))
                assertEquals(
                    "4",
                    ledger
                        .current()
                        .single { it.id.value == "new-chain" }
                        .movements
                        .single()
                        .amount.atomicUnits
                        .toString(),
                )
                val effective = overlays.replayForLedger(ledger.current()).activities
                assertEquals("reviewed", effective.single { it.activity.id.value == "new-chain" }.note)
                assertEquals("my chain", effective.single { it.activity.id.value == "new-chain" }.label)
                val holdings = HoldingsRepository(db)
                holdings.refresh(effective.map { it.activity })
                val market = MarketRepository(db)
                val usd = AssetId("usd")
                market.registerAsset(AssetDefinition(usd, AssetIdentity(AssetKind.FIAT, null, "USD")), AssetMetadata("USD", "Dollar", 2, 0))
                val owners = names.associate { AccountId(it) to OwnerId("owner") }
                val actions = mutableListOf<AccountingAction>()
                val prices = mutableMapOf<AssetId, QuoteValue?>()
                names.forEach { family ->
                    val asset = AssetId(family)
                    market.registerAsset(
                        AssetDefinition(asset, AssetIdentity(AssetKind.NATIVE, NetworkId(family), family)),
                        AssetMetadata(family, family, 0, 0),
                    )
                    market.recordPrice(PriceObservation(family, Price(asset, usd, ExactDecimal.parse("3")), ProviderId(family), 1, 1))
                    val price =
                        market
                            .lookup(asset, usd, 10, 10, PricePolicy(listOf(ProviderId(family)), 20))
                            .observation!!
                            .price.value
                    prices[asset] = QuoteValue(usd, Rational.fromDecimal(price))
                    val activity = effective.single { it.activity.id.value == family }.activity
                    actions += LedgerAccounting.actions(activity, TreatmentRepository(db).load(activity.id))
                    assertEquals(
                        if (family ==
                            "new-chain"
                        ) {
                            "5"
                        } else {
                            "6"
                        },
                        holdings
                            .current(
                                BalanceCoverage.COMPLETE,
                                10,
                            ).quantities
                            .getValue(BalanceKey(AccountId(family), asset))
                            .toString(),
                    )
                }
                val report = FifoAccounting.calculate(actions, owners, usd, prices)
                assertEquals(Rational.of("13"), report.valuations.single { it.lot.asset.value == "new-chain" }.unrealizedGain)
                assertEquals(Rational.of("16"), report.valuations.single { it.lot.asset.value == "exchange" }.unrealizedGain)
            }
            JvmSqliteDatabase(path.toString()).use { db ->
                Store(db)
                assertEquals(2, ActivityRepository(db).current().size)
                assertEquals(
                    "reviewed",
                    OverlayRepository(db)
                        .replay(ActivityRepository(db).current())
                        .single {
                            it.activity.id.value ==
                                "new-chain"
                        }.note,
                )
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test fun largeSyntheticPortfolioReplaysIncrementallyAndFully() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            val account = AccountId("portfolio")
            val asset = AssetId("synthetic")
            val activities =
                (0 until 10_000).map { i ->
                    Activity(
                        ActivityId("activity-$i"),
                        i.toLong(),
                        i.toString().padStart(8, '0'),
                        ActivityKind.DEPOSIT,
                        ActivityStatus.CONFIRMED,
                        listOf(Movement(EventId("event-$i"), account, AtomicAmount(asset, ExactInteger.parse("1")), MovementRole.TRANSFER)),
                        setOf(SourceRecordId("source-$i")),
                        "1",
                    )
                }
            val holdings = HoldingsRepository(db)
            val start = System.nanoTime()
            holdings.refresh(activities)
            val expected = holdings.current(BalanceCoverage.COMPLETE, 10000)
            repeat(2) {
                holdings.refresh(activities)
                assertEquals(expected, holdings.current(BalanceCoverage.COMPLETE, 10000))
            }
            holdings.refresh(activities, true)
            assertEquals(expected, holdings.current(BalanceCoverage.COMPLETE, 10000))
            println("10,000 activities: initial + two incremental + full replay in ${(System.nanoTime() - start) / 1_000_000} ms")
            assertEquals("10000", expected.quantities.getValue(BalanceKey(account, asset)).toString())
        }
    }

    @Test fun corruptProviderFixtureCannotCommit() {
        JvmSqliteDatabase(":memory:").use { db ->
            val store = Store(db)
            val provider =
                SyncProvider(
                    ProviderId("broken"),
                    FixtureHistory("bad", "1", corrupted = true),
                    setOf(NetworkId("bad")),
                    setOf(SourceId("bad")),
                )
            val clock = FixtureClock()
            val sync = SyncOrchestrator(db, store, listOf(provider), clock)
            val id = SyncJobId("job")
            sync.enqueue(SyncJob(id, SourceId("bad"), NetworkId("bad"), AccountId("bad")))
            assertTrue(sync.runOne())
            assertEquals(0, store.records().size)
            assertEquals(0, sync.status(id)!!.pages)
            clock.advance(1000)
        }
    }
}
