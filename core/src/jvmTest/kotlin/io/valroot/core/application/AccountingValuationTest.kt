package io.valroot.core.application

import io.valroot.core.accounting.QuoteValue
import io.valroot.core.accounting.Treatment
import io.valroot.core.ledger.Activity
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.ledger.Movement
import io.valroot.core.ledger.MovementRole
import io.valroot.core.market.AssetDefinition
import io.valroot.core.market.AssetIdentity
import io.valroot.core.market.AssetKind
import io.valroot.core.market.AssetMetadata
import io.valroot.core.market.PriceObservation
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.EventId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.OwnerId
import io.valroot.core.model.Price
import io.valroot.core.model.Rational
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.storage.JvmSqliteDatabase
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
            app.accounts.saveOwner(Owner(owner, "Owner"))
            app.accounts.saveAccount(Account(account, owner, "Account", AccountKind.ACCOUNT))
            val unpriced = AssetId("token:unpriced")
            val stale = AssetId("token:stale")
            listOf(unpriced, stale).forEach { asset ->
                app.assets.register(
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
                app.transactions.saveManualActivity(
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
                app.accounting.saveTreatments(ActivityId(id), listOf(Treatment.Acquire(event, QuoteValue(usd, Rational.of(cost)))))
            }
            buy("priced", AssetId("bitcoin:btc"), "10")
            buy("unpriced", unpriced, "5")
            buy("stale", stale, "7")
            app.prices.recordManual(
                PriceObservation("btc-fresh", Price(AssetId("bitcoin:btc"), usd, ExactDecimal.parse("100")), null, now, now, true),
            )
            app.prices.recordManual(
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
                    .accounting
                    .report()
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
