package io.polion.core.application

import io.polion.core.accounting.QuoteValue
import io.polion.core.accounting.Treatment
import io.polion.core.ledger.Activity
import io.polion.core.ledger.ActivityKind
import io.polion.core.ledger.ActivityStatus
import io.polion.core.ledger.Movement
import io.polion.core.ledger.MovementRole
import io.polion.core.market.AssetDefinition
import io.polion.core.market.AssetIdentity
import io.polion.core.market.AssetKind
import io.polion.core.market.AssetMetadata
import io.polion.core.market.PriceObservation
import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.EventId
import io.polion.core.model.ExactDecimal
import io.polion.core.model.ExactInteger
import io.polion.core.model.OwnerId
import io.polion.core.model.Price
import io.polion.core.model.Rational
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import io.polion.core.ownership.Owner
import io.polion.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountingValuationTest {
    private val now = 1_000_000_000_000L
    private val day = 86_400_000L

    @Test fun reportValuesOpenLotsOnlyWithFreshPrices() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { now }
            val owner = OwnerId("owner")
            val account = AccountId("account")
            app.saveOwner(Owner(owner, "Owner"))
            app.saveAccount(Account(account, owner, "Account", AccountKind.ACCOUNT))
            val unpriced = AssetId("token:unpriced")
            val stale = AssetId("token:stale")
            listOf(unpriced, stale).forEach { asset ->
                app.registerAsset(
                    AssetDefinition(asset, AssetIdentity(AssetKind.CUSTOM, null, asset.value)),
                    AssetMetadata(asset.value, asset.value, 0, 0),
                )
            }
            val usd = AssetId("USD")

            fun buy(
                id: String,
                asset: AssetId,
                cost: String,
            ) {
                val event = EventId(id)
                app.saveManualActivity(
                    Activity(
                        ActivityId(id),
                        1,
                        id,
                        ActivityKind.OTHER,
                        ActivityStatus.CONFIRMED,
                        listOf(Movement(event, account, AtomicAmount(asset, ExactInteger.parse("100000000")), MovementRole.TRANSFER)),
                        emptySet(),
                        null,
                        true,
                    ),
                )
                app.saveTreatments(ActivityId(id), listOf(Treatment.Acquire(event, QuoteValue(usd, Rational.of(cost)))))
            }
            buy("priced", AssetId("bitcoin:btc"), "10")
            buy("unpriced", unpriced, "5")
            buy("stale", stale, "7")
            app.recordManualPrice(
                PriceObservation("btc-fresh", Price(AssetId("bitcoin:btc"), usd, ExactDecimal.parse("100")), null, now, now, true),
            )
            app.recordManualPrice(
                PriceObservation(
                    "stale-price",
                    Price(stale, usd, ExactDecimal.parse("50")),
                    null,
                    now - 2 * day,
                    now - 2 * day,
                    true,
                ),
            )
            val valuations =
                app
                    .accountingReport()
                    .report.valuations
                    .associateBy { it.lot.asset }
            assertEquals(Rational.of("100"), valuations.getValue(AssetId("bitcoin:btc")).value)
            assertEquals(Rational.of("90"), valuations.getValue(AssetId("bitcoin:btc")).unrealizedGain)
            assertNull(valuations.getValue(unpriced).value)
            assertNull(valuations.getValue(unpriced).unrealizedGain)
            assertNull(valuations.getValue(stale).value)
            assertNull(valuations.getValue(stale).unrealizedGain)
        }
    }
}
