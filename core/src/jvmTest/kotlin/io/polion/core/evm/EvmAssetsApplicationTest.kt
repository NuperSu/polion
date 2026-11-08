package io.polion.core.evm

import io.polion.core.application.PortfolioApplication
import io.polion.core.ledger.Activity
import io.polion.core.ledger.ActivityKind
import io.polion.core.ledger.ActivityStatus
import io.polion.core.ledger.Movement
import io.polion.core.ledger.MovementRole
import io.polion.core.market.AssetDefinition
import io.polion.core.market.AssetIdentity
import io.polion.core.market.AssetKind
import io.polion.core.market.AssetMetadata
import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.EventId
import io.polion.core.model.ExactInteger
import io.polion.core.model.OwnerId
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import io.polion.core.ownership.Owner
import io.polion.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvmAssetsApplicationTest {
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/coingecko/$name")).readText()

    private val account = AccountId("evm")
    private val ethMain = EvmAssets.native(1)
    private val ethBase = EvmAssets.native(8453)
    private val usdcBase = EvmAssets.token(8453, "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913")
    private val spam = EvmAssets.token(8453, "0x" + "5".repeat(40))
    private val nft = EvmAssets.nft(1, "0x" + "6".repeat(40), ExactInteger.parse("7"))

    private fun setUp(db: JvmSqliteDatabase): PortfolioApplication {
        val app = PortfolioApplication(db) { 1_800_000_000_000L }
        app.snapshot()
        app.saveOwner(Owner(OwnerId("owner"), "Owner"))
        app.saveAccount(Account(account, OwnerId("owner"), "EVM", AccountKind.WALLET))

        fun register(
            asset: AssetId,
            kind: AssetKind,
            chain: Long,
            reference: String,
            symbol: String,
            decimals: Int,
            tokenId: String? = null,
        ) = app.registerAsset(
            AssetDefinition(asset, AssetIdentity(kind, evmNetwork(chain), reference, tokenId)),
            AssetMetadata(symbol, symbol, decimals, 0),
        )
        register(ethMain, AssetKind.NATIVE, 1, "native", "ETH", 18)
        register(ethBase, AssetKind.NATIVE, 8453, "native", "ETH", 18)
        register(usdcBase, AssetKind.TOKEN, 8453, "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", "USDC", 6)
        register(spam, AssetKind.TOKEN, 8453, "0x" + "5".repeat(40), "USDC", 6)
        register(nft, AssetKind.NFT, 1, "0x" + "6".repeat(40), "PUNK", 0, tokenId = "7")
        val movements =
            listOf(
                ethMain to "1000000000000000000",
                ethBase to "500000000000000000",
                usdcBase to "2500000",
                spam to "1000000000",
                nft to "1",
            ).mapIndexed {
                index,
                (asset, units),
                ->
                Movement(EventId("m$index"), account, AtomicAmount(asset, ExactInteger.parse(units)), MovementRole.TRANSFER)
            }
        app.saveManualActivity(
            Activity(
                ActivityId("evm-fixture"),
                1,
                "evm-fixture",
                ActivityKind.DEPOSIT,
                ActivityStatus.CONFIRMED,
                movements,
                emptySet(),
                null,
                manual = true,
            ),
        )
        app.setMarketDataConsent(true)
        app.refreshEvmCatalog(
            { url, _ -> if (url.contains("asset_platforms")) fixture("asset_platforms.json") else fixture("coins_list.json") },
        )
        return app
    }

    @Test fun chainAssetsLinkToOneCoinAndUnknownTokensStartHidden() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            assertEquals(setOf(ethMain, ethBase), app.assetsForCoin("ethereum").toSet())
            assertEquals("usd-coin", app.marketCoinId(usdcBase))
            assertEquals(null, app.marketCoinId(spam))
            // A same-symbol token that CoinGecko does not know, and the NFT, are hidden; known coins are shown.
            assertEquals(setOf(spam, nft), app.hiddenAssets())
            val snapshot = app.snapshot()
            assertEquals(setOf(ethMain, ethBase, usdcBase), snapshot.valuations.map { it.asset }.toSet())
            assertEquals(setOf(spam, nft), snapshot.hiddenAssets.map { it.asset }.toSet())
            // The user's choice wins in both directions.
            app.setAssetShown(spam, true)
            app.setAssetShown(usdcBase, false)
            assertEquals(setOf(usdcBase, nft), app.hiddenAssets())
            assertTrue(app.snapshot().valuations.any { it.asset == spam })
        }
    }

    @Test fun oneCoinPriceValuesEveryLinkedAsset() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val requests = mutableListOf<String>()
            val saved =
                app.refreshLinkedCoinPrices({ url, _ ->
                    requests += url
                    """{"ethereum":{"usd":3000.5,"last_updated_at":1799999990},"usd-coin":{"usd":1.0002,"last_updated_at":1799999990}}"""
                })
            assertEquals(3, saved)
            assertEquals(1, requests.size)
            assertTrue(requests.single().contains("ids=ethereum,usd-coin&vs_currencies=usd"))
            val values = app.snapshot().valuations.associate { it.asset to it.value?.toString() }
            assertEquals("3000.5", values[ethMain]?.trimEnd('0')?.trimEnd('.'))
            assertEquals("1500.25", values[ethBase]?.trimEnd('0')?.trimEnd('.'))
            assertEquals("2.5005", values[usdcBase]?.trimEnd('0')?.trimEnd('.'))
            // Links and visibility choices survive an encrypted restore.
            app.setAssetShown(spam, true)
            val archive = app.exportBackup("password".toCharArray())
            JvmSqliteDatabase(":memory:").use { staging ->
                app.restoreToStaging(staging, archive, "password".toCharArray())
                val restored = PortfolioApplication(staging) { 1_800_000_000_000L }
                assertEquals(setOf(ethMain, ethBase), restored.assetsForCoin("ethereum").toSet())
                assertEquals(setOf(nft), restored.hiddenAssets())
            }
        }
    }
}
