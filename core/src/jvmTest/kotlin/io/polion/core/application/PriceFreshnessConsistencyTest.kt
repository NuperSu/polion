package io.polion.core.application

import io.polion.core.bitcoin.BITCOIN_ASSET
import io.polion.core.bitcoin.BITCOIN_MAINNET
import io.polion.core.ledger.Activity
import io.polion.core.ledger.ActivityKind
import io.polion.core.ledger.ActivityStatus
import io.polion.core.ledger.Movement
import io.polion.core.ledger.MovementRole
import io.polion.core.market.AssetDefinition
import io.polion.core.market.AssetIdentity
import io.polion.core.market.AssetKind
import io.polion.core.market.AssetMetadata
import io.polion.core.market.KrakenPrices
import io.polion.core.market.MarketRepository
import io.polion.core.market.PriceObservation
import io.polion.core.market.PriceStatus
import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.EventId
import io.polion.core.model.ExactDecimal
import io.polion.core.model.ExactInteger
import io.polion.core.model.OwnerId
import io.polion.core.model.Price
import io.polion.core.model.ProviderId
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import io.polion.core.ownership.Owner
import io.polion.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class PriceFreshnessConsistencyTest {
    @Test fun oneQuoteHasTheSameFreshnessOnEveryCurrentView() {
        JvmSqliteDatabase(":memory:").use { db ->
            val now = 10 * 3_600_000L
            val app = PortfolioApplication(db) { now }
            val owner = OwnerId("owner")
            val account = AccountId("account")
            app.registerAsset(
                AssetDefinition(BITCOIN_ASSET, AssetIdentity(AssetKind.NATIVE, BITCOIN_MAINNET, "BTC")),
                AssetMetadata("BTC", "Bitcoin", 8, 0),
            )
            app.registerAsset(
                AssetDefinition(AssetId("USD"), AssetIdentity(AssetKind.FIAT, null, "USD")),
                AssetMetadata("USD", "US dollar", 2, 0),
            )
            app.saveOwner(Owner(owner, "Owner"))
            app.saveAccount(Account(account, owner, "Account", AccountKind.ACCOUNT))
            app.saveManualActivity(
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
            val history = app.history(now).single { it.asset == BITCOIN_ASSET }.priceStatus

            assertEquals(PriceStatus.STALE, dashboard)
            assertEquals(dashboard, history)
        }
    }
}
