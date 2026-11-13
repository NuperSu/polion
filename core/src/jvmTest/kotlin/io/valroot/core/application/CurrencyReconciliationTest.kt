package io.valroot.core.application

import io.valroot.core.bitcoin.BITCOIN_ASSET
import io.valroot.core.ledger.Activity
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityRepository
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.ledger.Movement
import io.valroot.core.ledger.MovementRole
import io.valroot.core.market.AssetIdentity
import io.valroot.core.market.AssetKind
import io.valroot.core.market.AssetMetadata
import io.valroot.core.market.MarketRepository
import io.valroot.core.market.PriceObservation
import io.valroot.core.market.PriceStatus
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.EventId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.OwnerId
import io.valroot.core.model.Price
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.reconcile.LinkState
import io.valroot.core.storage.JvmSqliteDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CurrencyReconciliationTest {
    @Test
    fun displayCurrenciesRegisterPersistAndSupportManualQuotes() {
        val file = Files.createTempFile("valroot-currencies", ".sqlite")
        try {
            JvmSqliteDatabase(file.toString()).use { db ->
                var at = 15552000000L
                val app = PortfolioApplication(db) { at }
                app.demo.seed()
                at++
                val market = MarketRepository(db)
                for ((code, name) in listOf("USD" to "US Dollar", "EUR" to "Euro", "GBP" to "Pound Sterling")) {
                    val currency = AssetId(code)
                    app.assets.setCurrency(currency)
                    assertEquals(currency, app.snapshot().currency)
                    assertEquals(AssetIdentity(AssetKind.FIAT, null, code), market.asset(currency)?.identity)
                    assertEquals(AssetMetadata(code, name, 2, 0), market.metadataAt(currency, 0))
                    if (code != "USD") {
                        assertEquals(
                            PriceStatus.MISSING,
                            app
                                .snapshot()
                                .valuations
                                .single()
                                .priceStatus,
                        )
                    }
                    app.prices.recordManual(
                        PriceObservation(
                            "manual-$code",
                            Price(BITCOIN_ASSET, currency, ExactDecimal.parse("100")),
                            null,
                            at,
                            at,
                            true,
                        ),
                    )
                    assertEquals(
                        "150.00000000",
                        app
                            .snapshot()
                            .valuations
                            .single()
                            .value
                            .toString(),
                    )
                }
            }
            JvmSqliteDatabase(file.toString()).use { db ->
                val app = PortfolioApplication(db) { 15552000001L }
                assertEquals(AssetId("GBP"), app.snapshot().currency)
                assertEquals(
                    "150.00000000",
                    app
                        .snapshot()
                        .valuations
                        .single()
                        .value
                        .toString(),
                )
                app.backup.wipe()
                app.assets.setCurrency(AssetId("EUR"))
                assertEquals(AssetId("EUR"), app.snapshot().currency)
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun reviewedPairsLeaveTheQueueImmediatelyAndAfterReopenWithoutChangingRecords() {
        val file = Files.createTempFile("valroot-reconciliation", ".sqlite")
        try {
            JvmSqliteDatabase(file.toString()).use { db ->
                val app = PortfolioApplication(db) { 1000L }
                val owner = OwnerId("owner")
                app.accounts.saveOwner(Owner(owner, "Owner"))
                for (id in listOf("outgoing", "incoming", "competing")) {
                    app.accounts.saveAccount(Account(AccountId(id), owner, id, AccountKind.ACCOUNT))
                    app.transactions.saveManualActivity(activity(id, if (id == "outgoing") "-10" else "10"))
                }
                val original = ActivityRepository(db).current()
                val initial = app.accounting.reconciliationCandidates(1000)
                assertEquals(2, initial.size)
                assertTrue(initial.all { it.ambiguous })
                assertEquals(0, app.transactions.setDecision(ActivityId("outgoing"), ActivityId("competing"), LinkState.REJECTED))
                val remaining = app.accounting.reconciliationCandidates(1000).single()
                assertEquals(setOf(ActivityId("outgoing"), ActivityId("incoming")), setOf(remaining.first, remaining.second))
                assertFalse(remaining.ambiguous)
                assertEquals(0, app.transactions.setDecision(ActivityId("outgoing"), ActivityId("incoming"), LinkState.ACCEPTED))
                assertTrue(app.accounting.reconciliationCandidates(1000).isEmpty())
                assertEquals(1, app.transactions.setDecision(ActivityId("competing"), ActivityId("outgoing"), LinkState.ACCEPTED))
                assertTrue(app.accounting.reconciliationCandidates(1000).isEmpty())
                assertEquals(original, ActivityRepository(db).current())
                assertTrue(original.all { ActivityRepository(db).history(it.id).size == 1 })
            }
            JvmSqliteDatabase(file.toString()).use { db ->
                val app = PortfolioApplication(db) { 1000L }
                assertTrue(app.accounting.reconciliationCandidates(1000).isEmpty())
                assertEquals(3, ActivityRepository(db).current().size)
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun activity(
        id: String,
        amount: String,
    ) = Activity(
        ActivityId(id),
        1000L,
        id,
        ActivityKind.TRANSFER,
        ActivityStatus.CONFIRMED,
        listOf(
            Movement(
                EventId("$id-movement"),
                AccountId(id),
                AtomicAmount(BITCOIN_ASSET, ExactInteger.parse(amount)),
                MovementRole.TRANSFER,
            ),
        ),
        emptySet(),
        null,
        manual = true,
    )
}
