package io.valroot.core.market

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoingeckoCatalogTest {
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/coingecko/$name")).readText()

    private val catalog =
        CoingeckoCatalog.parsePlatforms(fixture("asset_platforms.json")).let { platforms ->
            CoingeckoCatalog(platforms, CoingeckoCatalog.parseContracts(fixture("coins_list.json"), platforms))
        }

    @Test fun chainsMapToPlatformsAndNativeCoins() {
        // Captured from CoinGecko on 2026-11-08; Solana has no EVM chain ID and is left out.
        assertEquals(setOf(1L, 8453L, 42161L, 137L, 56L, 10L), catalog.platforms.map { it.chainId }.toSet())
        assertEquals("ethereum", catalog.nativeCoin(8453))
        assertEquals("ethereum", catalog.nativeCoin(42161))
        assertEquals("binancecoin", catalog.nativeCoin(56))
        assertEquals("polygon-ecosystem-token", catalog.nativeCoin(137))
        assertNull(catalog.nativeCoin(999_999))
    }

    @Test fun contractsMapToCoinsPerChain() {
        assertEquals("usd-coin", catalog.tokenCoin(1, "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"))
        assertEquals("usd-coin", catalog.tokenCoin(8453, "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913"))
        // The Ethereum USDC contract is not USDC on Base.
        assertNull(catalog.tokenCoin(8453, "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"))
        assertEquals(catalog.tokenCoin(1, "0xdac17f958d2ee523a2206206994597c13d831ec7"), "tether")
        // The device cache round-trips.
        val decoded = CoingeckoCatalog.decode(catalog.encode())
        assertEquals(catalog.platforms.toSet(), decoded.platforms.toSet())
        assertEquals("usd-coin", decoded.tokenCoin(42161, "0xaf88d065e77c8cc2239327c5edb3a432268e5831"))
    }

    @Test fun aContractListedUnderTwoCoinsIsNotMatched() {
        val platforms = listOf(EvmPlatform("ethereum", 1, "Ethereum", "ethereum"))
        val contract = "0x" + "1".repeat(40)
        val payload =
            """[{"id":"first","symbol":"a","name":"A","platforms":{"ethereum":"$contract"}},
            |{"id":"second","symbol":"b","name":"B","platforms":{"ethereum":"${contract.uppercase().replace("0X", "0x")}"}},
            |{"id":"third","symbol":"c","name":"C","platforms":{"ethereum":"not-an-address"}}]
            """.trimMargin()
        val contracts = CoingeckoCatalog.parseContracts(payload, platforms)
        assertNull(CoingeckoCatalog(platforms, contracts).tokenCoin(1, contract))
    }
}
