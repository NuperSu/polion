package io.valroot.core.application

import io.valroot.core.bitcoin.BITCOIN_ASSET
import io.valroot.core.bitcoin.BITCOIN_MAINNET
import io.valroot.core.ledger.Activity
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.ledger.Movement
import io.valroot.core.ledger.MovementRole
import io.valroot.core.market.AssetDefinition
import io.valroot.core.market.AssetIdentity
import io.valroot.core.market.AssetKind
import io.valroot.core.market.AssetMetadata
import io.valroot.core.market.KrakenPrices
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
import io.valroot.core.model.ProviderId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class PriceFreshnessConsistencyTest {
    @Test fun oneQuoteHasTheSameFreshnessOnEveryCurrentView() {
        JvmSqliteDatabase(":memory:").use { db ->
            val now = 10 * 3_600_000L
            val app = PortfolioApplication(db) { now }
            val owner = OwnerId("owner")
            val account = AccountId("account")
            app.assets.register(
                AssetDefinition(BITCOIN_ASSET, AssetIdentity(AssetKind.NATIVE, BITCOIN_MAINNET, "BTC")),
                AssetMetadata("BTC", "Bitcoin", 8, 0),
            )
            app.assets.register(
                AssetDefinition(AssetId("USD"), AssetIdentity(AssetKind.FIAT, null, "USD")),
                AssetMetadata("USD", "US dollar", 2, 0),
            )
            app.accounts.saveOwner(Owner(owner, "Owner"))
            app.accounts.saveAccount(Account(account, owner, "Account", AccountKind.ACCOUNT))
            app.transactions.saveManualActivity(
                Activity(
                    ActivityId("deposit"),
                    1000L,
                    "deposit",
                    ActivityKind.TRANSFER,
                    ActivityStatus.CONFIRMED,
                    listOf(
                        Movement(
                            EventId("deposit-in"),
                            account,
                            AtomicAmount(BITCOIN_ASSET, ExactInteger.parse("100000000")),
                            MovementRole.TRANSFER,
                        ),
                    ),
                    emptySet(),
                    null,
                    manual = true,
                ),
            )
            // Three hours old: outside the two-hour current window.
            val at = now - 3 * 3_600_000L
            MarketRepository(db).recordPrice(
                PriceObservation(
                    "quote",
                    Price(BITCOIN_ASSET, AssetId("USD"), ExactDecimal.parse("50000")),
                    ProviderId(KrakenPrices.PROVIDER_ID),
                    at,
                    at,
                ),
            )

            val dashboard =
                app
                    .snapshot()
                    .valuations
                    .single { it.asset == BITCOIN_ASSET }
                    .priceStatus
            val history =
                app.analytics
                    .history(now)
                    .single { it.asset == BITCOIN_ASSET }
                    .priceStatus

            assertEquals(PriceStatus.STALE, dashboard)
            assertEquals(dashboard, history)
        }
    }
}
