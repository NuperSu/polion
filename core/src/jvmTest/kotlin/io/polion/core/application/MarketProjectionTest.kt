package io.polion.core.application

import io.polion.core.market.AssetDefinition
import io.polion.core.market.AssetIdentity
import io.polion.core.market.AssetKind
import io.polion.core.market.AssetMetadata
import io.polion.core.market.KrakenPrices
import io.polion.core.market.MarketRepository
import io.polion.core.market.PriceObservation
import io.polion.core.market.PriceStatus
import io.polion.core.model.AssetId
import io.polion.core.model.ExactDecimal
import io.polion.core.model.Price
import io.polion.core.model.ProviderId
import io.polion.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarketProjectionTest {
    private val coin = AssetId("saved-coin")
    private val usd = AssetId("USD")

    @Test
    fun savedProviderQuoteIsVisibleWithoutHoldingsOrOwnership() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1000L }
            val before = app.snapshot()
            app.registerAsset(
                AssetDefinition(coin, AssetIdentity(AssetKind.CUSTOM, null, "saved-coin")),
                AssetMetadata("COIN", "Saved coin", 8, 0),
            )
            MarketRepository(db).recordPrice(provider("provider", "123.4567890123456789", 100))
            val snapshot = app.snapshot()
            val saved = snapshot.marketCoins.single()
            assertEquals(coin, saved.asset)
            assertEquals("COIN", saved.metadata?.symbol)
            assertEquals(ExactDecimal.parse("123.4567890123456789"), saved.price.price.value)
            assertEquals(PriceStatus.FRESH, saved.priceStatus)
            assertTrue(snapshot.valuations.isEmpty())
            assertTrue(snapshot.activities.isEmpty())
            assertEquals(0, snapshot.activityTotal)
            assertEquals(before.ownership, snapshot.ownership)
            assertEquals(before.balances, snapshot.balances)
        }
    }

    @Test
    fun perUnitHistoryPreservesGapsExactValuesAndManualOverrideWithdrawal() {
        var now = 1000L
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { now }
            app.snapshot()
            app.registerAsset(
                AssetDefinition(coin, AssetIdentity(AssetKind.CUSTOM, null, "saved-coin")),
                AssetMetadata("COIN", "Saved coin", 8, 0),
            )
            val repository = MarketRepository(db)
            repository.recordPrice(provider("first", "1.000000000000000001", 100))
            app.recordManualPrice(
                PriceObservation("manual", Price(coin, usd, ExactDecimal.parse("2.000000000000000002")), null, 200, 200, true),
            )
            repository.recordPrice(provider("latest", "3.000000000000000003", 300))
            val points = app.priceSeries(coin, usd, listOf(0, 100, 200, 300))
            assertNull(points[0].price)
            assertEquals(PriceStatus.MISSING, points[0].priceStatus)
            assertEquals(
                "1.000000000000000001",
                points[1]
                    .price
                    ?.price
                    ?.value
                    .toString(),
            )
            assertEquals("manual", points[2].price?.id)
            // A newer automatic observation replaces the older manual override.
            assertEquals("latest", points[3].price?.id)
            now = 400
            assertEquals(1, app.useProviderPrices(coin, usd))
            // The withdrawal takes effect at 400, so history before it still uses the manual observation.
            assertEquals(listOf("manual", "latest"), app.priceSeries(coin, usd, listOf(250, 300)).map { it.price?.id })
            assertTrue(app.snapshot().valuations.isEmpty())
            assertTrue(app.snapshot().activities.isEmpty())
        }
    }

    @Test
    fun marketListExcludesWrongCurrencyFutureUnknownProvidersAndFiatBases() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1000L }
            app.snapshot()
            app.registerAsset(
                AssetDefinition(coin, AssetIdentity(AssetKind.CUSTOM, null, "saved-coin")),
                AssetMetadata("COIN", "Saved coin", 8, 0),
            )
            val repository = MarketRepository(db)
            repository.recordPrice(provider("future", "1", 2000))
            repository.recordPrice(provider("unsupported", "2", 100).copy(providerId = ProviderId("unknown")))
            val eur = AssetId("EUR")
            app.registerAsset(
                AssetDefinition(eur, AssetIdentity(AssetKind.FIAT, null, "EUR")),
                AssetMetadata("EUR", "Euro", 2, 0),
            )
            repository.recordPrice(provider("fiat", "3", 100).copy(price = Price(eur, usd, ExactDecimal.parse("3"))))
            repository.recordPrice(provider("wrong-currency", "5", 100).copy(price = Price(coin, eur, ExactDecimal.parse("5"))))
            assertTrue(app.snapshot().marketCoins.isEmpty())
            repository.recordPrice(provider("eligible", "4", 100))
            assertEquals(listOf(coin), app.snapshot().marketCoins.map { it.asset })
        }
    }

    private fun provider(
        id: String,
        value: String,
        at: Long,
    ) = PriceObservation(id, Price(coin, usd, ExactDecimal.parse(value)), ProviderId(KrakenPrices.PROVIDER_ID), at, at)
}
