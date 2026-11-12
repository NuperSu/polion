package io.valroot.core.exchange

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.market.CoingeckoCatalog
import io.valroot.core.model.AssetId
import io.valroot.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExchangeCoinsTest {
    private val coins =
        """[{"id":"tether","symbol":"usdt","name":"Tether","platforms":{}},
           {"id":"bridged-tether","symbol":"usdt","name":"Bridged Tether","platforms":{}},
           {"id":"alpha-one","symbol":"abc","name":"Alpha One","platforms":{}},
           {"id":"alpha-two","symbol":"abc","name":"Alpha Two","platforms":{}},
           {"id":"solo","symbol":"solo","name":"Solo Coin","platforms":{}},
           {"id":"Bad Id","symbol":"bad","name":"Bad","platforms":{}}]"""

    private val catalog = CoingeckoCatalog(emptyList(), emptyMap(), emptyMap(), CoingeckoCatalog.parseSymbols(coins))

    @Test
    fun resolvesWellKnownUniqueAndRankedSymbols() {
        assertEquals("tether", ExchangeAssets.resolve("USDT", catalog, emptyList()))
        assertEquals("bitcoin", ExchangeAssets.resolve("btc", null, emptyList()))
        assertEquals("solo", ExchangeAssets.resolve("SOLO", catalog, emptyList()))
        assertNull(ExchangeAssets.resolve("ABC", catalog, emptyList()))
        assertEquals("alpha-two", ExchangeAssets.resolve("ABC", catalog, listOf("bitcoin", "alpha-two", "alpha-one")))
        assertNull(ExchangeAssets.resolve("NONE", catalog, emptyList()))
        assertNull(ExchangeAssets.resolve("BAD", catalog, emptyList()))
        assertEquals(AssetId("coin:tether"), ExchangeAssets.asset("tether"))
        assertEquals("tether", ExchangeAssets.coinOf(AssetId("coin:tether")))
        assertNull(ExchangeAssets.coinOf(AssetId("bitcoin:btc")))
    }

    @Test
    fun catalogKeepsSymbolsThroughItsCache() {
        val decoded = CoingeckoCatalog.decode(catalog.encode())
        assertTrue(decoded.hasSymbols)
        assertEquals(listOf("alpha-one" to "Alpha One", "alpha-two" to "Alpha Two"), decoded.coinsWithSymbol("abc"))
    }

    @Test
    fun applicationMatchesSymbolsOnceAndKeepsTheUsersChoice() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1_800_000_000_000L }
            app.snapshot()
            app.setMarketDataConsent(true)
            app.refreshEvmCatalog({ url, _ -> if (url.contains("asset_platforms")) PLATFORMS else coins }, "https://example.test")
            assertEquals(
                mapOf("USDT" to AssetId("coin:tether"), "SOLO" to AssetId("coin:solo")),
                app.exchangeAssets("bybit", setOf("USDT", "SOLO", "ABC")),
            )
            assertEquals("tether", app.marketCoinId(AssetId("coin:tether")))
            assertEquals(
                mapOf("ABC" to listOf("alpha-one" to "Alpha One", "alpha-two" to "Alpha Two")),
                app.unresolvedExchangeCoins("bybit"),
            )
            app.chooseExchangeCoin("bybit", "ABC", "alpha-two")
            app.chooseExchangeCoin("bybit", "SOLO", "alpha-one")
            assertEquals(
                mapOf("ABC" to AssetId("coin:alpha-two"), "SOLO" to AssetId("coin:alpha-one")),
                app.exchangeAssets("bybit", setOf("ABC", "SOLO")),
            )
            assertEquals(emptyMap(), app.unresolvedExchangeCoins("bybit"))
            // Another exchange matches its own symbols.
            assertEquals(mapOf("SOLO" to AssetId("coin:solo")), app.exchangeAssets("other", setOf("SOLO")))
        }
    }

    private companion object {
        const val PLATFORMS = """[{"id":"ethereum","chain_identifier":1,"name":"Ethereum","native_coin_id":"ethereum"}]"""
    }
}
