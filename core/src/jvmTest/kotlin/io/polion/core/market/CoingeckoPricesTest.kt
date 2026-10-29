package io.polion.core.market

import io.polion.core.model.AssetId
import io.polion.core.model.ExactDecimal
import io.polion.core.model.NetworkId
import io.polion.core.model.ProviderId
import io.polion.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoingeckoPricesTest {
    private val btc = AssetId("btc")
    private val usd = AssetId("usd")
    private val dayMillis = 1_700_006_400_000L - (1_700_006_400_000L % 86_400_000L)

    private fun register(market: MarketRepository) {
        market.registerAsset(AssetDefinition(usd, AssetIdentity(AssetKind.FIAT, null, "USD")), AssetMetadata("USD", "US dollar", 2, 0))
        market.registerAsset(
            AssetDefinition(btc, AssetIdentity(AssetKind.NATIVE, NetworkId("bitcoin-mainnet"), "BTC")),
            AssetMetadata("BTC", "Bitcoin", 8, 0),
        )
    }

    private fun history(price: String) = """{"id":"bitcoin","market_data":{"current_price":{"usd":$price,"eur":1}}}"""

    @Test fun parsesQuotedHistoricalPriceExactly() {
        assertEquals(ExactDecimal.parse("34567.891"), CoingeckoPrices.parseHistorical(history("34567.891"), "usd"))
        assertEquals(ExactDecimal.parse("0"), CoingeckoPrices.parseHistorical(history("0"), "usd"))
    }

    @Test fun missingOrMalformedQuotesAreNeverZero() {
        assertNull(CoingeckoPrices.parseHistorical("""{"id":"bitcoin"}""", "usd"))
        assertNull(CoingeckoPrices.parseHistorical("""{"market_data":{"current_price":{"eur":5}}}""", "usd"))
        assertNull(CoingeckoPrices.parseHistorical("""{"market_data":{"current_price":{"usd":"12.5"}}}""", "usd"))
        assertNull(CoingeckoPrices.parseHistorical(history("-1"), "usd"))
        assertNull(CoingeckoPrices.parseHistorical(history("1e3"), "usd"))
        assertNull(CoingeckoPrices.parseHistorical("""{"market_data":null}""", "usd"))
    }

    @Test fun dayConversionMatchesCalendarDates() {
        val recorded = mutableListOf<String>()
        val market = MarketRepository(JvmSqliteDatabase(":memory:")).also(::register)
        val client =
            CoingeckoPriceClient(
                { url ->
                    recorded.add(url)
                    history("1")
                },
                market,
                { 0L },
            )
        client.historical(btc, "bitcoin", usd, "usd", 0L, "epoch")
        assertEquals("https://api.coingecko.com/api/v3/coins/bitcoin/history?date=01-01-1970&localization=false", recorded.last())
        client.historical(btc, "bitcoin", usd, "usd", 1_709_164_800_000L, "leap")
        assertEquals("https://api.coingecko.com/api/v3/coins/bitcoin/history?date=29-02-2024&localization=false", recorded.last())
    }

    @Test fun storedHistoricalPriceIsReadBackOnlyUnderItsProviderPolicy() {
        val market = MarketRepository(JvmSqliteDatabase(":memory:")).also(::register)
        val client = CoingeckoPriceClient({ history("42000.5") }, market, { 5L })
        val result = client.historical(btc, "bitcoin", usd, "usd", dayMillis, "btc-day")
        assertEquals(PriceStatus.FRESH, result.status)
        val coingecko = PricePolicy(listOf(ProviderId(CoingeckoPrices.PROVIDER_ID)), maxAgeMillis = Long.MAX_VALUE / 4)
        val found = market.lookup(btc, usd, dayMillis, 5L, coingecko)
        assertEquals(PriceStatus.FRESH, found.status)
        assertEquals(ExactDecimal.parse("42000.5"), found.observation?.price?.value)
        assertEquals(ProviderId(CoingeckoPrices.PROVIDER_ID), found.observation?.providerId)
        val other = PricePolicy(listOf(ProviderId("someone-else")), maxAgeMillis = Long.MAX_VALUE / 4)
        assertEquals(PriceStatus.MISSING, market.lookup(btc, usd, dayMillis, 5L, other).status)
    }

    @Test fun missingProviderQuoteRecordsNothing() {
        val market = MarketRepository(JvmSqliteDatabase(":memory:")).also(::register)
        val client = CoingeckoPriceClient({ """{"market_data":{"current_price":{}}}""" }, market, { 5L })
        assertEquals(PriceStatus.MISSING, client.historical(btc, "bitcoin", usd, "usd", dayMillis, "none").status)
        val coingecko = PricePolicy(listOf(ProviderId(CoingeckoPrices.PROVIDER_ID)), maxAgeMillis = Long.MAX_VALUE / 4)
        assertEquals(PriceStatus.MISSING, market.lookup(btc, usd, dayMillis, 5L, coingecko).status)
    }
}
