package io.valroot.core.evm

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.ledger.Activity
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.ledger.Movement
import io.valroot.core.ledger.MovementRole
import io.valroot.core.market.AssetDefinition
import io.valroot.core.market.AssetIdentity
import io.valroot.core.market.AssetKind
import io.valroot.core.market.AssetMetadata
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.EventId
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.OwnerId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.storage.JvmSqliteDatabase
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

    private fun setUp(
        db: JvmSqliteDatabase,
        clock: () -> Long = { 1_800_000_000_000L },
    ): PortfolioApplication {
        val app = PortfolioApplication(db, clock)
        app.snapshot()
        app.accounts.saveOwner(Owner(OwnerId("owner"), "Owner"))
        app.accounts.saveAccount(Account(account, OwnerId("owner"), "EVM", AccountKind.WALLET))

        fun register(
            asset: AssetId,
            kind: AssetKind,
            chain: Long,
            reference: String,
            symbol: String,
            decimals: Int,
            tokenId: String? = null,
        ) = app.assets.register(
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
        app.transactions.saveManualActivity(
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
        app.market.setConsent(true)
        app.evm.refreshCatalog(
            { url, _ -> if (url.contains("asset_platforms")) fixture("asset_platforms.json") else fixture("coins_list.json") },
        )
        return app
    }

    @Test fun chainAssetsLinkToOneCoinAndUnknownTokensStartHidden() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            assertEquals(setOf(ethMain, ethBase), app.assets.forCoin("ethereum").toSet())
            assertEquals("usd-coin", app.market.coinId(usdcBase))
            assertEquals(null, app.market.coinId(spam))
            // A same-symbol token that CoinGecko does not know, and the NFT, are hidden; known coins are shown.
            assertEquals(setOf(spam, nft), app.assets.hidden())
            val snapshot = app.snapshot()
            assertEquals(setOf(ethMain, ethBase, usdcBase), snapshot.valuations.map { it.asset }.toSet())
            assertEquals(setOf(spam, nft), snapshot.hiddenAssets.map { it.asset }.toSet())
            // The user's choice wins in both directions.
            app.assets.setShown(spam, true)
            app.assets.setShown(usdcBase, false)
            assertEquals(setOf(usdcBase, nft), app.assets.hidden())
            assertTrue(app.snapshot().valuations.any { it.asset == spam })
        }
    }

    @Test fun oneCoinPriceValuesEveryLinkedAsset() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val requests = mutableListOf<String>()
            // USD comes from DefiLlama only, so CoinGecko is not asked while USD is the only currency.
            assertEquals(0, app.linkedCoins.refreshPrices({ url, _ -> error("unexpected $url") }))
            val saved =
                app.linkedCoins.refreshUsdPrices({ url ->
                    requests += url
                    """{"coins":{"coingecko:ethereum":{"price":3000.5,"symbol":"ETH","timestamp":1799999990,"confidence":0.99},""" +
                        """"coingecko:usd-coin":{"price":1.0002,"symbol":"USDC","timestamp":1799999990,"confidence":0.99}}}"""
                })
            assertEquals(3, saved)
            assertEquals(listOf("https://coins.llama.fi/prices/current/coingecko:ethereum,coingecko:usd-coin"), requests)
            val values = app.snapshot().valuations.associate { it.asset to it.value?.toString() }
            assertEquals("3000.5", values[ethMain]?.trimEnd('0')?.trimEnd('.'))
            assertEquals("1500.25", values[ethBase]?.trimEnd('0')?.trimEnd('.'))
            assertEquals("2.5005", values[usdcBase]?.trimEnd('0')?.trimEnd('.'))
            // Links and visibility choices survive an encrypted restore.
            app.assets.setShown(spam, true)
            val archive = app.backup.export("password".toCharArray())
            JvmSqliteDatabase(":memory:").use { staging ->
                app.backup.restoreToStaging(staging, archive, "password".toCharArray())
                val restored = PortfolioApplication(staging) { 1_800_000_000_000L }
                assertEquals(setOf(ethMain, ethBase), restored.assets.forCoin("ethereum").toSet())
                assertEquals(setOf(nft), restored.assets.hidden())
            }
        }
    }

    @Test fun nonUsdCurrenciesComeFromCoingecko() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            app.settings.setSecondaryCurrency(AssetId("EUR"))
            val requests = mutableListOf<String>()
            app.linkedCoins.refreshPrices({ url, _ ->
                requests += url
                """{"ethereum":{"eur":2800,"last_updated_at":1799999990}}"""
            })
            assertTrue(requests.single().contains("ids=ethereum,usd-coin&vs_currencies=eur&"))
        }
    }

    @Test fun usdHistoryComesFromDefiLlamaBackToTheFirstMovement() {
        JvmSqliteDatabase(":memory:").use { db ->
            val day = 86_400_000L
            var now = 1_800_000_000_000L
            val app = setUp(db) { now }
            val requests = mutableListOf<String>()
            val fetch: (String) -> String = { url ->
                requests += url
                val coin = url.substringAfter("coingecko:").substringBefore('?')
                val start = url.substringAfter("start=").substringBefore('&').toLong()
                val price = if (coin == "ethereum") "3000" else "1"
                """{"coins":{"coingecko:$coin":{"symbol":"X","confidence":0.99,"prices":""" +
                    """[{"timestamp":$start,"price":$price},{"timestamp":${start + 86_400},"price":$price}]}}}"""
            }
            val pauses = mutableListOf<Long>()
            assertEquals(2, app.linkedCoins.backfillUsdHistory(fetch, maxRequests = 2, pause = { pauses += it }))
            assertEquals(listOf(1_000L, 1_000L), pauses)
            // The newest 500 days first, then the 500 before them, for the held coin that sorts first.
            assertEquals(
                listOf(
                    "https://coins.llama.fi/chart/coingecko:ethereum?start=${(now - 500 * day) / 1000}&span=500&period=1d",
                    "https://coins.llama.fi/chart/coingecko:ethereum?start=${(now - 1000 * day) / 1000}&span=500&period=1d",
                ),
                requests,
            )
            // Older than CoinGecko's one-year limit, and recorded for each chain's ETH.
            val old = app.analytics.priceSeries(ethBase, AssetId("USD"), listOf(now - 1000 * day + 3_600_000)).single()
            assertEquals(
                "3000",
                old.price
                    ?.price
                    ?.value
                    ?.toString(),
            )
            // Daily ranges reach back to the first movement (1 ms after the epoch here), then 90 days of hourly prices.
            while (app.linkedCoins.backfillUsdHistory(fetch) > 0) Unit
            val daily = requests.filter { it.endsWith("period=1d") }
            val hourly = requests.filter { it.endsWith("period=1h") }
            assertEquals(2 * 42, daily.size)
            assertEquals(2 * 5, hourly.size)
            assertTrue(requests.indexOfLast { it.endsWith("period=1d") } < requests.indexOfFirst { it.endsWith("period=1h") })
            assertTrue(daily.any { it.contains("start=0&") })
            // Covered ranges are fetched once; two days later only the new days are asked for.
            val before = requests.size
            assertEquals(0, app.linkedCoins.backfillUsdHistory(fetch))
            now += 2 * day
            assertEquals(4, app.linkedCoins.backfillUsdHistory(fetch))
            assertTrue(requests.drop(before).all { it.contains("start=${(now - 2 * day) / 1000}&") })
        }
    }

    @Test fun linkedCoinHistoryIsFilledOnceDailyFirstAndValuesEveryLinkedAsset() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            app.settings.setSecondaryCurrency(AssetId("EUR"))
            val now = 1_800_000_000_000L
            val day = 86_400_000L
            val requests = mutableListOf<String>()
            val transport: (String, String?) -> String = { url, _ ->
                requests += url
                val price = if (url.contains("/ethereum/")) "3000" else "1"
                """{"prices":[[${now - 200 * day},$price],[${now - 100 * day},$price]]}"""
            }
            // Two linked coins, daily then hourly: the first call stops at its request limit.
            val pauses = mutableListOf<Long>()
            assertEquals(6, app.linkedCoins.backfillHistory(transport, maxRequests = 2, pause = { pauses += it }))
            // A pause precedes every request, so the host's paced CoinGecko transport accepts each one.
            assertEquals(listOf(6_100L, 6_100L), pauses)
            assertEquals(
                listOf("ethereum/market_chart?vs_currency=eur&days=365", "usd-coin/market_chart?vs_currency=eur&days=365"),
                requests.map { it.substringAfter("/coins/") },
            )
            val past = app.analytics.priceSeries(ethBase, AssetId("EUR"), listOf(now - 100 * day)).single()
            assertEquals(
                "3000",
                past.price
                    ?.price
                    ?.value
                    ?.toString(),
            )
            app.linkedCoins.backfillHistory(transport)
            assertEquals(4, requests.size)
            assertTrue(requests.drop(2).all { it.endsWith("days=90") })
            // Each range is fetched once.
            app.linkedCoins.backfillHistory(transport)
            assertEquals(4, requests.size)
        }
    }

    @Test fun aCoinSoldSinceStillGetsItsHistory() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            app.transactions.saveManualActivity(
                Activity(
                    ActivityId("sold-usdc"),
                    2,
                    "sold-usdc",
                    ActivityKind.PAYMENT,
                    ActivityStatus.CONFIRMED,
                    listOf(
                        Movement(EventId("sold"), account, AtomicAmount(usdcBase, ExactInteger.parse("-2500000")), MovementRole.TRANSFER),
                    ),
                    emptySet(),
                    null,
                    manual = true,
                ),
            )
            app.settings.setSecondaryCurrency(AssetId("EUR"))
            val requests = mutableListOf<String>()
            app.linkedCoins.backfillHistory({ url, _ ->
                requests += url
                """{"prices":[[1700000000000,1]]}"""
            })
            // The coin held now comes before the one sold, though it sorts later by name.
            assertEquals(
                listOf("ethereum", "usd-coin"),
                requests.filter { it.endsWith("days=365") }.map { it.substringAfter("/coins/").substringBefore('/') },
            )
        }
    }

    @Test fun rebasingTokensFollowTheProviderBalanceAsRewards() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val contract = "0xae7ab96520de3a18e5e111b5eaab095312d7fe84"
            val steth = EvmAssets.token(1, contract)
            app.assets.register(
                AssetDefinition(steth, AssetIdentity(AssetKind.TOKEN, evmNetwork(1), contract, null)),
                AssetMetadata("stETH", "Lido stETH", 18, 0),
            )
            // Lido transfers lose a wei or two to rounding.
            app.transactions.saveManualActivity(
                Activity(
                    ActivityId("steth-in"),
                    2,
                    "steth-in",
                    ActivityKind.DEPOSIT,
                    ActivityStatus.CONFIRMED,
                    listOf(
                        Movement(
                            EventId("in"),
                            account,
                            AtomicAmount(steth, ExactInteger.parse("999999999999999998")),
                            MovementRole.TRANSFER,
                        ),
                    ),
                    emptySet(),
                    null,
                    manual = true,
                ),
            )
            app.evm.setConsent(true)
            // The fixture catalog has no stETH coin, so it would start hidden.
            app.assets.setShown(steth, true)
            assertTrue(app.evm.holdsRebasing(account, 1))
            assertTrue(!app.evm.holdsRebasing(account, 8453))

            fun balance(units: String) = listOf(EvmTokenBalance(contract, EvmTokenStandard.ERC20, null, ExactInteger.parse(units)))
            assertEquals(1, app.evm.reconcileRebasingBalances(account, "0x" + "1".repeat(40), 1, balance("1014834724316850471")))
            val reward =
                app
                    .snapshot()
                    .activities
                    .map { it.activity }
                    .single { it.id.value.startsWith("evm-rebase:") }
            assertEquals(ActivityKind.REWARD, reward.kind)
            assertEquals(
                "14834724316850473",
                reward.movements
                    .single()
                    .amount.atomicUnits
                    .toString(),
            )
            assertEquals(0, app.evm.reconcileRebasingBalances(account, "0x" + "1".repeat(40), 1, balance("1014834724316850471")))
            // Ordinary tokens are left to the balance check, which reports a difference instead.
            assertEquals(0, app.evm.reconcileRebasingBalances(account, "0x" + "1".repeat(40), 8453, emptyList()))
            assertEquals(
                "1014834724316850471",
                app
                    .snapshot()
                    .valuations
                    .single { it.asset == steth }
                    .amount.atomicUnits
                    .toString(),
            )
        }
    }

    @Test fun unlistedNativePaymentsKeepTheBalanceAtOrAboveZeroAndMatchTheProvider() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val address = "0x" + "1".repeat(40)

            fun move(
                id: String,
                at: Long,
                units: String,
            ) = app.transactions.saveManualActivity(
                Activity(
                    ActivityId(id),
                    at,
                    id,
                    ActivityKind.OTHER,
                    ActivityStatus.CONFIRMED,
                    listOf(Movement(EventId(id), account, AtomicAmount(ethMain, ExactInteger.parse(units)), MovementRole.TRANSFER)),
                    emptySet(),
                    null,
                    manual = true,
                ),
            )
            // 1 ETH arrives at 1 ms (fixture); 3 leave at 20 ms though a contract payment of 2 was never listed; 0.5 arrives at 30 ms.
            move("pay", 20, "-3000000000000000000")
            move("in", 30, "500000000000000000")
            app.evm.setConsent(true)
            assertEquals(2, app.evm.reconcileNativeBalance(account, address, 1, ExactInteger.parse("700000000000000000")))
            val adjustments =
                app
                    .snapshot()
                    .activities
                    .map { it.activity }
                    .filter { it.id.value.startsWith("evm-adjust:") }
                    .associate {
                        it.occurredAtEpochMillis to
                            it.movements
                                .single()
                                .amount.atomicUnits
                                .toString()
                    }
            assertEquals(mapOf(19L to "2000000000000000000", 31L to "200000000000000000"), adjustments)
            assertEquals(
                "0",
                app
                    .analytics
                    .history(25)
                    .single { it.asset == ethMain }
                    .amount.atomicUnits
                    .toString(),
            )
            assertEquals(
                "700000000000000000",
                app
                    .snapshot()
                    .valuations
                    .single { it.asset == ethMain }
                    .amount.atomicUnits
                    .toString(),
            )
            // Derived again on each check: the same balance changes nothing, a new one replaces the closing adjustment.
            val revisions = db.query("SELECT COUNT(*) FROM ledger_activities").single().single()
            app.evm.reconcileNativeBalance(account, address, 1, ExactInteger.parse("700000000000000000"))
            assertEquals(revisions, db.query("SELECT COUNT(*) FROM ledger_activities").single().single())
            app.evm.reconcileNativeBalance(account, address, 1, ExactInteger.parse("500000000000000000"))
            assertEquals(
                "500000000000000000",
                app
                    .snapshot()
                    .valuations
                    .single { it.asset == ethMain }
                    .amount.atomicUnits
                    .toString(),
            )
        }
    }

    @Test fun linkedAssetsTakeTheirCoinLogo() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val requests = mutableListOf<String>()
            val transport: (String, String?) -> String = { url, _ ->
                requests += url
                """[{"id":"ethereum","symbol":"eth","name":"Ethereum","current_price":3000,
                   "image":"https://coin-images.coingecko.com/coins/images/279/large/ethereum.png?1696501628"}]"""
            }
            app.linkedCoins.refreshLogos(transport)
            assertTrue(
                requests.single().contains("coins/markets?vs_currency=usd&ids=ethereum,usd-coin&per_page=250&page=1&sparkline=false"),
            )
            val logo = "https://coin-images.coingecko.com/coins/images/279/small/ethereum.png?1696501628"
            assertEquals(mapOf(ethMain to logo, ethBase to logo), app.market.overview().assetLogos)
            // USD Coin had no logo in the response, so it is asked for again; a complete set is kept for a week.
            app.linkedCoins.refreshLogos(transport)
            assertEquals(2, requests.size)
        }
    }
}
